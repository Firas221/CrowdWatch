@echo off
rem Builds program\CrowdWatch.jar from source\src and runs the automatic tests.
rem Needs a JDK 17 or newer (javac and jar on the PATH).
rem   build.bat              build and test
rem   build.bat --no-tests   build only
setlocal
cd /d "%~dp0"
set OUT=build\classes
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
echo Compiling...
dir /s /b source\src\*.java > build\sources.txt
javac --release 17 -encoding UTF-8 -Xlint:-options -cp "source\lib\*;program\lib\javafx\win\*" -d "%OUT%" @build\sources.txt
if errorlevel 1 goto failed
xcopy /e /i /q /y source\src\crowdwatch\venues "%OUT%\crowdwatch\venues" > nul
xcopy /e /i /q /y source\src\crowdwatch\net\web "%OUT%\crowdwatch\net\web" > nul
copy /y source\src\crowdwatch\fx\*.css "%OUT%\crowdwatch\fx\" > nul
(
echo Manifest-Version: 1.0
echo Main-Class: crowdwatch.gui.App
echo Class-Path: lib/derby-10.16.1.1.jar lib/derbyshared-10.16.1.1.jar lib/derbytools-10.16.1.1.jar
) > build\manifest.txt
jar cfm program\CrowdWatch.jar build\manifest.txt -C "%OUT%" crowdwatch
if errorlevel 1 goto failed
echo Built program\CrowdWatch.jar
if "%1"=="--no-tests" goto end
echo Running the tests...
pushd program
java -cp "CrowdWatch.jar;lib\*" crowdwatch.test.TestRunner > ..\build\test-output.txt 2>&1
if errorlevel 1 (popd & echo TESTS FAILED - full output in build\test-output.txt & exit /b 1)
popd
powershell -NoProfile -Command "Get-Content build\test-output.txt -Tail 1"
goto end
:failed
echo BUILD FAILED
exit /b 1
:end
endlocal
