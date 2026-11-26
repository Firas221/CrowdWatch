#!/bin/sh
# Starts a DRILL server for training: the venue's sample data, nothing saved, the real venue untouched.
# usage: ./run-drill.sh [venue] [port] [script.drill]      e.g. ./run-drill.sh airport 8081 ../examples/airport-fire.drill
# Uses CrowdWatch-server.jar (Spring Boot) when it is here, otherwise CrowdWatch.jar.
cd "$(dirname "$0")"
JAR=CrowdWatch-server.jar
[ -f "$JAR" ] || JAR=CrowdWatch.jar
exec java -jar "$JAR" --drill "${1:-airport}" "${2:-8081}" ${3:+"$3"} ${CROWDWATCH_HTTPS:+--https}
