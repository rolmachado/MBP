package org.citopt.connde.constants;

/**
 * Constant terms and values.
 */
public final class Constants {

    //Regex for acceptable usernames
    public static final String USERNAME_REGEX = "^[_'.@A-Za-z0-9-]*$";

    //Possible user roles
    public static final String ADMIN = "ROLE_ADMIN";
    public static final String USER = "ROLE_USER";
    public static final String DEVICE = "ROLE_DEVICE";
    public static final String ANONYMOUS = "ROLE_ANONYMOUS";

    /**
     * Hide constructor.
     */
    private Constants() {
    }
}
