package crowdwatch.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.core.Records;
import crowdwatch.core.Reports;
import crowdwatch.core.Result;
import crowdwatch.core.VenueProfile;
import crowdwatch.db.Database;
import crowdwatch.ds.AVLTree;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.*;
import static crowdwatch.net.ApiServer.str;

/**
 * The server's newer endpoints, kept apart from {@link ApiServer}, which checks the caller's
 * rights before handing a request here:
 * <ul>
 * <li>reports over a period (JSON, CSV for Excel, printable HTML) and replay of the minute frames;</li>
 * <li>messages and photos between scout phones and the control room; a phone's position, duty and battery;</li>
 * <li>weather for heat stress; the other sites of a head office; Prometheus metrics;</li>
 * <li>data-protection requests (find, export, erase a person's data);</li>
 * <li>password change and reset; the venue editor's file;</li>
 * <li>the public help page for visitors, with QR codes to print.</li>
 * </ul>
 */
final class ApiExtras {
    /** Largest photo accepted from a phone (bytes after decoding). */
    static final int MAX_PHOTO = 2 * 1024 * 1024;
    /** Body limit for a message with a photo. */
    static final int MAX_MESSAGE_BODY = 3 * 1024 * 1024;
    /** Reports a visitor's address may send in {@link #PUBLIC_WINDOW_MINUTES} minutes. */
    static final int PUBLIC_REPORTS = 3, PUBLIC_WINDOW_MINUTES = 10;

    /** A reply: status, content type, body. */
    static final class Reply {
        final int code;
        final String type;
        final byte[] body;
        final String file;
        Reply(int code, String type, byte[] body, String file) { this.code = code; this.type = type; this.body = body; this.file = file; }
        static Reply json(int code, String json) { return new Reply(code, "application/json", json.getBytes(StandardCharsets.UTF_8), null); }
        static Reply ok(Result r) { return json(r.ok ? 200 : 422, "{\"ok\":" + r.ok + ",\"message\":" + str(r.message) + "}"); }
        static Reply text(String type, String body, String file) { return new Reply(200, type, body.getBytes(StandardCharsets.UTF_8), file); }
    }

    private final CrowdMonitor m;
    private final ApiServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> visitors = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String sitesCache;
    private volatile long sitesAt;
    final java.util.concurrent.atomic.AtomicLong requests = new java.util.concurrent.atomic.AtomicLong();

    ApiExtras(CrowdMonitor m, ApiServer server) { this.m = m; this.server = server; }

    static boolean handles(String endpoint) {
        switch (endpoint) {
            case "GET /api/report", "GET /api/replay", "POST /api/message", "GET /api/messages", "GET /api/photo", "POST /api/position", "POST /api/duty",
                 "POST /api/weather", "GET /api/sites", "GET /api/privacy", "GET /api/privacy/export", "POST /api/privacy/erase", "POST /api/password",
                 "POST /api/password/reset", "GET /api/venuefile", "POST /api/venuefile", "GET /help", "POST /api/public/report", "GET /api/qr", "GET /metrics":
                return true;
            default:
                return false;
        }
    }

