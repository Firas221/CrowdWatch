// Builds docs/CrowdWatch_Design-and-Integration-Guide.docx. The text is below; the test table is read
// from test-evidence/test-output.txt, so it always shows the latest run. See build-guide.sh.
const fs = require('fs');
const path = require('path');
const {
  Document, Packer, Paragraph, TextRun, ImageRun, Table, TableRow, TableCell, WidthType, ShadingType, AlignmentType,
  HeadingLevel, LevelFormat, BorderStyle, Footer, PageNumber, PageBreak, TableLayoutType,
} = require('docx');

const ROOT = path.join(__dirname, '..', '..');
const OUT = path.join(ROOT, 'docs', 'CrowdWatch_Design-and-Integration-Guide.docx');
const VERSION = '3.0';
const FONT = 'Calibri', MONO = 'Consolas';
const PAGE_W = 11906, MARGIN = 1134;            // A4, 2 cm margins
const TEXT_W = PAGE_W - 2 * MARGIN;             // 9638 DXA
const NAVY = '1F4E79', LIGHT = 'DBE7F5', ZEBRA = 'F5F8FC', GREY = '5A6472';

// ---------- inline text: **bold** and `code` ----------
function runs(text, opts = {}) {
  const out = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0, m;
  while ((m = re.exec(text))) {
    if (m.index > last) out.push(new TextRun({ text: text.slice(last, m.index), ...opts }));
    const t = m[0];
    if (t.startsWith('**')) out.push(new TextRun({ text: t.slice(2, -2), bold: true, ...opts }));
    else out.push(new TextRun({ text: t.slice(1, -1), font: MONO, size: opts.size ? opts.size - 1 : 20, ...opts, ...(opts.font ? {} : {}) }));
    last = m.index + t.length;
  }
  if (last < text.length) out.push(new TextRun({ text: text.slice(last), ...opts }));
  return out;
}

const body = [];
const add = (...x) => body.push(...x);
const P = (text, extra = {}) => add(new Paragraph({ children: runs(text), spacing: { after: 120, line: 288 }, ...extra }));
let spacer = null;   // the gap after the last table, dropped when a chapter starts right after it
const H1 = (text, pageBreak = true) => (body[body.length - 1] === spacer && body.pop(), add(new Paragraph({ heading: HeadingLevel.HEADING_1, pageBreakBefore: pageBreak, children: [new TextRun(text)] })));
const H2 = (text) => add(new Paragraph({ heading: HeadingLevel.HEADING_2, children: [new TextRun(text)] }));
const H3 = (text) => add(new Paragraph({ heading: HeadingLevel.HEADING_3, children: [new TextRun(text)] }));
const bullets = (items) => items.forEach((t) => add(new Paragraph({ numbering: { reference: 'bullets', level: 0 }, children: runs(t), spacing: { after: 80, line: 276 } })));
let numberedLists = 0;
const steps = (items) => {
  const ref = 'steps' + (numberedLists++ % 40);
  items.forEach((t) => add(new Paragraph({ numbering: { reference: ref, level: 0 }, children: runs(t), spacing: { after: 80, line: 276 } })));
};
const code = (lines) => lines.forEach((l, k) => add(new Paragraph({
  children: [new TextRun({ text: l === '' ? ' ' : l, font: MONO, size: 18 })],
  shading: { type: ShadingType.CLEAR, color: 'auto', fill: 'F2F4F7' },
  spacing: { before: k === 0 ? 60 : 0, after: k === lines.length - 1 ? 140 : 0, line: 252 },
  indent: { left: 140, right: 140 }, keepLines: true, keepNext: k < lines.length - 1,
})));
const note = (text) => add(new Paragraph({
  children: runs(text, { size: 20 }), spacing: { before: 60, after: 160, line: 276 },
  shading: { type: ShadingType.CLEAR, color: 'auto', fill: 'FFF7E6' },
  border: { left: { style: BorderStyle.SINGLE, size: 18, color: 'D97706', space: 8 } }, indent: { left: 160 },
}));

function cell(text, width, header, zebra) {
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    shading: header ? { type: ShadingType.CLEAR, color: 'auto', fill: LIGHT } : zebra ? { type: ShadingType.CLEAR, color: 'auto', fill: ZEBRA } : undefined,
    margins: { top: 50, bottom: 50, left: 90, right: 90 },
    children: String(text).split('\n').map((line) => new Paragraph({ children: runs(line, { size: 19, bold: header || undefined, color: header ? NAVY : undefined }), spacing: { after: 0, line: 252 } })),
  });
}
/** A table: header row, rows, relative column widths. */
function table(headers, rows, rel) {
  const total = rel.reduce((a, b) => a + b, 0);
  const widths = rel.map((r) => Math.floor((r / total) * TEXT_W));
  widths[widths.length - 1] += TEXT_W - widths.reduce((a, b) => a + b, 0);
  const border = { style: BorderStyle.SINGLE, size: 4, color: 'B8C6D6' };
  add(new Table({
    width: { size: TEXT_W, type: WidthType.DXA }, columnWidths: widths, layout: TableLayoutType.FIXED,
    borders: { top: border, bottom: border, left: border, right: border, insideHorizontal: border, insideVertical: border },
    rows: [
      new TableRow({ tableHeader: true, cantSplit: true, children: headers.map((h, i) => cell(h, widths[i], true)) }),
      ...rows.map((r, k) => new TableRow({ cantSplit: true, children: r.map((c, i) => cell(c, widths[i], false, k % 2 === 1)) })),
    ],
  }));
  // a small gap after the table (tiny, so it never spills onto a page of its own)
  spacer = new Paragraph({ children: [new TextRun({ text: '', size: 4 })], spacing: { before: 0, after: 100, line: 120 } });
  add(spacer);
}

/** PNG width and height from its header. */
function pngSize(file) {
  const b = fs.readFileSync(file);
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}
/** An image scaled to a width in centimetres (at most the text width), with a caption. */
function image(rel, caption, widthCm = 17) {
  const file = path.join(ROOT, rel);
  const { w, h } = pngSize(file);
  const maxPx = Math.round((Math.min(widthCm, 17) / 2.54) * 96);
  const width = Math.min(maxPx, w), height = Math.round((h * width) / w);
  add(new Paragraph({ alignment: AlignmentType.CENTER, keepNext: true, spacing: { before: 120, after: 60 },
    children: [new ImageRun({ type: 'png', data: fs.readFileSync(file), transformation: { width, height }, altText: { title: caption, description: caption, name: path.basename(file) } })] }));
  add(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 220 }, children: [new TextRun({ text: caption, italics: true, size: 19, color: GREY })] }));
}

/** The test cases of the latest run: number, title, expected, result. */
function testCases() {
  const txt = fs.readFileSync(path.join(ROOT, 'test-evidence', 'test-output.txt'), 'utf8');
  const out = [];
  const re = /^TC-(\d+)\s+(.+)\n\s+Expected:\s*(.*)\n\s+Actual:\s*(.*)\n\s+Result:\s*(\w+)/gm;
  let m;
  while ((m = re.exec(txt))) out.push({ n: m[1], title: m[2].trim(), expected: m[3].trim(), actual: m[4].trim(), result: m[5] });
  const total = /TOTAL: (\d+) passed, (\d+) failed/.exec(txt);
  return { cases: out, passed: total ? +total[1] : 0, failed: total ? +total[2] : 0 };
}

// =====================================================================================
// Title page
// =====================================================================================
add(new Paragraph({ spacing: { before: 2400, after: 120 }, children: [new TextRun({ text: 'CrowdWatch', bold: true, size: 72, color: NAVY, font: FONT })] }));
add(new Paragraph({ spacing: { after: 120 }, children: [new TextRun({ text: 'Crowd monitoring and response for any venue', size: 36, color: NAVY })] }));
add(new Paragraph({ spacing: { after: 600 }, border: { bottom: { style: BorderStyle.SINGLE, size: 12, color: NAVY, space: 6 } },
  children: [new TextRun({ text: 'Design and Integration Guide', size: 32, color: GREY })] }));
add(new Paragraph({ spacing: { after: 800 }, children: runs(`Version ${VERSION}  ·  Java 17 or later  ·  Spring Boot and React  ·  Apache Derby  ·  generalised from Umra Tech`, { size: 22, color: GREY }) }));
table(['Item', 'Details'], [
  ['Student name(s)', '[fill in]'], ['Student ID(s)', '[fill in]'], ['Course and section', '[fill in]'], ['Instructor', '[fill in]'],
  ['Submission date', '[fill in]'], ['Software version', `CrowdWatch ${VERSION} · runs on Java 17 or later`],
], [1, 2]);

H1('Contents');
[
  '1  What CrowdWatch is', '2  How the original Umra Tech requirements are met', '3  Quick start', '4  Architecture',
  '5  Data structures', '6  Rules', '7  Venue files', '8  The control-room console', '9  The desktop console',
  '10  Scout phones, radio and the visitor help page', '11  Automation and the emergency plan', '12  Security',
  '13  Data, saving and backups', '14  Integrating real systems', '15  Running a venue', '16  Building and testing',
  '17  Weak points removed', '18  Known limitations and future work',
].forEach((t) => add(new Paragraph({ children: [new TextRun({ text: t, size: 24 })], spacing: { after: 100 } })));

