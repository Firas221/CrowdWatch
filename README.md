# CrowdWatch

General crowd monitoring and response for any venue: an airport, a stadium, the Holy Mosques or a mall, described in a plain-text venue file. Java 17 or later.

CrowdWatch generalises the Umra Tech crowd-management project. It keeps live people counts per area, works out crowd levels, sends the nearest suitable unit to incidents, and supports the control room with automation, an SOS and evacuation plan, lost-person handling and a live venue map.

## Run it

| What | How |
|---|---|
| Desktop window (Swing) | `program/run.bat` or `program/run.sh`, then choose Airport, Stadium, Umrah or a `.venue` file |
| JavaFX console | `program/run-fx.bat` or `program/run-fx.sh` |
| Server and web console | `program/run-server.bat` or `program/run-server.sh`, then open `http://<this-pc>:8080/` |
| Scout phones | `http://<server>:8080/scout` |
| Tests (108 cases) | `program/run-tests.bat` or `program/run-tests.sh` |

## What is in it

- Hand-written data structures: AVL trees, queues, linked lists, circular buffers, a min-heap, a graph with Dijkstra, merge sort.
- Three consoles (Swing, JavaFX, web), a scout phone page and a JSON API with control and scout keys.
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

Full details, including how to build from source, are in `README.txt` and `docs/CrowdWatch_Design-and-Integration-Guide.pdf`.
