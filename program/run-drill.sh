#!/bin/sh
# Starts a DRILL server for training: the venue's sample data, nothing saved, the real venue untouched.
# usage: ./run-drill.sh [venue] [port] [script.drill]      e.g. ./run-drill.sh airport 8081 ../examples/airport-fire.drill
cd "$(dirname "$0")"
java -jar CrowdWatch.jar --drill "${1:-airport}" "${2:-8081}" ${3:+"$3"}
