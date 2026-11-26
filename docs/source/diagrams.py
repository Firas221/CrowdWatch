"""Draws the C4 and use-case diagrams of the guide as SVG (docs/diagrams/*.svg).
Run: python3 docs/source/diagrams.py, then node docs/source/svg2png.js (PNG for the guide)."""
import html, os, textwrap

OUT = os.path.join(os.path.dirname(__file__), '..', 'diagrams')
FONT = "DejaVu Sans, Arial, sans-serif"
PERSON, SYSTEM, CONTAINER, EXTERNAL, DB = '#08427b', '#1168bd', '#438dd5', '#8a8a8a', '#438dd5'


class Svg:
    def __init__(self, w, h, title):
        self.w, self.h, self.parts = w, h, []
        self.text(24, 40, title, 26, '#1f2937', bold=True)

    def add(self, s): self.parts.append(s)

    def text(self, x, y, s, size=14, color='#222', bold=False, anchor='start', italic=False):
        self.add(f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" text-anchor="{anchor}"'
                 f'{" font-weight=\"700\"" if bold else ""}{" font-style=\"italic\"" if italic else ""}>{html.escape(s)}</text>')

    def lines(self, x, y, s, size, color, width_px, anchor='middle', lh=1.3):
        n = max(8, int(width_px / (size * 0.56)))
        out = []
        for para in s.split('\n'):
            out += textwrap.wrap(para, n) or ['']
        for k, l in enumerate(out):
            self.text(x, y + k * size * lh, l, size, color, anchor=anchor)
        return len(out) * size * lh

    def save(self, name):
        body = '\n'.join(self.parts)
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.w}" height="{self.h}" viewBox="0 0 {self.w} {self.h}" font-family="{FONT}">'
               '<defs><marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">'
               '<path d="M0,0 L10,5 L0,10 z" fill="#555"/></marker></defs>'
               f'<rect width="{self.w}" height="{self.h}" fill="#fff"/>{body}</svg>')
        with open(os.path.join(OUT, name + '.svg'), 'w') as f: f.write(svg)


# ---------- C4 shapes ----------
def element(s, x, y, w, h, name, kind, desc, color, person=False, db=False):
    """A C4 box: name, [kind], description. Returns its centre and size."""
    if person:
        s.add(f'<circle cx="{x + w / 2}" cy="{y - 4}" r="30" fill="{color}" stroke="#062e56" stroke-width="2"/>')
        s.add(f'<rect x="{x}" y="{y + 16}" width="{w}" height="{h - 16}" rx="28" fill="{color}" stroke="#062e56" stroke-width="2"/>')
        top = y + 50
    elif db:
        s.add(f'<path d="M{x},{y + 18} v{h - 36} a{w / 2},18 0 0,0 {w},0 v-{h - 36} a{w / 2},18 0 0,0 -{w},0 z" fill="{color}" stroke="#2e6295" stroke-width="2"/>')
        s.add(f'<ellipse cx="{x + w / 2}" cy="{y + 18}" rx="{w / 2}" ry="18" fill="{color}" stroke="#2e6295" stroke-width="2"/>')
        top = y + 58
    else:
        s.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" fill="{color}" stroke="#0b3c6e" stroke-width="2"/>')
        top = y + 32
    s.text(x + w / 2, top, name, 18, '#fff', bold=True, anchor='middle')
    s.text(x + w / 2, top + 21, f'[{kind}]', 13, '#e8f0fb', anchor='middle')
    s.lines(x + w / 2, top + 46, desc, 13, '#fff', w - 24)
    return (x, y, w, h)


def boundary(s, x, y, w, h, label, kind):
    s.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="none" stroke="#444" stroke-width="2" stroke-dasharray="10 6"/>')
    s.text(x + w - 14, y + 26, label, 17, '#333', bold=True, anchor='end')
    s.text(x + w - 14, y + 45, f'[{kind}]', 13, '#555', anchor='end')