    Reply handle(String endpoint, AVLTree<String, String> q, Accounts.Who who, String host, boolean https, String address) throws IOException {
        switch (endpoint) {
            case "GET /api/report": return report(q);
            case "GET /api/replay": return replay(q);
            case "POST /api/message": return message(q, who);
            case "GET /api/messages": return messages(q, who);
            case "GET /api/photo": return photo(q, who);
            case "POST /api/position": synchronized (m) { return Reply.ok(m.reportPosition(q.search("unit"), q.search("area"))); }
            case "POST /api/duty": synchronized (m) { return Reply.ok(m.duty(q.search("unit"), !"0".equals(q.search("on")))); }
            case "POST /api/weather": {
                double t = Double.parseDouble(q.search("temp")), h = Double.parseDouble(q.search("humidity"));
                return Reply.ok(m.weather(t, h));
            }
            case "GET /api/sites": return Reply.json(200, sites());
            case "GET /api/privacy": return privacy(q, false);
            case "GET /api/privacy/export": return privacy(q, true);
            case "POST /api/privacy/erase": return erase(q, who);
            case "POST /api/password": {
                Accounts acc = server.accounts();
                if (acc == null || who.role == null || acc.user(who.name) == null) return Reply.json(422, "{\"ok\":false,\"message\":\"only a personal user can change a password\"}");
                String r = acc.changePassword(who.name, q.search("current"), q.search("password"), m.now());
                return Reply.ok(r == null ? Result.ok("Password changed.") : Result.fail(r));
            }
            case "POST /api/password/reset": {
                Accounts acc = server.accounts();
                if (acc == null) return Reply.json(404, "{\"ok\":false,\"message\":\"personal logins are not switched on\"}");
                String r = acc.resetPassword(q.search("user"), q.search("code"), q.search("password"), m.now());
                return Reply.ok(r == null ? Result.ok("New password set: sign in with it.") : Result.fail(r));
            }
            case "GET /api/venuefile": synchronized (m) { return Reply.text("text/plain; charset=utf-8", m.venue() == null ? "" : m.venue().text, null); }
            case "POST /api/venuefile": return saveVenue(q);
            case "GET /help": return Reply.text("text/html; charset=utf-8", HelpPage.page(publicAreas(), q.search("area"), m.venueName()), null);
            case "POST /api/public/report": return publicReport(q, address);
            case "GET /api/qr": return qrPage(host, https);
            case "GET /metrics": return Reply.text("text/plain; version=0.0.4; charset=utf-8", metrics(), null);
            default: return Reply.json(404, "{\"ok\":false}");
        }
    }

    // ------------------------------------------------------------------ reports and replay

    private Reply report(AVLTree<String, String> q) throws IOException {
        LocalDate to = q.search("to") == null ? m.now().toLocalDate() : LocalDate.parse(q.search("to"));
        LocalDate from = q.search("from") == null ? to.minusDays(6) : LocalDate.parse(q.search("from"));
        if (from.isAfter(to) || from.isBefore(to.minusDays(366))) return Reply.json(400, "{\"ok\":false,\"message\":\"choose a period of at most a year, from before to\"}");
        LocalDateTime start = from.atStartOfDay(), end = to.plusDays(1).atStartOfDay().minusSeconds(1);
        LinkedList<Records.Frame> frames = frames(start, end);
        LinkedList<Object[]> incidents = new LinkedList<>();
        Database db = server.database();
        if (db != null) try { for (Object[] r : db.incidentTimes(start, end)) incidents.addLast(r); } catch (java.sql.SQLException e) { /* memory only */ }
        LinkedList<Alert> alerts = new LinkedList<>();
        Reports.Report r;
        synchronized (m) {
            for (Incident i : m.incidentTree().inOrder()) incidents.addLast(Reports.times(i));
            for (Alert a : m.alerts()) alerts.addLast(a);
            r = Reports.build(m, from, to, frames, incidents, alerts);
        }
        String format = q.search("format") == null ? "json" : q.search("format");
        String name = "crowdwatch-report-" + from + "-to-" + to;
        switch (format) {
            case "csv": return Reply.text("text/csv; charset=utf-8", "﻿" + Reports.csv(r), name + ".csv");
            case "html": return Reply.text("text/html; charset=utf-8", Reports.html(r), null);
            default: return Reply.json(200, Reports.json(r));
        }
    }

    // the frames of a period: from memory where it has them, else from the database
    private LinkedList<Records.Frame> frames(LocalDateTime from, LocalDateTime to) {
        LinkedList<Records.Frame> out = new LinkedList<>();
        LocalDateTime firstInMemory;
        synchronized (m) { firstInMemory = m.records.firstFrame(); }
        Database db = server.database();
        if (db != null && (firstInMemory == null || from.isBefore(firstInMemory))) {
            try {
                LocalDateTime dbTo = firstInMemory == null ? to : (firstInMemory.isBefore(to) ? firstInMemory.minusSeconds(1) : to);
                for (Records.Frame f : db.frames(from, dbTo)) out.addLast(f);
            } catch (java.sql.SQLException e) { /* only what is in memory */ }
        }
        synchronized (m) { for (Records.Frame f : m.records.frames(from, to)) out.addLast(f); }
        return out;
    }

