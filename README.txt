CrowdWatch 2.0 - general crowd monitoring and response (Java 17 or later)

Desktop:   program/run.bat (or ./run.sh) -> choose Airport, Stadium, Umrah, or open a .venue file (Swing window)
Modern UI: program/run-fx.bat (or ./run-fx.sh) -> JavaFX console: everything in the Swing window plus the venue map, lost persons,
           evacuation proposals, public messages and the count check; dark control-room theme (Light button for light)
           (JavaFX 21 for Windows, Linux x64 and macOS is in program/lib/javafx)
Users:     program/add-user.bat <name> <OPERATOR|OFFICER|ADMIN> (or ./add-user.sh) -> personal logins with roles, also asked
           by the desktop windows; two-step sign-in with an authenticator app is set up in Venue settings of the web console;
           the first user should be an ADMIN. Passwords are stored as PBKDF2 hashes in crowdwatch-data/accounts.txt.
           Every change is written to the audit log (database table AUDIT and crowdwatch-data/audit/<venue>/).
Server:    program/run-server.bat        -> control room browsers open http://<this-pc>:8080/ (web console: sign in with
           your user name and password, or with an officer name and the control key)
           The plain dashboard (no JavaScript) is at http://<this-pc>:8080/classic
           java -jar CrowdWatch.jar --server <airport|stadium|umrah|file.venue> [port] [control-key] [scout-key]
           In real use add users (or give a control key): sensors and other systems use the control key; each scout phone
           uses its own device code (issued by an officer, works for its unit only) or the shared scout key.
           Requests without the control key: 50/s per address (-Dcrowdwatch.rateLimit=N).
HTTPS:     add --https (or set CROWDWATCH_HTTPS=1 for run-server): uses crowdwatch-data/tls/server.p12 (-Dcrowdwatch.keystore=...,
           password in CROWDWATCH_KEYSTORE_PASSWORD) or makes a self-signed certificate (browsers warn once until trusted)
Arabic:    the web console and the scout page have a language button (العربية): right to left, kept per browser
Tests:     program/run-tests.bat (or ./run-tests.sh) -> 126 cases, see test-evidence/test-output.txt
New venue: copy examples/template.venue, edit, open via Venue > Open venue file
Scouts:    issued phones/tablets open http://<server>:8080/scout (task, route and route map, Acknowledge, On scene, Resolved, SOS,
           lost-person lookouts with a Found button, 'I found someone who is lost')
Radio:     server mode prints every unit message as RADIO >> ...; add 'gateway: URL' to the venue file for a radio/SMS gateway
Automation: crowded areas get extra units (2 at HIGH from within 10 min walk, 4 at CRITICAL from anywhere,
           crowd-control and security first; reinforce: and reinforcemax: lines),
           sensor checks (stale / impossible counts), chasing units that have not arrived, backup and stand-ins,
           closing calmed crowd incidents, holding flow into busy areas, sending units ahead, sector cover,
           scheduled peaks (event: lines), evacuation PROPOSALS (an officer decides), shifts with handover reports,
           public screen / PA messages drafted for approval, daily database backup, GET /health for monitoring.
           All optional venue lines are explained in examples/template.venue.
Layout:    a venue file may include a drawing (map:, pos:), exit flows, corridor capacities, lifts and fire zones (zone:);
           consoles and scout phones then show the live map, and evacuation plans spread people over exits with a time to clear.
           All sample venues have one; the Umrah layout is a schematic of the real mosques (estimates, not official figures).
Lost:      lost-person reports send a lookout to every unit, widen the search, escalate at 15 and 30 min;
           scouts press Found; found persons are brought to the meeting point (meeting: line).
Officer:   check in every 15 min (Operations tab, JavaFX console or web console); review automatic dispatches; SOS and evacuation there too.
           The Swing Operations tab also has lost persons, the evacuation proposal, alarms, public messages, counting and handover.
Sensors:   feed: lines in the venue file: folder (count files), poll (a web address, text or JSON), tcp (devices on a socket);
           or implement crowdwatch.feed.CrowdFeed, or POST to /api/occupancy (a count or a +/- change);
           door counters between zones POST /api/move (from, to, count) so a person is never in two zones;
           outer gate counters use from=OUTSIDE / to=OUTSIDE, and the count check alerts when the zones' total and the gates disagree
Outputs:   implement crowdwatch.core.MonitorListener, or poll /api/alerts
Build:     build.bat (Windows) or ./build.sh -> compiles source/src, makes program/CrowdWatch.jar and runs the tests
           (./build.sh --no-tests to skip them). Needs a JDK 17 or newer. GitHub runs the same build for every change.
Stress:    test-evidence/stress-test/REPORT.txt (load generator, venue and results); web console load: test-evidence/web-console-load.txt
Guide:     docs/CrowdWatch_Design-and-Integration-Guide.pdf (class diagram: docs/CrowdWatch_Class-Diagram.png)
Licence:   MIT (see LICENSE); Apache Derby and OpenJFX keep their own licences.
Data:      incident details and contacts are encrypted in the database with crowdwatch-data/data.key (or CROWDWATCH_DATA_KEY):
           keep a copy of the key apart from the backups. Archived personal data is erased after 30 days (retention: line).
           -Dcrowdwatch.backupCopy=PATH (or CROWDWATCH_BACKUP_COPY) copies the daily backups to a second disk or share;
           -Dcrowdwatch.autosave=SECONDS changes the 60-second autosave.