def edge_point(b, tx, ty):
    """Where the line from the box's centre towards (tx, ty) leaves the box."""
    x, y, w, h = b
    cx, cy = x + w / 2, y + h / 2
    dx, dy = tx - cx, ty - cy
    if dx == 0 and dy == 0: return cx, cy
    sx = (w / 2) / abs(dx) if dx else 1e9
    sy = (h / 2) / abs(dy) if dy else 1e9
    k = min(sx, sy)
    return cx + dx * k, cy + dy * k


def rel(s, a, b, label, tech='', dx=0, dy=0, dashed=True):
    ax, ay = a[0] + a[2] / 2, a[1] + a[3] / 2
    bx, by = b[0] + b[2] / 2, b[1] + b[3] / 2
    x1, y1 = edge_point(a, bx, by)
    x2, y2 = edge_point(b, ax, ay)
    d = ' stroke-dasharray="7 5"' if dashed else ''
    s.add(f'<line x1="{x1:.0f}" y1="{y1:.0f}" x2="{x2:.0f}" y2="{y2:.0f}" stroke="#555" stroke-width="1.6"{d} marker-end="url(#arr)"/>')
    mx, my = (x1 + x2) / 2 + dx, (y1 + y2) / 2 + dy
    width = max(len(label), len(tech) + 2) * 7.2 + 12
    hgt = 34 if tech else 20
    s.add(f'<rect x="{mx - width / 2:.0f}" y="{my - 15:.0f}" width="{width:.0f}" height="{hgt}" fill="#fff" fill-opacity="0.92"/>')
    s.text(mx, my, label, 13, '#333', anchor='middle')
    if tech: s.text(mx, my + 15, f'[{tech}]', 12, '#666', anchor='middle', italic=True)


def legend(s, x, y, items):
    s.text(x, y, 'Legend', 15, '#333', bold=True)
    for k, (color, label) in enumerate(items):
        s.add(f'<rect x="{x}" y="{y + 12 + k * 26}" width="34" height="18" rx="4" fill="{color}"/>')
        s.text(x + 44, y + 26 + k * 26, label, 13, '#333')


# =====================================================================================
# C4 level 0: system landscape
s = Svg(1700, 1080, 'C4 level 0 - System landscape: CrowdWatch across an organisation\'s venues')
hq = element(s, 260, 90, 300, 150, 'Head-office manager', 'Person', 'Follows every site from the head office', PERSON, person=True)
hqs = element(s, 980, 80, 420, 170, 'CrowdWatch - head office', 'Software system', 'Sites view: each site\'s people, crowded areas, incidents, evacuation and officer (sites.txt).', SYSTEM)
rel(s, hq, hqs, 'Opens the Sites view', 'browser, HTTPS')
boundary(s, 40, 300, 1620, 440, 'Venue operator: its venues', 'Enterprise')
names = [('CrowdWatch - Airport', 'Terminals, gates, security screening; passengers'),
         ('CrowdWatch - Stadium', 'Stands, concourses, turnstiles; fans'),
         ('CrowdWatch - Holy Mosques', 'Masjid al-Haram and al-Masjid an-Nabawi; pilgrims'),
         ('CrowdWatch - Mall', 'Floors, food court, cinema; shoppers')]
venues = [element(s, 80 + k * 395, 380, 345, 170, n, 'Software system', d + '. Its own server, database and control room.', SYSTEM) for k, (n, d) in enumerate(names)]
for k, v in enumerate(venues):
    rel(s, hqs, v, 'Reads the overview' if k == 2 else '', 'HTTPS, control key' if k == 2 else '', dx=40 if k == 2 else 0)
cr = element(s, 600, 600, 500, 110, 'Control room of each venue', 'People', 'operators, officers, response units, visitors', PERSON)
for v in venues:
    x1, y1 = cr[0] + cr[2] / 2 + (v[0] + v[2] / 2 - 850) * 0.45, 600
    s.add(f'<line x1="{x1:.0f}" y1="{y1}" x2="{v[0] + v[2] / 2:.0f}" y2="550" stroke="#555" stroke-width="1.6" stroke-dasharray="7 5" marker-end="url(#arr)"/>')