    private Reply replay(AVLTree<String, String> q) {
        LocalDateTime to = q.search("to") == null ? m.now() : LocalDateTime.parse(q.search("to"));
        LocalDateTime from = q.search("from") == null ? to.minusHours(1) : LocalDateTime.parse(q.search("from"));
        if (from.isAfter(to) || Duration.between(from, to).toHours() > 24) return Reply.json(400, "{\"ok\":false,\"message\":\"replay at most 24 hours at a time\"}");
        LinkedList<Records.Frame> frames = frames(from, to);
        StringBuilder sb = new StringBuilder("{\"from\":").append(str(from.withNano(0).toString())).append(",\"to\":").append(str(to.withNano(0).toString())).append(",\"frames\":[");
        int k = 0;
        for (Records.Frame f : frames) {
            sb.append(k++ > 0 ? "," : "").append("{\"t\":").append(str(f.minute.toString())).append(",\"areas\":{");
            for (int i = 0; i < f.ids.length; i++) sb.append(i > 0 ? "," : "").append(str(f.ids[i])).append(":[").append(f.occupancy[i]).append(',').append(str(f.level[i])).append(']');
            sb.append("}}");
        }
        sb.append("],\"alerts\":[");
        k = 0;
        synchronized (m) {
            for (Alert a : m.alerts()) {
                if (a.time.isBefore(from) || a.time.isAfter(to)) continue;
                sb.append(k++ > 0 ? "," : "").append("{\"time\":").append(str(a.time.withNano(0).toString())).append(",\"kind\":").append(str(a.kind.name())).append(",\"text\":").append(str(a.text)).append('}');
            }
        }
        return Reply.json(200, sb.append("]}").toString());
    }

    // ------------------------------------------------------------------ messages and photos

    private Path photoFolder() {
        Path base = server.dataFolder();
        String code;
        synchronized (m) { code = m.venue() == null ? "venue" : Database.safeName(m.venue().code); }
        return base == null ? null : base.resolve("photos").resolve(code);
    }

    private Reply message(AVLTree<String, String> q, Accounts.Who who) throws IOException {
        String photoName = null;
        String data = q.search("photo");
        if (data != null && !data.isEmpty()) {
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("^data:image/(jpeg|png|webp);base64,(.+)$", java.util.regex.Pattern.DOTALL).matcher(data);
            if (!mt.find()) return Reply.json(400, "{\"ok\":false,\"message\":\"the photo must be a JPEG, PNG or WebP image\"}");
            byte[] img = java.util.Base64.getMimeDecoder().decode(mt.group(2));
            if (img.length > MAX_PHOTO) return Reply.json(413, "{\"ok\":false,\"message\":\"photo larger than 2 MB\"}");
            Path folder = photoFolder();
            if (folder == null) return Reply.json(422, "{\"ok\":false,\"message\":\"photos are not stored on this server\"}");
            Files.createDirectories(folder);
            cleanPhotos(folder);
            byte[] rnd = new byte[9];
            new java.security.SecureRandom().nextBytes(rnd);
            photoName = "photo-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(rnd) + "." + (mt.group(1).equals("jpeg") ? "jpg" : mt.group(1));
            Files.write(folder.resolve(photoName), img);
            Accounts.ownerOnlyFile(folder.resolve(photoName));
        }
        int incident = q.search("incident") == null || q.search("incident").isBlank() ? 0 : ApiServer.num(q.search("incident"));
        Result r;
        synchronized (m) {
            r = who != null && who.role == null
                ? m.messageFromUnit(q.search("unit"), q.search("text"), incident, photoName)
                : m.messageUnit(q.search("unit"), q.search("text"), who == null ? "control" : who.name, incident);
        }
        return Reply.ok(r);
    }

