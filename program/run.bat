@echo off
rem Starts the CrowdWatch desktop console (JavaFX). Usage: run.bat [venue code or .venue file]
cd /d "%~dp0"
java --module-path lib\javafx\win --add-modules javafx.controls -cp "CrowdWatch.jar;lib/*" crowdwatch.fx.FxLauncher %*