s.text(1120, 640, 'use their own site', 13, '#333')
ext = [('Company directory', 'LDAP / Active Directory sign-in', 'Sign users in', 'LDAPS'),
       ('SMS, WhatsApp, e-mail', 'Urgent alerts to duty officers', 'Send alerts', 'HTTPS JSON'),
       ('Monitoring', 'Prometheus, health checks', 'Are watched by', 'HTTPS'),
       ('Counting systems', 'Turnstiles, door counters, cameras', 'Send counts', 'file, HTTPS, TCP')]
for k, (n, d, l, t) in enumerate(ext):
    e = element(s, 80 + k * 395, 840, 345, 130, n, 'External system', d, EXTERNAL)
    cx = e[0] + e[2] / 2
    up = k == 3
    y1, y2 = (840, 740) if up else (740, 840)
    s.add(f'<line x1="{cx:.0f}" y1="{y1}" x2="{cx:.0f}" y2="{y2}" stroke="#555" stroke-width="1.6" stroke-dasharray="7 5" marker-end="url(#arr)"/>')
    s.text(cx + 10, 785, ('The venues: ' if not up else '') + l.lower() if not up else l, 13, '#333')
    s.text(cx + 10, 801, f'[{t}]', 12, '#666', italic=True)
s.lines(850, 1030, 'Each venue runs its own CrowdWatch with its own data; the head office only reads their overviews.', 13, '#444', 1200)
s.save('c4-level0-landscape')

# =====================================================================================
# C4 level 1: system context of one venue
s = Svg(1700, 1250, 'C4 level 1 - System context: CrowdWatch at one venue')
people = [('Control-room operator', 'Watches the crowd, enters counts, reports and follows incidents'),
          ('Security officer', 'On watch: decisions, reviews, evacuation, public messages'),
          ('Administrator', 'Users, venue file, personal-data requests'),
          ('Response unit (scout)', 'Receives tasks on a phone or radio; acknowledges, reports, SOS'),
          ('Visitor', 'Asks for help with the help page (QR code)')]
ps = [element(s, 40 + k * 330, 80, 290, 170, n, 'Person', d, PERSON, person=True) for k, (n, d) in enumerate(people)]
cw = element(s, 560, 470, 580, 210, 'CrowdWatch', 'Software system',
             'Keeps live counts per area, works out crowd levels, sends the nearest suitable unit, runs the automation, SOS, evacuation and lost-person procedures, and records everything.', SYSTEM)
exts = [('Counting systems', 'Turnstiles, door counters, CCTV head counts, ticket gates'),
        ('Fire alarm panel', 'Alarms (directly or entered by the control room)'),
        ('Weather station', 'Temperature and humidity for heat stress'),
        ('Radio / SMS gateway', 'Delivers unit messages to radios; public screens and PA'),
        ('Alert service', 'SMS, WhatsApp or e-mail to the duty officer\'s phone'),
        ('Company directory', 'LDAP / Active Directory'),
        ('Monitoring', 'Prometheus, health checks'),
        ('Other CrowdWatch sites', 'Head-office Sites view'),
        ('CCTV', 'Camera views per area')]
pos = [(40, 470), (40, 700), (40, 930), (1360, 470), (1360, 700), (1360, 930), (440, 960), (750, 960), (1060, 960)]
es = [element(s, x, y, 300 if x in (40, 1360) else 280, 140, n, 'External system', d, EXTERNAL) for (n, d), (x, y) in zip(exts, pos)]
labels = [('Watches, enters, reports', 'browser, HTTPS'), ('Decides, checks in', 'browser, HTTPS'), ('Manages', 'browser, HTTPS'),
          ('Gets tasks, acknowledges, SOS', 'phone, HTTPS'), ('Reports a need', 'help page, HTTPS')]
