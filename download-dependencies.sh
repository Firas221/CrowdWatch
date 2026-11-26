#!/bin/sh
# Downloads everything the build needs, once: Maven (through the wrapper), the Java libraries
# (Spring Boot, Derby, test tools) and the web console's libraries (React, MUI, ECharts...).
# After this, build.sh and build-spring.sh also work without internet.
# Needs a JDK 17 or newer and Node.js 20+ installed first.
set -e
cd "$(dirname "$0")"
java -version
node --version
echo "Downloading the web console's libraries..."
(cd web && npm ci --no-audit --no-fund)
echo "Downloading Maven and the server's libraries..."
(cd server && sh mvnw -B -q dependency:go-offline && sh mvnw -B -q -DskipTests package -Dmaven.main.skip -Dspring-boot.repackage.skip >/dev/null 2>&1 || true)
echo "Done: everything needed to build is downloaded. Next: ./build.sh and ./build-spring.sh"
