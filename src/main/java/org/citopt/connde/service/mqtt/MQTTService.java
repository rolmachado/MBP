package org.citopt.connde.service.mqtt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.codec.binary.Base64;
import org.citopt.connde.service.settings.SettingsService;
import org.citopt.connde.service.settings.model.BrokerLocation;
import org.citopt.connde.service.settings.model.Settings;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * This services provides means and support for MQTT-related tasks. It allows to publish and receive MQTT messages at
 * various topics and uses the settings service in order to determine its configuration.
 * topics
 */
@Service
@EnableScheduling
@PropertySource(value = "classpath:application.properties")
public class MQTTService {
    //URL frame of the broker to use (protocol and port, address will be filled in)
    private static final String BROKER_URL = "tcp://%s:1883";
    //Client id that is assigned to the client instance with an unique suffix to avoid name collisions
    private static final String CLIENT_ID = "mbp-client-" + getUniqueClientSuffix();

    //Autowired components
    private SettingsService settingsService;

    //Stores the reference of the mqtt client
    private MqttClient mqttClient = null;

    //Set of topics the MQTT service is supposed to subscribe
    private Set<String> subscribedTopics = new HashSet<>();

    //Callback object to use for incoming MQTT messages
    private MqttCallback mqttCallback = null;

    @Value("${security.user.name}")
    private String httpUser;

    @Value("${security.user.password}")
    private String httpPassword;

    @Value("${security.oauth2.client.access-token-uri}")
    private String oauth2TokenUri = "http://192.168.2.133:8080/MBP/oauth/token";

    @Value("${security.oauth2.client.grant-type}")
    private String oauth2GrantType;

    @Value("${security.oauth2.client.client-id}")
    private String oauth2ClientId;

    @Value("${security.oauth2.client.client-secret}")
    private String oauth2ClientSecret;

    private String accessToken;
    private long expiresIn;

    /**
     * Initializes the value logger service.
     *
     * @param settingsService Settings service that manages the application settings
     */
    @Autowired
    public MQTTService(SettingsService settingsService) {
        this.settingsService = settingsService;

        //Setup and start the MQTT client
        try {
            initialize();
        } catch (MqttException e) {
            System.err.println("MqttException: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IOException: " + e.getMessage());
        }
    }

    /**
     * Initializes, configures and starts the MQTT client that belongs to this service.
     * The required parameters are derived from the settings service. If the MQTT client is already running, it will
     * be terminated, disconnected and restarted with new settings.
     *
     * @throws MqttException In case of an error during execution of mqtt operations
     * @throws IOException   In case of an I/O issue
     */
    public void initialize() throws MqttException, IOException {
        //Disconnect the old mqtt client if already connected
        if ((mqttClient != null) && (mqttClient.isConnected())) {
            mqttClient.disconnectForcibly();
        }

        //Stores the address of the desired mqtt broker
        String brokerAddress = "localhost";

        //Determine from settings if a remote broker should be used instead
        Settings settings = settingsService.getSettings();
        if (settings.getBrokerLocation().equals(BrokerLocation.REMOTE)) {
            //Retrieve IP address of external broker from settings
            brokerAddress = settings.getBrokerIPAddress();
        }

        //Instantiate memory persistence
        MemoryPersistence persistence = new MemoryPersistence();

        //Create new mqtt client with the full broker URL
        mqttClient = new MqttClient(String.format(BROKER_URL, brokerAddress), CLIENT_ID, persistence);

        //Connect and subscribe to the topics
        mqttClient.connect();
        //Subscribe all topics in the topic set
        for (String topic : subscribedTopics) {
            mqttClient.subscribe(topic);
        }

        //Set MQTT callback object if available
        if (mqttCallback != null) {
            mqttClient.setCallback(mqttCallback);
        }
    }

