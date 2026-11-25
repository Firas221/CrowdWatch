@echo off
cd /d "%~dp0"
java -cp "CrowdWatch.jar;lib/*" crowdwatch.test.TestRunner
pause
