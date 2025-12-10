#!/bin/bash
JAVA_CMD=$(which java)
JAR_FILE="/home/pi/localmesh-chat/LocalMeshChatServer.jar"
LOG_FILE="/var/log/localmesh.log"

$JAVA_CMD -Xms128m -Xmx256m -jar $JAR_FILE >> $LOG_FILE 2>&1 &
echo $! > /var/run/localmesh.pid