// =====================================================================================
H1('1  What CrowdWatch is');
P('CrowdWatch keeps live people counts for every area of a venue, works out how crowded each area is, sends the nearest suitable response unit to incidents, and supports the control room with automation, an SOS and evacuation plan, lost-person handling and a live venue map. It is a general version of the Umra Tech crowd-management project: the core knows nothing about mosques, airports or stadiums. It knows areas with a capacity, walking links between them, response units with skills, and incident categories with a priority. Each venue is described in a short text file, so the same program runs an airport, a stadium, the Holy Mosques or a mall without code changes.');
table(['Umra Tech', 'CrowdWatch'], [
  ['Gate or courtyard with a manual crowd level', 'Area with a capacity and a live people count; the level is worked out from the venue\'s thresholds (and from people per m² when the floor area is known). Staff can still set a level by hand.'],
  ['Two fixed sites (Makkah, Madinah)', 'Any number of sectors; which areas can reach which comes from the area graph'],
  ['Distance = difference of position numbers', 'Real walking time: shortest path (Dijkstra) over the area graph'],
  ['Scout team with languages', 'Response unit with skills and languages (security, first aid, wheelchair, lost person, ...)'],
  ['Lost-person case and assistance request (two types)', 'One incident type; the categories (code, priority, required skill, personal data) come from the venue file'],
  ['Three fixed queues', 'One first-in-first-out queue per priority (CRITICAL, HIGH, NORMAL, LOW); escalation moves a waiting incident up'],
  ['Manual input only', 'Sensor feeds (folder, web address, TCP socket), the JSON API, and listeners that pass alerts on to other systems'],
  ['Crowd level only', 'Trend warning: people per minute predicts the minutes to CRITICAL and warns up to 10 minutes early'],
  ['One desktop', 'A server for the whole control room: a React console in any browser with live updates, scout phones, a visitor help page and a JSON API; plus the JavaFX desktop console'],
  ['-', 'Crowd-aware routes for signage and staff that avoid HIGH and CRITICAL areas and never pass restricted areas'],
], [1, 2]);
H2('What is new in version 3.0');
bullets([
  '**Spring Boot server.** The web layer runs on Spring Boot: one runnable jar (`program/CrowdWatch-server.jar`) with its web server inside. The core, the rules and the database code are unchanged.',
  '**React console.** The control-room console is rebuilt in React with TypeScript: every view of the earlier console, a live venue map, charts, English and Arabic, light and dark, and phone screens.',
  '**Live updates.** The server tells every console the moment something changes (WebSocket), instead of each browser asking every 3 seconds. A change made anywhere appears on the other screens in under a second.',
  '**Spring Security.** Every request is identified and its role checked before any CrowdWatch code runs, with one role table shared by both servers.',
  '**Spring controllers.** What the consoles read and the 22 control-room changes are Spring endpoints; they use the same rules, audit log and data code as before.',
  '**Saving within seconds.** Changes are saved about 10 seconds after they happen, not only every 60 seconds.',
  '**Easier set-up.** One script downloads everything the build needs; Maven no longer has to be installed; the run scripts and service installers start the new server.',
]);

// =====================================================================================
H1('2  How the original Umra Tech requirements are met');
P('CrowdWatch keeps every feature of the original Umra Tech brief. The Umrah venue file (`umrah.venue`) is loaded with the same code as the airport and the stadium, and test cases TC-33 and TC-34 check that it still behaves like the original system.');
table(['Umra Tech requirement', 'How CrowdWatch meets it', 'Evidence'], [
  ['Monitor the crowd level at each gate and courtyard', 'Each gate or courtyard is an area in umrah.venue. Staff can still record LOW to CRITICAL by hand, or a sensor count sets the level automatically.', 'TC-11, TC-32'],
  ['Two sites: Makkah and Madinah', 'Two sectors in one venue file. They are not linked, so a team in Makkah is never sent to Madinah.', 'TC-34'],
  ['Scout teams with languages', 'Units with languages and skills (T01 to T16). A team that speaks the reporter\'s language is preferred.', 'TC-33'],
  ['Lost-person cases', 'Category LOST_PERSON (CRITICAL, skill lost-person, personal data erased when resolved), with a lookout to every unit (section 11.7).', 'TC-12, TC-21, TC-92 to TC-96'],
  ['Assistance requests (wheelchair, elderly, medical)', 'Categories MEDICAL, ELDERLY, WHEELCHAIR and DIRECTIONS, each with its own priority and skill.', 'TC-13, TC-16'],
  ['Send the nearest suitable team', 'Shortest walking time (Dijkstra) over the area graph instead of the difference of position numbers.', 'TC-09, TC-12, TC-13'],
  ['Waiting queues when no team is free', 'Four first-in-first-out queues by priority; escalation moves a case up.', 'TC-16, TC-18, TC-22'],
  ['Case history and search by ID', 'A linked-list history per incident and unit; lookup by ID in AVL trees.', 'TC-01 to TC-04, TC-38'],
  ['Save and reload the data', 'Apache Derby database, one folder per venue, saved about 10 seconds after every change.', 'TC-38, TC-142'],
  ['Scouts are told about their task', 'The task goes to the scout\'s phone and radio, with acknowledgement, reminder and reassignment; automatic dispatch when an area gets crowded.', 'TC-48, TC-53, TC-61, TC-63'],
], [3, 5, 2]);

// =====================================================================================
H1('3  Quick start');
H2('3.1  What to install');
P('To **run** CrowdWatch you need Java 17 or newer. To **build** it from source you also need Git and Node.js 20 or newer; Maven is downloaded by the project itself (`server/mvnw`). The ready-to-run zip of each release already contains both jars, so a venue that uses a release needs only Java.');
table(['System', 'Commands'], [
  ['Windows (PowerShell)', '`winget install --id Git.Git -e`\n`winget install --id EclipseAdoptium.Temurin.17.JDK -e`\n`winget install --id OpenJS.NodeJS.LTS -e`\nthen open a new PowerShell window'],
  ['macOS (Homebrew)', '`brew install git node`\n`brew install --cask temurin@17`'],
  ['Ubuntu or Debian', '`sudo apt install -y git openjdk-17-jdk curl`\n`curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -`\n`sudo apt install -y nodejs`'],
], [1, 3]);
H2('3.2  Build');
P('In the top folder of the project (use the `.bat` files on Windows and the `.sh` files elsewhere):');
steps([
  '`download-dependencies` fetches Maven, the Java libraries (Spring Boot, Derby, test tools) and the console\'s libraries (React, MUI, ECharts). Afterwards the builds also work without internet.',
  '`build` compiles the core, makes `program/CrowdWatch.jar` and runs the 142 core tests.',
  '`build-spring` builds the React console and the Spring Boot server, runs their tests, and makes `program/CrowdWatch-server.jar`.',
]);
H2('3.3  Run');
table(['What', 'How'], [
  ['Control-room server', '`program/run-server.bat [venue] [port] [control-key] [scout-key]` (or `run-server.sh`), then open `http://<this-computer>:8080/` in any browser. Venue: airport, stadium, umrah or a .venue file.'],
  ['Users', '`program/add-user.bat <name> <OPERATOR|OFFICER|ADMIN>` (or `add-user.sh`). The first user should be an ADMIN.'],
  ['HTTPS', 'Set `CROWDWATCH_HTTPS=1` before `run-server` (section 12.7).'],
  ['Desktop console', '`program/run.bat` (or `run.sh`): choose a venue (section 9).'],
  ['Scout phones', '`http://<server>:8080/scout` with the unit\'s device code (section 10).'],
  ['Training drill', '`program/run-drill.bat airport 8081 ..\\examples\\airport-fire.drill` (section 15.3).'],
  ['As a service', 'Fill in `program/service.conf`, then `install-service-windows.bat` (as administrator), `sudo ./install-service-linux.sh` or `sudo ./install-service-mac.sh` (section 15.1).'],
  ['Tests', '`program/run-tests.bat` (or `run-tests.sh`): the 142 core tests.'],
], [1, 3]);
note('`run-server` starts the Spring Boot server (`CrowdWatch-server.jar`) when it is in `program/`, which it is in every release. If it has not been built yet, it starts the built-in server of `CrowdWatch.jar` with the classic console and says so. Both use the same data.');

