CrowdWatch 3.0 - crowd monitoring and response for any venue
=============================================================

Runs on Java 17 or later. The full guide is docs/CrowdWatch_Design-and-Integration-Guide.pdf.


START
  Server        program/run-server.bat [venue] [port] [control-key] [scout-key]   (or ./run-server.sh)
                venue: airport, stadium, umrah or a .venue file; port 8080 by default.
                Then open http://<this-computer>:8080/ in any browser.
                Uses CrowdWatch-server.jar (Spring Boot, React console, live updates); if it has not
                been built, CrowdWatch.jar's built-in server with the classic console.
  Users         program/add-user.bat <name> <OPERATOR|OFFICER|ADMIN>   (or ./add-user.sh)
                The first user should be an ADMIN. Without users, sign in with an officer name and
                the control key. Without users and without a key, anyone on the network can do
                everything: only for demonstrations.
  HTTPS         set CROWDWATCH_HTTPS=1 before run-server. Own certificate: -Dcrowdwatch.keystore=FILE.p12
                and CROWDWATCH_KEYSTORE_PASSWORD; otherwise a self-signed one is made.
  Scout phones  http://<server>:8080/scout with the unit's device code (Venue settings > Scout device codes)
  Visitors      http://<server>:8080/help (English and Arabic); Venue settings > Print help QR codes
  Desktop       program/run.bat (or ./run.sh): JavaFX console on one computer
  Drill         program/run-drill.bat airport 8081 ..\examples\airport-fire.drill   (training, nothing saved)
  Service       fill in program/service.conf, then install-service-windows.bat (as administrator),
                sudo ./install-service-linux.sh or sudo ./install-service-mac.sh
  Tests         program/run-tests.bat (or ./run-tests.sh): 142 core tests

BUILD FROM SOURCE (top folder; .bat on Windows, .sh elsewhere)
  Needs a JDK 17+, Git and Node.js 20+ (Maven comes with server/mvnw).
  download-dependencies   once: Maven, the Java libraries and the console's libraries
  build                   program/CrowdWatch.jar and the 142 core tests (--no-tests to skip)
  build-spring            the React console and the Spring Boot server: program/CrowdWatch-server.jar,
                          with the server's and the console's tests

THE CONSOLE
  Overview, Areas and crowd, Incidents, Units and scouts, Lost persons, SOS and evacuation, Alerts,
  Reports, Replay, Sites, Venue editor, Venue settings. Changes appear on every screen in under a
  second (LIVE badge). Arabic and light theme in the header. Classic console: /legacy;
  plain dashboard without JavaScript: /classic.

ROLES
  OPERATOR  read everything; counts, incidents, units' progress, SOS, alarms, lost persons, messages
  OFFICER   also the decisions: check-in, reviews, evacuation, broadcasts, public messages,
            automatic dispatch, device codes, audit log, reports
  ADMIN     also users, the venue editor and personal-data requests
  Every change and refusal is in the audit log (database and crowdwatch-data/audit).

DATA
  crowdwatch-data/<venue>/   the venue's Apache Derby database, saved about 10 s after every change
                             and every 60 s (-Dcrowdwatch.saveDelay, -Dcrowdwatch.autosave)
  crowdwatch-data/backups/   daily backups, 7 kept; also copy them elsewhere with
                             -Dcrowdwatch.backupCopy=PATH or CROWDWATCH_BACKUP_COPY
  crowdwatch-data/data.key   key for the encrypted personal data: keep a copy apart from the backups
  crowdwatch-data/accounts.txt  users (password hashes only)
  Restore: stop the server, put backups/<venue>/<date>/<venue> in place of crowdwatch-data/<venue>.

NEW VENUE
  Copy examples/template.venue (it explains every line), edit it, start the server with its path.

CONNECT OTHER SYSTEMS
  Sensors   feed: lines in the venue file (folder, web address, TCP socket), or
            POST /api/occupancy and /api/move with the control key (X-Api-Key header)
  Outputs   gateway: (radio/SMS), notify: (urgent alerts nobody dealt with), GET /api/alerts,
            GET /health, GET /metrics (Prometheus)
  Sites     java -jar CrowdWatch.jar --site add NAME http://address:8080 CONTROL-KEY
  Directory java -jar CrowdWatch.jar --user NAME ROLE directory, with CROWDWATCH_LDAP_URL and CROWDWATCH_LDAP_USER

LICENCE
  MIT (see LICENSE). Apache Derby, OpenJFX, Spring Boot, React and the other libraries keep their own licences.
