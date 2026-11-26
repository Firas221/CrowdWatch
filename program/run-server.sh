#!/bin/sh
# Starts the CrowdWatch server: control room browsers open http://<this-pc>:8080/
# usage: ./run-server.sh [venue] [port] [control-key] [scout-key]
#   venue: airport, stadium, umrah or a .venue file (default airport); port: default 8080
# HTTPS: CROWDWATCH_HTTPS=1 ./run-server.sh ...      Personal logins: add users first with ./add-user.sh <name> <ROLE>
# Uses the Spring Boot server (CrowdWatch-server.jar: React console, live updates) when it is here,
# otherwise the built-in server of CrowdWatch.jar (classic console). Both use the same data.
cd "$(dirname "$0")"
JAR=CrowdWatch-server.jar
if [ ! -f "$JAR" ]; then
  JAR=CrowdWatch.jar
  echo "CrowdWatch-server.jar not found (build it with ../build-spring.sh): starting the built-in server with the classic console."
fi
exec java -jar "$JAR" --server "${1:-airport}" "${2:-8080}" ${3:+"$3"} ${4:+"$4"} ${CROWDWATCH_HTTPS:+--https}