// =====================================================================================
H1('4  Architecture');
H2('4.1  The parts');
table(['Part', 'Folder', 'What it does'], [
  ['Core', '`source/src/crowdwatch/core, model, ds`', 'CrowdMonitor holds the venue\'s state and every rule; AutoRules adds the automation; hand-written data structures (section 5). Knows nothing about the web.'],
  ['Data', '`source/src/crowdwatch/db`', 'Apache Derby database per venue: save, load, archive, backups, encryption of personal data, saving soon after a change (SaveSoon).'],
  ['Shared web code', '`source/src/crowdwatch/net`', 'ApiServer (rights, audit log, the 22 changes, the built-in JDK web server), ConsoleData (what the consoles read), Accounts (users and sessions), ApiExtras (reports, replay, messages, privacy), the classic pages.'],
  ['Spring Boot server', '`server/` (crowdwatch.spring)', 'Spring Security in front of every request; Spring controllers for reads and changes; the live-update WebSocket; HTTPS through Spring; serves the React console.'],
  ['React console', '`web/`', 'The control-room console in TypeScript (Vite, MUI, Apache ECharts, TanStack Query, react-i18next).'],
  ['Desktop console', '`source/src/crowdwatch/fx`', 'The JavaFX console for one computer; works on the same monitor and database.'],
  ['Program entry', '`source/src/crowdwatch/gui`', 'App: users, sites, drills, the built-in server, the desktop console; App.prepare() sets up a server for both web servers.'],
  ['Sensors', '`source/src/crowdwatch/feed`', 'Folder, web-address and TCP feeds, CSV, simulation and drill scripts.'],
], [2, 3, 6]);
H2('4.2  How a request is handled');
steps([
  'A browser, scout phone or other system sends a request to the Spring Boot server (Tomcat, HTTP or HTTPS).',
  '**Spring Security** identifies the caller with CrowdWatch\'s accounts: a personal session token, the plain dashboard\'s cookie, the control key, the scout key or a scout phone\'s device code. Requests without the control key are rate-limited per address. The endpoint\'s role is checked against the shared role table (`ApiServer.refusal`): no sign-in gives 401, a role that may not use it 403, and a refused change is written to the audit log.',
  'A **Spring controller** takes the request: `ConsoleController` for what the consoles read, `ActionController` for the control-room changes, `ApiBridge` for everything else (sign-in, users, messages, reports, the pages).',
  'The controller calls the **shared web code** in `ApiServer`: `read()` (half-second cache, monitor lock, fair turns), `act()` (a device code may act only for its own unit; a signed-in user always acts in their own name; the audit log) or `dispatch()`.',
  '**CrowdMonitor** applies the rules. Its listeners run afterwards on their own thread: the live-update WebSocket tells every console that something changed, SaveSoon schedules a save, gateways and SMS services are told about alerts.',
]);
P('The built-in JDK server (`CrowdWatch.jar --server`) calls the same `ApiServer` methods directly, so both servers share the rules, rights, audit log and data code, and the 142 core tests cover the code the Spring server runs.');
image('docs/CrowdWatch_Web-Layer.png', 'The web layer: clients, the Spring Boot server, the shared web code, the core and the database');
H2('4.3  Live updates');
P('Each console keeps one WebSocket open to `/ws`, signed in like any other request (the session token or control key is given in the address, because browsers cannot add headers to a WebSocket). After a change the server sends `{"type":"change"}` (at most one every 300 ms) and, for an alert, `{"type":"alert","kind":...}`. The messages carry no venue data: the console then reloads what it shows through the normal API, which checks the user\'s rights. Each connection\'s sign-in is checked again every 60 seconds, and connections of users who signed out are closed. While the socket is down the console asks every 3 seconds, as before; the LIVE badge shows which is happening.');
H2('4.4  Class diagram of the core');
image('docs/CrowdWatch_Class-Diagram.png', 'The core: CrowdMonitor owns the model in AVL trees and linked lists; feeds, listeners, channels and the database work through it');
P('Filled diamond = composition, solid arrow = association, dashed arrow = dependency, hollow triangle = implements. Full-size images: `docs/CrowdWatch_Class-Diagram.png` and `docs/CrowdWatch_Web-Layer.png` (also as SVG).');

// =====================================================================================
H1('5  Data structures');
P('The core uses hand-written, generic data structures (package `crowdwatch.ds`), not the Java collections.');
table(['Structure', 'Used for', 'Cost'], [
  ['AVL tree (self-balancing search tree)', 'Areas, units, incidents and categories by ID; graph node index; distance cache; request parameters', 'O(log n) insert, search and delete, guaranteed'],
  ['Circular buffer', 'The last 60 sampled readings per area (at most one per 10 s, about 10 minutes) for the trend; the last 20 messages in each unit\'s inbox', 'O(1) add; fixed memory'],
  ['Graph (adjacency lists)', 'Areas and walking links: routes, nearest unit, evacuation plans, lost-person search areas, reinforcement distances', 'O(V+E) space'],
  ['Min-heap', 'Dijkstra frontier', 'O(log n) insert and poll; Dijkstra O((V+E) log V)'],
  ['Queue × 4', 'Waiting incidents per priority; the listener event queue', 'O(1) enqueue and dequeue'],
  ['Linked list', 'Alerts (newest 1,000), histories, skills, listeners, search paths', 'O(1) add at either end'],
  ['Merge sort', 'Incidents by priority and time, areas by load, units; evacuation planning (largest crowd first)', 'O(n log n), stable'],
], [3, 6, 3]);

// =====================================================================================
H1('6  Rules');
bullets([
  '**Crowd level** LOW, MODERATE, HIGH or CRITICAL from the occupancy percentage and the venue\'s three thresholds (airport 60 / 80 / 95). With a floor area the level also follows people per m² (section 11.9); the more dangerous of the two counts. Rising to HIGH or CRITICAL raises an alert; dropping below HIGH raises an all-clear.',
  '**Dispatch:** among AVAILABLE units that have the category\'s skill and can reach the area, the shortest walking time wins; a unit that does not speak the reporter\'s language gets a small penalty.',
  '**No suitable unit:** the incident waits in its priority queue. When a unit becomes free, CRITICAL is served first, then HIGH, NORMAL and LOW; first come, first served within a level.',
  '**Restricted areas:** a unit moves in only with an officer\'s approval; public routes never pass through them.',
  '**Personal data:** categories marked personal (lost person, reduced mobility, ...) have their details and contact erased when resolved.',
  'A unit with an open incident cannot change its status; it must resolve the incident or be reassigned first.',
  '**Trend:** from the readings of the last 10 minutes, the change in people per minute predicts when the area reaches CRITICAL; if within 10 minutes, a TREND alert is raised once, and not again for the same area within 10 minutes.',
  'Readings are sampled: at most one stored reading per 10 seconds, so the 60 slots always cover about the last 10 minutes, even when a turnstile reports every person.',
  'When a unit is on scene, or its incident is resolved, its position becomes the incident\'s area, so the next dispatch uses its real position.',
  '**Alert noise:** a level only drops once the occupancy is 2 percentage points below its threshold (HIGH from 80 %, back to MODERATE below 78 %).',
  'Only the newest 1,000 alerts are kept. Text typed by users is limited to the database column sizes, and requests to 64 KB (3 MB for a message with a photo).',
  'Resolved incidents leave memory after 24 hours (`archive:`) and are kept in the database\'s archive tables; the summary still counts them.',
]);

// =====================================================================================
H1('7  Venue files');
P('A venue is one text file. Copy `examples/template.venue`, which explains every line, edit it, and start the server or the desktop console with its path. Each venue gets its own database folder, `crowdwatch-data/<code>`.');
code([
  'venue: Sample International Airport',
  'code: airport',
  'thresholds: 60 80 95',
  'area: SEC-1 | Terminal 1 Security Screening | Landside T1 | CHECKPOINT | 450',
  'area: APRON | Apron and Stand Area | Airside | RESTRICTED | 150 | restricted',
  'link: CHECKIN-1 SEC-1 3',
  'category: SECURITY | Security concern / unattended bag | CRITICAL | security',
  'category: PRM | Reduced-mobility assistance | NORMAL | wheelchair | personal',
  'unit: A02 | Security Team Alpha | SEC-1 | Arabic, English, security, crowd-control',
  'auto: CROWD HIGH trend',
  'exit: CURB-1 CURB-2 TRAIN',
]);
H2('7.1  The lines');
table(['Line', 'Meaning (default in brackets)'], [
  ['`venue:` `code:` `thresholds:`', 'Display name; short ID (also the database folder); the percentages where MODERATE, HIGH and CRITICAL start'],
  ['`area:`', 'ID | name | sector | type | capacity | restricted (optional)'],
  ['`link:`', 'AREA AREA minutes or auto [people per minute] [stairs or lift]; two-way; auto works the time out from the drawing'],
  ['`category:`', 'CODE | label | priority | required skill (blank = any unit) | personal (erase details when resolved)'],
  ['`unit:`', 'ID | name | starting area | skills and languages [| HH:MM-HH:MM working hours]'],
  ['`occupancy:`', 'Starting count of an area'],
  ['`auto:`', 'CATEGORY HIGH or CRITICAL [trend]: report this category automatically when an area reaches the level (section 11.1)'],
  ['`exit:`', 'Exits and assembly points used by the evacuation plan'],
  ['`supervision:` `ack:`', 'Minutes between officer check-ins (15); minutes a unit has to acknowledge a task (2)'],
  ['`gateway:` `notify:`', 'Radio or SMS gateway that receives every unit message; address for urgent alerts nobody dealt with [minutes] (3)'],
  ['`archive:` `retention:`', 'How long resolved incidents stay in memory (24h); days before archived personal data is erased (30)'],
  ['`feed:`', 'folder PATH [s], poll URL [s] [TOKEN-VARIABLE] or tcp PORT [addresses]: sensor connections (section 14.1)'],
  ['`stale:` `jump:`', 'Minutes of silence before a sensor is stale (5); % of capacity a count may jump within a minute (40)'],
  ['`onscene:` `maxwait:` `autoclose:`', 'Chase a unit not on scene (10); stand-in after waiting (10); close a calmed automatic incident (10)'],
  ['`reinforce:` `reinforceskills:` `reinforcemax:` `reinforcehold:`', 'Units wanted at HIGH and CRITICAL (2 4); skills sent first (crowd-control security); longest walk at HIGH (10); minutes calm before they return (10)'],
  ['`preposition:` `holdflow:` `cover:`', 'Send a unit to an area CRITICAL within this time (15); hold flow into HIGH areas (on); a free unit in every sector (on)'],
  ['`propose:` `signs:`', 'Minutes CRITICAL and rising before an evacuation is proposed (10); draft public messages for approval (on)'],
  ['`reconcile:`', 'Alert when the areas\' total and the gate counts differ by more than this % (5)'],
  ['`meeting:` `lost:`', 'Where found persons are brought; first search radius and how often it widens, in walking minutes (5 5)'],
  ['`event:` `eventlead:` `eventoffset:`', 'A daily peak HH:MM | minutes | area or sector | name; preparation starts earlier (30); levels start earlier (10 points)'],
  ['`shift:`', 'Shift change times; a handover report is written at each'],
  ['`map:` `scale:` `pos:` `shape:`', 'The drawing and its size; metres per map unit; an area\'s rectangle; an outline for an area that is not a rectangle'],
  ['`size:` `density:`', 'An area\'s floor area in m²; people per m² for MODERATE, HIGH and CRITICAL (2 4 5)'],
  ['`exitflow:` `zone:`', 'People per minute an exit lets out; fire zones (NAME | AREA AREA ...)'],
  ['`camera:` `outdoor:`', 'An area\'s camera view; areas in the open air (named in heat alerts)'],
], [2, 5]);
P('A venue without the optional lines works as well: without `auto:` there is no automatic dispatch, without `exit:` no evacuation plan, without `map:` no venue map.');

