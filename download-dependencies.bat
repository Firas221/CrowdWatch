@echo off
rem Downloads everything the build needs, once: Maven (through the wrapper), the Java libraries
rem (Spring Boot, Derby, test tools) and the web console's libraries (React, MUI, ECharts...).
rem After this, build.bat and build-spring.bat also work without internet.
rem Needs a JDK 17 or newer and Node.js 20+ installed first.
setlocal
java -version || (echo Java is missing: install a JDK 17 or newer first & exit /b 1)
call node --version || (echo Node.js is missing: install Node.js 20 or newer first & exit /b 1)
echo Downloading the web console's libraries...
cd /d "%~dp0web"
call npm ci --no-audit --no-fund || exit /b 1
echo Downloading Maven and the server's libraries...
cd /d "%~dp0server"
call mvnw.cmd -B -q dependency:go-offline || exit /b 1
call mvnw.cmd -B -q -DskipTests package -Dmaven.main.skip -Dspring-boot.repackage.skip >nul 2>&1
echo Done: everything needed to build is downloaded. Next: build.bat and build-spring.bat
