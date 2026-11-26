@echo off
rem Started by the CrowdWatch task (install-service-windows.bat): reads service.conf and keeps the server running.
rem Uses CrowdWatch-server.jar (Spring Boot) when it is here, otherwise CrowdWatch.jar; checked at every start.
cd /d "%~dp0"
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("service.conf") do set "%%a=%%b"
if "%VENUE%"=="" set VENUE=airport
if "%PORT%"=="" set PORT=8080
set HTTPS=
if not "%CROWDWATCH_HTTPS%"=="" set HTTPS=--https
:again
set JAR=CrowdWatch-server.jar
if not exist "%JAR%" set JAR=CrowdWatch.jar
java -jar "%JAR%" --server "%VENUE%" %PORT% %CONTROL_KEY% %SCOUT_KEY% %HTTPS%
rem stopped or failed: wait 10 seconds and start again
timeout /t 10 /nobreak >nul
goto again
