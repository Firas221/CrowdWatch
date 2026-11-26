@echo off
rem Starts the CrowdWatch server: control room browsers open http://<this-pc>:8080/
rem usage: run-server.bat [venue] [port] [control-key] [scout-key]
rem   venue: airport, stadium, umrah or a .venue file (default airport); port: default 8080
rem HTTPS: set CROWDWATCH_HTTPS=1 first.   Personal logins: add users first with add-user.bat <name> <ROLE>
rem Uses the Spring Boot server (CrowdWatch-server.jar: React console, live updates) when it is here,
rem otherwise the built-in server of CrowdWatch.jar (classic console). Both use the same data.
setlocal
cd /d "%~dp0"
set VENUE=%~1
if "%VENUE%"=="" set VENUE=airport
set PORT=%~2
if "%PORT%"=="" set PORT=8080
set HTTPS=
if not "%CROWDWATCH_HTTPS%"=="" set HTTPS=--https
set JAR=CrowdWatch-server.jar
if not exist "%JAR%" (
  set JAR=CrowdWatch.jar
  echo CrowdWatch-server.jar not found ^(build it with ..\build-spring.bat^): starting the built-in server with the classic console.
)
java -jar "%JAR%" --server "%VENUE%" %PORT% %3 %4 %HTTPS%
