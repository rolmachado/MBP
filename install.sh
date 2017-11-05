#!/bin/sh

#########################################################################################################################
# This install script provides a fully automated installation of the RMP.						#
# It requires systemd as the running init system.									#
# It installs Java, Python3, Mosquitto, MongoDB and Tomcat8 to run the RMP.						#
# Moreover it installs git and maven to build the necessary files.							#
#															#
# After the installation you will find a folder named connde in the directory the script was started.			#	
# This folder contains all source code of the RMP and can be used to build it.						#	
# More over the necessary python scripts, running the discovery service and the value logger will be installed using a 	#
# virtual Python environment.												#
# The environment and configuration files will be stored in /etc/rmp							#
# while the scripts are installed at /opt/rmp										#
#########################################################################################################################

echo "write hostname\n"
sudo sh -c "echo '127.0.0.1' $(hostname) >> /etc/hosts";
echo "\nupdate package repositories\n"
sudo apt-get -qy update;
#sudo apt-get -qy upgrade;

#Installing Java8, pip
echo "\nInstalling Java and pip...\n"
sudo apt-get install -qy openjdk-8-jdk python3-pip libbluetooth-dev; # bluetooth-dev for installation of PyBluez required

# Install python virtual environment and install python packages
echo "\nInstalling python packages...\n"
sudo -H pip3 install -r python-packages.txt

echo "\nInstalling Mosquitto Broker, MongoDB, Tomcat8, git and maven...\n"
# Install Mosquitto Broker
sudo apt-get install -qy mosquitto;
sudo systemctl start mosquitto;

# Install and start MongoDB 
sudo apt-get -qy install mongodb-server;
sudo systemctl start mongodb;
#sudo systemctl status mongodb;

#sudo /etc/init.d/mongodb start;
#sudo /etc/init.d/mongodb status;

# Install Tomcat 9
sudo apt-get install -qy tomcat8;
sudo systemctl start tomcat8;

# INstall git andmaven
sudo apt-get install -qy git;
sudo apt-get install -qy maven;

# checkout repository
echo "\nChecking out the repository\n"
sudo git clone https://github.com/rossojo/connde.git
cd connde/java-projects/restful-connde
echo "\nBuilding .war file...\n"
sudo mvn clean install

# deploy war to Tomcat
echo "\nDeploying .war file...\n"
sudo mv target/restful-connde-1.0-SNAPSHOT.war /var/lib/tomcat8/webapps/MBP.war

# Install discovery service
echo "\nInstalling python scripts...\n"
sudo mkdir -p /opt/rmp
sudo mkdir -p /etc/rmp
cd ../../python-scripts
sudo cp -r rmpdiscovery rmpdiscovery.py rmpdiscovery.sh rmpadvertise.sh rmpadvertise.py /opt/rmp
sudo chmod 775 /opt/rmpdiscover.sh
sudo chmod 775 /opt/rmpadvertise.sh
sudo cp value-logger.py rmp-value-logger.sh /opt/rmp
sudo chmod 775 /opt/rmp-value-logger.sh
sudo cp rmpdiscovery.service rmpadvertise.service rmp-value-logger.service /etc/systemd/system
sudo systemctl start rmpdiscovery.service rmp-value-logger.service

echo "\nInstallation finished"
