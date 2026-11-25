/* CrowdWatch web console. Plain JavaScript, no libraries, works offline.
   It polls the server's /api endpoints every 3 seconds and draws everything in the browser. */
(function () {
  'use strict';
  var POLL_MS = 3000, HISTORY = 20, CHART_MIN = 60;
  var $ = function (id) { return document.getElementById(id); };
  var store = {
    get: function (k) { try { return localStorage.getItem('cw.' + k); } catch (e) { return null; } },
    set: function (k, v) { try { if (v == null) localStorage.removeItem('cw.' + k); else localStorage.setItem('cw.' + k, v); } catch (e) { /* private window */ } }
  };
  var S = {
    key: store.get('key') || '', officer: store.get('officer') || '', view: store.get('view') || 'overview',
    token: store.get('token') || '', role: store.get('role') || '', logins: false, users: null, audit: null, userMode: true,
    ov: null, areas: [], incidents: [], alerts: [], units: [], venue: null, online: true,
    hist: { people: [], high: [], open: [], avail: [], review: [] }, series: {}, seeded: {}, top: [],
    sel: null, detail: null, alertFilter: 'ALL', incFilter: 'OPEN', zone: '', preview: null, lost: [], signs: [],
    layout: null, mapUrl: null, planMode: store.get('planMode') || 'map',
    seenAlert: store.get('seenAlert') || '', timer: null, busy: false
  };

  // ---------- helpers ----------
  function esc(s) { return s == null ? '' : String(s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function fmt(n) { return n == null ? '-' : Number(n).toLocaleString('en-US'); }
  function pct(a) { return a.capacity > 0 ? Math.round(a.occupancy * 100 / a.capacity) : 0; }
  function icon(d, size, sw) { return '<svg width="' + (size || 18) + '" height="' + (size || 18) + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="' + (sw || 2.2) + '" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="' + d + '"/></svg>'; }
  var I = {
    overview: 'M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z',
    areas: 'M12 2l9 5v10l-9 5-9-5V7zM12 22V12M21 7l-9 5-9-5',
    incidents: 'M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
    units: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8',
    emergency: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
    alerts: 'M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0',
    settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM4 12h2M18 12h2M12 4v2M12 18v2M6.3 6.3l1.4 1.4M16.3 16.3l1.4 1.4M6.3 17.7l1.4-1.4M16.3 7.7l1.4-1.4',
    plus: 'M12 5v14M5 12h14', check: 'M20 6L9 17l-5-5', people: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
    review: 'M9 11l3 3L22 4M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11', chev: 'M9 6l6 6-6 6',
    trend: 'M3 17l6-6 4 4 8-8M14 7h7v7',
    lost: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM21 21l-5-5M11 8v3l2 2',
    sign: 'M3 11l18-5v12L3 13v-2zM11.6 16.8a3 3 0 1 1-5.8-1.6', radio: 'M4.9 19.1a10 10 0 0 1 0-14.2M19.1 4.9a10 10 0 0 1 0 14.2M8.5 15.5a5 5 0 0 1 0-7M15.5 8.5a5 5 0 0 1 0 7M12 12h.01'
  };
  var LEVELS = ['LOW', 'MODERATE', 'HIGH', 'CRITICAL'];
  var PRI = { CRITICAL: 'CRITICAL', HIGH: 'HIGH', NORMAL: 'NORMAL', LOW: 'MODERATE' };
  var STATUS = { WAITING: 'MODERATE', ASSIGNED: 'NORMAL', ON_SCENE: 'VIOLET', RESOLVED: 'LOW' };
  var USTAT = { AVAILABLE: 'LOW', BUSY: 'NORMAL', OFF_DUTY: 'GRAY', UNREACHABLE: 'CRITICAL' };
  var AKIND = { CROWD: ['HIGH', I.areas], TREND: ['VIOLET', I.trend], CLEARED: ['LOW', I.check], NO_UNIT: ['CRITICAL', I.units], ESCALATED: ['CRITICAL', I.incidents],
    SYSTEM: ['GRAY', I.settings], AUTO_DISPATCH: ['NORMAL', I.radio], SUPERVISION: ['CRITICAL', I.review], NO_ACK: ['MODERATE', I.radio], SOS: ['CRITICAL', I.incidents],
    EVACUATION: ['HIGH', I.emergency], BROADCAST: ['NORMAL', I.radio], DELIVERY: ['MODERATE', I.radio],
    SENSOR: ['GRAY', I.trend], FOLLOW_UP: ['MODERATE', I.units], FLOW: ['HIGH', I.areas], REPOSITION: ['NORMAL', I.units], EVENT: ['VIOLET', I.alerts],
    PROPOSAL: ['CRITICAL', I.emergency], ALARM: ['CRITICAL', I.incidents], LOST: ['VIOLET', I.lost], SHIFT: ['NORMAL', I.units], ANNOUNCE: ['MODERATE', I.sign] };
  function cssVar(n) { return getComputedStyle(document.body).getPropertyValue('--' + n).trim(); }
  function pill(k, text) { return '<span class="pill k-' + k + '">' + esc(text) + '</span>'; }
  function initials(name) { var p = String(name || '?').trim().split(/\s+/); return (p[0][0] + (p.length > 1 ? p[p.length - 1][0] : (p[0][1] || ''))).toUpperCase(); }
  function alertTime(a) { return String(a.time).substring(11, 19); }

  // ---------- server ----------
  // the sign-in of this browser: a session token (personal login) or the control key
  function auth() {
    var h = {};
    if (S.token) h.Authorization = 'Bearer ' + S.token;
    else if (S.key) h['X-Api-Key'] = S.key;
    return h;
  }
  var RANK = { VIEWER: 0, OPERATOR: 1, OFFICER: 2, ADMIN: 3 };
  // true if this browser's user has at least the role (the control key counts as ADMIN)
  function can(role) { return !S.token || (RANK[S.role] || 0) >= RANK[role]; }
  function api(path, form) {
    var opt = { headers: auth() };
    if (form) {
      opt.method = 'POST';
      opt.headers['Content-Type'] = 'application/x-www-form-urlencoded';
      var p = new URLSearchParams();
      Object.keys(form).forEach(function (k) { if (form[k] != null) p.append(k, form[k]); });
      opt.body = p.toString();
    }
    return fetch(path, opt).then(function (r) {
      if (r.status === 401) { var e = new Error(S.token ? 'Your session has ended - please sign in again.' : 'The control key is missing or wrong.'); e.auth = true; throw e; }
      if (r.status === 429) throw new Error('The server says: too many requests.');
      return r.text().then(function (t) { try { return JSON.parse(t); } catch (x) { return { ok: false, message: t }; } });
    });
  }
  function act(path, form, after) {
    form = form || {};
    if (form.officer === undefined) form.officer = S.officer;
    return api(path, form).then(function (r) {
      toast(r.message || (r.ok ? 'Done.' : 'Refused.'), r.ok ? 'good' : 'bad');
      if (r.ok && after) after(r);
      poll();
      return r;
    }).catch(fail);
  }
  function fail(e) {
    if (e && e.auth) { logout(e.message); return; }
    S.online = false; renderChrome();
    toast(e && e.message ? e.message : 'The server cannot be reached.', 'bad');
  }

  function poll() {
    if (S.busy) return;
    S.busy = true;
    Promise.all([api('/api/overview'), api('/api/areas'), api('/api/incidents'), api('/api/alerts'), api('/api/units'),
      S.venue ? Promise.resolve(S.venue) : api('/api/venue'), api('/api/lost'), api('/api/announcements')]).then(function (r) {
      S.ov = r[0]; S.areas = r[1]; S.incidents = r[2]; S.alerts = r[3]; S.units = r[4]; S.venue = r[5]; S.lost = r[6]; S.signs = r[7]; S.online = true;
      record();
      var waits = [seedChart()];
      if (!S.layout) waits.push(loadLayout());
      if (!S.preview && S.ov.evacuation) waits.push(loadPreview());
      if (S.sel != null) waits.push(api('/api/incident?id=' + S.sel).then(function (d) { S.detail = d; }));
      if (S.view === 'emergency' && (S.ov.evacuation || S.zone)) waits.push(loadPreview());
      return Promise.all(waits);
    }).then(render).catch(function (e) { fail(e); }).then(function () { S.busy = false; });
  }
  // the venue drawing and where each area is on it (fetched once; the image needs the key, so it is loaded as a blob)
  function loadLayout() {
    return api('/api/layout').then(function (l) {
      S.layout = l;
      if (!l.map || !l.image) return;
      var opt = { headers: auth() };
      return fetch('/api/map', opt).then(function (r) { return r.ok ? r.blob() : null; }).then(function (b) { if (b) S.mapUrl = URL.createObjectURL(b); });
    });
  }
  function loadPreview() {
    return api('/api/evacuation?zone=' + encodeURIComponent(S.zone || '')).then(function (p) { S.preview = p; });
  }

  // ---------- history for sparklines, deltas and the chart ----------
  function push(arr, v) { arr.push(v); if (arr.length > HISTORY) arr.shift(); }
  function record() {
    var o = S.ov, now = Date.now();
    push(S.hist.people, o.people);
    push(S.hist.high, o.levels.HIGH + o.levels.CRITICAL);
    push(S.hist.open, o.incidents.open);
    push(S.hist.avail, o.units.AVAILABLE);
    push(S.hist.review, o.review);
    S.areas.forEach(function (a) {
      var s = S.series[a.id] || (S.series[a.id] = []);
      s.push({ t: now, p: pct(a) });
      while (s.length && s[0].t < now - CHART_MIN * 60000) s.shift();
    });
    var by = S.areas.slice().sort(function (x, y) { return pct(y) - pct(x) || (x.id < y.id ? -1 : 1); });
    S.top = by.slice(0, 3).map(function (a) { return a.id; });
  }
  function seedChart() {
    var need = S.top.filter(function (id) { return !S.seeded[id]; });
    return Promise.all(need.map(function (id) {
      S.seeded[id] = true;
      return api('/api/readings?area=' + encodeURIComponent(id)).then(function (r) {
        if (!r.ok) return;
        var now = Date.now(), old = r.readings.map(function (x) { return { t: now - x.s * 1000, p: r.capacity > 0 ? Math.round(x.n * 100 / r.capacity) : 0 }; })
          .filter(function (x) { return x.t > now - CHART_MIN * 60000; });
        var cur = S.series[id] || [];
        var first = cur.length ? cur[0].t : now + 1;
        S.series[id] = old.filter(function (x) { return x.t < first; }).sort(function (a, b) { return a.t - b.t; }).concat(cur);
      });
    }));
  }
  function spark(vals) {
    if (vals.length < 2) vals = [vals[0] || 0, vals[0] || 0];
    var mx = Math.max.apply(null, vals), mn = Math.min.apply(null, vals);
    return vals.map(function (v, k) { return Math.round(k * 120 / (vals.length - 1)) + ',' + Math.round(30 - (v - mn) * 26 / Math.max(1, mx - mn)); }).join(' ');
  }
  function delta(arr, unit) {
    if (arr.length < 2) return 'live';
    var d = arr[arr.length - 1] - arr[0];
    if (unit === '%' && arr[0] > 0) { var p = Math.round(d * 100 / arr[0]); return p === 0 ? 'steady' : (p > 0 ? '+' : '') + p + '%'; }
    return d === 0 ? 'steady' : (d > 0 ? '+' : '') + d;
  }

  // ---------- chrome: header, banners, navigation ----------
  function renderChrome() {
    var o = S.ov;
    $('live').className = 'live' + (S.online ? '' : ' off');
    $('liveText').textContent = S.online ? 'LIVE ' + (o ? o.now.substring(0, 5) : '') : 'OFFLINE';
    if (!o) return;
    $('venueName').textContent = o.venue;
    document.title = 'CrowdWatch - ' + o.venue;
    $('officerName').innerHTML = esc((S.token ? '' : 'Officer ') + S.officer) + (S.token ? '<span class="role-tag">' + esc(S.role) + '</span>' : '');
    $('avatar').textContent = initials(S.officer);
    var st = $('officerState');
    var mine = o.officer && o.officer.toLowerCase() === S.officer.toLowerCase();
    st.textContent = o.overdue ? 'Check-in OVERDUE - click to check in' : mine ? 'On watch - ' + shortStatus(o.officerStatus) : 'On watch: ' + o.officer;
    st.className = 'officer-state' + (o.overdue ? ' bad' : '');
    var fresh = S.alerts.filter(function (a) { return a.time > S.seenAlert; }).length;
    $('bellCount').hidden = fresh === 0;
    $('bellCount').textContent = fresh > 99 ? '99+' : fresh;
    var b = '';
    if (!S.online) b += '<div class="banner down">The server is not answering. The figures below are from the last good update.</div>';
    if (o.evacuation) b += '<div class="banner evac">' + icon(I.emergency, 18) + '<span>EVACUATION of ' + esc(o.evacuation) + ' in progress. Public routes avoid the zone.</span><button data-act="go" data-arg="emergency">Open plan</button></div>';
    if (o.proposal) b += '<div class="banner sup">' + icon(I.emergency, 18) + '<span>EVACUATION PROPOSED for ' + esc(o.proposal.zone) + ': ' + esc(o.proposal.reason) + '. You decide.</span>'
      + '<button data-act="propstart">Start evacuation</button><button data-act="propdismiss">Dismiss</button></div>';
    if (o.overdue) b += '<div class="banner sup">' + icon(I.review, 18) + '<span>' + esc(o.officer ? 'Officer check-in is OVERDUE: ' + o.officerStatus : 'No security officer is on watch.') + '</span><button data-act="checkin">Check in now</button></div>';
    $('banners').innerHTML = b;
    var nav = [['overview', 'Overview'], ['areas', 'Areas and crowd'], ['incidents', 'Incidents', o.incidents.open, o.incidents.CRITICAL ? 'CRITICAL' : 'NORMAL'],
      ['units', 'Units and scouts', o.units.UNREACHABLE || null, 'CRITICAL'], ['lost', 'Lost persons', o.lost || null, 'VIOLET'],
      ['emergency', 'SOS and evacuation', o.evacuation || o.proposal ? '!' : o.signs || null, o.evacuation || o.proposal ? 'CRITICAL' : 'MODERATE'],
      ['alerts', 'Alerts', fresh || null, 'MODERATE'], ['settings', 'Venue settings']];
    var online = o.units.online, total = o.units.total;
    $('nav').innerHTML = nav.map(function (n) {
      return '<button class="nav-btn' + (S.view === n[0] ? ' on' : '') + '" data-act="go" data-arg="' + n[0] + '">' + icon(I[n[0]], 18, 2) + '<span class="l">' + n[1] + '</span>'
        + (n[2] ? '<span class="nav-badge" style="background:' + cssVar(n[3]) + '">' + esc(n[2]) + '</span>' : '') + '</button>';
    }).join('') + '<div class="sys"><span class="sys-title">System</span>'
      + '<div class="sys-row"><span class="sdot" style="background:' + cssVar(S.online ? 'LOW' : 'CRITICAL') + '"></span>' + (S.online ? 'Server online' : 'Server not answering') + '</div>'
      + '<div class="sys-row"><span class="sdot" style="background:' + cssVar(online === total ? 'LOW' : online === 0 ? 'CRITICAL' : 'MODERATE') + '"></span>' + online + ' of ' + total + ' devices online</div>'
      + '<div class="sys-row"><span class="sdot" style="background:' + cssVar(o.automation ? 'LOW' : 'GRAY') + '"></span>Automatic dispatch ' + (o.automation ? 'ON' : 'OFF') + '</div>'
      + '<div class="sys-row"><span class="sdot" style="background:' + cssVar('NORMAL') + '"></span>' + fmt(o.archived) + ' archived incidents</div></div>';
  }
  function shortStatus(s) { s = String(s || ''); var i = s.indexOf('checked in'); return i >= 0 ? s.substring(i) : s; }

  // ---------- render ----------
  var TITLES = { overview: 'Overview', areas: 'Areas and crowd', incidents: 'Incidents', units: 'Units and scouts', lost: 'Lost persons', emergency: 'SOS and evacuation', alerts: 'Alerts', settings: 'Venue settings' };
  function render() {
    renderChrome();
    if (!S.ov) return;
    var main = $('main'), ae = document.activeElement;
    var typing = ae && /^(INPUT|SELECT|TEXTAREA)$/.test(ae.tagName) && ae.type !== 'checkbox' && ae.id !== 'search';
    if (typing && $('blade').contains(ae)) return;      // do not pull a form away while someone is filling it in
    if (typing && main.contains(ae)) { renderBlade(); return; }
    var keep = {}, focus = document.activeElement && document.activeElement.id;
    main.querySelectorAll('[data-keep]').forEach(function (el) { keep[el.id] = el.type === 'checkbox' ? el.checked : el.value; });
    var body = ({ overview: vOverview, areas: vAreas, incidents: vIncidents, units: vUnits, lost: vLost, emergency: vEmergency, alerts: vAlerts, settings: vSettings }[S.view] || vOverview)();
    main.innerHTML = '<div class="page-head"><div><div class="crumb">CrowdWatch / ' + esc(S.ov.venue) + ' / ' + TITLES[S.view] + '</div><h1>' + TITLES[S.view] + '</h1></div>'
      + '<div class="actions"><button class="btn" data-act="report">' + icon(I.plus, 16) + 'Report incident</button>'
      + '<button class="btn" data-act="go" data-arg="emergency">' + icon(I.emergency, 16) + 'Evacuate</button>'
      + '<button class="btn-sos" data-act="sos">' + icon(I.incidents, 16, 2.4) + 'SOS</button></div></div>' + statusChips() + body;
    Object.keys(keep).forEach(function (id) { var el = $(id); if (el) { if (el.type === 'checkbox') el.checked = keep[id]; else el.value = keep[id]; } });
    if (focus && $(focus) && main.contains($(focus))) $(focus).focus();
    renderBlade();
  }
  function statusChips() {
    var o = S.ov, c = [];
    c.push(o.automation ? ['LOW', 'Automatic dispatch ON' + (o.autoRule ? ' - ' + o.autoRule : ''), 'settings', false] : ['GRAY', 'Automatic dispatch OFF', 'settings', false]);
    if (o.proposal) c.push(['CRITICAL', 'Evacuation proposed for ' + o.proposal.zone + ' - decide now', 'emergency', true]);
    if (o.lost) c.push(['VIOLET', o.lost + ' lost person' + (o.lost > 1 ? 's' : '') + ' missing', 'lost', true]);
    if (o.signs) c.push(['MODERATE', o.signs + ' public message' + (o.signs > 1 ? 's' : '') + ' to approve', 'emergency', false]);
    if (o.stale) c.push(['GRAY', o.stale + ' area' + (o.stale > 1 ? 's' : '') + ' with doubtful sensor data', 'areas', false]);
    if (o.countCheck && !o.countCheck.ok) c.push(['CRITICAL', 'Counts do not add up: ' + fmt(Math.abs(o.countCheck.difference)) + (o.countCheck.difference > 0 ? ' counted twice?' : ' missing?') + ' - recount', 'settings', true]);
    (o.events || []).forEach(function (e) { c.push(['VIOLET', 'Scheduled peak: ' + e, 'settings', false]); });
    if (o.review) c.push(['MODERATE', o.review + ' automatic dispatch' + (o.review > 1 ? 'es' : '') + ' awaiting review', 'incidents:REVIEW', false]);
    if (o.incidents.waiting) c.push(['CRITICAL', o.incidents.waiting + ' incident' + (o.incidents.waiting > 1 ? 's' : '') + ' waiting for a unit', 'incidents:WAITING', true]);
    S.incidents.filter(function (i) { return i.reminders > 0 && !i.acknowledged && i.unit; }).slice(0, 2).forEach(function (i) {
      c.push(['CRITICAL', i.unit + ' has not acknowledged I' + i.id, 'units', true]);
    });
    S.areas.filter(function (a) { return a.minutesToCritical > 0 && a.minutesToCritical <= 30; }).sort(function (x, y) { return x.minutesToCritical - y.minutesToCritical; }).slice(0, 2).forEach(function (a) {
      c.push(['VIOLET', a.id + ' rising +' + a.ratePerMinute + '/min - CRITICAL in ~' + a.minutesToCritical + ' min', 'areas', false]);
    });
    return '<div class="chips">' + c.map(function (x) {
      return '<button class="chip k-' + x[0] + '" data-act="go" data-arg="' + x[2] + '"><span class="cd' + (x[3] ? ' blink' : '') + '"></span>' + esc(x[1]) + '</button>';
    }).join('') + '</div>';
  }

  // ---------- overview ----------
  function vOverview() {
    var o = S.ov, hi = o.levels.HIGH + o.levels.CRITICAL;
    var busiest = S.areas.slice().sort(function (x, y) { return pct(y) - pct(x); })[0];
    var kp = [
      ['People on site', fmt(o.people), (o.capacity ? Math.round(o.people * 100 / o.capacity) : 0) + '% of ' + fmt(o.capacity) + ' capacity'
        + (o.countCheck ? ' - gates: ' + fmt(o.countCheck.expected) + (o.countCheck.ok ? ' (agrees)' : ' (DIFFERS)') : ''), delta(S.hist.people, '%'), 'NORMAL', I.people, S.hist.people, 'areas'],
      ['Areas HIGH or CRITICAL', hi, hi && busiest ? busiest.id + ' at ' + pct(busiest) + '%' : 'all areas below HIGH', delta(S.hist.high), hi ? 'HIGH' : 'LOW', I.areas, S.hist.high, 'areas'],
      ['Open incidents', o.incidents.open, o.incidents.CRITICAL + ' critical, ' + o.incidents.waiting + ' waiting', delta(S.hist.open), o.incidents.CRITICAL ? 'CRITICAL' : 'NORMAL', I.incidents, S.hist.open, 'incidents'],
      ['Units available', o.units.AVAILABLE, 'of ' + (o.units.total - o.units.OFF_DUTY) + ' on duty', delta(S.hist.avail), o.units.AVAILABLE ? 'LOW' : 'CRITICAL', I.check, S.hist.avail, 'units'],
      ['Awaiting review', o.review, 'automatic dispatches', delta(S.hist.review), 'VIOLET', I.review, S.hist.review, 'incidents:REVIEW']
    ];
    var kpis = '<div class="kpis">' + kp.map(function (k) {
      return '<button class="kpi" style="border-top-color:' + cssVar(k[4]) + '" data-act="go" data-arg="' + k[7] + '"><div class="kpi-top"><span class="kpi-icon k-' + k[4] + '" style="color:' + cssVar(k[4]) + '">' + icon(k[5]) + '</span>'
        + '<span class="kpi-delta k-' + k[4] + '">' + esc(k[3]) + '</span></div><span class="kpi-label">' + k[0] + '</span><span class="kpi-value">' + k[1] + '</span>'
        + '<svg class="spark" viewBox="0 0 120 34" preserveAspectRatio="none"><polyline points="' + spark(k[6]) + '" fill="none" stroke="' + cssVar(k[4]) + '" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/></svg>'
        + '<span class="kpi-note">' + esc(k[2]) + '</span></button>';
    }).join('') + '</div>';
    var alerts = S.alerts.slice(0, 6).map(alertRow).join('') || '<div class="empty">No alerts yet.</div>';
    return kpis
      + '<div class="row">' + planCard('Live floor plan', hasMap() && S.planMode === 'map' ? 'The venue drawing: colour is the crowd level, blue dots are units, dashed green outlines are exits' : 'Each row is a sector in walking order - colour is the crowd level, a pulsing outline means act now')
      + '<section class="card narrow"><div class="card-head"><h2>Live alerts</h2><button class="link" data-act="go" data-arg="alerts">View all</button></div><div>' + alerts + '</div></section></div>'
      + '<div class="row"><section class="card wide">' + chart() + '</section><section class="card narrow">' + donuts() + '</section></div>';
  }
  function legend() { return '<div class="legend">' + LEVELS.map(function (l) { return '<span><span class="sw" style="background:' + cssVar(l) + '"></span>' + l + '</span>'; }).join('') + '</div>'; }
  function lanes() {
    var bySector = {}, order = [];
    S.areas.forEach(function (a) { if (!bySector[a.sector]) { bySector[a.sector] = []; order.push(a.sector); } bySector[a.sector].push(a); });
    return order.map(function (sec) {
      var list = walkOrder(bySector[sec]);
      return '<div class="lane"><span class="lane-name">' + esc(sec) + '</span><div class="lane-tiles">' + list.map(function (a, k) {
        return tile(a) + (k < list.length - 1 ? '<span class="arrow">' + icon(I.chev, 16, 2.4) + '</span>' : '');
      }).join('') + '</div></div>';
    }).join('');
  }
  // order a sector's areas along their walking links: start at an end of the chain, then follow neighbours
  function walkOrder(list) {
    var ids = {}, out = [], done = {};
    list.forEach(function (a) { ids[a.id] = a; });
    var deg = function (a) { return a.links.filter(function (l) { return ids[l]; }).length; };
    var rest = list.slice().sort(function (x, y) { return deg(x) - deg(y) || (x.id < y.id ? -1 : 1); });
    while (out.length < list.length) {
      var cur = rest.filter(function (a) { return !done[a.id]; })[0];
      while (cur) {
        done[cur.id] = true; out.push(cur);
        var next = cur.links.filter(function (l) { return ids[l] && !done[l]; }).map(function (l) { return ids[l]; })
          .sort(function (x, y) { return deg(x) - deg(y) || (x.id < y.id ? -1 : 1); })[0];
        cur = next;
      }
    }
    return out;
  }
  function tile(a) {
    var p = pct(a), rising = a.minutesToCritical > 0 && a.minutesToCritical <= 30;
    var cls = a.level === 'HIGH' || a.level === 'CRITICAL' ? ' pulse-red' : rising ? ' pulse-amber' : '';
    var doubt = a.stale ? 'STALE' : a.suspect ? 'CHECK' : '';
    var tip = a.name + (a.stale ? ' - no sensor data: the count may be out of date' : '') + (a.suspect ? ' - sensor count doubtful: ' + a.suspect : '') + (a.flowHeld ? ' - flow held by units next to it' : '') + (a.eventOffset ? ' - scheduled peak: levels start ' + a.eventOffset + ' points earlier' : '');
    return '<button class="tile k-' + (doubt ? 'GRAY' : a.level) + (rising ? ' rising' : '') + (a.evacuating ? ' evac' : '') + (doubt ? ' doubt' : '') + cls + '" data-act="area" data-arg="' + esc(a.id) + '" title="' + esc(tip) + '">'
      + '<span class="tile-top"><span>' + esc(a.id) + '</span><span class="lv">' + (a.evacuating ? 'EVAC' : doubt || a.level) + '</span></span>'
      + '<span class="tile-pct">' + p + '%</span><span class="track"><span class="bar" style="width:' + Math.min(100, p) + '%;background:linear-gradient(90deg,' + cssVar(a.level) + 'AA,' + cssVar(a.level) + ')"></span></span>'
      + '<span class="tile-n">' + fmt(a.occupancy) + ' / ' + fmt(a.capacity) + (a.flowHeld ? ' - HOLD' : '') + '</span>'
      + (a.unitsWanted ? '<span class="tile-units' + (a.unitsHere < a.unitsWanted ? ' short' : '') + '">' + a.unitsHere + ' of ' + a.unitsWanted + ' units</span>' : '') + '</button>';
  }
  function alertRow(a) {
    var k = AKIND[a.kind] || ['GRAY', I.alerts];
    return '<div class="al"><span class="al-icon k-' + k[0] + '">' + icon(k[1], 14, 2.4) + '</span><div class="al-body"><span class="al-meta">' + pill(k[0], a.kind) + '<span class="sub">' + alertTime(a) + (a.ref ? ' - ' + esc(a.ref) : '') + '</span></span>'
      + '<span class="al-text">' + esc(a.text) + '</span></div></div>';
  }
  function chart() {
    var t = S.venue ? S.venue.thresholds : [50, 80, 95], now = Date.now(), oldest = now;
    S.top.forEach(function (id) { var s = S.series[id]; if (s && s.length && s[0].t < oldest) oldest = s[0].t; });
    // the window grows with the data held: 5 minutes after a fresh start, up to 60
    var win = Math.max(5, Math.min(CHART_MIN, Math.ceil((now - oldest) / 300000) * 5)), from = now - win * 60000;
    var cols = ['HIGH', 'VIOLET', 'NORMAL'];
    var y = function (p) { return Math.round(220 - Math.max(0, Math.min(105, p)) * 2.1); };
    var x = function (tm) { return Math.round((tm - from) * 600 / (win * 60000)); };
    var lines = S.top.map(function (id, k) {
      var s = (S.series[id] || []).filter(function (pt) { return pt.t >= from; });
      if (!s.length) return '';
      var pts = s.map(function (pt) { return x(pt.t) + ',' + y(pt.p); });
      if (s.length === 1) pts.unshift('0,' + y(s[0].p));
      var col = cssVar(cols[k]);
      return '<polygon points="' + pts[0].split(',')[0] + ',220 ' + pts.join(' ') + ' ' + pts[pts.length - 1].split(',')[0] + ',220" fill="url(#cwFill' + k + ')"/>'
        + '<polyline points="' + pts.join(' ') + '" fill="none" stroke="' + col + '" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>'
        + '<circle cx="' + pts[pts.length - 1].split(',')[0] + '" cy="' + pts[pts.length - 1].split(',')[1] + '" r="4.5" fill="' + col + '"/>';
    }).join('');
    var defs = cols.map(function (c, k) { return '<linearGradient id="cwFill' + k + '" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="' + cssVar(c) + '" stop-opacity="0.30"/><stop offset="1" stop-color="' + cssVar(c) + '" stop-opacity="0"/></linearGradient>'; }).join('');
    var grid = [0, 1, 2, 3, 4].map(function (q) { var gx = q * 150; return '<line x1="' + gx + '" y1="0" x2="' + gx + '" y2="220" stroke="' + cssVar('border') + '" stroke-width="1"/>'; }).join('');
    return '<div class="card-head"><div><h2>Occupancy - last ' + win + ' minutes</h2><span class="sub">The three busiest areas. Dashed lines: HIGH ' + t[1] + '% and CRITICAL ' + t[2] + '%</span></div>'
      + '<div class="chart-legend">' + S.top.map(function (id, k) { return '<span><i style="background:' + cssVar(cols[k]) + '"></i>' + esc(id) + '</span>'; }).join('') + '</div></div>'
      + '<svg class="chart" viewBox="0 0 600 230" preserveAspectRatio="none" role="img" aria-label="Occupancy of ' + esc(S.top.join(', ')) + ' over the last hour"><defs>' + defs + '</defs>' + grid
      + '<line x1="0" y1="' + y(t[1]) + '" x2="600" y2="' + y(t[1]) + '" stroke="' + cssVar('MODERATE') + '" stroke-width="1.5" stroke-dasharray="6 6"/>'
      + '<line x1="0" y1="' + y(t[2]) + '" x2="600" y2="' + y(t[2]) + '" stroke="' + cssVar('CRITICAL') + '" stroke-width="1.5" stroke-dasharray="6 6"/>' + lines + '</svg>'
      + '<div class="sub" style="display:flex;justify-content:space-between"><span>-' + win + ' min</span><span>-' + (win * 3 / 4) + '</span><span>-' + (win / 2) + '</span><span>-' + (win / 4) + '</span><span>now</span></div>';
  }
  function donut(parts, total, label) {
    var at = 0, stops = parts.filter(function (p) { return p[1] > 0; }).map(function (p) { var s = at; at += p[1] / Math.max(1, total) * 360; return cssVar(p[2]) + ' ' + s + 'deg ' + at + 'deg'; });
    if (!stops.length) stops = [cssVar('border') + ' 0deg 360deg'];
    return '<div class="donut-wrap"><div class="donut" style="background:conic-gradient(' + stops.join(',') + ')"><div class="donut-hole"><b>' + total + '</b><span class="sub">' + label + '</span></div></div>'
      + '<div class="donut-legend">' + parts.map(function (p) { return '<span><span class="ld" style="background:' + cssVar(p[2]) + '"></span>' + p[0] + ' <b>' + p[1] + '</b></span>'; }).join('') + '</div></div>';
  }
  function donuts() {
    var o = S.ov, u = o.units, i = o.incidents;
    return '<h2>Units</h2>' + donut([['Available', u.AVAILABLE, 'LOW'], ['Busy', u.BUSY, 'NORMAL'], ['Unreachable', u.UNREACHABLE, 'CRITICAL'], ['Off duty', u.OFF_DUTY, 'GRAY']], u.total, 'units')
      + '<h2>Open incidents</h2>' + donut([['Critical', i.CRITICAL, 'CRITICAL'], ['High', i.HIGH, 'HIGH'], ['Normal', i.NORMAL, 'NORMAL'], ['Low', i.LOW, 'MODERATE']], i.open, 'open');
  }

  // ---------- areas ----------
  function vAreas() {
    var rows = S.areas.slice().sort(function (x, y) { return pct(y) - pct(x); }).map(function (a) {
      var p = pct(a), trend = a.ratePerMinute ? (a.ratePerMinute > 0 ? '+' : '') + a.ratePerMinute + '/min' + (a.minutesToCritical > 0 ? ', CRITICAL in ~' + a.minutesToCritical + ' min' : '') : 'steady';
      return '<tr class="click" data-act="area" data-arg="' + esc(a.id) + '"><td><b>' + esc(a.id) + '</b><div class="sub">' + esc(a.name) + '</div></td><td>' + esc(a.sector) + '</td>'
        + '<td><span class="mini"><span class="bar" style="width:' + Math.min(100, p) + '%;background:' + cssVar(a.level) + '"></span></span>' + p + '%</td>'
        + '<td>' + fmt(a.occupancy) + ' / ' + fmt(a.capacity) + '</td><td>' + pill(a.level, a.level) + (a.evacuating ? ' ' + pill('HIGH', 'EVACUATING') : '') + (a.restricted ? ' ' + pill('GRAY', 'staff only') : '') + '</td>'
        + '<td style="color:' + (a.minutesToCritical > 0 ? cssVar('VIOLET-t') : a.ratePerMinute > 0 ? cssVar('MODERATE-t') : cssVar('sub')) + ';font-weight:' + (a.ratePerMinute ? 700 : 400) + '">' + esc(trend) + '</td>'
        + '<td>' + (a.unitsWanted ? '<b style="color:' + (a.unitsHere < a.unitsWanted ? cssVar('CRITICAL-t') : cssVar('LOW-t')) + '">' + a.unitsHere + ' of ' + a.unitsWanted + '</b>' : a.unitsHere) + '</td>'
        + '<td class="sub">' + esc(a.links.join(', ')) + '</td></tr>';
    }).join('');
    return planCard('Live floor plan', 'Click an area to enter a head count')
      + '<section class="card"><div class="card-head"><h2>All areas by load</h2><span class="sub">' + S.areas.length + ' areas</span></div><div class="tbl-wrap"><table><thead><tr><th>Area</th><th>Sector</th><th>Load</th><th>People</th><th>Level</th><th>Trend</th><th>Units</th><th>Leads to</th></tr></thead><tbody>' + rows + '</tbody></table></div></section>';
  }
  function areaDialog(id) {
    var a = S.areas.filter(function (x) { return x.id === id; })[0];
    if (!a) return;
    modal('blue', a.id + ' - ' + a.name, a.sector + ' - ' + pct(a) + '% (' + a.level + '), ' + fmt(a.occupancy) + ' of ' + fmt(a.capacity),
      '<form class="form" data-form="count" data-arg="' + esc(a.id) + '"><label>People counted now<input id="fCount" type="number" min="0" required value="' + a.occupancy + '"></label>'
      + '<div class="inline"><button type="button" class="btn" data-act="adj" data-arg="-10">-10</button><button type="button" class="btn" data-act="adj" data-arg="-1">-1</button><button type="button" class="btn" data-act="adj" data-arg="1">+1</button><button type="button" class="btn" data-act="adj" data-arg="10">+10</button></div>'
      + '<div class="sub">Trend: ' + (a.ratePerMinute ? a.ratePerMinute + ' people a minute' : 'steady') + '. Leads to: ' + esc(a.links.join(', ') || 'nothing') + '.</div>'
      + '<div class="dialog-foot"><button type="button" class="btn" data-act="close">Cancel</button><button class="btn-primary">Save count</button></div></form>');
    $('fCount').focus();
  }

  // ---------- incidents ----------
  function vIncidents() {
    var f = S.incFilter, list = S.incidents.filter(function (i) {
      return f === 'OPEN' || (f === 'REVIEW' ? i.review : f === 'WAITING' ? i.status === 'WAITING' : f === 'AUTO' ? i.automatic : i.priority === f);
    });
    var count = function (k) { return S.incidents.filter(function (i) { return k === 'OPEN' || (k === 'REVIEW' ? i.review : k === 'WAITING' ? i.status === 'WAITING' : k === 'AUTO' ? i.automatic : i.priority === k); }).length; };
    var chips = [['OPEN', 'All open', 'NORMAL'], ['CRITICAL', 'Critical', 'CRITICAL'], ['WAITING', 'Waiting for a unit', 'MODERATE'], ['REVIEW', 'Awaiting review', 'VIOLET'], ['AUTO', 'Automatic', 'NORMAL']];
    var rows = list.map(function (i) {
      return '<tr class="click' + (S.sel === i.id ? ' sel' : '') + '" data-act="inc" data-arg="' + i.id + '"><td><b>I' + i.id + '</b></td><td>' + esc(i.label) + (i.automatic ? ' ' + pill('VIOLET', i.review ? 'AUTO - review' : 'AUTO') : '') + '</td>'
        + '<td>' + pill(PRI[i.priority], i.priority) + '</td><td>' + esc(i.area) + '</td><td>' + pill(STATUS[i.status] || 'GRAY', i.status.replace('_', ' ')) + '</td>'
        + '<td>' + (i.unit ? esc(i.unit) : '<span class="sub">-</span>') + '<div class="sub" style="color:' + (i.reminders && !i.acknowledged ? cssVar('CRITICAL-t') : '') + '">' + esc(i.device) + '</div></td>'
        + '<td>' + i.minutesOpen + ' min<div class="sub">' + esc(i.reported) + '</div></td></tr>';
    }).join('');
    return '<div class="chips">' + chips.map(function (c) { return '<button class="chip k-' + c[2] + (f === c[0] ? ' sel' : '') + '" data-act="incf" data-arg="' + c[0] + '">' + c[1] + ' <b>' + count(c[0]) + '</b></button>'; }).join('') + '</div>'
      + '<section class="card"><div class="card-head"><div><h2>Open incidents</h2><span class="sub">Most urgent first. Click one for details, route and actions.</span></div></div>'
      + (rows ? '<div class="tbl-wrap"><table><thead><tr><th>ID</th><th>What</th><th>Priority</th><th>Area</th><th>Status</th><th>Unit and device</th><th>Open</th></tr></thead><tbody>' + rows + '</tbody></table></div>' : '<div class="empty">Nothing here. ' + (f === 'OPEN' ? 'No open incidents.' : '') + '</div>') + '</section>';
  }
  function renderBlade() {
    var bl = $('blade'), d = S.detail;
    if (S.sel == null || !d) { bl.hidden = true; return; }
    var keep = {};
    bl.querySelectorAll('[data-keep]').forEach(function (el) { keep[el.id] = el.value; });
    var focus = document.activeElement && bl.contains(document.activeElement) ? document.activeElement.id : null;
    bl.hidden = false;
    if (!d.ok) { bl.innerHTML = '<div class="blade-head" style="background:linear-gradient(135deg,#2F7CF6,#8B5CF6)"><button class="x" data-act="unsel" aria-label="Close">x</button><h2>I' + S.sel + '</h2></div><div class="blade-body"><div class="empty">' + esc(d.message) + '</div></div>'; return; }
    var k = PRI[d.priority], c1 = cssVar(k);
    var units = S.units.filter(function (u) { return u.status === 'AVAILABLE' && u.id !== d.unit; });
    var acts = '';
    if (d.open) {
      if (d.automatic && !d.reviewedBy) acts += '<button class="btn-green" data-act="review" data-arg="true">' + icon(I.check, 16) + ' Approve dispatch</button><button class="btn" data-act="review" data-arg="false">Reject</button>';
      if (d.status === 'ASSIGNED') acts += '<button class="btn" data-act="onscene">Confirm on scene</button>';
      acts += '<button class="btn" data-act="escalate">Escalate</button>';
      if (d.lost && !d.foundWhere) acts += '<button class="btn-green" data-act="foundopen" data-arg="' + d.id + '">Person found...</button>';
    }
    bl.innerHTML = '<div class="blade-head" style="background:linear-gradient(135deg,' + c1 + ',' + c1 + '99)"><button class="x" data-act="unsel" aria-label="Close">x</button>'
      + '<span style="font-size:12px;font-weight:700;opacity:.9">I' + d.id + ' - ' + esc(d.priority) + (d.automatic ? ' - AUTOMATIC' : '') + '</span><h2>' + esc(d.label) + ' at ' + esc(d.area) + '</h2>'
      + '<span style="font-size:13px">' + esc(d.status.replace('_', ' ')) + (d.unit ? ' - ' + esc(d.unit) + ': ' + esc(d.device) : ' - ' + esc(d.device)) + '</span></div>'
      + '<div class="blade-body"><dl class="kv"><dt>Area</dt><dd>' + esc(d.area) + ' - ' + esc(d.areaName) + '</dd><dt>Reported</dt><dd>' + esc(d.reported) + '</dd>'
      + '<dt>Details</dt><dd>' + esc(d.details || '-') + '</dd>' + (d.contact ? '<dt>Contact</dt><dd>' + esc(d.contact) + '</dd>' : '') + (d.language ? '<dt>Language</dt><dd>' + esc(d.language) + '</dd>' : '')
      + (d.lost ? '<dt>Lost person</dt><dd>' + (d.foundWhere ? '<b>FOUND at ' + esc(d.foundWhere) + '</b> by ' + esc(d.foundBy) : '<b>MISSING</b> - search ' + esc(d.search.join(', '))) + '</dd><dt>Meeting point</dt><dd>' + esc(d.meeting) + '</dd>' : '')
      + '<dt>Unit</dt><dd>' + esc(d.unit || 'none yet') + '</dd><dt>Route</dt><dd>' + esc(d.route) + '</dd>' + (d.automatic ? '<dt>Review</dt><dd>' + esc(d.reviewedBy ? 'reviewed by ' + d.reviewedBy : 'awaiting an officer') + '</dd>' : '') + '</dl>'
      + (acts ? '<div class="blade-actions">' + acts + '</div>' : '')
      + (d.open ? '<form class="form" data-form="reassign"><div class="inline"><label>Send another unit<select id="bUnit" data-keep>' + (units.length ? units.map(function (u) { return '<option value="' + esc(u.id) + '">' + esc(u.id + ' - ' + u.name + ' (' + u.area + ')') + '</option>'; }).join('') : '<option value="">no unit available</option>') + '</select></label>'
        + '<label>Reason<input id="bReason" data-keep placeholder="optional"></label></div><button class="btn"' + (units.length ? '' : ' disabled') + '>Reassign</button></form>'
        + '<form class="form" data-form="resolve"><label>Resolve with a note<textarea id="bNote" data-keep required maxlength="300" placeholder="What was done"></textarea></label><button class="btn-primary">' + icon(I.check, 16) + ' Resolve incident</button></form>' : '')
      + '<h2>Timeline</h2><div class="timeline">' + d.history.map(function (h) { return '<div class="tl"><div class="t">' + esc(h.t) + '</div>' + esc(h.text) + '</div>'; }).join('') + '</div></div>';
    Object.keys(keep).forEach(function (id) { var el = $(id); if (el && keep[id] != null && (el.tagName !== 'SELECT' || el.querySelector('option[value="' + CSS.escape(keep[id]) + '"]'))) el.value = keep[id]; });
    if (focus && $(focus)) $(focus).focus();
  }
  function select(id) {
    S.sel = id; S.detail = null;
    api('/api/incident?id=' + id).then(function (d) { S.detail = d; render(); }).catch(fail);
  }

  // ---------- units ----------
  function vUnits() {
    var o = S.ov;
    var cards = S.units.map(function (u) {
      var k = USTAT[u.status] || 'GRAY';
      return '<section class="card unit"><div class="unit-head"><span class="u-av" style="background:linear-gradient(135deg,' + cssVar(k === 'GRAY' ? 'sub' : k) + ',' + cssVar(k === 'GRAY' ? 'sub' : k) + 'AA)">' + esc(u.id) + '</span>'
        + '<span class="u-name"><b>' + esc(u.name) + '</b><span class="sub">' + esc(u.area) + ' - ' + esc(u.sector) + '</span></span>' + pill(k, u.status.replace('_', ' ')) + '</div>'
        + '<div class="sub" style="display:flex;align-items:center;gap:6px"><span class="sdot' + (u.online ? '' : '') + '" style="background:' + cssVar(u.online ? 'LOW' : 'GRAY') + '"></span>Device ' + (u.online ? 'online' : 'offline') + (u.lastContact ? ' - last contact ' + esc(u.lastContact) : ' - never connected') + '</div>'
        + (u.skills ? '<div class="sub">Skills: ' + esc(u.skills) + '</div>' : '')
        + (u.reinforcing ? '<div>' + pill('HIGH', 'REINFORCING ' + u.reinforcing) + (u.home ? ' <span class="sub">returns to ' + esc(u.home) + '</span>' : '') + '</div>' : '')
        + (u.task ? '<button class="task" data-act="inc" data-arg="' + u.task.id + '" style="cursor:pointer;text-align:left;color:inherit"><span>' + pill(PRI[u.task.priority], u.task.priority) + ' <b>I' + u.task.id + '</b> ' + esc(u.task.label) + ' at ' + esc(u.task.area) + '</span>'
          + '<span class="sub" style="color:' + (/reminder/.test(u.task.device) ? cssVar('CRITICAL-t') : '') + '">' + esc(u.task.device) + '</span></button>' : '<div class="task"><span class="sub">No task - ready to be sent</span></div>')
        + '</section>';
    }).join('');
    return '<div class="row"><section class="card narrow">' + donut([['Available', o.units.AVAILABLE, 'LOW'], ['Busy', o.units.BUSY, 'NORMAL'], ['Unreachable', o.units.UNREACHABLE, 'CRITICAL'], ['Off duty', o.units.OFF_DUTY, 'GRAY']], o.units.total, 'units') + '</section>'
      + '<section class="card wide"><h2>Scout devices</h2><div class="sub">Scouts open <b>/scout</b> on their issued phone with the scout key. Tasks arrive there and on the radio gateway; a task that is not acknowledged within the set minutes is sent again and then raised as an alert.</div>'
      + '<div style="display:flex;gap:18px;flex-wrap:wrap"><div><div class="kpi-value">' + o.units.online + '<span class="sub"> / ' + o.units.total + '</span></div><div class="sub">devices online</div></div>'
      + '<div><div class="kpi-value">' + o.incidents.unacknowledged + '</div><div class="sub">tasks not yet acknowledged</div></div></div></section></div>'
      + '<div class="units">' + cards + '</div>';
  }

  // ---------- emergency ----------
  function zoneOptions(sel) {
    var v = S.venue;
    return '<option value="">Choose an area or sector</option><optgroup label="Sectors">' + v.sectors.map(function (s) { return '<option' + (s === sel ? ' selected' : '') + '>' + esc(s) + '</option>'; }).join('') + '</optgroup>'
      + ((v.fireZones || []).length ? '<optgroup label="Fire zones">' + v.fireZones.map(function (s) { return '<option' + (s === sel ? ' selected' : '') + '>' + esc(s) + '</option>'; }).join('') + '</optgroup>' : '')
      + '<optgroup label="Areas">' + v.areas.map(function (s) { return '<option' + (s === sel ? ' selected' : '') + '>' + esc(s) + '</option>'; }).join('') + '</optgroup>';
  }

  // ---------- venue map ----------
  function hasMap() { return S.layout && S.layout.map; }
  function planCard(title, sub) {
    var map = hasMap() && S.planMode === 'map';
    return '<section class="card wide"><div class="card-head"><div><h2>' + title + '</h2><span class="sub">' + sub + '</span></div><div class="actions">'
      + (hasMap() ? '<div class="seg"><button class="' + (map ? 'on' : '') + '" data-act="plan" data-arg="map">Map</button><button class="' + (map ? '' : 'on') + '" data-act="plan" data-arg="lanes">Lanes</button></div>' : '')
      + legend() + '</div></div>' + (map ? venueMap(S.ov.evacuation && S.preview ? S.preview.rows : null) : lanes()) + '</section>';
  }
  /**
   * The venue drawing with every area on it in its crowd colour, the number of units there, exits with their
   * flow, and (when rows are given) the evacuation routes as arrows, thicker for more people.
   */
  function venueMap(rows) {
    var L = S.layout, W = L.w, H = L.h, fs = Math.max(11, Math.round(W / 95));
    var byId = {}; S.areas.forEach(function (a) { byId[a.id] = a; });
    var centre = {}; L.areas.forEach(function (p) { centre[p.id] = [p.x + p.w / 2, p.y + p.h / 2]; });
    var unitsAt = {}; S.units.forEach(function (u) { if (u.status !== 'OFF_DUTY') unitsAt[u.area] = (unitsAt[u.area] || 0) + 1; });
    var svg = '<svg class="venue-map" viewBox="0 0 ' + W + ' ' + H + '" role="img" aria-label="Venue map with live crowd levels">'
      + '<defs><marker id="cwArrow" viewBox="0 0 10 10" refX="7" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="' + cssVar('LOW') + '"/></marker>'
      + '<marker id="cwArrowZ" viewBox="0 0 10 10" refX="7" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="' + cssVar('HIGH') + '"/></marker></defs>'
      + (S.mapUrl ? '<image href="' + S.mapUrl + '" x="0" y="0" width="' + W + '" height="' + H + '" preserveAspectRatio="none"/>' : '<rect width="' + W + '" height="' + H + '" fill="' + cssVar('tint') + '"/>');
    L.areas.forEach(function (p) {
      var a = byId[p.id] || p, lv = a.restricted ? 'GRAY' : (a.stale || a.suspect) ? 'GRAY' : a.level, col = cssVar(lv);
      var hot = !a.restricted && (a.level === 'HIGH' || a.level === 'CRITICAL');
      svg += '<g class="ma' + (hot ? ' hot' : '') + '" data-act="area" data-arg="' + esc(p.id) + '"><title>' + esc((a.name || p.id) + ' - ' + (a.restricted ? 'staff only' : pct(a) + '% ' + a.level)) + '</title>'
        + '<rect x="' + p.x + '" y="' + p.y + '" width="' + p.w + '" height="' + p.h + '" rx="8" fill="' + col + '" fill-opacity="' + (a.restricted ? 0.12 : hot ? 0.42 : 0.26) + '" stroke="' + col + '" stroke-width="' + (hot ? 4 : 2)
        + '"' + (p.exit ? ' stroke-dasharray="10 6"' : '') + (a.evacuating ? ' stroke-dasharray="4 4"' : '') + '/>'
        + '<text x="' + (p.x + 7) + '" y="' + (p.y + fs + 4) + '" font-size="' + fs + '" font-weight="800" fill="' + cssVar('text') + '">' + esc(p.id) + '</text>'
        + (a.restricted ? '' : '<text x="' + (p.x + 7) + '" y="' + (p.y + 2 * fs + 8) + '" font-size="' + Math.round(fs * 1.25) + '" font-weight="800" fill="' + cssVar(lv + '-t') + '">' + pct(a) + '%</text>')
        + (p.exit ? '<text x="' + (p.x + p.w - 7) + '" y="' + (p.y + p.h - 8) + '" text-anchor="end" font-size="' + fs + '" font-weight="800" fill="' + cssVar('LOW-t') + '">EXIT' + (p.flow ? ' ' + fmt(p.flow) + '/min' : '') + '</text>' : '')
        + (unitsAt[p.id] ? '<g><circle cx="' + (p.x + p.w - fs) + '" cy="' + (p.y + fs) + '" r="' + fs * 0.85 + '" fill="' + cssVar('accent') + '"/><text x="' + (p.x + p.w - fs) + '" y="' + (p.y + fs * 1.35) + '" text-anchor="middle" font-size="' + fs + '" font-weight="800" fill="#fff">' + unitsAt[p.id] + '</text></g>' : '')
        + '</g>';
    });
    if (rows) {
      var drawn = {};
      rows.forEach(function (r) {
        var ids = String(r.path).replace(/^\[|\]$/g, '').split(' -> ');
        if (ids.length < 2 || drawn[r.path]) return;
        drawn[r.path] = true;
        var pts = ids.map(function (id) { return centre[id]; }).filter(Boolean);
        if (pts.length < 2) return;
        var people = parseInt(r.people, 10) || 0, w = people > 0 ? Math.max(3, Math.min(14, Math.log(people + 1) * 1.4)) : 2;
        svg += '<polyline points="' + pts.map(function (q) { return q[0] + ',' + q[1]; }).join(' ') + '" fill="none" stroke="' + cssVar(r.inZone ? 'HIGH' : 'LOW') + '" stroke-opacity="0.9" stroke-width="' + w
          + '" stroke-linecap="round" stroke-linejoin="round" marker-end="url(#' + (r.inZone ? 'cwArrowZ' : 'cwArrow') + ')"><title>' + esc(r.from + ' -> ' + r.exit + (people ? ': ' + fmt(people) + ' people, out in about ' + r.clear + ' min' : '')) + '</title></polyline>';
      });
    }
    return svg + '</svg>';
  }
  function areaOptions(id) { return S.venue.areas.map(function (s) { return '<option' + (s === id ? ' selected' : '') + '>' + esc(s) + '</option>'; }).join(''); }
  function signsCard() {
    var pend = S.signs.filter(function (a) { return a.status === 'PROPOSED'; }), done = S.signs.filter(function (a) { return a.status !== 'PROPOSED'; }).slice(0, 5);
    return '<div class="card-head"><div><h2>' + icon(I.sign, 18) + ' Public screens and PA</h2><span class="sub">The system drafts messages (busy areas, evacuation, long-missing persons). Nothing reaches the public until an officer approves it.</span></div></div>'
      + (pend.length ? pend.map(function (a) {
        return '<div class="task"><span class="sub">#' + a.id + ' drafted ' + esc(a.time) + ' about ' + esc(a.ref) + '</span><b>' + esc(a.text) + '</b><div class="actions">'
          + '<button class="btn-green" data-act="signok" data-arg="' + a.id + '">Approve and send</button><button class="btn sm" data-act="signno" data-arg="' + a.id + '">Reject</button></div></div>';
      }).join('') : '<div class="empty">No drafts waiting.</div>')
      + '<form class="form" data-form="announce"><div class="inline"><label>Your own message<input id="anText" data-keep maxlength="300" placeholder="e.g. Gate C is closed, please use Gate B"></label><button class="btn-primary">Send to screens and PA</button></div></form>'
      + (done.length ? '<div class="sub">Recent: ' + done.map(function (a) { return '#' + a.id + ' ' + a.status.toLowerCase() + (a.by ? ' by ' + esc(a.by) : ''); }).join(' - ') + '</div>' : '');
  }
  function vEmergency() {
    var o = S.ov, p = S.preview, active = o.evacuation;
    var showRows = p ? p.rows.filter(function (r) { return r.inZone || parseInt(r.people, 10) > 0; }) : [];
    var rows = p && p.rows.length ? (p.clearMinutes ? '<div class="clear-est">Estimated time until everyone in the zone is out: <b>' + p.clearMinutes + ' min</b><span class="sub"> - from walking times, exit flows and corridor capacities in the venue file; people present now</span></div>' : '')
      + (hasMap() ? venueMap(p.rows) : '')
      + '<div class="tbl-wrap"><table><thead><tr><th>From</th><th>Exit</th><th>Route</th><th>Walk</th><th>People</th><th>All out</th></tr></thead><tbody>' + showRows.map(function (r) {
      return '<tr><td><b>' + esc(r.from) + '</b>' + (r.inZone ? ' ' + pill('HIGH', 'in zone') : '') + '</td><td><span class="exit-tag">' + esc(r.exit) + '</span></td><td class="sub">' + esc(String(r.path).replace(/^\[|\]$/g, '')) + '</td><td>' + esc(r.min) + ' min</td>'
        + '<td>' + fmt(parseInt(r.people, 10) || 0) + '</td><td>' + (r.clear && r.clear !== '-' && p.clearMinutes ? '~' + esc(r.clear) + ' min' : '-') + '</td></tr>';
    }).join('') + '</tbody></table></div>' : '<div class="empty">' + (p ? 'No plan: the venue has no exits defined or the zone is unknown.' : 'Choose a zone and press Preview to see where everyone would be sent.') + '</div>';
    var prop = o.proposal ? '<section class="card" style="border-color:' + cssVar('CRITICAL') + '"><div class="card-head"><div><h2>Evacuation PROPOSED for ' + esc(o.proposal.zone) + '</h2><span class="sub">' + esc(o.proposal.reason) + ' (since ' + esc(o.proposal.time) + '). The system never starts an evacuation by itself.</span></div>'
      + '<div class="actions"><button class="btn-sos" data-act="propstart">Start evacuation</button><button class="btn" data-act="propdismiss">Dismiss</button></div></div></section>' : '';
    return prop + '<div class="row"><section class="card sos-card narrow"><div><h2>' + icon(I.incidents, 18) + ' SOS - someone needs help now</h2><div class="sub">Creates a CRITICAL incident and sends the nearest free units within ' + 5 + ' minutes straight away.</div></div>'
      + '<form class="form" data-form="sos"><label>Where<select id="sArea" data-keep required>' + areaOptions('') + '</select></label><label>What is happening<input id="sText" data-keep maxlength="300" placeholder="e.g. person collapsed near gate A4"></label>'
      + '<button class="btn-white">Send SOS</button></form></section>'
      + '<section class="card wide"><div class="card-head"><div><h2>Evacuation plan</h2><span class="sub">' + (active ? 'Evacuation of <b>' + esc(active) + '</b> is in progress. Units in the zone guide people to their exit; others keep exits clear.' : 'Preview first: every public area is sent to its nearest exit outside the zone, by a route that avoids the zone.') + '</span></div>'
      + (active ? pill('HIGH', 'IN PROGRESS') : '') + '</div>'
      + (active ? '<div class="actions"><button class="btn-green" data-act="evacend">' + icon(I.check, 16) + ' End evacuation</button></div>'
        : '<form class="form" data-form="preview"><div class="inline"><label>Zone<select id="eZone" data-keep>' + zoneOptions(S.zone) + '</select></label><button type="submit" class="btn">Preview plan</button><button type="button" class="btn-sos" data-act="evacstart"' + (S.zone ? '' : ' disabled') + '>Start evacuation</button></div></form>')
      + rows + '</section></div>'
      + '<div class="row"><section class="card wide">' + signsCard() + '</section><section class="card narrow"><h2>' + icon(I.incidents, 18) + ' Alarm input</h2>'
      + '<div class="sub">For a fire or other alarm panel without a connection (panels can POST /api/alarm). A unit is sent to check at once and an evacuation of the sector is proposed.</div>'
      + '<form class="form" data-form="alarm"><div class="inline"><label>Where<select id="aArea" data-keep>' + areaOptions('') + '</select></label><label>Alarm<input id="aText" data-keep value="Fire alarm" maxlength="120"></label></div>'
      + '<button class="btn-sos">Raise alarm</button></form></section></div>'
      + '<section class="card"><h2>Broadcast to every unit</h2><form class="form" data-form="broadcast"><div class="inline"><label>Message<input id="bcText" data-keep required maxlength="300" placeholder="Sent to every device and the radio gateway"></label><button class="btn-primary">Send broadcast</button></div></form></section>';
  }

  // ---------- alerts ----------
  function vAlerts() {
    var kinds = {}, f = S.alertFilter;
    S.alerts.forEach(function (a) { kinds[a.kind] = (kinds[a.kind] || 0) + 1; });
    var list = S.alerts.filter(function (a) { return f === 'ALL' || a.kind === f; });
    return '<div class="chips"><button class="chip k-NORMAL' + (f === 'ALL' ? ' sel' : '') + '" data-act="alf" data-arg="ALL">All <b>' + S.alerts.length + '</b></button>'
      + Object.keys(kinds).sort().map(function (k) { return '<button class="chip k-' + (AKIND[k] || ['GRAY'])[0] + (f === k ? ' sel' : '') + '" data-act="alf" data-arg="' + k + '">' + k.replace('_', ' ') + ' <b>' + kinds[k] + '</b></button>'; }).join('') + '</div>'
      + '<section class="card"><div class="card-head"><h2>Newest ' + S.alerts.length + ' alerts</h2><span class="sub">Older alerts are in the reports and the database</span></div><div>' + (list.map(alertRow).join('') || '<div class="empty">No alerts of this kind.</div>') + '</div></section>';
  }

  // ---------- lost persons ----------
  function lostCategory() { var c = S.venue.categories.filter(function (c) { return (c.skill || '').toLowerCase() === 'lost-person'; })[0]; return c ? c.code : null; }
  function vLost() {
    var cat = lostCategory();
    var cards = S.lost.map(function (l) {
      var k = l.found ? 'LOW' : 'VIOLET';
      return '<section class="card unit" style="border-top:3px solid ' + cssVar(k) + '"><div class="unit-head"><span class="u-av" style="background:' + cssVar(k) + '">I' + l.id + '</span>'
        + '<span class="u-name"><b>' + esc(l.details) + '</b><span class="sub">last seen ' + esc(l.area) + ' (' + esc(l.areaName) + ') - ' + esc(l.reported) + '</span></span>'
        + pill(l.found ? 'LOW' : 'CRITICAL', l.found ? 'FOUND' : 'MISSING ' + l.minutes + ' min') + '</div>'
        + (l.found ? '<div class="task"><b>Found at ' + esc(l.foundWhere) + ' by ' + esc(l.foundBy) + '</b><span class="sub">Bring to ' + esc(l.meeting) + ', check identity with the reporter, then resolve.</span></div>'
          : '<div class="task"><span class="sub">Search area (' + l.radius + ' min walk, widens every ' + S.venue.rules.lostWiden + ' min)</span><span>' + l.search.map(function (s) { return '<span class="pill k-VIOLET" style="margin:2px">' + esc(s) + '</span>'; }).join('') + '</span></div>')
        + '<dl class="kv"><dt>Meeting point</dt><dd>' + esc(l.meeting) + '</dd><dt>On the case</dt><dd>' + esc(l.unit || 'waiting for a unit') + '</dd>' + (l.contact ? '<dt>Reporter</dt><dd>' + esc(l.contact) + (l.language ? ' (' + esc(l.language) + ')' : '') + '</dd>' : '') + '</dl>'
        + '<div class="actions">' + (l.found ? '' : '<button class="btn-green" data-act="foundopen" data-arg="' + l.id + '">Person found...</button>') + '<button class="btn" data-act="inc" data-arg="' + l.id + '">Open case</button></div></section>';
    }).join('');
    return '<div class="row"><section class="card wide"><h2>Report a lost person</h2><div class="sub">A lookout with the description goes to every unit at once, units at exits check people leaving, the search area widens every '
      + S.venue.rules.lostWiden + ' min, and the case is escalated at 15 and 30 min. Children and vulnerable adults are handled as CRITICAL.</div>'
      + (cat ? '<form class="form" data-form="lostreport"><div class="inline"><label>Last seen at<select id="lArea" data-keep>' + areaOptions('') + '</select></label><label>Reporter contact<input id="lContact" data-keep maxlength="120" placeholder="phone or where they wait"></label><label>Language<input id="lLang" data-keep maxlength="40" placeholder="optional"></label></div>'
        + '<label>Description<textarea id="lDetails" data-keep required maxlength="400" placeholder="Age, clothes, hair, language, who they were with"></textarea></label><button class="btn-primary">Send lookout</button></form>' : '<div class="empty">This venue has no lost-person category.</div>')
      + '</section><section class="card narrow"><h2>Found someone?</h2><div class="sub">For a person found alone. They are recorded as found, a unit looks after them, and open cases are listed to compare.</div>'
      + '<form class="form" data-form="foundperson"><label>Where<select id="fpArea" data-keep>' + areaOptions('') + '</select></label><label>Description<textarea id="fpDetails" data-keep required maxlength="380" placeholder="Age, clothes, what they say"></textarea></label>'
      + '<button class="btn">Record found person</button></form></section></div>'
      + (cards ? '<div class="units lostgrid">' + cards + '</div>' : '<section class="card"><div class="empty">No open lost-person cases.</div></section>');
  }
  function foundDialog(id) {
    var l = S.lost.filter(function (x) { return x.id === id; })[0];
    modal('blue', 'I' + id + ' found', l ? l.details : '', '<form class="form" data-form="found" data-arg="' + id + '"><label>Found at<select id="fdArea">' + areaOptions(l ? l.area : '') + '</select></label>'
      + '<div class="sub">The lookout stops for every unit and the person is brought to ' + esc(l ? l.meeting : 'the meeting point') + '.</div>'
      + '<div class="dialog-foot"><button type="button" class="btn" data-act="close">Cancel</button><button class="btn-green">Mark found</button></div></form>');
  }
  // true while the screen is in Arabic (see i18n.js); long generated sentences are written in Arabic directly
  function arabic() { return !!(window.cwLang && window.cwLang.get() === 'ar'); }
  function rulesCardAr(r) {
    var on = function (b) { return b ? 'مفعّل' : 'متوقف'; };
    var cc = S.ov.countCheck;
    return '<section class="card"><div class="card-head"><div><h2>Automatic rules</h2><span class="sub">They act while automatic dispatch is ON (sensor checks, shifts and handover reports always run).</span></div>'
      + '<button class="btn" data-act="handover">' + icon(I.review, 16) + ' Handover report now</button></div><dl class="kv">'
      + '<dt>Sensor checks</dt><dd>تُعد البيانات قديمة بعد ' + r.stale + ' دقيقة بلا قراءة؛ ويُشك في العدد إذا قفز أكثر من ' + r.jump + '% من السعة خلال دقيقة</dd>'
      + '<dt>Follow-through</dt><dd>تذكير بعد ' + r.onscene + ' دقيقة من التأكيد دون الوصول، ودعم بعد ' + 2 * r.onscene + ' دقيقة؛ وحدة بديلة بعد ' + r.maxwait + ' دقيقة انتظار؛ تُغلق بلاغات الحشد التلقائية بعد ' + r.autoclose + ' دقيقة دون المرتفع</dd>'
      + '<dt>Count check</dt><dd>' + (cc ? 'المناطق ' + fmt(cc.counted) + '، البوابات ' + fmt(cc.expected) + ' (الفرق ' + fmt(cc.difference) + ')' + (cc.ok ? '' : ' - <b>لا تتطابق</b>')
        + ' <button class="btn sm" data-act="countreset">Reset after a recount</button>' : 'غير مستخدمة (لا يرسل أي عداد بوابات دخولاً أو خروجاً)') + '</dd>'
      + '<dt>Reinforcement</dt><dd>' + (r.reinforceCritical ? 'تحصل المنطقة المزدحمة على ' + r.reinforceHigh + ' وحدات عند المرتفع و' + r.reinforceCritical + ' عند الحرج ('
        + esc((r.reinforceSkills.length ? r.reinforceSkills : ['crowd-control', 'security']).join('، ')) + ' أولاً؛ عند المرتفع فقط الوحدات ضمن ' + (r.reinforceMax ? r.reinforceMax + ' دقيقة مشي' : 'أي مسافة') + '، وعند الحرج من أي مسافة)؛ وتعود إلى مواقعها بعد ' + r.reinforceHold + ' دقيقة من الهدوء' : 'متوقف') + '</dd>'
      + '<dt>Crowd flow</dt><dd>إيقاف التدفق ' + on(r.holdflow) + '؛ إرسال الوحدات مسبقاً ' + (r.preposition ? 'للمناطق التي ستصبح حرجة خلال ' + r.preposition + ' دقيقة' : 'متوقف') + '؛ تغطية القطاعات ' + on(r.cover) + '</dd>'
      + '<dt>Evacuation</dt><dd>يُقترح بعد ' + r.propose + ' دقيقة من الحرج مع الارتفاع، أو عند إنذار؛ ولا يبدؤه إلا ضابط</dd>'
      + '<dt>Lost persons</dt><dd>نطاق البحث ' + r.lostRadius + ' دقيقة مشي، ويتسع كل ' + r.lostWiden + ' دقيقة؛ نقطة التجمع ' + esc(r.meeting || 'مكان البلاغ') + '</dd>'
      + '<dt>Public messages</dt><dd>المسودات ' + on(r.signs) + ' (تتطلب اعتماداً)</dd>'
      + '<dt>Scheduled peaks</dt><dd>' + (r.events.length ? r.events.map(function (e) { return esc(e.time + ' ' + e.name + ' (' + e.zone + '، ' + e.minutes + ' دقيقة)'); }).join('؛ ') : 'لا يوجد') + '</dd>'
      + '<dt>Shift changes</dt><dd>' + esc(r.shifts.join('، ') || 'لا يوجد') + ' - يُكتب تقرير تسليم عند كل تبديل</dd></dl></section>';
  }
  function rulesCard(r) {
    if (!r) return '';
    if (arabic()) return rulesCardAr(r);
    var on = function (b) { return b ? 'on' : 'off'; };
    return '<section class="card"><div class="card-head"><div><h2>Automatic rules</h2><span class="sub">They act while automatic dispatch is ON (sensor checks, shifts and handover reports always run).</span></div>'
      + '<button class="btn" data-act="handover">' + icon(I.review, 16) + ' Handover report now</button></div><dl class="kv">'
      + '<dt>Sensor checks</dt><dd>stale after ' + r.stale + ' min without data; suspect if a count jumps more than ' + r.jump + '% of capacity within a minute</dd>'
      + '<dt>Follow-through</dt><dd>reminder ' + r.onscene + ' min after acknowledging without arriving, backup at ' + 2 * r.onscene + ' min; stand-in after ' + r.maxwait + ' min waiting; automatic crowd incidents close after ' + r.autoclose + ' min below HIGH</dd>'
      + '<dt>Count check</dt><dd>' + (S.ov.countCheck ? 'areas ' + fmt(S.ov.countCheck.counted) + ', gates ' + fmt(S.ov.countCheck.expected) + ' (difference ' + fmt(S.ov.countCheck.difference) + ')'
        + (S.ov.countCheck.ok ? '' : ' - <b>do not add up</b>') + ' <button class="btn sm" data-act="countreset">Reset after a recount</button>' : 'not in use (no gate counter sends people in or out of the venue)') + '</dd>'
      + '<dt>Reinforcement</dt><dd>' + (r.reinforceCritical ? 'a crowded area gets ' + r.reinforceHigh + ' units at HIGH and ' + r.reinforceCritical + ' at CRITICAL ('
        + esc((r.reinforceSkills.length ? r.reinforceSkills : ['crowd-control', 'security']).join(', ')) + ' first; at HIGH only units within ' + (r.reinforceMax ? r.reinforceMax + ' min walk' : 'any distance') + ', at CRITICAL any distance); they return to their posts ' + r.reinforceHold + ' min after it calms' : 'off') + '</dd>'
      + '<dt>Crowd flow</dt><dd>hold flow ' + on(r.holdflow) + '; units sent ahead ' + (r.preposition ? 'for areas CRITICAL within ' + r.preposition + ' min' : 'off') + '; sector cover ' + on(r.cover) + '</dd>'
      + '<dt>Evacuation</dt><dd>proposed after ' + r.propose + ' min CRITICAL and rising, or on an alarm; only an officer starts it</dd>'
      + '<dt>Lost persons</dt><dd>search ' + r.lostRadius + ' min walk, widening every ' + r.lostWiden + ' min; meeting point ' + esc(r.meeting || 'where reported') + '</dd>'
      + '<dt>Public messages</dt><dd>drafts ' + on(r.signs) + ' (approval needed)</dd>'
      + '<dt>Scheduled peaks</dt><dd>' + (r.events.length ? r.events.map(function (e) { return esc(e.time + ' ' + e.name + ' (' + e.zone + ', ' + e.minutes + ' min)'); }).join('; ') : 'none') + '</dd>'
      + '<dt>Shift changes</dt><dd>' + esc(r.shifts.join(', ') || 'none') + ' - a handover report is written at each</dd></dl></section>';
  }
  function handoverDialog() {
    var opt = { headers: auth() };
    fetch('/api/handover', opt).then(function (r) { return r.text(); }).then(function (t) {
      modal('blue', 'Handover report', 'Current situation for the officer coming on watch',
        '<pre class="mono" style="white-space:pre-wrap;max-height:60vh;overflow:auto;margin:0">' + esc(t) + '</pre><div class="dialog-foot"><button class="btn" data-act="close">Close</button>'
        + '<a class="btn-primary" download="handover.txt" href="data:text/plain;charset=utf-8,' + encodeURIComponent(t) + '">Download</a></div>');
      $('modal').querySelector('.dialog').style.maxWidth = '760px';
    }).catch(fail);
  }

  // ---------- settings ----------
  function vSettings() {
    var v = S.venue, o = S.ov;
    return '<div class="row"><section class="card narrow"><h2>Supervision</h2><dl class="kv"><dt>On watch</dt><dd>' + esc(o.officer || 'nobody') + '</dd><dt>Status</dt><dd>' + esc(o.officerStatus) + '</dd><dt>Check-in every</dt><dd>' + v.supervisionMinutes + ' min</dd></dl>'
      + '<button class="btn-primary" data-act="checkin">' + icon(I.check, 16) + ' Check in as ' + esc(S.officer) + '</button>'
      + '<label class="switch"><input type="checkbox" id="autoSw" data-act="auto"' + (o.automation ? ' checked' : '') + '> Automatic dispatch ' + (o.automation ? 'ON' : 'OFF') + '</label>'
      + '<div class="sub">' + esc(o.autoRule ? 'Rule: send ' + o.autoRule : 'This venue has no automatic dispatch rule.') + ' Switching it is logged with your name.</div></section>'
      + '<section class="card wide"><h2>' + esc(v.name) + ' <span class="sub">' + esc(v.code) + '</span></h2><dl class="kv"><dt>Crowd levels</dt><dd>MODERATE from ' + v.thresholds[0] + '%, HIGH from ' + v.thresholds[1] + '%, CRITICAL from ' + v.thresholds[2] + '%</dd>'
      + '<dt>Acknowledge within</dt><dd>' + v.ackMinutes + ' min, then the task is sent again</dd><dt>Archive after</dt><dd>' + v.archiveMinutes + ' min resolved</dd>'
      + '<dt>Radio gateway</dt><dd>' + (v.gateway ? 'configured' : 'not configured') + '</dd><dt>Exits</dt><dd>' + esc(v.exits.join(', ') || 'none') + '</dd><dt>Sectors</dt><dd>' + esc(v.sectors.join(', ')) + '</dd></dl>'
      + '<div class="sub">These settings come from the venue file. Edit it and restart to change them.</div></section></div>'
      + rulesCard(v.rules)
      + '<section class="card"><h2>Incident categories</h2><div class="tbl-wrap"><table><thead><tr><th>Code</th><th>Label</th><th>Priority</th></tr></thead><tbody>'
      + v.categories.map(function (c) { return '<tr><td class="mono">' + esc(c.code) + '</td><td>' + esc(c.label) + '</td><td>' + pill(PRI[c.priority], c.priority) + '</td></tr>'; }).join('') + '</tbody></table></div></section>'
      + accessCard()
      + '<section class="card"><h2>This browser</h2><div class="actions"><button class="btn" data-act="theme">Switch light or dark</button><button class="btn" data-act="logout">Sign out and forget the key</button><a class="btn" href="/classic">Plain dashboard</a></div></section>';
  }

  // ---------- users, scout device codes and audit log ----------
  function accessCard() {
    if (!S.logins && S.token === '') {
      if (!can('OFFICER')) return '';
    }
    var units = S.units.map(function (u) { return '<option>' + esc(u.id) + '</option>'; }).join('');
    var h = '<section class="card"><h2>Users and access</h2>'
      + (S.token ? '<div class="sub">Signed in as ' + esc(S.officer) + ' <span class="role-tag">' + esc(S.role) + '</span></div>' : '')
      + '<div class="sub">Every change made here or on the network is recorded with the name of the person who made it.</div>';
    if (can('OFFICER')) {
      h += '<h2>Scout device codes</h2><div class="sub">Each scout phone gets its own code that only works for its unit. A new code cancels the old one.</div>'
        + '<form class="form" data-form="devcode"><div class="inline"><label>Unit<select id="dcUnit" data-keep>' + units + '</select></label>'
        + '<button class="btn-primary">Issue new code</button><button type="button" class="btn" data-act="devrevoke">Cancel code</button></div></form>'
        + '<div class="actions"><button class="btn" data-act="audit">Show audit log</button></div>';
      if (S.audit) h += '<div class="tbl-wrap audit"><table><thead><tr><th>Time</th><th>User</th><th>Role</th><th>Action</th><th>Result</th><th>Details</th></tr></thead><tbody>'
        + (S.audit.length ? S.audit.map(function (e) {
          return '<tr><td class="mono">' + esc(e.time.replace('T', ' ')) + '</td><td>' + esc(e.user) + '</td><td>' + esc(e.role) + '</td><td class="mono">' + esc(e.action) + '</td><td class="' + (e.ok ? '' : 'bad') + '">' + (e.ok ? 'OK' : 'REFUSED') + '</td><td>' + esc(e.summary) + '</td></tr>';
        }).join('') : '<tr><td colspan="6">none yet</td></tr>') + '</tbody></table></div>';
    }
    if (can('ADMIN') && S.logins !== null) {
      h += '<h2>Users</h2>' + (S.users ? '<div class="tbl-wrap"><table><thead><tr><th>User name</th><th>Role</th><th>Can sign in</th></tr></thead><tbody>' + S.users.map(function (u) {
          return '<tr><td class="mono">' + esc(u.name) + '</td><td>' + esc(u.role) + '</td><td>' + (u.active ? 'OK' : '-') + '</td></tr>';
        }).join('') + '</tbody></table></div>' : '')
        + '<form class="form" data-form="user"><div class="inline"><label>User name<input id="uName" data-keep required autocapitalize="none"></label>'
        + '<label>Role<select id="uRole" data-keep>' + ['VIEWER', 'OPERATOR', 'OFFICER', 'ADMIN'].map(function (r) { return '<option value="' + r + '">' + r + '</option>'; }).join('') + '</select></label></div>'
        + '<div class="inline"><label>Password<input id="uPass" type="password" autocomplete="new-password" placeholder="empty keeps the current password"></label>'
        + '<label class="switch"><input type="checkbox" id="uActive" data-keep checked> Can sign in</label></div><button class="btn-primary">Save user</button></form>';
    }
    return h + '</section>';
  }
  function loadUsers() { if (!can('ADMIN')) return Promise.resolve(); return api('/api/users').then(function (u) { S.users = Array.isArray(u) ? u : null; }).catch(function () { S.users = null; }); }

  // ---------- dialogs, toast ----------
  function modal(color, title, sub, body) {
    var m = $('modal');
    m.innerHTML = '<div class="dialog" role="dialog" aria-modal="true" aria-label="' + esc(title) + '"><div class="dialog-head ' + color + '"><h2>' + esc(title) + '</h2><span class="sub">' + esc(sub) + '</span></div><div class="dialog-body">' + body + '</div></div>';
    m.hidden = false;
  }
  function closeModal() { $('modal').hidden = true; $('modal').innerHTML = ''; }
  function reportDialog() {
    var v = S.venue;
    modal('blue', 'Report incident', 'It is sent to the nearest free unit with the right skills',
      '<form class="form" data-form="report"><label>What<select id="rCat" required>' + v.categories.map(function (c) { return '<option value="' + esc(c.code) + '">' + esc(c.label + ' (' + c.priority + ')') + '</option>'; }).join('') + '</select></label>'
      + '<label>Where<select id="rArea" required>' + areaOptions('') + '</select></label><label>Details<textarea id="rDetails" maxlength="300" placeholder="What you see, who is involved"></textarea></label>'
      + '<div class="inline"><label>Contact<input id="rContact" placeholder="optional"></label><label>Language<input id="rLang" placeholder="optional"></label></div>'
      + '<div class="dialog-foot"><button type="button" class="btn" data-act="close">Cancel</button><button class="btn-primary">Report</button></div></form>');
  }
  function sosDialog() {
    modal('red', 'SOS', 'A CRITICAL incident: the nearest free units are sent at once',
      '<form class="form" data-form="sosd"><label>Where<select id="dArea" required>' + areaOptions('') + '</select></label><label>What is happening<input id="dText" maxlength="300" placeholder="optional"></label>'
      + '<div class="dialog-foot"><button type="button" class="btn" data-act="close">Cancel</button><button class="btn-sos">Send SOS</button></div></form>');
  }
  var toastTimer;
  function toast(text, kind) {
    var t = $('toast');
    t.textContent = text; t.className = 'toast ' + (kind || ''); t.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.hidden = true; }, 4200);
  }

  // ---------- events ----------
  function go(arg) {
    var p = String(arg).split(':');
    S.view = p[0]; store.set('view', S.view);
    if (p[0] === 'incidents') S.incFilter = p[1] || 'OPEN';
    if (p[0] === 'alerts' && S.alerts.length) { S.seenAlert = S.alerts[0].time; store.set('seenAlert', S.seenAlert); }
    if (p[0] === 'emergency' && (S.zone || S.ov.evacuation)) loadPreview().then(render);
    render(); window.scrollTo(0, 0);
  }
  document.addEventListener('click', function (e) {
    var el = e.target.closest('[data-act]');
    if (!el) { if (e.target === $('modal')) closeModal(); return; }
    var a = el.getAttribute('data-act'), arg = el.getAttribute('data-arg');
    switch (a) {
      case 'go': go(arg); break;
      case 'area': areaDialog(arg); break;
      case 'adj': var c = $('fCount'); c.value = Math.max(0, (parseInt(c.value, 10) || 0) + parseInt(arg, 10)); break;
      case 'close': closeModal(); break;
      case 'inc': select(parseInt(arg, 10)); break;
      case 'unsel': S.sel = null; S.detail = null; render(); break;
      case 'incf': S.incFilter = arg; render(); break;
      case 'alf': S.alertFilter = arg; render(); break;
      case 'report': reportDialog(); break;
      case 'sos': sosDialog(); break;
      case 'checkin': act('/api/checkin', {}); break;
      case 'auto': act('/api/automation', { on: el.checked ? '1' : '0' }); break;
      case 'review': act('/api/review', { id: S.sel, decision: arg, note: arg === 'true' ? 'approved in the web console' : 'rejected in the web console' }); break;
      case 'onscene': act('/api/onscene', { id: S.sel }); break;
      case 'escalate': act('/api/escalate', { id: S.sel }); break;
      case 'evacstart':
        modal('red', 'Start evacuation of ' + S.zone + '?', 'Every unit is told at once and public routes avoid the zone',
          '<div class="form"><div>Units inside the zone will guide people to their exit; every other unit keeps the exits clear. This is logged with your name.</div><div class="dialog-foot"><button class="btn" data-act="close">Cancel</button><button class="btn-sos" data-act="evacgo">Start evacuation</button></div></div>');
        break;
      case 'evacgo': closeModal(); act('/api/evacuate', { zone: S.zone }, loadPreview); break;
      case 'evacend': act('/api/evacuate/end', {}, function () { S.preview = null; }); break;
      case 'theme': theme(); break;
      case 'logout': logout(); break;
      case 'propstart': act('/api/proposal', { decision: 'start' }); break;
      case 'propdismiss': act('/api/proposal', { decision: 'dismiss' }); break;
      case 'signok': act('/api/announcement', { id: arg, decision: 'approve' }); break;
      case 'signno': act('/api/announcement', { id: arg, decision: 'reject' }); break;
      case 'foundopen': foundDialog(parseInt(arg, 10)); break;
      case 'handover': handoverDialog(); break;
      case 'countreset': if (confirm('Reset the count check? Only do this after a full recount: the total now becomes the new starting point.')) act('/api/countcheck/reset', {}); break;
      case 'plan': S.planMode = arg; store.set('planMode', arg); render(); break;
      case 'audit': api('/api/audit').then(function (r) { if (Array.isArray(r)) S.audit = r; else toast(r.message, 'bad'); render(); }).catch(fail); break;
      case 'devrevoke': act('/api/devices/revoke', { unit: $('dcUnit').value }); break;
    }
  });
  document.addEventListener('change', function (e) {
    if (e.target.id === 'eZone') { S.zone = e.target.value; S.preview = null; if (S.zone) loadPreview().then(render); else render(); }
  });
  document.addEventListener('submit', function (e) {
    var f = e.target.getAttribute('data-form');
    if (!f) return;
    e.preventDefault();
    var v = function (id) { var el = $(id); return el ? el.value.trim() : ''; };
    switch (f) {
      case 'count': act('/api/occupancy', { area: e.target.getAttribute('data-arg'), count: v('fCount'), source: 'STAFF' }, closeModal); break;
      case 'report': act('/api/incidents', { category: v('rCat'), area: v('rArea'), details: v('rDetails'), contact: v('rContact') || null, language: v('rLang') || null }, function () { closeModal(); }); break;
      case 'sosd': act('/api/sos', { area: v('dArea'), text: v('dText') || 'SOS from the control room' }, function () { closeModal(); go('incidents'); }); break;
      case 'sos': act('/api/sos', { area: v('sArea'), text: v('sText') || 'SOS from the control room' }, function () { $('sText').value = ''; }); break;
      case 'preview': S.zone = v('eZone'); loadPreview().then(render).catch(fail); break;
      case 'broadcast': act('/api/broadcast', { text: v('bcText') }, function () { $('bcText').value = ''; }); break;
      case 'resolve': act('/api/resolve', { id: S.sel, note: v('bNote') }, function () { S.sel = null; S.detail = null; }); break;
      case 'reassign': act('/api/reassign', { id: S.sel, unit: v('bUnit'), reason: v('bReason') || 'reassigned in the web console' }); break;
      case 'alarm': act('/api/alarm', { area: v('aArea'), text: v('aText') }); break;
      case 'announce': act('/api/announce', { text: v('anText') }, function () { $('anText').value = ''; }); break;
      case 'found': act('/api/found', { id: e.target.getAttribute('data-arg'), area: v('fdArea') }, closeModal); break;
      case 'foundperson': act('/api/foundperson', { area: v('fpArea'), details: v('fpDetails') }, function () { $('fpDetails').value = ''; }); break;
      case 'devcode': api('/api/devices/code', { unit: v('dcUnit') }).then(function (r) {
          if (!r.ok) { toast(r.message, 'bad'); return; }
          modal('blue', 'Scout device codes', r.unit, '<div class="form"><div>Type this code on the scout phone of ' + esc(r.unit) + '. It replaces the unit\'s earlier code.</div><div><span class="code-big">' + esc(r.code)
            + '</span></div><div class="dialog-foot"><button class="btn-primary" data-act="close">Close</button></div></div>');
        }).catch(fail); break;
      case 'user': act('/api/users', { name: v('uName'), role: v('uRole'), password: $('uPass').value || null, active: $('uActive').checked ? '1' : '0' }, function () { $('uPass').value = ''; loadUsers().then(render); }); break;
      case 'lostreport': act('/api/incidents', { category: lostCategory(), area: v('lArea'), details: v('lDetails'), contact: v('lContact') || null, language: v('lLang') || null },
        function () { $('lDetails').value = ''; }); break;
    }
  });
  document.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); $('search').focus(); $('search').select(); }
    if (e.key === 'Escape') { if (!$('modal').hidden) closeModal(); else if (S.sel != null) { S.sel = null; render(); } }
  });
  $('search').addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    var q = this.value.trim(), u = q.toUpperCase();
    if (!q) return;
    var m = /^I?(\d+)$/i.exec(q);
    if (m) { select(parseInt(m[1], 10)); return; }
    var a = S.areas.filter(function (x) { return x.id.toUpperCase() === u; })[0] || S.areas.filter(function (x) { return (x.id + ' ' + x.name).toUpperCase().indexOf(u) >= 0; })[0];
    if (a) { areaDialog(a.id); return; }
    var un = S.units.filter(function (x) { return (x.id + ' ' + x.name).toUpperCase().indexOf(u) >= 0; })[0];
    if (un) { go('units'); toast(un.id + ' - ' + un.name + ': ' + un.status + ' at ' + un.area + (un.task ? ', on I' + un.task.id : ''), 'good'); return; }
    toast('Nothing called "' + q + '".', 'bad');
  });
  $('themeBtn').onclick = theme;
  $('bellBtn').onclick = function () { go('alerts'); };
  $('officerBtn').onclick = function () { act('/api/checkin', {}); };
  $('logoutBtn').onclick = function () { logout(); };

  function theme(set) {
    var light = typeof set === 'string' ? set === 'light' : !document.body.classList.contains('light');
    document.body.classList.toggle('light', light);
    document.body.classList.toggle('dark', !light);
    $('themeLabel').textContent = light ? 'Dark' : 'Light';
    store.set('theme', light ? 'light' : 'dark');
    if (S.ov) render();
  }

  // ---------- sign in ----------
  function start() {
    $('login').hidden = true; $('app').hidden = false;
    loadUsers().then(poll);
    clearInterval(S.timer); S.timer = setInterval(function () { if (!document.hidden) poll(); }, POLL_MS);
  }
  // shows user name and password when the server has personal logins, otherwise officer name and control key
  function loginMode(user) {
    S.userMode = user;
    $('lgUserBox').hidden = !user; $('lgKeyBox').hidden = user;
    $('lgSwitch').hidden = !S.logins;
    $('lgSwitch').textContent = user ? 'Use the control key instead' : 'Use my user name and password';
    $('lgSubmit').textContent = user ? 'Sign in' : 'Sign in and check in';
    (user ? $('lgUser') : $('lgOfficer')).focus();
  }
  function logout(msg) {
    clearInterval(S.timer);
    if (S.token) fetch('/api/logout', { method: 'POST', headers: auth() }).catch(function () { });
    S.key = ''; S.token = ''; S.role = ''; S.users = null; S.audit = null;
    store.set('key', null); store.set('token', null); store.set('role', null);
    $('app').hidden = true; $('login').hidden = false;
    $('lgErr').textContent = typeof msg === 'string' ? msg : '';
    $('lgOfficer').value = S.officer;
    fetch('/api/me').then(function (r) { return r.json(); }).then(function (me) { S.logins = !!me.logins; loginMode(S.logins); }).catch(function () { loginMode(false); });
  }
  $('lgSwitch').addEventListener('click', function () { loginMode(!S.userMode); });
  $('loginForm').addEventListener('submit', function (e) {
    e.preventDefault();
    $('lgErr').textContent = '';
    if (S.userMode) {
      var body = new URLSearchParams({ user: $('lgUser').value.trim(), password: $('lgPass').value });
      fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body.toString() }).then(function (r) { return r.json(); }).then(function (r) {
        if (!r.ok) { $('lgErr').textContent = r.message; return; }
        $('lgPass').value = '';
        S.token = r.token; S.role = r.role; S.officer = r.name; S.key = '';
        store.set('token', S.token); store.set('role', S.role); store.set('officer', S.officer); store.set('key', null);
        // officers check in on signing in; viewers and operators do not supervise
        (can('OFFICER') ? api('/api/checkin', {}) : Promise.resolve({ ok: true, message: 'Signed in as ' + r.name + ' (' + r.role + ')' })).then(function (c) { start(); toast(c.message, c.ok ? 'good' : 'bad'); });
      }).catch(function (err) { $('lgErr').textContent = err.message || 'The server cannot be reached.'; });
      return;
    }
    S.officer = $('lgOfficer').value.trim(); S.key = $('lgKey').value.trim(); S.token = '';
    if (!S.officer) { $('lgErr').textContent = 'Officer name'; return; }
    api('/api/checkin', { officer: S.officer }).then(function (r) {
      if (!r.ok) { $('lgErr').textContent = r.message; return; }
      store.set('key', S.key); store.set('officer', S.officer); store.set('token', null);
      start(); toast(r.message, 'good');
    }).catch(function (err) { $('lgErr').textContent = err.message || 'The server cannot be reached.'; });
  });

  // redraw in the new language (long generated sentences are written per language)
  document.addEventListener('cwlang', function () { if (S.ov && !$('app').hidden) render(); });
  theme(store.get('theme') || 'dark');
  fetch('/api/me', { headers: auth() }).then(function (r) { return r.json(); }).then(function (me) {
    S.logins = !!me.logins;
    if (me.signedIn && (S.token || S.officer)) {
      if (S.token) { S.role = me.role; S.officer = me.name; }
      start();
    } else logout();
  }).catch(function () { logout('The server cannot be reached.'); });
})();