offs = [(-60, 0), (-20, 0), (0, -30), (30, 0), (70, 0)]
for p, (l, t), (dx, dy) in zip(ps, labels, offs): rel(s, p, cw, l, t, dx, dy)
rel(s, es[0], cw, 'Send counts and moves', 'folder, HTTPS, TCP')
rel(s, es[1], cw, 'Raises alarms', 'HTTPS')
rel(s, es[2], cw, 'Sends weather', 'count line, HTTPS', dx=30)
rel(s, cw, es[3], 'Sends unit messages', 'HTTPS JSON')
rel(s, cw, es[4], 'Sends urgent alerts', 'HTTPS JSON')
rel(s, cw, es[5], 'Checks passwords', 'LDAPS', dx=-30)
rel(s, es[6], cw, 'Reads health, metrics', 'HTTPS', dx=-20)
rel(s, es[7], cw, 'Reads the overview', 'HTTPS, key')
rel(s, cw, es[8], 'Links camera views', 'URL', dx=20)
s.lines(850, 1170, 'CrowdWatch does not call the emergency services, sound alarms or open doors: the control room does, following the venue\'s emergency plan.', 13, '#444', 1100)
s.save('c4-level1-context')

# =====================================================================================
# C4 level 2: containers
s = Svg(1700, 1240, 'C4 level 2 - Containers: what CrowdWatch is made of')
pu = [(80, 'Control room', 'Operators, officers, administrators, in a browser'), (450, 'Response unit', 'Scout phone'),
      (800, 'Visitor', 'Phone, QR code'), (1290, 'Control room', 'On one computer (desktop)')]
pp = [element(s, x, 80, 320, 150, n, 'Person', d, PERSON, person=True) for x, n, d in pu]
boundary(s, 40, 290, 1620, 650, 'CrowdWatch', 'Software system')
react = element(s, 80, 360, 320, 170, 'Control-room console', 'Container: React, TypeScript',
                'Every view, live venue map, charts; English and Arabic. Runs in the browser.', CONTAINER)
pages = element(s, 450, 360, 670, 170, 'Scout, help and classic pages', 'Container: HTML, JavaScript',
                'Scout phone page (/scout), visitor help page (/help), classic console (/legacy), plain dashboard (/classic). Served by the server.', CONTAINER)
desk = element(s, 1290, 360, 320, 170, 'Desktop console', 'Container: Java 17, JavaFX',
               'Control room on one computer; runs the core itself.', CONTAINER)
server = element(s, 450, 640, 670, 260, 'CrowdWatch server', 'Container: Java 17, Spring Boot, Spring Security, Tomcat',
                 'Spring Security checks every request; Spring controllers for reads and changes; live updates over a WebSocket. '
                 'The core (CrowdMonitor, rules, automation), sensor feeds, watchdog and saving run inside it. '
                 'Alternative: the built-in JDK server of CrowdWatch.jar, same rules.', CONTAINER)
files = element(s, 70, 680, 290, 200, 'Data folder', 'Container: files (crowdwatch-data)',
                'Accounts (hashes), data key, daily backups, audit and log files, photos, handover reports, certificate.', CONTAINER)
db = element(s, 1290, 650, 320, 240, 'Venue database', 'Container: Apache Derby, embedded',
             'In the server\'s process. Saved 10 s after a change and every 60 s. Personal data encrypted.', DB, db=True)
ex = [(80, 'Counting systems', 'Turnstiles, door counters, cameras'), (630, 'Radio / SMS gateway', 'Unit messages, public screens and PA'),
      (1180, 'Alerts, directory, monitoring', 'SMS or e-mail service, LDAP, Prometheus')]
ee = [element(s, x, 1020, 420, 140, n, 'External system', d, EXTERNAL) for x, n, d in ex]
rel(s, pp[0], react, 'Uses', 'browser')
rel(s, pp[1], pages, 'Uses /scout', 'phone browser')
rel(s, pp[2], pages, 'Uses /help', 'phone browser')
rel(s, pp[3], desk, 'Uses', 'JavaFX window')
rel(s, react, server, 'Reads and changes; live updates', 'HTTPS JSON, WebSocket', dx=-30)
rel(s, pages, server, 'Reads and changes', 'HTTPS JSON', dx=90)
rel(s, server, files, 'Writes', 'files')
rel(s, server, db, 'Saves, loads', 'JDBC')
rel(s, desk, db, 'Saves and loads', 'JDBC', dx=80)
rel(s, ee[0], server, 'Count lines', 'file, HTTPS, TCP', dx=-40)
rel(s, server, ee[1], 'Unit messages', 'HTTPS JSON', dx=-70)
rel(s, server, ee[2], 'Alerts, sign-in, metrics', 'HTTPS, LDAPS', dx=40)
s.lines(850, 1200, 'Only one program opens a venue\'s database at a time: the server or the desktop console. Level 3 (components of the server): the web-layer diagram. Level 4 (code): the class diagram of the core.', 13, '#444', 1500)
s.save('c4-level2-containers')
print('C4 diagrams written')

