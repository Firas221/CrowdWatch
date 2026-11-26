@echo off
rem Starts a DRILL server for training: the venue's sample data, nothing saved, the real venue untouched.
rem usage: run-drill.bat [venue] [port] [script.drill]    e.g. run-drill.bat airport 8081 ..\examples\airport-fire.drill
cd /d "%~dp0"
set VENUE=%1
if "%VENUE%"=="" set VENUE=airport
set PORT=%2
if "%PORT%"=="" set PORT=8081
java -jar CrowdWatch.jar --drill %VENUE% %PORT% %3
