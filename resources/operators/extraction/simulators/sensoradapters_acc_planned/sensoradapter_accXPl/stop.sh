#!/bin/bash
sudo kill -9 $(ps -ef | grep AccXPlSim.jar | grep -v grep | awk '{print $2}')