// =====================================================================================
H1('8  The control-room console');
P('Any computer, tablet or phone on the venue network opens `http://<server>:8080/` in a browser. Nothing is installed: the console comes from the server and needs no internet connection.');
H2('8.1  Signing in');
bullets([
  'With personal logins, sign in with your user name and password, and the 6-digit code from your authenticator app if you set up two-step sign-in. Officers are checked in as the security officer at once.',
  'Without personal logins, or with **Use the control key instead**, sign in with your name and the control key. The key is kept in that browser only; **Sign out** forgets it.',
  '**Forgot password?** sets a new password with a one-time reset code from an administrator.',
  'The console and the classic console (`/legacy`) keep the sign-in in the same place, so moving between them keeps you signed in.',
]);
image('screenshots/console-01-sign-in.png', 'Sign-in', 12);
H2('8.2  The views');
table(['View', 'What you can do', 'Who'], [
  ['Overview', 'Key figures, chips for what needs attention (proposals, heat, missing persons, messages to approve, doubtful sensors, count mismatches, units that have not acknowledged, areas about to turn CRITICAL), the live venue map, the busiest areas, areas by level, the most urgent incidents and the latest alerts. Automatic dispatch on or off.', 'All; switch: officers'],
  ['Areas and crowd', 'Every area with its fill, level, trend, minutes to CRITICAL, density and units; search and level filter. An area\'s panel shows its recent readings and takes a head count.', 'All'],
  ['Incidents', 'Open incidents by status, report an incident. The panel: details, the unit\'s route, review of an automatic dispatch, on scene, escalate, send another unit, resolve with a note, messages with the unit, the history.', 'Operators; review: officers'],
  ['Units and scouts', 'A card per unit: status, device online, battery, shift, skills, current task; messages and photos with the unit.', 'All'],
  ['Lost persons', 'Report a lost person (the lookout goes to every unit), each case with its search area and meeting point, mark a person found, record someone found alone.', 'Operators'],
  ['SOS and evacuation', 'SOS for an area; preview an evacuation of an area, sector or fire zone on the map with people per route and the time to clear; start or end it; decide on a proposed evacuation; approve public messages or send your own; alarm input; message every unit.', 'SOS, alarm: operators; the rest: officers'],
  ['Alerts', 'The newest 50 alerts, filtered by kind.', 'All'],
  ['Reports', 'A period\'s busiest areas, people by hour, incidents by day and category, minutes to acknowledge and to arrive; download for Excel (CSV) or print as PDF.', 'Officers'],
  ['Replay', 'Up to 24 hours on the map, minute by minute, with the alerts of the time; Play runs through it.', 'All'],
  ['Sites', 'A head office\'s other sites with their situation (only when sites are set up).', 'All'],
  ['Venue editor', 'Drag areas on the drawing, resize them, type exact positions, add areas; download the venue file or save it on the server.', 'Administrators'],
  ['Venue settings', 'Check-in and automatic dispatch, the venue\'s settings and automatic rules, the count check, the handover report, incident categories, weather, help-page QR codes, your password, two-step sign-in, scout device codes, the audit log, users and reset codes, personal-data requests, theme and sign-out.', 'Parts by role'],
], [2, 7, 2]);
P('The header has the venue, the LIVE badge (green while updates are pushed), a red **SOS** button, the language and theme buttons, the officer on watch (click to check in) and sign-out. The menu shows how many incidents are open, units unreachable and persons missing. Buttons a role may not use are hidden, and the server refuses them anyway.');
image('screenshots/console-02-overview.png', 'Overview: attention chips, key figures and the live venue map');
image('screenshots/console-04-incident.png', 'An incident\'s panel: actions, messages with the unit and the history');
image('screenshots/console-03-areas.png', 'Areas and crowd, with one area\'s readings');
image('screenshots/console-06-lost-persons.png', 'Lost persons: report, search area, meeting point, found');
image('screenshots/console-07-evacuation-preview.png', 'SOS and evacuation: a preview of the evacuation plan with the routes on the map');
image('screenshots/console-09-reports.png', 'Reports for a period');
image('screenshots/console-10-replay.png', 'Replay of the day, minute by minute');
image('screenshots/console-11-venue-editor.png', 'The venue editor (administrators)');
image('screenshots/console-12-users-and-audit.png', 'Venue settings: users, two-step sign-in, scout device codes and the audit log');
H2('8.3  Languages, themes and phones');
P('The language button switches the console to Arabic, right to left; the choice is kept in that browser. Every fixed text, level, status and button is translated. Names from the venue file and identifiers such as I1003 stay as they are; sentences written by the server (alerts, results) stay in English. The theme button switches between dark (the default for control rooms) and light. On a phone the menu folds away and the views stack.');
image('screenshots/console-14-arabic.png', 'The console in Arabic');
image('screenshots/console-15-light.png', 'The light theme');
image('screenshots/console-16-phone.png', 'On a phone', 4.5);
H2('8.4  The Umrah venue');
image('screenshots/console-17-umrah-map.png', 'The Holy Mosques on the venue map, with crowd levels and units');
image('screenshots/console-18-umrah-evacuation.png', 'Evacuation preview of the Mataf fire zone: the crowd is spread over several exits and the time to clear is estimated');
H2('8.5  The classic pages');
P('The earlier plain-JavaScript console stays at `/legacy`, and the plain dashboard, which works without JavaScript, at `/classic` (with its own sign-in page and an HttpOnly, SameSite=Strict cookie).');

// =====================================================================================
H1('9  The desktop console');
P('The JavaFX desktop console runs on one computer, on the same monitor, database, autosave and watchdog as the server. Start it with `program/run.bat` (Windows) or `program/run.sh`; JavaFX 21 for Windows, Linux x64 and macOS is included in `program/lib/javafx`.');
steps([
  'Choose Airport, Stadium or Umrah, or open your own .venue file. The first time, the venue\'s sample data is created in `crowdwatch-data/<code>`.',
  'If that venue\'s database is already open in another CrowdWatch program, the console says so and offers to continue in memory only; the window then shows NOT SAVED.',
  'When users exist, sign in with your user name and password (and code). An operator\'s officer-only controls are switched off; moving a unit into a restricted area needs an officer\'s approval.',
]);
P('Its views: Overview, Areas and crowd, Incidents, Units and scouts, Routes, SOS and evacuation, Alerts, Reports, Data structures, and Venue and server (settings, automation, users, device codes, audit log, starting the built-in server). Reports, replay, the venue editor, sites, messages and personal-data requests are in the browser console only.');
image('screenshots/javafx-overview.png', 'Desktop console: overview');
image('screenshots/javafx-incident-details.png', 'Desktop console: an incident\'s details');
image('screenshots/javafx-sos-evacuation.png', 'Desktop console: SOS and evacuation');

// =====================================================================================
H1('10  Scout phones, radio and the visitor help page');
H2('10.1  How units receive their orders');
table(['Channel', 'How it works', 'Set-up'], [
  ['Scout phone (issued phone or tablet)', 'Open `http://<server>:8080/scout` and enter the unit\'s device code once. The task appears in large type with the walking route and details; the phone vibrates and beeps on a new task, reminder, SOS or evacuation. Buttons: Acknowledge, On scene, Resolved and a red SOS. With a layout, a small map shows the route. Lost-person lookouts have a Found button. The phone also sends messages and photos, its position, duty and battery.', 'An officer issues a device code per unit (section 12.4); the shared scout key also works.'],
  ['Radio gateway', 'Every message is posted as JSON (unit, kind, incident, text) to the venue\'s gateway, which sends it to the unit\'s radio as a text or text-to-speech, or as an SMS.', '`gateway:` line and a bridge for the radio system'],
  ['Radio operator', 'The server prints every message for a unit without an online phone as `RADIO >> unit: text` for an operator to read out.', 'Nothing extra'],
], [2, 6, 2]);
bullets([
  'A unit must acknowledge a task within the venue\'s `ack:` time (2 minutes); On scene also counts. Without an answer a reminder is sent and the control room told; after a second wait the unit is marked UNREACHABLE and the task goes to the next best unit.',
  'If a gateway cannot deliver a message, a DELIVERY alert tells the control room to reach the unit another way.',
]);
image('screenshots/scout-device.png', 'Scout phone: the task with route and buttons', 8);
image('screenshots/scout-lookout.png', 'Scout phone: a lost-person lookout with the Found button', 8);
H2('10.2  The visitor help page');
P('`/help` works without signing in, in English and Arabic: a visitor reports a lost person, someone unwell, a person they found or other help. The report becomes an incident marked [visitor report] and is dispatched as usual. A phone may send 3 reports in 10 minutes. Venue settings > Print help QR codes prints one code per public area; scanning it opens the page with the area chosen.');
image('screenshots/help-page-arabic.png', 'The visitor help page in Arabic', 8);

