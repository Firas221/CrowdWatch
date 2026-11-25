CrowdWatch 2.0 - general crowd monitoring and response (Java 17 or later)

Desktop:   program/run.bat (or ./run.sh) -> choose Airport, Stadium, Umrah, or open a .venue file (Swing window)
Modern UI: program/run-fx.bat (or ./run-fx.sh) -> JavaFX console: everything in the Swing window plus the venue map, lost persons,
           evacuation proposals, public messages and the count check; dark control-room theme (Light button for light)
           (JavaFX 21 for Windows, Linux x64 and macOS is in program/lib/javafx)
Server:    program/run-server.bat        -> control room browsers open http://<this-pc>:8080/ (web console: sign in with officer name and control key)
           The plain dashboard (no JavaScript) is at http://<this-pc>:8080/classic
           java -jar CrowdWatch.jar --server <airport|stadium|umrah|file.venue> [port] [control-key] [scout-key]
           Always give a control key in real use: dashboards, sensors and other systems use it; scout phones use the scout key.
           Requests without the control key: 50/s per address (-Dcrowdwatch.rateLimit=N). Use a reverse proxy with HTTPS beyond the venue network.
Tests:     program/run-tests.bat (or ./run-tests.sh) -> 108 cases, see test-evidence/test-output.txt
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
Officer:   check in every 15 min (Operations tab, JavaFX console or web console); review automatic dispatches; SOS and evacuation there too
Sensors:   implement crowdwatch.feed.CrowdFeed, or POST to /api/occupancy (a count or a +/- change);
           door counters between zones POST /api/move (from, to, count) so a person is never in two zones;
           outer gate counters use from=OUTSIDE / to=OUTSIDE, and the count check alerts when the zones' total and the gates disagree
Outputs:   implement crowdwatch.core.MonitorListener, or poll /api/alerts
Build:     cd source && javac --release 17 -cp "lib/*:../program/lib/javafx/linux/*" -d classes $(find src -name "*.java") && cp -r src/crowdwatch/venues classes/crowdwatch/ && cp src/crowdwatch/fx/*.css classes/crowdwatch/fx/ && mkdir -p classes/crowdwatch/net/web && cp src/crowdwatch/net/web/* classes/crowdwatch/net/web/
           run it with: java -cp "classes:lib/*" crowdwatch.gui.App   (Windows: use ; instead of : in -cp)
Stress:    test-evidence/stress-test/REPORT.txt (load generator, venue and results); web console load: test-evidence/web-console-load.txt
Guide:     docs/CrowdWatch_Design-and-Integration-Guide.pdf (class diagram: docs/CrowdWatch_Class-Diagram.png)
