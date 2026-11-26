@echo off
rem CrowdWatch server on Spring Boot, with the React console and live updates.
rem usage: run-spring.bat [venue] [port] [control-key] [scout-key]
rem HTTPS: set CROWDWATCH_HTTPS=1 first.   Drill: run-spring.bat --drill airport 8081 [file.drill]
rem Build it first with ..\build-spring.bat (it is also in every release zip).
cd /d "%~dp0"
if "%~1"=="" (
  java -jar CrowdWatch-server.jar airport 8080
) else (
  java -jar CrowdWatch-server.jar %*
)