// =====================================================================================
H1('11  Automation and the emergency plan');
P('This section covers what CrowdWatch does without anyone pressing a button, and the SOS and evacuation plan. The software supports the people in the control room; it does not replace them, the venue\'s emergency plan, or the emergency services.');
H2('11.1  Automatic dispatch on crowd levels');
steps([
  'Sensors or staff update an area\'s count, and the level is worked out from the venue\'s thresholds.',
  'When the area reaches the level on the `auto:` line (HIGH in the samples), or a TREND warning says it will become CRITICAL within 10 minutes, CrowdWatch reports the venue\'s crowd category there itself.',
  'The nearest available unit with the crowd-control skill is sent; if none is free, the incident waits in its queue.',
  'While the area stays crowded no duplicate is created; if it reaches CRITICAL, the same incident is raised to CRITICAL.',
  'An officer reviews it: approve, or reject as a false alarm (which closes it and frees the unit). An officer can switch automatic dispatch off, for example during a drill; crowd alerts are still raised.',
]);
H2('11.2  Security officer supervision');
P('An officer checks in from a console (signing in counts as a check-in). The `supervision:` line sets how often (every 15 minutes in the samples). If nobody has checked in, or the check-in is overdue, a SUPERVISION alarm is raised and repeated every period until someone checks in. Reviewing an automatic dispatch, ordering or ending an evacuation and messaging all units each record the officer\'s name and count as a check-in.');
H2('11.3  SOS');
P('An SOS comes from a scout\'s phone (with the scout\'s own position), from a console, or from another system. CrowdWatch then:');
steps([
  'creates a CRITICAL incident of the built-in SOS category;',
  'sends the nearest available unit, never the scout who asked for help;',
  'if no unit is free, takes the nearest unit away from a NORMAL or LOW task, and that task waits again;',
  'alerts every other unit within 5 minutes\' walk as possible backup;',
  'raises an SOS alarm in the control room; the assigned unit must acknowledge like any task.',
]);
H2('11.4  Evacuation');
P('Exits are listed on the `exit:` line. An officer chooses a zone (an area, a sector or a fire zone) and previews the plan, which changes nothing; then starts it. CrowdWatch then:');
steps([
  'marks the zone, so routes for staff and signage avoid it;',
  'plans every public area connected to the zone: the nearest exit outside the zone by a crowd-aware route that avoids the zone and never uses a lift; with exit flows and corridor capacities, large crowds are spread over several exits and the time until the zone is clear is estimated (section 11.8);',
  'tells units inside the zone which exit to guide people to, and every other unit to keep the exits clear;',
  'shows an EVACUATION banner on every console and scout phone, and the routes on the map;',
  'when the officer ends the evacuation, reopens the zone and tells every unit.',
]);
H2('11.5  Control-room emergency procedure');
P('CrowdWatch does not call the emergency services, sound alarms, make announcements or open doors. Agree a procedure with the venue\'s safety officer; a starting point:');
steps([
  'SOS alarm: confirm with the assigned unit by radio within a minute. If a life is at risk, call the emergency services at once (in Saudi Arabia 911 where the unified number is in service, otherwise 997 ambulance, 998 civil defence, 999 police; check the numbers for your venue).',
  'SUPERVISION alarm: an officer checks in at once and reviews the waiting automatic dispatches.',
  'NO_ACK or DELIVERY alert: call the unit on another channel; if it cannot be reached, send another unit.',
  'Evacuation: follow the venue\'s emergency plan and the authorities\' instructions first. CrowdWatch coordinates the units; it does not replace the fire alarm, PA system or door controls.',
  'Afterwards: end the evacuation, review and close the incidents.',
]);
H2('11.6  Further automatic rules');
P('A watchdog runs these rules every 15 seconds (class AutoRules). Rules that send or move units act only while automatic dispatch is on; the sensor checks, shifts and handover reports always run. Every action raises an alert and is written to the history.');
table(['Rule', 'What happens', 'Why'], [
  ['Stale sensor', 'No count for 5 min: the area is marked STALE and the nearest free unit is asked to count by eye; the next count clears it.', 'Automation must not trust a dead sensor.'],
  ['Impossible jump', 'A count changing by more than 40 % of capacity within a minute is marked CHECK; a unit is asked to count.', 'A faulty counter must not silently change the level.'],
  ['Not arrived', 'A unit that acknowledged but is not on scene after 10 min is reminded; after 20 min the incident is escalated and a backup sent.', 'Acknowledged is not arrived.'],
  ['Calmed down', 'An automatic crowd incident closes itself once its area has stayed below HIGH for 10 min.', 'Units are not tied up after the crowd has gone.'],
  ['Long wait', 'An incident waiting 10 min for a unit with the right skill gets the nearest free unit as a stand-in.', 'Some help now is better than the right help much later.'],
  ['Reinforcement', 'A crowded area is brought up to 2 units at HIGH (from within 10 min walk) and 4 at CRITICAL (from anywhere), crowd-control and security first; they return 10 min after it calms.', 'Extra hands arrive while the crowd builds.'],
  ['Hold flow', 'When an area turns HIGH, units in the areas leading into it are told to slow people going in, and told when it is normal.', 'Stops the crowd at its source.'],
  ['Send ahead', 'An area expected to reach CRITICAL within 15 min, with no unit there, gets the nearest free unit.', 'The unit is there before the crush.'],
  ['Sector cover', 'A sector with no free unit gets one from a sector with two or more.', 'No part of the venue is left without a unit.'],
  ['Scheduled peak', 'From the `event:` lines: 30 min before a peak, the zone\'s levels start 10 points earlier and free units are placed there.', 'Peaks such as flight banks or prayer times are known in advance.'],
  ['Evacuation proposal', 'An area CRITICAL and rising for 10 min, or an alarm, makes the system PROPOSE an evacuation; only an officer can start it.', 'A machine suggests, a person decides.'],
  ['Public screens and PA', 'Messages are drafted when an area turns HIGH, an evacuation starts or a person has been missing 30 min; they reach the public only after an officer approves them.', 'Nothing is said to the public without a person checking it.'],
  ['Shifts and handover', 'Units go off and on duty with their working hours; at each shift change a handover report is written.', 'The next officer starts with the full picture.'],
  ['Count check', 'The gate counts say how many people should be inside; if the areas add up to a different number for 5 min, the control room is told (counted twice or missing).', 'Sensors that double count are found.'],
], [2, 6, 3]);
H2('11.7  Lost persons');
steps([
  'The case is assigned like any incident, and a LOOKOUT with the description, place and time goes at once to every other unit. Units inside the search area search around them; units at an exit check everyone leaving.',
  'A child or vulnerable adult (words such as child, boy, girl, elderly, dementia, wheelchair, or an age of 12 or under or 75 or over) is raised to CRITICAL.',
  'The search area is every public area within 5 minutes\' walk of where the person was last seen; it widens every 5 minutes.',
  'After 15 minutes every exit is asked to check; after 30 minutes the control room is advised to call the authorities and a PA message is drafted for approval.',
  'Found: from a console, or a scout presses Found. The lookout stops, the person is brought to the meeting point (`meeting:`), and the case is resolved when they are reunited; the description and contact are then erased.',
  'Someone found alone is recorded as found, a unit looks after them, and the open cases are listed to compare.',
]);
H2('11.8  Venue layout and evacuation planning');
P('With exit flows (`exitflow:`) or corridor capacities (`link:` with people per minute), the evacuation plan is no longer "everyone to the nearest exit". Areas are planned largest crowd first; a crowd of more than 5,000 is split into groups (at most four), and each group takes the exit where it would be out soonest: walking time plus queueing at the exit and at every narrow link, counting everyone already sent there. Lifts are never used. Only areas connected to the zone are planned, so evacuating part of Makkah does not move anyone in Madinah.');
P('The Umrah venue is a schematic of the real mosques: the Mataf and its upper levels, the King Fahd and King Abdullah expansions, the Mas\'a, the gates and courtyards in Makkah; the old mosque with the Rawdah, the north halls and the gates in Madinah. Positions, capacities, gate flows and walking times are estimates; the official plans and figures of the General Authority for the Affairs of the Two Holy Mosques must replace them, and the system must follow the evacuation plan approved by the Civil Defence.');
H2('11.9  Counting people, density and heat');
bullets([
  'Each area\'s number is the people in it now. A camera or staff count replaces the previous number; a counter that only counts passes sends +1 or -1.',
  'A counter on a doorway between two zones sends a **move** (from, to, count): both zones change together, so nobody is counted in both or in neither. Counters at the outer gates send moves from or to OUTSIDE; from these the count check knows how many should be inside.',
  '**Density:** with an area\'s floor area (`size:`), the level also follows people per m²: MODERATE from 2, HIGH from 4, CRITICAL from 5 (`density:`). The more dangerous of the two levels counts.',
  '**Heat stress:** temperature and humidity come from a weather station (count line `weather,38,45`), a console or a drill. The heat index (US National Weather Service formula) and its band are shown on every console; when the band changes, an alert names the outdoor areas and what staff should do.',
  '**Urgent alerts to a phone:** with `notify:`, an SOS, alarm, evacuation proposal, overdue check-in, unanswered task, missing person or heat warning that no officer dealt with in time is posted as JSON to an SMS, WhatsApp or e-mail service.',
]);