    // photos are personal data: they are deleted after the venue's retention period
    private void cleanPhotos(Path folder) {
        int days;
        synchronized (m) { days = m.venue() == null ? 30 : m.venue().retentionDays; }
        if (days <= 0) return;
        long limit = System.currentTimeMillis() - days * 86_400_000L;
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(folder, "photo-*")) {
            for (Path p : ds) if (Files.getLastModifiedTime(p).toMillis() < limit) Files.deleteIfExists(p);
        } catch (IOException ignored) { }
    }

    private Reply messages(AVLTree<String, String> q, Accounts.Who who) {
        String unit = who != null && who.unit != null ? who.unit : q.search("unit");
        int incident = q.search("incident") == null ? 0 : ApiServer.num(q.search("incident"));
        StringBuilder sb = new StringBuilder("[");
        synchronized (m) {
            int k = 0;
            for (Records.Message x : m.records.messages(unit == null || unit.isBlank() ? null : unit, incident)) {
                sb.append(k++ > 0 ? "," : "").append("{\"id\":").append(x.id).append(",\"time\":").append(str(x.time.withNano(0).toString())).append(",\"unit\":").append(str(x.unit))
                  .append(",\"fromUnit\":").append(x.fromUnit).append(",\"by\":").append(str(x.by)).append(",\"incident\":").append(x.incident).append(",\"text\":").append(str(x.text))
                  .append(",\"photo\":").append(x.photo != null).append('}');
            }
        }
        return Reply.json(200, sb.append(']').toString());
    }

    private Reply photo(AVLTree<String, String> q, Accounts.Who who) throws IOException {
        Records.Message x;
        synchronized (m) { x = m.records.message(ApiServer.num(q.search("id"))); }
        if (x == null || x.photo == null) return Reply.json(404, "{\"ok\":false,\"message\":\"no photo\"}");
        if (who != null && who.unit != null && !who.unit.equalsIgnoreCase(x.unit)) return Reply.json(403, "{\"ok\":false,\"message\":\"this device can only see its own unit's photos\"}");
        Path folder = photoFolder();
        Path f = folder == null ? null : folder.resolve(x.photo).normalize();
        if (f == null || !f.startsWith(folder) || !Files.exists(f)) return Reply.json(404, "{\"ok\":false,\"message\":\"the photo was deleted (retention period)\"}");
        String type = x.photo.endsWith(".png") ? "image/png" : x.photo.endsWith(".webp") ? "image/webp" : "image/jpeg";
        return new Reply(200, type, Files.readAllBytes(f), null);
    }

    // ------------------------------------------------------------------ sites of a head office

    /**
     * The other CrowdWatch servers of a head office, from crowdwatch-data/sites.txt
     * (one per line: name | address | control key). Each site's overview is fetched with its key,
     * at most every 5 seconds.
     */
    private String sites() {
        long now = System.currentTimeMillis();
        String cached = sitesCache;
        if (cached != null && now - sitesAt < 5000) return cached;
        Path file = server.dataFolder() == null ? null : server.dataFolder().resolve("sites.txt");
        StringBuilder sb = new StringBuilder("[");
        if (file != null && Files.exists(file)) {
            try {
                int k = 0;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    String[] f = t.split("\\s*\\|\\s*");
                    if (f.length < 2) continue;
                    String overview = null, problem = null;
                    try {
                        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(f[1].replaceAll("/+$", "") + "/api/overview")).timeout(Duration.ofSeconds(4));
                        if (f.length > 2 && !f[2].isEmpty()) b.header("X-Api-Key", f[2]);
                        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                        if (r.statusCode() == 200 && r.body().startsWith("{")) overview = r.body(); else problem = "HTTP " + r.statusCode();
                    } catch (IOException | IllegalArgumentException e) {
                        problem = "not answering";
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        problem = "interrupted";
                    }
                    sb.append(k++ > 0 ? "," : "").append("{\"name\":").append(str(f[0])).append(",\"url\":").append(str(f[1])).append(",\"ok\":").append(overview != null)
                      .append(",\"problem\":").append(str(problem)).append(",\"overview\":").append(overview == null ? "null" : overview).append('}');
                }
            } catch (IOException e) { /* no sites */ }
        }
        String json = sb.append(']').toString();
        sitesCache = json;
        sitesAt = now;
        return json;
    }

    // ------------------------------------------------------------------ data-protection requests

    private Reply privacy(AVLTree<String, String> q, boolean export) {
        String find = q.search("find") == null ? "" : q.search("find").trim();
        if (find.length() < 3) return Reply.json(400, "{\"ok\":false,\"message\":\"search for at least 3 characters: a name, phone number or e-mail\"}");
        String needle = find.toLowerCase(java.util.Locale.ROOT);
        StringBuilder sb = new StringBuilder("{\"find\":").append(str(find)).append(",\"incidents\":[");
        int k = 0;
        synchronized (m) {
            for (Incident i : m.incidentTree().inOrder()) {
                if (!contains(i.details, needle) && !contains(i.contact, needle)) continue;
                sb.append(k++ > 0 ? "," : "").append(hit(i.id, i.category.code, i.area.id, i.reported, i.details, i.contact, "current"));
            }
        }
        Database db = server.database();
        if (db != null) {
            try {
                for (Object[] r : db.searchArchive(find))
                    sb.append(k++ > 0 ? "," : "").append(hit((Integer) r[0], (String) r[1], (String) r[2], (LocalDateTime) r[3], (String) r[4], (String) r[5], "archive"));
            } catch (java.sql.SQLException e) { /* current data only */ }
        }
        sb.append("],\"messages\":[");
        k = 0;
        synchronized (m) {
            for (Records.Message x : m.records.allMessages()) {
                if (!contains(x.text, needle)) continue;
                sb.append(k++ > 0 ? "," : "").append("{\"id\":").append(x.id).append(",\"time\":").append(str(x.time.withNano(0).toString())).append(",\"unit\":").append(str(x.unit))
                  .append(",\"incident\":").append(x.incident).append(",\"text\":").append(str(x.text)).append(",\"photo\":").append(x.photo != null).append('}');
            }
        }
        String json = sb.append("]}").toString();
        return export ? Reply.text("application/json; charset=utf-8", json, "crowdwatch-personal-data.json") : Reply.json(200, json);
    }

    private static boolean contains(String s, String needle) { return s != null && s.toLowerCase(java.util.Locale.ROOT).contains(needle); }

    private static String hit(int id, String category, String area, LocalDateTime reported, String details, String contact, String where) {
        return "{\"id\":" + id + ",\"category\":" + str(category) + ",\"area\":" + str(area) + ",\"reported\":" + str(reported == null ? null : reported.withNano(0).toString())
            + ",\"details\":" + str(details) + ",\"contact\":" + str(contact) + ",\"where\":" + str(where) + "}";
    }

    private Reply erase(AVLTree<String, String> q, Accounts.Who who) throws IOException {
        String ids = q.search("ids") == null ? "" : q.search("ids");
        int done = 0;
        Database db = server.database();
        Path folder = photoFolder();
        for (String part : ids.split("[,\\s]+")) {
            if (part.isBlank()) continue;
            int id = ApiServer.num(part);
            boolean any;
            LinkedList<String> photos = new LinkedList<>();
            synchronized (m) {
                for (Records.Message x : m.records.messages(null, id)) if (x.photo != null) photos.addLast(x.photo);
                any = m.erasePersonal(id, who == null ? "admin" : who.name);
            }
            if (db != null) try { any |= db.eraseArchived(id); } catch (java.sql.SQLException e) { /* memory erased; archive at the next try */ }
            if (folder != null) for (String p : photos) Files.deleteIfExists(folder.resolve(p));
            if (any) done++;
        }
        return Reply.ok(done > 0 ? Result.ok("Personal data erased from " + done + " incident(s); the rest of each record stays for statistics.") : Result.fail("No incident found with those numbers."));
    }

    // ------------------------------------------------------------------ venue editor

    private Reply saveVenue(AVLTree<String, String> q) throws IOException {
        String text = q.search("text");
        if (text == null || text.isBlank()) return Reply.json(400, "{\"ok\":false,\"message\":\"no venue text\"}");
        VenueProfile p;
        try { p = VenueProfile.parse(text); new CrowdMonitor(() -> m.now()).loadVenue(p, true); }
        catch (RuntimeException e) { return Reply.json(422, "{\"ok\":false,\"message\":" + str("Not saved: " + e.getMessage()) + "}"); }
        Path base = server.dataFolder();
        if (base == null) return Reply.json(422, "{\"ok\":false,\"message\":\"no data folder\"}");
        Path folder = base.resolve("venues");
        Files.createDirectories(folder);
        String stamp = m.now().withNano(0).toString().replace(":", "").replace("-", "").replace("T", "-");
        Path f = folder.resolve(Database.safeName(p.code) + "-" + stamp + ".venue");
        Files.writeString(f, text, StandardCharsets.UTF_8);
        return Reply.ok(Result.ok("Saved as " + f.toAbsolutePath() + ". Check it, then start the server with this file (run-server " + f.getFileName() + ") to use it."));
    }

    // ------------------------------------------------------------------ the public help page

    private String[][] publicAreas() {
        LinkedList<String[]> out = new LinkedList<>();
        synchronized (m) { for (Area a : m.areaTree().inOrder()) if (!a.restricted) out.addLast(new String[] { a.id, a.name }); }
        return out.toArray(new String[0][]);
    }

    private Reply publicReport(AVLTree<String, String> q, String address) {
        long now = System.currentTimeMillis();
        long[] slot = visitors.compute(address, (k, v) -> {
            if (v == null || now - v[0] > PUBLIC_WINDOW_MINUTES * 60_000L) return new long[] { now, 1 };
            v[1]++;
            return v;
        });
        if (visitors.size() > 10_000) visitors.clear();
        if (slot[1] > PUBLIC_REPORTS) return Reply.json(429, "{\"ok\":false,\"message\":\"Too many reports from this phone: please ask a member of staff.\"}");
        String kind = q.search("kind") == null ? "help" : q.search("kind");
        String area = q.search("area"), details = q.search("details"), contact = q.search("contact"), language = q.search("language");
        if (details == null || details.isBlank()) return Reply.json(400, "{\"ok\":false,\"message\":\"Please describe what is needed.\"}");
        String text = "[visitor report] " + details.trim();
        Result r;
        synchronized (m) {
            Area a = m.findArea(area);
            if (a == null || a.restricted) return Reply.json(400, "{\"ok\":false,\"message\":\"Choose where you are.\"}");
            if (kind.equals("found")) r = m.foundPerson(a.id, text, contact, "visitor");
            else {
                String skill = kind.equals("lost") ? "lost-person" : kind.equals("medical") ? "first-aid" : null;
                Category chosen = null, fallback = null;
                for (Category c : m.categoryTree().inOrder()) {
                    if (skill != null && skill.equalsIgnoreCase(c.skill)) { chosen = c; break; }
                    if (fallback == null || c.priority.compareTo(fallback.priority) > 0) fallback = c;
                }
                if (chosen == null) chosen = fallback;
                r = m.reportIncident(chosen.code, a.id, text, contact, language);
            }
        }
        return Reply.json(r.ok ? 200 : 422, "{\"ok\":" + r.ok + ",\"message\":" + str(r.ok ? "Thank you. Staff have been told and are on their way. Please stay where you are." : r.message) + "}");
    }

    private Reply qrPage(String host, boolean https) {
        String base = (https ? "https://" : "http://") + (host == null ? "localhost" : host);
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><head><meta charset='utf-8'><title>CrowdWatch help codes</title><style>")
            .append("body{font-family:system-ui,Arial,sans-serif;margin:20px}h1{font-size:20px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:16px}")
            .append(".c{border:1px solid #ccd;border-radius:10px;padding:12px;text-align:center;break-inside:avoid}.c b{display:block;font-size:16px}.c small{word-break:break-all;color:#556}")
            .append("button{padding:8px 14px;margin-bottom:12px}@media print{button{display:none}}</style></head><body><h1>")
            .append(ApiServer.html(m.venueName())).append(" - help codes for visitors</h1><p>Print and put each code up in its area. Scanning it opens the help page with the area already chosen. Help / مساعدة</p>")
            .append("<button onclick='print()'>Print</button><div class='grid'>");
        for (String[] a : publicAreas()) {
            String url = base + "/help?area=" + java.net.URLEncoder.encode(a[0], StandardCharsets.UTF_8);
            sb.append("<div class='c'><b>Need help? / تحتاج مساعدة؟</b>").append(QrCode.encode(url).svg(4)).append("<div>").append(ApiServer.html(a[1])).append("</div><small>").append(ApiServer.html(url)).append("</small></div>");
        }
        return Reply.text("text/html; charset=utf-8", sb.append("</div></body></html>").toString(), null);
    }

    // ------------------------------------------------------------------ monitoring

    /** Prometheus text format: people, areas, incidents, units, alerts, requests, saves. */
    private String metrics() {
        StringBuilder sb = new StringBuilder();
        synchronized (m) {
            int people = 0;
            sb.append("# HELP crowdwatch_area_people People counted in an area now\n# TYPE crowdwatch_area_people gauge\n");
            for (Area a : m.areaTree().inOrder()) { people += a.occupancy; sb.append("crowdwatch_area_people{area=\"").append(a.id).append("\"} ").append(a.occupancy).append('\n'); }
            sb.append("# HELP crowdwatch_area_percent Load of an area in percent of its capacity\n# TYPE crowdwatch_area_percent gauge\n");
            for (Area a : m.areaTree().inOrder()) sb.append("crowdwatch_area_percent{area=\"").append(a.id).append("\"} ").append(a.percent()).append('\n');
            sb.append("# HELP crowdwatch_area_level Crowd level: 0 LOW, 1 MODERATE, 2 HIGH, 3 CRITICAL\n# TYPE crowdwatch_area_level gauge\n");
            for (Area a : m.areaTree().inOrder()) sb.append("crowdwatch_area_level{area=\"").append(a.id).append("\"} ").append(a.level.ordinal()).append('\n');
            sb.append("# HELP crowdwatch_people_on_site People on site\n# TYPE crowdwatch_people_on_site gauge\ncrowdwatch_people_on_site ").append(people).append('\n');
            int[] pr = new int[4];
            int open = 0;
            for (Incident i : m.openIncidentsByPriority()) { pr[i.priority.ordinal()]++; open++; }
            sb.append("# HELP crowdwatch_incidents_open Open incidents by priority\n# TYPE crowdwatch_incidents_open gauge\n");
            String[] names = { "CRITICAL", "HIGH", "NORMAL", "LOW" };
            for (int k = 0; k < 4; k++) sb.append("crowdwatch_incidents_open{priority=\"").append(names[k]).append("\"} ").append(pr[k]).append('\n');
            int[] us = new int[4];
            for (Unit u : m.unitTree().inOrder()) us[u.status.ordinal()]++;
            sb.append("# HELP crowdwatch_units Units by status\n# TYPE crowdwatch_units gauge\n");
            String[] st = { "AVAILABLE", "BUSY", "OFF_DUTY", "UNREACHABLE" };
            for (int k = 0; k < 4; k++) sb.append("crowdwatch_units{status=\"").append(st[k]).append("\"} ").append(us[k]).append('\n');
            sb.append("# HELP crowdwatch_alerts_kept Alerts held in memory\n# TYPE crowdwatch_alerts_kept gauge\ncrowdwatch_alerts_kept ").append(m.alerts().size()).append('\n');
            sb.append("# HELP crowdwatch_officer_overdue 1 if the officer's check-in is overdue\n# TYPE crowdwatch_officer_overdue gauge\ncrowdwatch_officer_overdue ")
              .append(m.officer() == null || m.officerStatus().contains("OVERDUE") ? 1 : 0).append('\n');
            LocalDateTime saved = m.lastSaved();
            sb.append("# HELP crowdwatch_seconds_since_save Seconds since the last save (-1 never)\n# TYPE crowdwatch_seconds_since_save gauge\ncrowdwatch_seconds_since_save ")
              .append(saved == null ? -1 : Duration.between(saved, m.now()).getSeconds()).append('\n');
        }
        sb.append("# HELP crowdwatch_requests_total Requests answered by the server\n# TYPE crowdwatch_requests_total counter\ncrowdwatch_requests_total ").append(requests.get()).append('\n');
        return sb.toString();
    }
}
