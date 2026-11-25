#!/bin/sh
# usage: ./run-server.sh [venue] [port] [control-key] [scout-key]
# HTTPS: CROWDWATCH_HTTPS=1 ./run-server.sh ...   (see the guide, section on HTTPS)
# Personal logins: add users first with ./add-user.sh <name> <ROLE>
cd "$(dirname "$0")"
java -jar CrowdWatch.jar --server "${1:-airport}" "${2:-8080}" ${3:+"$3"} ${4:+"$4"} ${CROWDWATCH_HTTPS:+--https}
