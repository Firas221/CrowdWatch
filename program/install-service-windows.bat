@echo off
rem Installs CrowdWatch as a task that starts when Windows starts (and restarts after a failure).
rem Run in a command prompt opened with "Run as administrator":   install-service-windows.bat
rem Remove:   install-service-windows.bat remove
rem Settings: service.conf (VENUE, PORT, CONTROL_KEY, SCOUT_KEY, CROWDWATCH_HTTPS)
setlocal
cd /d "%~dp0"
net session >nul 2>&1
if errorlevel 1 (echo Run this in a command prompt opened as administrator & exit /b 1)
if /i "%1"=="remove" (
  schtasks /end /tn CrowdWatch >nul 2>&1
  schtasks /delete /tn CrowdWatch /f
  exit /b 0
)
rem the task runs run-service.bat, which reads service.conf and restarts the server if it stops
schtasks /create /tn CrowdWatch /tr "\"%~dp0run-service.bat\"" /sc onstart /ru SYSTEM /rl highest /f
if errorlevel 1 exit /b 1
schtasks /run /tn CrowdWatch
echo CrowdWatch starts with Windows now. Logs: crowdwatch-data\logs
