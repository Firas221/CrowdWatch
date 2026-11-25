@echo off
rem usage: run-server.bat [venue] [port] [control-key] [scout-key]
cd /d "%~dp0"
set VENUE=%1
if "%VENUE%"=="" set VENUE=airport
set PORT=%2
if "%PORT%"=="" set PORT=8080
java -jar CrowdWatch.jar --server %VENUE% %PORT% %3 %4
