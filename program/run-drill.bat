@echo off
rem Starts a DRILL server for training: the venue's sample data, nothing saved, the real venue untouched.
rem usage: run-drill.bat [venue] [port] [script.drill]    e.g. run-drill.bat airport 8081 ..\examples\airport-fire.drill
rem Uses CrowdWatch-server.jar (Spring Boot) when it is here, otherwise CrowdWatch.jar.
setlocal
cd /d "%~dp0"
set VENUE=%~1
if "%VENUE%"=="" set VENUE=airport
set PORT=%~2
if "%PORT%"=="" set PORT=8081
set HTTPS=
if not "%CROWDWATCH_HTTPS%"=="" set HTTPS=--https
set JAR=CrowdWatch-server.jar
if not exist "%JAR%" set JAR=CrowdWatch.jar
java -jar "%JAR%" --drill "%VENUE%" %PORT% %3 %HTTPS%
