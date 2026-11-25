@echo off
rem usage: run-server.bat [venue] [port] [control-key] [scout-key]
rem HTTPS: set CROWDWATCH_HTTPS=1 before running (see the guide, section on HTTPS)
rem Personal logins: add users first with add-user.bat <name> <ROLE>
cd /d "%~dp0"
set VENUE=%1
if "%VENUE%"=="" set VENUE=airport
set PORT=%2
if "%PORT%"=="" set PORT=8080
set HTTPS=
if not "%CROWDWATCH_HTTPS%"=="" set HTTPS=--https
java -jar CrowdWatch.jar --server %VENUE% %PORT% %3 %4 %HTTPS%