// =====================================================================================
H1('12  Security');
H2('12.1  Roles');
P('Each person signs in with their own user name and password and has one of three roles; each role can do everything the roles before it can.');
table(['Role', 'What it may do'], [
  ['OPERATOR', 'Read everything; enter counts and movements, report incidents, put units on scene, resolve, send another unit, escalate, raise an alarm, SOS, record found persons, messages with units, weather.'],
  ['OFFICER', 'The decisions: check in as the security officer, review automatic dispatches, start or end an evacuation, decide on a proposal, message all units, approve public messages, switch automatic dispatch, reset the count check, issue or cancel scout device codes, read the audit log and the reports.'],
  ['ADMIN', 'Also manages users, the venue file (editor) and personal-data requests.'],
  ['Scout phone', 'With its device code: only its own unit\'s task, messages and photos, position and duty, SOS and Found. With the shared scout key: the same functions for any unit.'],
  ['Control key', 'Everything, for sensors, gateways and other systems.'],
], [1, 4]);
H2('12.2  How the server checks every request');
bullets([
  '**Spring Security** identifies every request before any CrowdWatch code runs (section 4.2) and turns the caller into a Spring authentication with the role as its authority. The role each endpoint needs comes from one table shared with the built-in server (`ApiServer.refusal`), so the two servers cannot disagree.',
  'No sign-in: 401. A role that may not use the endpoint: 403 with the reason, and a refused change is written to the audit log. Behind it, the API checks again, and also that a device code acts only for its own unit\'s tasks (403 otherwise).',
  'For a signed-in user, the officer name is always their own: a name typed in a form is ignored, so nobody can act in someone else\'s name.',
  'Requests without the control key: 50 a second per address on average, bursts of 200 (more get 429; `-Dcrowdwatch.rateLimit=N`).',
  'No server sessions and no CSRF tokens: the console sends its sign-in in a header, which another web site cannot make a browser send; the plain dashboard\'s cookie is HttpOnly and SameSite=Strict.',
  'Headers on every reply: nosniff, framing only by this site, no referrer to other sites, HSTS over HTTPS, and a content security policy for the console (scripts only from this server).',
]);
H2('12.3  Users and passwords');
bullets([
  'Users are added with `program/add-user.bat <name> <ROLE>` (or `add-user.sh`), in the desktop console, or by an administrator in Venue settings. The last administrator cannot be demoted or switched off.',
  'Passwords are never stored: `crowdwatch-data/accounts.txt` holds a random salt and a PBKDF2-SHA256 hash (100,000 rounds). Passwords need at least 8 characters. After 5 wrong passwords in a row the name is locked for 5 minutes. A sign-in lasts 12 hours and survives a restart (only a hash of the token is kept); signing out, a new password or switching a user off ends it.',
  '**Two-step sign-in:** Venue settings > Set up two-step sign-in shows a key for an authenticator app; after the first code is accepted, every sign-in needs the app\'s current 6-digit code too. An administrator can switch it off for someone who lost their phone.',
  '**Forgot password:** an administrator makes a one-time reset code (valid 30 minutes); the user sets a new password with it on the sign-in page. Signed-in users change their own password in Venue settings.',
  '**Company directory:** `--user NAME ROLE directory` makes a user sign in with LDAP or Active Directory (`CROWDWATCH_LDAP_URL`, `CROWDWATCH_LDAP_USER`); the role stays in CrowdWatch.',
]);
H2('12.4  Scout device codes');
P('An officer issues a 10-character code for one unit (Venue settings > Scout device codes) and types it on that unit\'s phone. The phone can then see only its own unit and act only on its own tasks. Issuing a new code cancels the old one, so a lost phone is shut out at once.');
H2('12.5  Audit log');
P('Every sign-in, failed sign-in, change and refusal is written with time, user, role, action, parameters and result (never a password or key) to the venue\'s database (table AUDIT, never deleted by a save) and to a daily file `crowdwatch-data/audit/<venue>/`. Desktop actions are written too. Officers read the newest 200 entries in Venue settings.');
H2('12.6  Personal data');
bullets([
  'Incident details, contacts and message texts are encrypted in the database (AES-256-GCM) with the key in `crowdwatch-data/data.key` (owner only) or `CROWDWATCH_DATA_KEY`. Keep a copy of the key apart from the backups.',
  'Archived incidents keep their details and contact for 30 days (`retention:`), then these are erased; the rest of the record stays for reports.',
  '**Personal-data requests** (administrators): search by name, phone or e-mail across current and archived incidents and messages, export the result as a file, or erase it; searches and erasures are in the audit log.',
]);
H2('12.7  HTTPS');
P('Set `CROWDWATCH_HTTPS=1` before `run-server` (or add `--https`). Only TLS 1.3 and 1.2 are accepted, and browsers are told to keep using HTTPS. For real use, put the venue\'s certificate and key in a PKCS12 file and name it with `-Dcrowdwatch.keystore=<file>` (default `crowdwatch-data/tls/server.p12`), with its password in `CROWDWATCH_KEYSTORE_PASSWORD`. Without one, CrowdWatch makes a self-signed certificate for this computer; the connection is encrypted, but each browser warns once until it is trusted.');

// =====================================================================================
H1('13  Data, saving and backups');
H2('13.1  The database');
P('CrowdWatch uses **Apache Derby 10.16**, a database written in Java that runs inside the program: a venue installs nothing else. Each venue has its own database in `crowdwatch-data/<code>/`. The same database code is used by the Spring server, the built-in server and the desktop console.');
table(['Tables', 'What they hold'], [
  ['venue, area_state, units, unit_skills, counters, state', 'The venue file, each area\'s count and level, units, the next incident number, officer, automation and evacuation state'],
  ['incidents, history', 'Open and recent incidents with their histories (details and contact encrypted)'],
  ['incident_archive, history_archive', 'Resolved incidents after 24 hours, written once and never deleted by a save'],
  ['alerts, frames', 'The newest alerts; every area\'s count once a minute (kept 90 days, for reports and replay)'],
  ['messages, audit', 'Messages between units and the control room (encrypted); the audit log'],
], [2, 4]);
P('A database from an earlier version is upgraded automatically when it opens (new columns and tables are added).');
H2('13.2  Saving');
bullets([
  '**About 10 seconds after a change** (`-Dcrowdwatch.saveDelay`, 2 to 60): the first change schedules one save, and every change until then is part of it, so a busy venue saves about every 10 seconds, not after every sensor reading.',
  '**Every 60 seconds** as well (`-Dcrowdwatch.autosave`), and once more when the server or desktop console is stopped normally.',
  'A save copies the state while the monitor is locked for a few milliseconds and writes it outside the lock, so sensors and consoles keep working (about 2 seconds of writing for 30,000 incidents).',
  'A crash or power cut therefore loses about 10 seconds of work. This was checked by killing a server 12 seconds after a change and restarting it: the change was there (test case TC-142 checks the timing).',
  'Only one program may open a venue\'s database. A second one refuses to start (server) or asks to run in memory only (desktop), instead of showing sample data.',
]);
H2('13.3  Backups and restoring');
bullets([
  'The first save of each day copies the database to `crowdwatch-data/backups/<venue>/<date>/`; the newest 7 are kept.',
  'Copy them to a second disk or network share as well with `-Dcrowdwatch.backupCopy=PATH` or `CROWDWATCH_BACKUP_COPY`.',
  'To restore: stop the server, move `crowdwatch-data/<venue>` aside, copy `backups/<venue>/<date>/<venue>` in its place, and start the server. Keep `data.key` with it, or the encrypted fields cannot be read.',
]);
table(['In crowdwatch-data/', 'What it is'], [
  ['`<venue>/`', 'The venue\'s Derby database'],
  ['`backups/<venue>/<date>/`', 'Daily backups'],
  ['`accounts.txt`', 'Users (password hashes), device codes, session hashes; owner only'],
  ['`data.key`', 'The key for personal data; keep a copy apart from the backups'],
  ['`audit/<venue>/`, `logs/`', 'Daily audit files; daily log files (30 days kept)'],
  ['`reports/<venue>/`, `photos/`, `venues/`, `sites.txt`, `tls/`', 'Handover reports, photos from phones, venue files saved by the editor, a head office\'s sites, the HTTPS certificate'],
], [2, 4]);

