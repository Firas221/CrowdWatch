#!/bin/sh
# CrowdWatch server on Spring Boot, with the React console and live updates.
# usage: ./run-spring.sh [venue] [port] [control-key] [scout-key]
# HTTPS: CROWDWATCH_HTTPS=1 ./run-spring.sh ...      Drill: ./run-spring.sh --drill airport 8081 [file.drill]
# Build it first with ../build-spring.sh (it is also in every release zip).
cd "$(dirname "$0")"
if [ "$1" = "--drill" ]; then exec java -jar CrowdWatch-server.jar "$@"; fi
exec java -jar CrowdWatch-server.jar "${1:-airport}" "${2:-8080}" ${3:+"$3"} ${4:+"$4"}
