#!/bin/sh
cd "$(dirname "$0")"
java -cp "CrowdWatch.jar:lib/*" crowdwatch.test.TestRunner