// =====================================================================================
H1('14  Integrating real systems');
H2('14.1  Sensors');
P('A venue file names its sensor connections with `feed:` lines; they start and stop with the server. Every connection takes the same count lines, one reading a line: `AREA,COUNT` (people in an area now), `AREA,+N` or `AREA,-N` (people who entered or left), `move,FROM,TO,COUNT` (through a door between two areas; OUTSIDE for the outer gates).');
table(['Venue line', 'For'], [
  ['`feed: folder counts 10`', 'Counting systems that export a file: every 10 s each .csv or .txt file in the folder is read once and moved to done/ (or failed/).'],
  ['`feed: poll https://counter.local/api/counts 15 COUNTER_TOKEN`', 'Counting systems with a web address: asked every 15 s; count lines or JSON with area and count. The token comes from the named environment variable.'],
  ['`feed: tcp 5020 10.0.0.7 10.0.0.8`', 'Devices that write text to a socket: each line is answered OK or ERR; only the listed addresses may connect.'],
], [3, 5]);
P('Other systems use the API with the control key, for example:');
code(['curl -X POST -H "X-Api-Key: MyControlKey" \\', '     -d "area=SEC-1&count=420" https://ops-pc:8080/api/occupancy']);
P('In Java, a class that implements `CrowdFeed` calls the monitor when a device reports. Every public CrowdMonitor method is synchronized, so feeds may run on their own threads:');
code([
  'public class TurnstileFeed implements CrowdFeed {',
  '    public String name() { return "Turnstiles"; }',
  '    public void start(CrowdMonitor m) {',
  '        vendorApi.onPass((gate, dir) ->',
  '            m.adjustOccupancy(gate, dir == IN ? 1 : -1, Source.SENSOR));',
  '    }',
  '    public void stop() { vendorApi.close(); }',
  '    public boolean running() { return vendorApi.isOpen(); }',
  '}',
]);
H2('14.2  Outputs');
P('Register a `MonitorListener` to pass alerts to SMS, a PA system, signage or another service. Listeners are called on their own event thread, in order, outside the monitor\'s lock, so a slow gateway cannot hold up the sensors or the consoles. Without code: the `gateway:` line (every unit message), `notify:` (urgent alerts nobody dealt with), `GET /api/alerts`, `GET /health` (ok, degraded or fail, no key) and `GET /metrics` (Prometheus, with the control key or `CROWDWATCH_METRICS_KEY`).');
code([
  'monitor.addListener(new MonitorListener() {',
  '    public void onAlert(Alert a) {',
  '        if (a.kind == AlertKind.CROWD)',
  '            signage.show(a.ref, monitor.route(a.ref, "EXIT", true).path);',
  '    }',
  '});',
]);
H2('14.3  The API');
P('Requests send the sign-in as `Authorization: Bearer <token>` (from `POST /api/login`) or `X-Api-Key: <key>`; changes are form posts. Replies are JSON; a change gives 200 when done and 422 with the reason when a rule refuses it. "Served by" names the Spring component.');
table(['Method and path', 'Parameters', 'Returns', 'Role', 'Served by'], [
  ['GET /api/overview, /areas, /incidents, /units, /alerts, /venue, /layout, /lost, /announcements, /status', '-', 'What the consoles show', 'any', 'ConsoleController'],
  ['GET /api/incident, /readings, /evacuation, /route', 'id / area / zone / from, to, avoid', 'One incident; an area\'s readings; an evacuation plan or preview; a route', 'any', 'ConsoleController'],
  ['GET /api/summary, /handover', '-', 'Text summary; the handover report', 'any', 'ConsoleController'],
  ['POST /api/occupancy, /move', 'area, count or delta, source / from, to, count', 'A count or a change; people moving between areas', 'OPERATOR', 'ActionController'],
  ['POST /api/incidents', 'category, area, details, contact, language', 'Report an incident; it is dispatched', 'OPERATOR', 'ActionController'],
  ['POST /api/ack, /onscene, /resolve, /reassign, /escalate', 'unit / id, note / id, unit, reason', 'A unit\'s progress; another unit; a higher priority', 'OPERATOR, phone', 'ActionController'],
  ['POST /api/sos, /alarm, /found, /foundperson', 'area, text, unit / id, area / area, details', 'SOS; alarm panel input; lost persons', 'OPERATOR, phone', 'ActionController'],
  ['POST /api/checkin, /review, /automation, /evacuate, /evacuate/end, /proposal, /broadcast, /announce, /announcement, /countcheck/reset', 'officer, id, decision, zone, text, on', 'The officer\'s decisions', 'OFFICER', 'ActionController'],
  ['POST /api/login, /logout; GET /api/me', 'user, password, code', 'Session token; who is signed in', 'none', 'ApiBridge'],
  ['GET, POST /api/users; POST /users/reset, /devices/code, /devices/revoke, /twostep/start, /confirm, /off, /password, /password/reset', 'name, role, password, unit, code', 'Users, device codes, two-step sign-in, passwords', 'ADMIN / OFFICER / user', 'ApiBridge'],
  ['GET /api/report, /replay, /audit', 'from, to, format=json|csv|html', 'Reports; minute-by-minute replay; audit log', 'OFFICER (replay: any)', 'ApiBridge'],
  ['POST /api/message, /position, /duty; GET /messages, /photo, /unit', 'unit, text, photo, area, on', 'Messages and photos; a phone\'s position and duty; its task', 'OPERATOR, phone', 'ApiBridge'],
  ['GET /api/privacy, /privacy/export; POST /privacy/erase; GET, POST /api/venuefile', 'find / ids / text', 'Personal-data requests; the venue file for the editor', 'ADMIN', 'ApiBridge'],
  ['GET /api/sites, /map, /qr; POST /api/weather', '- / - / - / temp, humidity', 'Head-office sites; the drawing; help QR codes; weather', 'any / OPERATOR', 'ApiBridge'],
  ['GET /health, /metrics, /help; POST /api/public/report', '- / - / area / kind, area, details', 'Monitoring; the visitor help page and its reports', 'none (metrics: key)', 'ApiBridge'],
  ['GET /ws', 'token or key', 'Live updates (WebSocket)', 'any', 'LiveSocket'],
], [4, 3, 4, 2, 2]);

// =====================================================================================
H1('15  Running a venue');
H2('15.1  As a service');
P('Fill in `program/service.conf` (venue, port, keys, HTTPS), then run `install-service-windows.bat` as administrator (a task that starts with Windows and restarts the server if it stops), `sudo ./install-service-linux.sh` (systemd) or `sudo ./install-service-mac.sh` (launchd). Each start uses `CrowdWatch-server.jar` when it is in `program/`, otherwise `CrowdWatch.jar`, so building the Spring server later needs no reinstall. Remove the service with the same script and `remove`. The server\'s output also goes to `crowdwatch-data/logs`, one file a day, 30 days kept.');
H2('15.2  Updating');
P('Stop the server, replace `program/` with the new release (keep `crowdwatch-data/` and `service.conf`), and start it again. The database is upgraded automatically; sign-ins survive.');
H2('15.3  Drills');
P('`program/run-drill.bat airport 8081 ..\\examples\\airport-fire.drill` starts a training server with the venue\'s sample data: it never opens the real database, every screen shows DRILL, and the script plays timed events (counts, a lost child, a medical case, a fire alarm, heat, an SOS). A drill file has one event a line: `MM:SS count|move|incident|lost|alarm|sos|weather|say ...`.');
H2('15.4  Several venues');
P('A head office lists its sites with `java -jar CrowdWatch.jar --site add NAME http://address:port CONTROL-KEY` (kept in `crowdwatch-data/sites.txt`, owner only); the console\'s Sites view shows each site\'s situation and opens it. Each venue runs its own server.');

// =====================================================================================
H1('16  Building and testing');
H2('16.1  Building');
table(['Script (top folder)', 'What it does', 'Needs'], [
  ['`download-dependencies`', 'Fetches Maven, the Java libraries and the console\'s libraries once', 'JDK 17+, Node.js 20+'],
  ['`build`', 'Compiles `source/src`, makes `program/CrowdWatch.jar`, runs the 142 core tests (`--no-tests` skips them)', 'JDK 17+'],
  ['`build-spring`', 'Builds the React console into the server, the Spring Boot server with Maven (`server/mvnw`), runs the console and Spring tests, copies `program/CrowdWatch-server.jar`', 'JDK 17+, Node.js 20+'],
  ['`web/`: `npm run dev`', 'Serves the console on port 5173 against a server on 8080, reloading on every edit', 'Node.js 20+'],
  ['`docs/source/build-guide.sh`', 'Rebuilds this guide (Word and PDF) from `make-guide.js`', 'Node.js, LibreOffice'],
], [2, 5, 2]);
P('GitHub builds and tests every change on Windows and Linux: the core build, and the Spring server with the console. Pushing a tag such as `v3.0` publishes a release with the ready-to-run zip, both jars included.');
H2('16.2  The tests');
const tc = testCases();
table(['Suite', 'Cases', 'What it covers'], [
  ['Core (`TestRunner`)', `${tc.passed} passed, ${tc.failed} failed`, 'Data structures, every rule on four venues, the network API, the database, automation, sensors, security, reports and the rest, with a fixed clock (table below).'],
  ['Spring server (JUnit, `server/src/test`)', '21 passed', 'The API through Spring; every read and every change is a Spring endpoint; the control key and personal roles; refused changes in the audit log; device codes limited to their unit; the plain dashboard\'s form key and redirect; bad input (400) and rules (422); live updates pushed in under 2 s; the WebSocket needs a sign-in; security headers; the rate limit.'],
  ['Console (Vitest, `web/src`)', '5 passed', 'Sign-in storage, roles, sign-in headers, the WebSocket address, form posts and sign-out on 401.'],
  ['In a browser (Playwright)', 'every view', 'Signed in as an administrator, an operator and with the control key: every view and its main actions, a change made elsewhere shown in under a second, Arabic, the phone layout, the operator\'s narrower menu; no errors in the browser.'],
], [2, 2, 6]);
P('The core test cases, from the latest run (`test-evidence/test-output.txt`):');
table(['TC', 'What is tested', 'Expected (and actual)', 'Result'],
  tc.cases.map((c) => [c.n, c.title, c.expected === c.actual ? c.expected : `${c.expected}\nactual: ${c.actual}`, c.result]), [1, 6, 5, 1.3]);
