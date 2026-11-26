# CrowdWatch

[![Build and test](https://github.com/Firas221/crowdwatch/actions/workflows/ci.yml/badge.svg)](https://github.com/Firas221/crowdwatch/actions/workflows/ci.yml)

General crowd monitoring and response for any venue: an airport, a stadium, the Holy Mosques or a mall, described in a plain-text venue file. Java 17 or later.

CrowdWatch generalises the Umra Tech crowd-management project. It keeps live people counts per area, works out crowd levels, sends the nearest suitable unit to incidents, and supports the control room with automation, an SOS and evacuation plan, lost-person handling and a live venue map.

## Run it

| What | How |
|---|---|
| Desktop window (Swing) | `program/run.bat` or `program/run.sh`, then choose Airport, Stadium, Umrah or a `.venue` file |
| JavaFX console | `program/run-fx.bat` or `program/run-fx.sh` |
| Users | `program/add-user.bat <name> <ROLE>` or `./add-user.sh` (roles OPERATOR, OFFICER, ADMIN; the first should be ADMIN) |
| Server and web console | `program/run-server.bat` or `program/run-server.sh`, then open `http://<this-pc>:8080/` and sign in |
| HTTPS | add `--https`, or set `CROWDWATCH_HTTPS=1` before `run-server` |
| Scout phones | `http://<server>:8080/scout` with the unit's device code |
| Tests (117 cases) | `program/run-tests.bat` or `program/run-tests.sh` |
| Build from source | `build.bat` or `./build.sh` (JDK 17+): makes `program/CrowdWatch.jar` and runs the tests |

## What is in it

- Hand-written data structures: AVL trees, queues, linked lists, circular buffers, a min-heap, a graph with Dijkstra, merge sort.
- Three consoles (Swing, JavaFX, web), a scout phone page and a JSON API.
- Security: personal logins with three roles, PBKDF2 password hashes, lock after wrong passwords, a device code per scout phone that works only for its unit, an audit log of every change (database and daily file), built-in HTTPS, and control and scout keys for other systems.
- Arabic: the web console and the scout page switch to Arabic, right to left.
- Automation: automatic dispatch, officer supervision, task acknowledgement, SOS, evacuation proposals and plans, reinforcement of crowded areas, sensor checks, lost persons, shifts and handover reports, public messages that need officer approval, and a count check against the gates.
- Venue layouts: a drawing, area positions, exit flows, corridor capacities, lifts and fire zones. All sample venues include one; the Umrah layout is a schematic of the real mosques with estimated figures, not official data.
- Apache Derby persistence with archive tables and daily backups.

## Folders

| Folder | Contents |
|---|---|
| `source/` | Java source (`source/src/crowdwatch/...`) and the Derby libraries |
| `program/` | Ready-to-run `CrowdWatch.jar`, run scripts and JavaFX for Windows, Linux and macOS |
| `docs/` | Design and Integration Guide (PDF and Word) and the class diagram |
| `examples/` | Template venue file explaining every line, its drawing, and a CSV of sample counts |
| `test-evidence/` | Test output, stress-test tools and results, web console load test |
| `screenshots/` | Screenshots of the consoles |

GitHub builds and tests every change on Windows and Linux. Pushing a tag such as `v2.0` publishes a release with the ready-to-run zip.

Full details are in `README.txt` and `docs/CrowdWatch_Design-and-Integration-Guide.pdf`.

## Licence

MIT (see `LICENSE`). Apache Derby (Apache License 2.0) and OpenJFX (GPL v2 with the Classpath Exception) keep their own licences.
