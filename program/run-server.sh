#!/bin/sh
# usage: ./run-server.sh [venue] [port] [control-key] [scout-key]
cd "$(dirname "$0")"
java -jar CrowdWatch.jar --server "${1:-airport}" "${2:-8080}" ${3:+"$3"} ${4:+"$4"}
