# CrowdWatch

[![Build and test](https://github.com/Firas221/crowdwatch/actions/workflows/ci.yml/badge.svg)](https://github.com/Firas221/crowdwatch/actions/workflows/ci.yml)

General crowd monitoring and response for any venue: an airport, a stadium, the Holy Mosques or a mall, described in a plain-text venue file. Java 17 or later.

CrowdWatch generalises the Umra Tech crowd-management project. It keeps live people counts per area, works out crowd levels, sends the nearest suitable unit to incidents, and supports the control room with automation, an SOS and evacuation plan, lost-person handling and a live venue map.

## Run it

| What | How |
|---|---|
| Desktop console (JavaFX) | `program/run.bat` or `program/run.sh`, then choose Airport, Stadium, Umrah or a `.venue` file |
| Users | `program/add-user.bat <name> <ROLE>` or `./add-user.sh` (roles OPERATOR, OFFICER, ADMIN; the first should be ADMIN) |
| Server and web console | `program/run-server.bat` or `program/run-server.sh`, then open `http://<this-pc>:8080/` and sign in |
| Server on Spring Boot, new React console (preview) | `program/run-spring.bat` or `program/run-spring.sh` (same arguments), then open `http://<this-pc>:8080/`; build it with `build-spring.bat` / `./build-spring.sh` (JDK 17+ and Node.js 20+; `download-dependencies.bat` / `.sh` fetches Maven and all libraries once); see below |
| HTTPS | add `--https`, or set `CROWDWATCH_HTTPS=1` before `run-server` |
| Scout phones | `http://<server>:8080/scout` with the unit's device code |
| Drill (training) | `program/run-drill.bat` or `./run-drill.sh airport 8081 ../examples/airport-fire.drill` |
| Run as a service | fill in `program/service.conf`, then `install-service-windows.bat` (as administrator) or `sudo ./install-service-linux.sh` / `install-service-mac.sh` |
| Tests (141 cases) | `program/run-tests.bat` or `program/run-tests.sh` |
| Build from source | `build.bat` or `./build.sh` (JDK 17+): makes `program/CrowdWatch.jar` and runs the tests |
| VS Code | open the top `CrowdWatch` folder with the Extension Pack for Java; Run and Debug has the desktop console, the server and the tests (settings in `.vscode`, JavaFX for Windows) |

## What is in it

- Hand-written data structures: AVL trees, queues, linked lists, circular buffers, a min-heap, a graph with Dijkstra, merge sort.
- Two consoles (JavaFX desktop and web), a scout phone page and a JSON API.
- Security: personal logins with three roles in the web console, the plain dashboard and the desktop console; optional two-step sign-in with an authenticator app; PBKDF2 password hashes, lock after wrong passwords, sign-ins kept over a restart; a device code per scout phone that works only for its unit; officer approval for restricted areas; an audit log of every change (database and daily file); built-in HTTPS; control and scout keys for other systems.
- Personal data: incident details and contacts encrypted in the database (AES-256-GCM), erased from archived incidents after 30 days; daily backups optionally copied to a second folder.
- Sensors: `feed:` lines connect counting systems by folder, web address (text or JSON) or TCP socket, besides the JSON API.
- Crowd density: with an area's floor area the level also follows people per square metre (dangerous from 4 per m²).
- Reports (Excel/CSV and PDF), replay of any period minute by minute, drills on a training server, a venue editor on the drawing, several venues in one head-office view.
- Messages and photos between scout phones and the control room; the phone reports its position, duty and battery.
- Urgent alerts nobody dealt with go to the duty officer's phone (SMS, WhatsApp or e-mail gateway); heat-stress alerts; camera views per area.
- A public help page with printable QR codes, in English and Arabic, for visitors to report a lost person or ask for help.
- Company directory (LDAP / Active Directory) sign-in, password reset codes, data-protection requests (find, export, erase), Prometheus metrics, daily log files, service installers.
- Arabic: the web console and the scout page switch to Arabic, right to left.
- Automation: automatic dispatch, officer supervision, task acknowledgement, SOS, evacuation proposals and plans, reinforcement of crowded areas, sensor checks, lost persons, shifts and handover reports, public messages that need officer approval, and a count check against the gates.
- Venue layouts: a drawing, area positions or outlines, exit flows, corridor capacities, lifts and fire zones. All sample venues include one; the Umrah layout is a schematic of the real mosques with estimated figures, not official data.
- Apache Derby persistence with archive tables and daily backups.

## Spring Boot and React

The web layer runs on Spring Boot (back end) and React with TypeScript (front end). The Java core, the rules and the 141 tests are unchanged; the JDK server (`--server`) still works.

- `server/`: Spring Boot program. It runs the same monitor, database and accounts as `--server`.
  - **Spring Security** identifies every request with CrowdWatch's accounts (session token, dashboard cookie, control key, scout key, device code) and checks each endpoint's role before any code runs, using the same role table as the API (`ApiServer.refusal`). Refused changes go to the audit log; requests without the control key are rate-limited. Passwords, two-step codes, lockout and LDAP stay in `Accounts`.
  - **Spring controllers** serve what the consoles read (`ConsoleController`: overview, areas, incidents, units, alerts, venue, layout, lost persons, public messages, incident, readings, evacuation, status, route, summary, handover), from `ConsoleData`, the same code the JDK server uses. Changes go through `ApiBridge` to the API, which writes the audit log and checks that a device code acts only for its own unit.
  - **Live updates**: changes are pushed over a WebSocket (`/ws`) instead of each browser asking every 3 seconds.
- `web/`: React console (Vite, MUI, Apache ECharts, TanStack Query, react-i18next, English and Arabic) with every view: overview with the venue map, areas, incidents (with messages and photos), units, lost persons, SOS and evacuation, alerts, reports, replay, sites, venue editor and venue settings (users, two-step sign-in, device codes, audit log, personal-data requests). `npm run dev` serves it on port 5173 against a server on 8080. The classic console stays at `/legacy`, the plain dashboard at `/classic`.
- Build: `download-dependencies` once, then `build-spring` (JDK 17+ and Node.js 20+; Maven comes with `server/mvnw`). Run: `program/run-spring`.

## Folders

| Folder | Contents |
|---|---|
| `source/` | Java source (`source/src/crowdwatch/...`) and the Derby libraries |
| `server/` | Spring Boot server (Maven) |
| `web/` | React and TypeScript console (Vite) |
| `program/` | Ready-to-run `CrowdWatch.jar`, run scripts and JavaFX for Windows, Linux and macOS |
| `docs/` | Design and Integration Guide (PDF and Word) and the class diagram |
| `examples/` | Template venue file explaining every line, its drawing, and a CSV of sample counts |
| `test-evidence/` | Test output, stress-test tools and results, web console load test |
| `screenshots/` | Screenshots of the consoles |

GitHub builds and tests every change on Windows and Linux. Pushing a tag such as `v2.0` publishes a release with the ready-to-run zip.

Full details are in `README.txt` and `docs/CrowdWatch_Design-and-Integration-Guide.pdf`.

## Licence

MIT (see `LICENSE`). Apache Derby (Apache License 2.0) and OpenJFX (GPL v2 with the Classpath Exception) keep their own licences.