    @Scheduled(fixedRate = 31536000, initialDelay = 10000)
    public void initializeWithOAuth2Token()  throws MqttException, IOException{

        System.out.println("###################### Request Oauth2 Token for MBP");

        //Disconnect the old mqtt client if already connected
        if ((mqttClient != null) && (mqttClient.isConnected())) {
            mqttClient.disconnectForcibly();
        }

        //Stores the address of the desired mqtt broker
        String brokerAddress = "localhost";

        //Determine from settings if a remote broker should be used instead
        Settings settings = settingsService.getSettings();
        if (settings.getBrokerLocation().equals(BrokerLocation.REMOTE)) {
            //Retrieve IP address of external broker from settings
            brokerAddress = settings.getBrokerIPAddress();
        }

        //Instantiate memory persistence
        MemoryPersistence persistence = new MemoryPersistence();

        requestOAuth2Token();

        //Create new mqtt client with the full broker URL
        mqttClient = new MqttClient(String.format(BROKER_URL, brokerAddress), CLIENT_ID, persistence);
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setCleanSession(true);
        connectOptions.setUserName(accessToken);
        connectOptions.setPassword("any".toCharArray());

        //Connect and subscribe to the topics
        mqttClient.connect();
        //Subscribe all topics in the topic set
        for (String topic : subscribedTopics) {
            mqttClient.subscribe(topic);
        }

        //Set MQTT callback object if available
        if (mqttCallback != null) {
            mqttClient.setCallback(mqttCallback);
        }
    }

    /**
     * Lets the MQTT service subscribe a certain MQTT topic.
     *
     * @param topic The topic to subscribe
     */
    public void subscribe(String topic) throws MqttException {
        //Sanity check
        if ((topic == null) || topic.isEmpty()) {
            throw new IllegalArgumentException("Topic must not be null or empty.");
        }

        //Add to topic
        subscribedTopics.add(topic);

        //Subscribe
        mqttClient.subscribe(topic);
    }

    /**
     * Lets the MQTT service unsubscribe a certain MQTT topic.
     *
     * @param topic The topic to unsubscribe
     */
    public void unsubscribe(String topic) throws MqttException {
        //Sanity check
        if ((topic == null) || topic.isEmpty()) {
            throw new IllegalArgumentException("Topic must not be null or empty.");
        }

        //Remove from set
        subscribedTopics.remove(topic);

        //Unsubscribe
        mqttClient.unsubscribe(topic);
    }

    /**
     * Sets the MQTT callback object that is supposed to be notified about incoming MQTT messages at topics
     * which are subscribed by the MQTT service.
     *
     * @param mqttCallback The MQTT callback object to set
     */
    public void setMqttCallback(MqttCallback mqttCallback) {
        //Sanity check
        if (mqttCallback == null) {
            throw new IllegalArgumentException("MQTT callback object must not be null.");
        }

        //Store reference to object
        this.mqttCallback = mqttCallback;

        //Update client
        this.mqttClient.setCallback(mqttCallback);
    }

    /**
     * Publishes a MQTT message with a certain payload at a certain topic.
     *
     * @param topic   The topic to publish the message at
     * @param payload The payload of the message (may be empty)
     */
    public void publish(String topic, String payload) throws MqttException {
        //Do not do anything if MQTT client is not available
        if (mqttClient == null) {
            return;
        }

        //Sanity check
        if ((topic == null) || topic.isEmpty()) {
            throw new IllegalArgumentException("Topic must not be null or empty.");
        } else if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null.");
        }

        //Create new MQTT message
        MqttMessage message = new MqttMessage(payload.getBytes());

        //Publish message
        mqttClient.publish(topic, message);
    }

    /**
     * Creates an unique suffix that might be appended to a MQTT client ID in order to avoid name collisions.
     *
     * @return The unique suffix
     */
    private static String getUniqueClientSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Request an OAuth2 Access Token with client credentials of the MBP.
     */
    public void requestOAuth2Token() {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders httpHeaders = createHeaders(httpUser, httpPassword);
        HttpEntity<String> request = new HttpEntity<>(httpHeaders);
        UriComponentsBuilder uriComponentsBuilder = UriComponentsBuilder.fromHttpUrl(oauth2TokenUri)
                .queryParam("grant_type", oauth2GrantType)
                .queryParam("client-id", oauth2ClientId)
                .queryParam("scope", "read");
        ResponseEntity<String> response = restTemplate.exchange(uriComponentsBuilder.toUriString(), HttpMethod.POST, request, String.class);
        try {
            JSONObject body = new JSONObject(response.getBody());
            accessToken = body.getString("access_token");
            expiresIn = body.getLong("expires_in");
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Create a header for basic http authentication (base64 encoded).
     *
     * @param username is the name of the OAuth client
     * @param password is the secrect of the OAuth client
     * @return an instance of {@link HttpHeaders}
     */
    private HttpHeaders createHeaders(String username, String password) {
        return new HttpHeaders() {{
            String auth = username + ":" + password;
            byte[] encodedAuth = Base64.encodeBase64(
                    auth.getBytes(StandardCharsets.US_ASCII));
            String authHeader = "Basic " + new String(encodedAuth);
            set("Authorization", authHeader);
        }};
    }
}