# =====================================================================================
# Use-case diagrams (UML), one per venue, from use-cases.json
import json, math
UC = json.load(open(os.path.join(os.path.dirname(__file__), 'use-cases.json')))


def stick(s, x, y, label):
    s.add(f'<circle cx="{x}" cy="{y - 38}" r="13" fill="#fff" stroke="#222" stroke-width="2"/>')
    s.add(f'<line x1="{x}" y1="{y - 25}" x2="{x}" y2="{y + 8}" stroke="#222" stroke-width="2"/>')
    s.add(f'<line x1="{x - 20}" y1="{y - 14}" x2="{x + 20}" y2="{y - 14}" stroke="#222" stroke-width="2"/>')
    s.add(f'<line x1="{x}" y1="{y + 8}" x2="{x - 15}" y2="{y + 32}" stroke="#222" stroke-width="2"/>')
    s.add(f'<line x1="{x}" y1="{y + 8}" x2="{x + 15}" y2="{y + 32}" stroke="#222" stroke-width="2"/>')
    s.lines(x, y + 54, label, 14, '#222', 170)


for v in UC['venues']:
    cases, names, left = v['cases'], {**UC['actors'], **v['names']}, UC['left']
    used = []
    for c in cases:
        for a in c[2]:
            if a not in used: used.append(a)
    sides = {0: [a for a in used if a in left], 1: [a for a in used if a not in left]}
    rh, top, W = 84, 120, 1600
    span = max(len(cases) * rh, max(len(x) for x in sides.values()) * 170)
    rh = span / len(cases)
    H = int(top + span + 90)
    s = Svg(W, H, f'Use cases - {v["title"]}')
    s.add(f'<rect x="420" y="{top - 30}" width="760" height="{span + 50:.0f}" rx="12" fill="#f7f9fc" stroke="#4a6a8f" stroke-width="2"/>')
    s.text(440, top - 8, 'CrowdWatch', 16, '#4a6a8f', bold=True)
    cx, rx, ry = 800, 300, 32
    pos = {c[0]: (cx, top + 20 + (k + 0.5) * rh) for k, c in enumerate(cases)}
    apos = {}
    for side, acts in sides.items():
        acts.sort(key=lambda a: sum(pos[c[0]][1] for c in cases if a in c[2]) / sum(1 for c in cases if a in c[2]))
        x = 190 if side == 0 else 1410
        for k, a in enumerate(acts):
            apos[a] = (x, top + 20 + (k + 0.5) * span / len(acts))
    for c in cases:
        ey = pos[c[0]][1]
        for a in c[2]:
            ax, ay = apos[a]
            ex = cx - rx if ax < cx else cx + rx
            sx = ax + (26 if ax < cx else -26)
            s.add(f'<line x1="{sx}" y1="{ay - 14:.0f}" x2="{ex}" y2="{ey:.0f}" stroke="#555" stroke-width="1.3"/>')
    for c in cases:
        _, cy = pos[c[0]]
        s.add(f'<ellipse cx="{cx}" cy="{cy:.0f}" rx="{rx}" ry="{ry}" fill="#dbe7f5" stroke="#1f4e79" stroke-width="2"/>')
        s.text(cx, cy - 5, c[0], 12, '#1f4e79', bold=True, anchor='middle')
        s.lines(cx, cy + 13, c[1], 14, '#14324f', 2 * rx - 40)
    for a, (x, y) in apos.items(): stick(s, x, y, names[a])
    s.save('use-cases-' + v['key'])
print('use-case diagrams written')
