#!/bin/sh
# usage: ./add-user.sh <name> <OPERATOR|OFFICER|ADMIN>   add a user or change role and password
#        ./add-user.sh <name> off|on                            switch a user off or on
#        ./add-user.sh list                                     list the users
# The password is asked twice (or taken from CROWDWATCH_PASSWORD in scripts).
cd "$(dirname "$0")"
java -jar CrowdWatch.jar --user "$@"
