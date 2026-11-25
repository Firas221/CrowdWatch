@echo off
rem usage: add-user.bat <name> <VIEWER|OPERATOR|OFFICER|ADMIN>   add a user or change role and password
rem        add-user.bat <name> off^|on                            switch a user off or on
rem        add-user.bat list                                     list the users
rem The password is asked twice (or taken from CROWDWATCH_PASSWORD in scripts).
cd /d "%~dp0"
java -jar CrowdWatch.jar --user %*