H2('16.3  Load tests');
P('The server was loaded with a generated venue of 400 areas, 760 links and 250 units with automation on (`test-evidence/stress-test`). The first table is the original stress test of the built-in server, before and after its fixes.');
table(['Measure', 'First version', 'After the fixes'], [
  ['Busy venue: 250 scout phones, 20 consoles, 400 sensors (1/s), 2 reports/s', '506 req/s, no errors; scout p99 43 ms', '508 req/s, no errors; scout p99 5 ms'],
  ['One client, small reply', '44 ms; 32 req/s', '1.5 ms; 382 req/s'],
  ['1,024 clients sending back to back', '3,927 req/s; 17 failed; slowest 1.5 s', '5,361 req/s; no errors; slowest 0.29 s'],
  ['Connections sending half a request', '4 froze the server', '300: others answered in 1 ms'],
  ['30,000 incidents: final save / restart', '5.3 s / 3.1 s', '2.2 s / 1.5 s'],
], [4, 3, 3]);
const cmpFile = path.join(ROOT, 'test-evidence', 'stress-test', 'spring-and-built-in.txt');
if (fs.existsSync(cmpFile)) {
  const lines = fs.readFileSync(cmpFile, 'utf8').split('\n').filter((l) => l.includes('|') && !l.startsWith('#'));
  const rows = lines.map((l) => l.split('|').map((x) => x.trim()));
  P('The same load against both servers of this version, one after the other on the same 4-core computer (load generator on the same computer, so the highest rates are understated):');
  table(rows[0], rows.slice(1), [4, 3, 3]);
}

// =====================================================================================
H1('17  Weak points removed');
table(['Earlier weak point', 'Fix', 'Evidence'], [
  ['Version 3.0', '', ''],
  ['The browser console asked the server every 3 seconds, so changes reached other screens late and every console added load', 'Changes are pushed over a WebSocket; consoles reload only when something changed', 'Spring test: change pushed in under 2 s; browser: shown in about 0.8 s'],
  ['Sign-in, roles and headers were hand-written in the web server', 'Spring Security checks every request first, with one role table shared by both servers; refusals audited', 'Spring security tests (roles, device codes, audit, rate limit, headers)'],
  ['Up to 60 seconds of work could be lost in a crash', 'Saved about 10 seconds after every change, as well as every 60 s', 'TC-142; server killed 12 s after a change kept it'],
  ['One 1,200-line console script', 'React components in TypeScript, typed API replies, ready-made libraries for charts, tables and languages', 'Console tests; browser checks of every view'],
  ['Installed services kept running the old server', 'Run scripts and installers start the Spring server when it is there', 'Start scripts, systemd and launchd commands run by hand'],
  ['600 connections sending half a request delayed every other request by up to 5 s', 'The Spring server (Tomcat) reads requests without tying up a worker thread', 'Load test: others answered in 1 ms (built-in server: 4.9 s)'],
  ['Earlier versions', '', ''],
  ['Sequential incident IDs made the search tree a chain', 'AVL trees keep their height at most 1.44 log2 n', 'TC-03, TC-04'],
  ['Dijkstra ran for every dispatch', 'Results cached per area; cleared only when areas or links change', 'TC-29, TC-30'],
  ['Thresholds on % only, so warnings came late', 'People-per-minute trend; TREND alert up to 10 minutes before CRITICAL', 'TC-28, TC-42'],
  ['Read methods were not synchronized', 'All monitor methods synchronized; consoles and reports hold the lock while reading', 'Stress test: 0 errors (51 before)'],
  ['A second program on the same venue silently showed sample data', 'The database is checked first; the server refuses to start', 'TC-41'],
  ['Every save locked the monitor for 1.7 s; alerts grew without limit', 'Copy under the lock, write outside it; newest 1,000 alerts kept', 'TC-39, TC-44'],
  ['A slow listener (SMS gateway) froze everything', 'Listeners on their own event thread', 'TC-45'],
  ['Crowd alerts only: no unit was sent unless someone reported', 'Automatic dispatch at HIGH or on a TREND warning, reviewed by an officer', 'TC-48 to TC-51'],
  ['Units were never told about their tasks', 'Scout phones, radio gateway and radio operator relay; acknowledgement with reminder and reassignment', 'TC-53, TC-61 to TC-63'],
  ['No SOS or evacuation plan', 'SOS with backup and pre-emption; evacuation plans with exit flows and time to clear', 'TC-55 to TC-60, TC-104'],
  ['Four half-sent connections froze the server; replies waited 44 ms', 'More threads, time limits, fair turns, TCP_NODELAY, a read cache', 'TC-72, TC-73; stress test'],
  ['Anyone on the network could read everything', 'Keys, then personal logins with roles, device codes, audit log, HTTPS', 'TC-70, TC-112 to TC-116'],
], [4, 4, 3]);

// =====================================================================================
H1('18  Known limitations and future work');
table(['Limitation', 'Effect', 'Possible improvement'], [
  ['One server per venue, embedded database', 'If the server computer fails, every console stops until it is restarted or the database is restored elsewhere; about 10 s of changes can be lost.', 'A standby server that takes over automatically (a client/server database such as PostgreSQL with replication).'],
  ['Every save rewrites the current state', 'About 2 s of writing with 30,000 incidents; with saving after changes, a very large venue spends that every 10 s in the background.', 'Save only what changed.'],
  ['One lock for the whole venue', 'About 5,400 requests a second on 4 cores: about ten times a busy venue, but not several large venues on one server.', 'Finer-grained locking, or one server per venue.'],
  ['The newest features are in the browser console', 'Reports, replay, the editor, sites, messages and personal-data requests are not in the desktop console.', 'Add them to the desktop console, or retire it in favour of the browser console.'],
  ['The scout phone and help pages are the earlier pages', 'They work with the new server, but are not built with React.', 'Rebuild them with the console\'s components.'],
  ['Scout phones are web pages', 'No push notifications without the page open; a locked phone may stop checking; positions are what the scout reports, not GPS.', 'A phone app with push notifications, offline queueing and indoor positioning.'],
  ['A self-signed certificate makes browsers warn', 'Each browser and phone warns once until it is trusted.', 'Use the venue\'s own certificate (section 12.7).'],
  ['Two-step sign-in is optional', 'An administrator without it is marked, not forced.', 'Make it compulsory for administrators and officers.'],
  ['Directory sign-in checks only the password', 'The role is set in CrowdWatch, not read from directory groups.', 'Map directory groups to roles.'],
  ['The rate limit is per address', 'Devices behind one router share one limit.', 'Raise it, or give trusted gateways the control key.'],
  ['The help page has no CAPTCHA', '3 reports per phone in 10 minutes limit misuse; someone with many phones could still send false reports, each marked [visitor report].', 'A challenge, or a review step for visitor reports.'],
  ['The evacuation plan does not know live conditions', 'Smoke, closed doors or a blocked exit are not known unless an officer evacuates around them; clearing times are estimates.', 'Inputs from the fire panel, smoke detectors and door controls.'],
  ['The Umrah layout is a schematic', 'Positions, capacities, gate flows and walking times are estimates, not official figures.', 'Load the official drawings and figures.'],
  ['Linear trend prediction', 'A sudden surge is seen only once readings rise, unless it is on the `event:` lines.', 'Read peaks from flight or event schedules; seasonal forecasting.'],
  ['No radio system is supported directly', 'A radio needs a gateway or bridge for its brand, or an operator reading the console relay.', 'Ready-made bridges for common DMR and TETRA systems.'],
  ['Personal data is protected by one key', 'Whoever has the key and the database can read it; history lines are not encrypted.', 'A hardware key store; encrypt the histories too.'],
  ['Arabic covers the screens', 'Sentences written by the server and names from the venue file stay in English.', 'Arabic texts in the server and the venue file.'],
  ['JavaFX is included for Windows, Linux x64 and macOS', 'On Linux on ARM (a Raspberry Pi) the desktop console does not start; the server does.', 'Add the OpenJFX linux-aarch64 files.'],
], [3, 5, 4]);

// =====================================================================================
const doc = new Document({
  creator: 'CrowdWatch', title: 'CrowdWatch - Design and Integration Guide', description: `CrowdWatch ${VERSION}`,
  styles: {
    default: { document: { run: { font: FONT, size: 22 } } },
    paragraphStyles: [
      { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { font: FONT, size: 36, bold: true, color: NAVY }, paragraph: { spacing: { before: 0, after: 200 }, keepNext: true, outlineLevel: 0 } },
      { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { font: FONT, size: 27, bold: true, color: NAVY }, paragraph: { spacing: { before: 280, after: 120 }, keepNext: true, outlineLevel: 1 } },
      { id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true,
        run: { font: FONT, size: 23, bold: true, color: NAVY }, paragraph: { spacing: { before: 200, after: 80 }, keepNext: true, outlineLevel: 2 } },
    ],
  },
  numbering: {
    config: [
      { reference: 'bullets', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 400, hanging: 260 } } } }] },
      ...Array.from({ length: 40 }, (_, k) => ({ reference: 'steps' + k, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 400, hanging: 300 } } } }] })),
    ],
  },
  sections: [{
    properties: { page: { size: { width: PAGE_W, height: 16838 }, margin: { top: MARGIN, bottom: MARGIN, left: MARGIN, right: MARGIN } } },
    footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.RIGHT, children: [
      new TextRun({ text: `CrowdWatch ${VERSION}  ·  Design and Integration Guide  ·  page `, size: 17, color: GREY }),
      new TextRun({ children: [PageNumber.CURRENT], size: 17, color: GREY }),
    ] })] }) },
    children: body,
  }],
});
Packer.toBuffer(doc).then((buf) => { fs.writeFileSync(OUT, buf); console.log('wrote', OUT, Math.round(buf.length / 1024), 'KB'); });
