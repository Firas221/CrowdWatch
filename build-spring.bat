@echo off
rem Builds the Spring Boot server with the React console: program\CrowdWatch-server.jar.
rem Needs a JDK 17 or newer and Node.js 20+ (Maven is fetched by server\mvnw.cmd) (only for building; the jar needs Java only).
rem   build-spring.bat              build the console and the server, run both test suites
rem   build-spring.bat --no-tests   build only
setlocal
cd /d "%~dp0web"
echo Building the web console (React, TypeScript)...
call npm ci --no-audit --no-fund || exit /b 1
if not "%~1"=="--no-tests" (call npm test || exit /b 1)
call npm run build || exit /b 1
cd /d "%~dp0server"
echo Building the server (Spring Boot)...
if "%~1"=="--no-tests" (call mvnw.cmd -B -q package -DskipTests || exit /b 1) else (call mvnw.cmd -B -q package || exit /b 1)
copy /y target\CrowdWatch-server.jar ..\program\CrowdWatch-server.jar >nul
echo Built program\CrowdWatch-server.jar - start it with program\run-spring.bat
