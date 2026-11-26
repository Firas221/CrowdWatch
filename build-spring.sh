#!/bin/sh
# Builds the Spring Boot server with the React console: program/CrowdWatch-server.jar.
# Needs a JDK 17 or newer and Node.js 20+ (Maven is fetched by server/mvnw) (only for building; the jar needs Java only).
#   ./build-spring.sh              build the console and the server, run both test suites
#   ./build-spring.sh --no-tests   build only
set -e
cd "$(dirname "$0")"
echo "Building the web console (React, TypeScript)..."
cd web
npm ci --no-audit --no-fund
if [ "$1" != "--no-tests" ]; then npm test; fi
npm run build
cd ../server
echo "Building the server (Spring Boot)..."
if [ "$1" = "--no-tests" ]; then sh mvnw -B -q package -DskipTests; else sh mvnw -B -q package; fi
cp target/CrowdWatch-server.jar ../program/CrowdWatch-server.jar
echo "Built program/CrowdWatch-server.jar - start it with program/run-spring.sh"
