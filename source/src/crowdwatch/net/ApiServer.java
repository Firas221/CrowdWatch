package crowdwatch.net;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.core.Result;
import crowdwatch.ds.AVLTree;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.*;
import crowdwatch.model.Enums.Source;
import crowdwatch.model.Enums.IncidentStatus;
import crowdwatch.core.VenueProfile;

/**
 * Built-in HTTP server that lets other terminals use the same monitor: a live web dashboard
 * for browsers and a small JSON API for other systems.
 * <p>
 * Uses the JDK's own com.sun.net.httpserver, so no extra library is needed.
 * <p>
 * <b>Robustness.</b> Requests are handled by {@link #THREADS} threads, and a client gets
 * {@link #REQUEST_SECONDS} seconds to send its request, so slow or stalled clients (a phone on
 * weak Wi-Fi, or someone holding connections open on purpose) cannot occupy every thread.
 * TCP_NODELAY is switched on, so small replies are not held back by about 40 ms. Up to
 * {@link #BACKLOG} new connections can wait to be accepted. Each reply is built while holding the
 * monitor's lock and sent after it is released. Read-only dashboard data is cached for half a
 * second (see {@link ReplyCache}). Bodies over {@link #MAX_BODY} get 413.
 * <p>
 * <b>Keys.</b> With a control key set, every /api request needs a key in the X-Api-Key header
 * (or a {@code key} field): the control key allows everything; the optional scout key allows
 * only what a scout device needs (GET /api/unit and POST /api/ack, /api/onscene, /api/resolve,
 * /api/sos). Missing or wrong key: 401; scout key on a control function: 403. The pages / and
 * /scout are open (they hold no data; their scripts send the key). Requests without the control
 * key are limited per address ({@link RateLimiter}); too many get 429. Without any key the server
 * is open to everyone on the network, which is only acceptable for a demonstration.
 */
public class ApiServer {
    private final CrowdMonitor m;
    // null means no key is required
    private final String apiKey;
    // optional key for scout devices, null if none
    private final String scoutKey;
    private final RateLimiter limiter;
    private final ReplyCache cache = new ReplyCache();
    // personal logins, device codes and the audit log; null = keys only (as in earlier versions)
    private volatile Accounts accounts;
    // the venue's database (reports, replay, data-protection search) and the data folder (photos, sites, venue edits)
    private volatile crowdwatch.db.Database database;
    private volatile java.nio.file.Path dataFolder;
    // true when this server runs a drill (training, nothing saved)
    private volatile boolean drill;
    private final ApiExtras extras;
    // the caller when the control key is used (or the server is open): full rights
    private static final Accounts.Who SYSTEM = accountsWho("control key", Accounts.Role.ADMIN, null);
    // what each role may change (each role also has the rights of the roles below it); every signed-in user may read
    private static final String[] OPERATOR_POSTS = { "POST /api/message", "POST /api/position", "POST /api/duty", "POST /api/weather", "POST /api/password", "POST /api/twostep/start", "POST /api/twostep/confirm", "POST /api/twostep/off", "POST /api/occupancy", "POST /api/move", "POST /api/incidents", "POST /api/resolve", "POST /api/onscene",
        "POST /api/ack", "POST /api/sos", "POST /api/found", "POST /api/foundperson", "POST /api/reassign", "POST /api/escalate", "POST /api/alarm" };
    private static final String[] ADMIN_ONLY = { "GET /api/users", "POST /api/users", "POST /api/users/reset", "GET /api/privacy", "GET /api/privacy/export", "POST /api/privacy/erase",
        "GET /api/venuefile", "POST /api/venuefile" };
    private static final String[] OFFICER_GETS = { "GET /api/audit", "GET /api/report" };
    // requests that reached the monitor take turns in arrival order (fair), so none waits far longer than the rest
    private final java.util.concurrent.Semaphore turns = new java.util.concurrent.Semaphore(WORKING, true);
    /** Requests allowed to work on the monitor at the same time; the others queue fairly. */
    public static final int WORKING = 16;
    /** Worker threads for requests. */
    public static final int THREADS = 512;
    /** Connections that may wait to be accepted. */
    public static final int BACKLOG = 1024;
    /** Seconds a client has to send its request (and the server to send its reply is 30). */
    public static final int REQUEST_SECONDS = 5;
    /** Requests a second allowed per address for requests without the control key (property crowdwatch.rateLimit, 0 = off). */
    public static final int RATE_LIMIT = Integer.getInteger("crowdwatch.rateLimit", 50);
    // pages that hold no data and may be loaded without a key
    private static final String[] PUBLIC = { "GET /", "GET /console", "GET /console.css", "GET /console.js", "GET /i18n.js", "GET /classic", "GET /scout", "GET /health",
        "POST /api/login", "GET /api/me", "POST /api/logout", "POST /classic/login", "POST /classic/logout", "GET /help", "POST /api/public/report", "POST /api/password/reset", "GET /metrics" };
    // the venue drawing: needs a key (it shows the building), sent as an image
    // what a scout device may do with the scout key
    private static final String[] SCOUT = { "GET /api/unit", "POST /api/ack", "POST /api/onscene", "POST /api/resolve", "POST /api/sos", "POST /api/found", "POST /api/foundperson",
        "GET /api/layout", "GET /api/map", "POST /api/message", "GET /api/messages", "GET /api/photo", "POST /api/position", "POST /api/duty" };
    // read-only replies that may be cached for half a second
    private static final String[] CACHED = { "GET /api/summary", "GET /api/areas", "GET /api/incidents", "GET /api/alerts", "GET /api/status",
        "GET /api/overview", "GET /api/units", "GET /api/venue", "GET /api/lost", "GET /api/announcements", "GET /api/layout" };

    static {
        // read once by the JDK when the first HTTP server is created; an explicit -D setting wins
        setDefault("sun.net.httpserver.nodelay", "true");
        setDefault("sun.net.httpserver.maxReqTime", String.valueOf(REQUEST_SECONDS));
        setDefault("sun.net.httpserver.maxRspTime", "30");
        // keep more idle keep-alive connections than the JDK default (200), or busy clients get dropped connections
        setDefault("sun.net.httpserver.maxIdleConnections", "4000");
    }

    // sets a system property unless it was given on the command line
    private static void setDefault(String key, String value) { if (System.getProperty(key) == null) System.setProperty(key, value); }

    // true if s is one of the values
    private static boolean in(String s, String[] values) { for (String v : values) if (v.equals(s)) return true; return false; }
    /** Largest request body accepted (64 KB). Bigger requests get 413, so a client cannot exhaust memory. */
    static final int MAX_BODY = 64 * 1024;
    // null while stopped
    private HttpServer server;
    // true while serving HTTPS
    private volatile boolean secure;

    /** @return true if the server is running with HTTPS */
    public boolean secure() { return server != null && secure; }

    /**
     * @param monitor the shared monitor
     * @param apiKey  key required for changes, or null/blank for none
     */
    public ApiServer(CrowdMonitor monitor, String apiKey) { this(monitor, apiKey, null, RATE_LIMIT); }

    /**
     * @param monitor   the shared monitor
     * @param apiKey    control key, or null/blank for an open server
     * @param scoutKey  key for scout devices, or null/blank for none
     * @param perSecond requests a second allowed per address without the control key (0 = no limit)
     */
    public ApiServer(CrowdMonitor monitor, String apiKey, String scoutKey, int perSecond) {
        this.m = monitor;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.scoutKey = scoutKey == null || scoutKey.isBlank() ? null : scoutKey;
        this.limiter = new RateLimiter(perSecond);
        this.extras = new ApiExtras(monitor, this);
    }

    /** Gives the server the venue's database, for reports, replay and data-protection requests. */
    public void setDatabase(crowdwatch.db.Database db) { database = db; }

    crowdwatch.db.Database database() { return database; }

    /** Sets the data folder (crowdwatch-data): photos, the head office's sites.txt, saved venue edits. */
    public void setDataFolder(java.nio.file.Path folder) { dataFolder = folder; }

    java.nio.file.Path dataFolder() { return dataFolder; }

    /** Marks this server as a drill: every console shows that it is training, not the real venue. */
    public void setDrill(boolean on) { drill = on; }

    /** @return text escaped for HTML */
    static String html(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;"); }

    /**
     * Starts listening.
     *
     * @param port TCP port, e.g. 8080 (0 picks a free port, used by the tests)
     * @return the port actually used
     * @throws IOException if the port is already in use
     */
    public synchronized int start(int port) throws IOException { return start(port, null); }

    /**
     * Starts listening, with HTTPS if a TLS context is given (see {@link Tls}). With HTTPS every
     * reply also carries Strict-Transport-Security, so browsers keep using HTTPS.
     *
     * @param port TCP port (0 picks a free port)
     * @param tls  TLS context with the server's certificate, or null for plain HTTP
     * @return the port actually used
     * @throws IOException if the port is already in use
     */
    public synchronized int start(int port, javax.net.ssl.SSLContext tls) throws IOException {
        if (server != null) return server.getAddress().getPort();
        if (tls != null) {
            com.sun.net.httpserver.HttpsServer https = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress(port), BACKLOG);
            https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls) {
                @Override public void configure(com.sun.net.httpserver.HttpsParameters p) {
                    javax.net.ssl.SSLParameters sp = tls.getDefaultSSLParameters();
                    sp.setProtocols(new String[] { "TLSv1.3", "TLSv1.2" });
                    p.setSSLParameters(sp);
                }
            });
            server = https;
        } else {
            server = HttpServer.create(new InetSocketAddress(port), BACKLOG);
        }
        secure = tls != null;
        server.setExecutor(Executors.newFixedThreadPool(THREADS, r -> { Thread t = new Thread(r, "api"); t.setDaemon(true); return t; }));
        server.createContext("/", this::handle);
        server.start();
        return server.getAddress().getPort();
    }

    /** Stops the server at once. */
    public synchronized void stop() {
        if (server != null) server.stop(0);
        server = null;
    }

    /** @return true while the server is listening */
    public boolean running() { return server != null; }

    /**
     * Handles one request: reads the parameters (query string and, for POST, the form body),
     * checks the API key for POST requests, and dispatches on method and path.
     * <p>
     * A form posted from the dashboard includes a {@code back} field; it gets a redirect back to
     * the dashboard with the result message. Other POST callers get JSON:
     * 200 if the operation succeeded, 422 if a rule refused it. Bad input gives 400 and an
     * unknown path 404, and a body over {@link #MAX_BODY} 413.
     */
    private void handle(HttpExchange ex) throws IOException {
        int code;
        String type = "application/json", body;
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            AVLTree<String, String> q = params(ex.getRequestURI().getRawQuery());
            if (method.equals("POST")) {
                int limit = path.equals("/api/message") ? ApiExtras.MAX_MESSAGE_BODY : MAX_BODY;
                byte[] raw = ex.getRequestBody().readNBytes(limit + 1);
                if (raw.length > limit) { send(ex, 413, type, err("request body larger than " + limit + " bytes")); return; }
                AVLTree<String, String> form = params(new String(raw, StandardCharsets.UTF_8));
                // merge the form body into the query parameters
                for (String k : form.keysInOrder()) q.insert(k, form.search(k));
            }
            String endpoint = method + " " + path;
            String given = header(ex, "X-Api-Key") != null ? header(ex, "X-Api-Key") : q.search("key");
            Accounts acc = accounts;
            boolean open = apiKey == null && (acc == null || !acc.hasUsers());
            boolean control = open || (apiKey != null && apiKey.equals(given));
            if (!control && limiter.tooMany(ex.getRemoteAddress().getAddress().getHostAddress())) { send(ex, 429, type, err("too many requests, slow down")); return; }
            Accounts.Who who = control ? SYSTEM : identify(acc, ex, given);
            extras.requests.incrementAndGet();
            if (endpoint.equals("GET /metrics")) {
                String mk = System.getProperty("crowdwatch.metricsKey", System.getenv("CROWDWATCH_METRICS_KEY"));
                boolean ok = control || (mk != null && !mk.isBlank() && mk.equals(bearer(ex)));
                if (!ok) { send(ex, 401, type, err("metrics need the control key or the metrics key (bearer)")); return; }
            }
            if (endpoint.equals("POST /api/login")) { login(ex, acc, q); return; }
            if (endpoint.equals("GET /api/me")) { send(ex, 200, type, me(who, acc)); return; }
            if (who == null && !in(endpoint, PUBLIC)) { send(ex, 401, type, err(acc != null && acc.hasUsers() ? "please sign in" : "missing or wrong API key")); return; }
            if (who != null && !in(endpoint, PUBLIC)) {
                String refused = permit(who, endpoint, q);
                if (refused != null) {
                    if (method.equals("POST")) audit(who, endpoint, q, false, refused);
                    send(ex, 403, type, err(refused));
                    return;
                }
            }
            if (endpoint.equals("POST /api/logout")) { if (acc != null) acc.logout(bearer(ex)); send(ex, 200, type, "{\"ok\":true,\"message\":\"signed out\"}"); return; }
            if (endpoint.equals("POST /classic/login") || endpoint.equals("POST /classic/logout")) { classicSign(ex, acc, endpoint, q); return; }
            if (endpoint.equals("GET /classic") && who == null && acc != null && acc.hasUsers()) { send(ex, 200, "text/html; charset=utf-8", Dashboard.loginPage(q.search("msg"))); return; }
            if (ApiExtras.handles(endpoint)) {
                ApiExtras.Reply rp = extras.handle(endpoint, q, who, header(ex, "Host"), ex instanceof com.sun.net.httpserver.HttpsExchange, ex.getRemoteAddress().getAddress().getHostAddress());
                if (method.equals("POST") && !endpoint.equals("POST /api/public/report") && !endpoint.equals("POST /api/password/reset"))
                    audit(who, endpoint, q, rp.code < 300, new String(rp.body, StandardCharsets.UTF_8).replaceAll(".*\"message\":\"([^\"]*)\".*", "$1"));
                if (endpoint.equals("POST /api/public/report")) audit(new Accounts.Who("visitor " + ex.getRemoteAddress().getAddress().getHostAddress(), null, null), endpoint, q, rp.code < 300, "visitor report");
                if (rp.file != null) ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + rp.file + "\"");
                if (endpoint.equals("GET /api/photo")) ex.getResponseHeaders().set("Cache-Control", "private, max-age=600");
                sendBytes(ex, rp.code, rp.type, rp.body);
                return;
            }
            if (path.startsWith("/api/users") || path.startsWith("/api/devices") || path.startsWith("/api/twostep") || endpoint.equals("GET /api/audit")) {
                if (acc == null) { send(ex, 404, type, err("personal logins are not switched on for this server")); return; }
                String[] reply = accountsEndpoint(acc, who, endpoint, q);
                send(ex, Integer.parseInt(reply[0]), type, reply[1]);
                return;
            }
            if (endpoint.equals("GET /api/map")) {
                byte[] img;
                String imgType;
                synchronized (m) { img = m.venue() == null ? null : m.venue().mapBytes(); imgType = m.venue() == null ? "" : m.venue().mapType(); }
                if (img == null) { send(ex, 404, type, err("this venue has no map drawing")); return; }
                ex.getResponseHeaders().set("Content-Type", imgType);
                ex.getResponseHeaders().set("Cache-Control", "private, max-age=300");
                ex.sendResponseHeaders(200, img.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(img); }
                ex.close();
                return;
            }
            String cacheKey = endpoint + "?" + ex.getRequestURI().getRawQuery();
            if (in(endpoint, CACHED)) {
                String[] hit = cache.get(cacheKey);
                if (hit != null) { send(ex, 200, hit[0], hit[1]); return; }
            }
            if (method.equals("POST")) cache.clear();
            Result r = null;
            // build the reply while holding the monitor's lock; send it after releasing the lock,
            // so a slow or stalled client never holds up the monitor
            turns.acquireUninterruptibly();
            try { synchronized (m) {
                switch (method + " " + path) {
                    case "GET /", "GET /console" -> { code = 200; type = "text/html; charset=utf-8"; body = resource("console.html"); }
                    case "GET /console.css" -> { code = 200; type = "text/css; charset=utf-8"; body = resource("console.css"); }
                    case "GET /console.js" -> { code = 200; type = "application/javascript; charset=utf-8"; body = resource("console.js"); }
                    case "GET /i18n.js" -> { code = 200; type = "application/javascript; charset=utf-8"; body = resource("i18n.js"); }
                    case "GET /classic" -> { code = 200; type = "text/html; charset=utf-8"; body = Dashboard.page(m, apiKey != null && (who == null || who == SYSTEM), who != null && who != SYSTEM); }
                    case "GET /api/overview" -> { code = 200; body = overview(); }
                    case "GET /api/units" -> { code = 200; body = units(); }
                    case "GET /api/incident" -> { code = 200; body = incident(num(q.search("id"))); }
                    case "GET /api/venue" -> { code = 200; body = venue(); }
                    case "GET /api/readings" -> { code = 200; body = readings(q.search("area")); }
                    case "GET /api/evacuation" -> { code = 200; body = evacuation(q.search("zone")); }
                    case "POST /api/reassign" -> { code = 0; body = null; r = m.reassign(num(q.search("id")), q.search("unit"), q.search("reason")); }
                    case "POST /api/escalate" -> { code = 0; body = null; r = m.escalate(num(q.search("id"))); }
                    case "GET /health" -> { body = health(); code = body.contains("\"status\":\"fail\"") ? 503 : 200; }
                    case "GET /api/lost" -> { code = 200; body = lost(); }
                    case "GET /api/layout" -> { code = 200; body = layout(); }
                    case "GET /api/announcements" -> { code = 200; body = announcements(); }
                    case "GET /api/handover" -> { code = 200; type = "text/plain; charset=utf-8"; body = m.handoverReport(); }
                    case "POST /api/found" -> { code = 0; body = null; r = m.found(num(q.search("id")), q.search("area"), by(q)); }
                    case "POST /api/foundperson" -> { code = 0; body = null; r = m.foundPerson(q.search("area"), q.search("details"), q.search("contact"), by(q)); }
                    case "POST /api/move" -> { code = 0; body = null; r = m.movePeople(q.search("from"), q.search("to"), num(q.search("count")), source(q)); }
                    case "POST /api/countcheck/reset" -> { code = 0; body = null; r = m.resetCountCheck(q.search("officer")); }
                    case "POST /api/alarm" -> { code = 0; body = null; r = m.alarm(q.search("area"), q.search("text")); }
                    case "POST /api/proposal" -> { code = 0; body = null; r = m.decideProposal("start".equals(q.search("decision")), q.search("officer")); }
                    case "POST /api/announcement" -> { code = 0; body = null; r = m.decideAnnouncement(num(q.search("id")), "approve".equals(q.search("decision")), q.search("officer")); }
                    case "POST /api/announce" -> { code = 0; body = null; r = m.announce(q.search("text"), q.search("officer")); }
                    case "GET /api/summary" -> { code = 200; type = "text/plain; charset=utf-8"; body = m.summary(); }
                    case "GET /api/areas" -> { code = 200; body = areas(); }
                    case "GET /api/incidents" -> { code = 200; body = incidents(); }
                    case "GET /api/alerts" -> { code = 200; body = alerts(); }
                    case "GET /api/route" -> { code = 200; body = route(q); }
                    case "POST /api/occupancy" -> { code = 0; body = null; r = q.search("delta") != null
                        ? m.adjustOccupancy(q.search("area"), num(q.search("delta")), source(q))
                        : m.updateOccupancy(q.search("area"), num(q.search("count")), source(q)); }
                    case "POST /api/incidents" -> { code = 0; body = null; r = m.reportIncident(q.search("category"), q.search("area"), q.search("details"), q.search("contact"), q.search("language")); }
                    case "POST /api/resolve" -> { code = 0; body = null; r = m.resolve(num(q.search("id")), q.search("note")); }
                    case "POST /api/onscene" -> { code = 0; body = null; r = m.confirmOnScene(num(q.search("id"))); }
                    case "GET /scout" -> { code = 200; type = "text/html; charset=utf-8"; body = ScoutPage.page(apiKey != null || (accounts != null && accounts.hasUsers())); }
                    case "GET /api/unit" -> {
                        code = 200;
                        if (q.search("battery") != null && q.search("battery").matches("\\d{1,3}")) m.battery(q.search("id"), Integer.parseInt(q.search("battery")));
                        body = unit(q.search("id"));
                    }
                    case "GET /api/status" -> { code = 200; body = status(); }
                    case "POST /api/ack" -> { code = 0; body = null; r = m.acknowledge(q.search("unit")); }
                    case "POST /api/sos" -> { code = 0; body = null; r = m.sos(q.search("area"), q.search("unit"), q.search("text")); }
                    case "POST /api/checkin" -> { code = 0; body = null; r = m.officerCheckIn(q.search("officer")); }
                    case "POST /api/review" -> { code = 0; body = null; r = m.review(num(q.search("id")), q.search("officer"), !"false".equals(q.search("decision")), q.search("note")); }
                    case "POST /api/evacuate" -> { code = 0; body = null; r = m.evacuate(q.search("zone"), q.search("officer")); }
                    case "POST /api/evacuate/end" -> { code = 0; body = null; r = m.endEvacuation(q.search("officer")); }
                    case "POST /api/broadcast" -> { code = 0; body = null; r = m.broadcast(q.search("text"), q.search("officer")); }
                    case "POST /api/automation" -> { code = 0; body = null; r = automation(q); }
                    default -> { code = 404; body = err("unknown endpoint " + method + " " + path); }
                }
            } } finally { turns.release(); }
            if (r != null && q.search("back") != null) {
                ex.getResponseHeaders().add("Location", "/classic?msg=" + java.net.URLEncoder.encode((r.ok ? "OK: " : "REFUSED: ") + r.message, StandardCharsets.UTF_8));
                code = 303;
                body = "";
                type = "text/plain";
            } else if (r != null) {
                code = r.ok ? 200 : 422;
            }
            if (r == null && code == 200 && in(endpoint, CACHED)) cache.put(cacheKey, type, body);
            if (r != null && code != 303) {
                body = "{\"ok\":" + r.ok + ",\"message\":" + str(r.message) + "}";
            }
            if (r != null) audit(who, endpoint, q, r.ok, r.message);
        } catch (RuntimeException e) {
            code = 400;
            body = err(String.valueOf(e.getMessage()));
        }
        send(ex, code, type, body);
    }

    /** @return JSON array of areas (busiest first) with level, trend rate and minutes to CRITICAL */
    private String areas() {
        StringBuilder sb = new StringBuilder("[");
        for (Area a : m.areasByLoad()) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":").append(str(a.id)).append(",\"name\":").append(str(a.name)).append(",\"sector\":").append(str(a.sector))
              .append(",\"type\":").append(str(a.type)).append(",\"occupancy\":").append(a.occupancy).append(",\"capacity\":").append(a.capacity)
              .append(",\"percent\":").append(a.percent()).append(",\"level\":").append(str(a.level.name()))
              .append(",\"ratePerMinute\":").append(Math.round(m.ratePerMinute(a))).append(",\"minutesToCritical\":").append(m.minutesToCritical(a))
              .append(",\"restricted\":").append(a.restricted).append(",\"evacuating\":").append(a.evacuating)
              .append(",\"stale\":").append(a.staleSince != null).append(",\"suspect\":").append(str(a.suspect)).append(",\"flowHeld\":").append(a.flowHeld)
              .append(",\"eventOffset\":").append(a.thresholdOffset)
              .append(",\"size\":").append(Math.round(a.sizeM2)).append(",\"density\":").append(Math.round(a.density() * 10) / 10.0)
              .append(",\"densityLevel\":").append(str(m.densityLevel(a).name())).append(",\"camera\":").append(str(m.venue() == null ? null : m.venue().cameraOf(a.id)))
              .append(",\"unitsHere\":").append(crowdwatch.core.AutoRulesProbe.unitsAt(m, a.id)).append(",\"unitsWanted\":").append(crowdwatch.core.AutoRulesProbe.target(m, a.id)).append(",\"links\":[");
            int k = 0;
            for (crowdwatch.ds.Graph.Edge e : m.map().neighbours(m.map().indexOf(a.id))) sb.append(k++ > 0 ? "," : "").append(str(m.map().name(e.to)));
            sb.append("]}");
        }
        return sb.append(']').toString();
    }

    /** @return JSON array of open incidents by priority */
    private String incidents() {
        StringBuilder sb = new StringBuilder("[");
        for (Incident i : m.openIncidentsByPriority()) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":").append(i.id).append(",\"category\":").append(str(i.category.code)).append(",\"priority\":").append(str(i.priority.name()))
              .append(",\"area\":").append(str(i.area.id)).append(",\"status\":").append(str(i.status.name())).append(",\"unit\":").append(i.unitId == null ? "null" : str(i.unitId))
              .append(",\"minutesOpen\":").append(i.minutesOpen(m.now())).append(",\"label\":").append(str(i.category.label))
              .append(",\"reported\":").append(str(i.reported.format(HistoryEntry.FORMAT))).append(",\"automatic\":").append(i.automatic)
              .append(",\"review\":").append(i.automatic && i.reviewedBy == null).append(",\"device\":").append(str(deviceText(i)))
              .append(",\"acknowledged\":").append(i.acknowledged != null || i.status == IncidentStatus.ON_SCENE).append(",\"reminders\":").append(i.reminders)
              .append(",\"lost\":").append(i.lostCase()).append(",\"found\":").append(str(i.foundWhere)).append('}');
        }
        return sb.append(']').toString();
    }

    /** @return what the assigned unit's device has done with an incident, in words */
    static String deviceText(Incident i) {
        if (!i.open()) return "closed";
        if (i.unitId == null) return "waiting in the " + i.priority + " queue";
        if (i.status == IncidentStatus.ON_SCENE) return "on scene";
        if (i.acknowledged != null) return "acknowledged";
        return i.reminders > 0 ? "not acknowledged - reminder sent" : "sent, not yet acknowledged";
    }

    /** @return true if a unit's device has been in contact within the last 30 seconds */
    private boolean online(Unit u) {
        return u.lastContact != null && java.time.Duration.between(u.lastContact, m.now()).getSeconds() < crowdwatch.core.ConsoleRelay.DEVICE_ONLINE_SECONDS;
    }

    /**
     * Everything the console's overview needs in one reply: people and capacity, areas per level,
     * open incidents per priority, units per status and devices online, officer, automation,
     * dispatches awaiting review, evacuation and the archive count.
     */
    private String overview() {
        int people = 0, cap = 0, open = 0, waiting = m.waitingCount(), unack = 0, online = 0;
        int[] lv = new int[4], pr = new int[4], us = new int[4];
        for (Area a : m.areaTree().inOrder()) { people += a.occupancy; cap += a.capacity; lv[a.level.ordinal()]++; }
        for (Incident i : m.openIncidentsByPriority()) {
            open++; pr[i.priority.ordinal()]++;
            if (i.status == IncidentStatus.ASSIGNED && i.acknowledged == null) unack++;
        }
        for (Unit u : m.unitTree().inOrder()) { us[u.status.ordinal()]++; if (online(u)) online++; }
        VenueProfile v = m.venue();
        String rule = v == null || v.autoCategory == null ? null : v.autoCategory + " at " + v.autoLevel + (v.autoOnTrend ? " or TREND" : "");
        return "{\"venue\":" + str(m.venueName()) + ",\"code\":" + str(v == null ? "" : v.code) + ",\"now\":" + str(m.now().toLocalTime().withNano(0).toString())
            + ",\"people\":" + people + ",\"capacity\":" + cap + ",\"areas\":" + m.areaTree().size()
            + ",\"levels\":{\"LOW\":" + lv[0] + ",\"MODERATE\":" + lv[1] + ",\"HIGH\":" + lv[2] + ",\"CRITICAL\":" + lv[3] + "}"
            + ",\"incidents\":{\"open\":" + open + ",\"CRITICAL\":" + pr[0] + ",\"HIGH\":" + pr[1] + ",\"NORMAL\":" + pr[2] + ",\"LOW\":" + pr[3]
            + ",\"waiting\":" + waiting + ",\"unacknowledged\":" + unack + "}"
            + ",\"units\":{\"total\":" + m.unitTree().size() + ",\"AVAILABLE\":" + us[0] + ",\"BUSY\":" + us[1] + ",\"OFF_DUTY\":" + us[2] + ",\"UNREACHABLE\":" + us[3] + ",\"online\":" + online + "}"
            + ",\"review\":" + m.awaitingReview().length + ",\"automation\":" + m.automation() + ",\"autoRule\":" + str(rule)
            + ",\"officer\":" + str(m.officer()) + ",\"officerStatus\":" + str(m.officerStatus()) + ",\"overdue\":" + (m.officer() == null || m.officerStatus().contains("OVERDUE"))
            + ",\"evacuation\":" + str(m.evacuationZone()) + ",\"archived\":" + m.archivedCount()
            + ",\"proposal\":" + proposalJson() + ",\"signs\":" + m.pendingAnnouncements().length + ",\"stale\":" + staleCount()
            + ",\"lost\":" + missingCount() + ",\"events\":" + list(m.activeEvents()) + ",\"countCheck\":" + countCheckJson()
            + ",\"drill\":" + drill + ",\"heat\":" + heatJson() + ",\"sites\":" + (dataFolder != null && java.nio.file.Files.exists(dataFolder.resolve("sites.txt"))) + "}";
    }

    /** @return the weather and heat stress as JSON, or null without a recent reading */
    private String heatJson() {
        double[] w = m.records.weatherNow(m.now());
        if (w == null) return "null";
        return "{\"temp\":" + Math.round(w[0] * 10) / 10.0 + ",\"humidity\":" + Math.round(w[1]) + ",\"index\":" + Math.round(w[2]) + ",\"band\":" + str(m.records.heatBandNow(m.now()))
            + ",\"outdoor\":" + list(toList(m.outdoorAreas())) + "}";
    }

    private static LinkedList<String> toList(String[] a) {
        LinkedList<String> l = new LinkedList<>();
        for (String x : a) l.addLast(x);
        return l;
    }

    /** @return the count check as JSON {expected, counted, difference, ok}, or null if gates are not counted */
    private String countCheckJson() {
        long[] c = m.countCheck();
        if (c == null) return "null";
        long allowed = Math.max(50, Math.abs(c[0]) * Math.max(0, m.venue().reconcilePercent) / 100);
        return "{\"expected\":" + c[0] + ",\"counted\":" + c[1] + ",\"difference\":" + c[2] + ",\"ok\":" + (m.venue().reconcilePercent <= 0 || Math.abs(c[2]) <= allowed) + "}";
    }

    /** @return JSON array of units with status, current task and whether the device is online */
    private String units() {
        StringBuilder sb = new StringBuilder("[");
        for (Unit u : m.unitsSorted()) {
            if (sb.length() > 1) sb.append(',');
            Incident t = m.activeIncident(u.id);
            sb.append("{\"id\":").append(str(u.id)).append(",\"name\":").append(str(u.name)).append(",\"area\":").append(str(u.area.id))
              .append(",\"sector\":").append(str(u.area.sector)).append(",\"status\":").append(str(u.status.name()))
              .append(",\"skills\":").append(str(u.skillList())).append(",\"online\":").append(online(u)).append(",\"battery\":").append(u.battery)
              .append(",\"shift\":").append(str(u.shift)).append(",\"staged\":").append(str(u.stagedFor)).append(",\"reinforcing\":").append(str(u.reinforcing)).append(",\"home\":").append(str(u.home))
              .append(",\"lastContact\":").append(u.lastContact == null ? "null" : str(u.lastContact.format(HistoryEntry.FORMAT)))
              .append(",\"task\":").append(t == null ? "null" : "{\"id\":" + t.id + ",\"label\":" + str(t.category.label) + ",\"area\":" + str(t.area.id)
                  + ",\"priority\":" + str(t.priority.name()) + ",\"device\":" + str(deviceText(t)) + "}")
              .append('}');
        }
        return sb.append(']').toString();
    }

    /** @return one incident with its details, unit route and history; {"ok":false} if it is not in memory */
    private String incident(int id) {
        Incident i = m.findIncident(id);
        if (i == null) return err("no incident I" + id + " in memory (it may be archived)");
        Unit u = i.unitId == null ? null : m.findUnit(i.unitId);
        String route = "-";
        if (u != null) {
            CrowdMonitor.Route r = m.responderRoute(u.area.id, i.area.id);
            route = u.area == i.area ? "the unit is there" : r.found() ? r.path + " (" + r.walk + " min)" : "no route - ask the control room";
        }
        StringBuilder h = new StringBuilder("[");
        for (HistoryEntry e : i.history) h.append(h.length() > 1 ? "," : "").append("{\"t\":").append(str(e.time.format(HistoryEntry.FORMAT))).append(",\"text\":").append(str(e.text)).append('}');
        return "{\"ok\":true,\"id\":" + i.id + ",\"category\":" + str(i.category.code) + ",\"label\":" + str(i.category.label) + ",\"priority\":" + str(i.priority.name())
            + ",\"area\":" + str(i.area.id) + ",\"areaName\":" + str(i.area.name) + ",\"status\":" + str(i.status.name()) + ",\"unit\":" + str(i.unitId)
            + ",\"reported\":" + str(i.reported.format(HistoryEntry.FORMAT)) + ",\"details\":" + str(i.details) + ",\"contact\":" + str(i.contact)
            + ",\"language\":" + str(i.language) + ",\"automatic\":" + i.automatic + ",\"reviewedBy\":" + str(i.reviewedBy) + ",\"open\":" + i.open()
            + ",\"device\":" + str(deviceText(i)) + ",\"route\":" + str(route)
            + ",\"lost\":" + i.lostCase() + ",\"foundWhere\":" + str(i.foundWhere) + ",\"foundBy\":" + str(i.foundBy) + ",\"search\":" + list(m.searchAreas(i))
            + ",\"meeting\":" + str(m.meetingFor(i.area.id)) + ",\"history\":" + h + "]}";
    }

    /** @return the venue's settings, categories, sectors and areas (for forms and the settings view) */
    private String venue() {
        VenueProfile v = m.venue();
        int[] t = m.thresholds();
        StringBuilder cats = new StringBuilder("["), sectors = new StringBuilder("["), ids = new StringBuilder("["), exits = new StringBuilder("[");
        for (Category c : m.categoryTree().inOrder()) cats.append(cats.length() > 1 ? "," : "").append("{\"code\":").append(str(c.code)).append(",\"label\":").append(str(c.label)).append(",\"priority\":").append(str(c.priority.name())).append(",\"skill\":").append(str(c.skill)).append('}');
        crowdwatch.ds.LinkedList<String> seen = new crowdwatch.ds.LinkedList<>();
        for (Area a : m.areaTree().inOrder()) {
            ids.append(ids.length() > 1 ? "," : "").append(str(a.id));
            if (!seen.contains(a.sector)) { seen.addLast(a.sector); sectors.append(sectors.length() > 1 ? "," : "").append(str(a.sector)); }
        }
        for (String e : v.exits) exits.append(exits.length() > 1 ? "," : "").append(str(e));
        return "{\"name\":" + str(v.name) + ",\"code\":" + str(v.code) + ",\"thresholds\":[" + t[0] + "," + t[1] + "," + t[2] + "]"
            + ",\"auto\":" + (v.autoCategory == null ? "null" : "{\"category\":" + str(v.autoCategory) + ",\"level\":" + str(v.autoLevel) + ",\"trend\":" + v.autoOnTrend + "}")
            + ",\"supervisionMinutes\":" + v.supervisionMinutes + ",\"ackMinutes\":" + v.ackMinutes + ",\"archiveMinutes\":" + v.archiveMinutes
            + ",\"gateway\":" + (v.gateway != null) + ",\"automation\":" + m.automation()
            + ",\"exits\":" + exits + "],\"categories\":" + cats + "],\"sectors\":" + sectors + "],\"areas\":" + ids + "],\"fireZones\":" + fireZoneList(v)
            + ",\"rules\":{\"stale\":" + v.staleMinutes + ",\"jump\":" + v.jumpPercent + ",\"onscene\":" + v.onSceneMinutes + ",\"autoclose\":" + v.autoCloseMinutes
            + ",\"maxwait\":" + v.maxWaitMinutes + ",\"preposition\":" + v.prepositionMinutes + ",\"holdflow\":" + v.holdFlow + ",\"cover\":" + v.coverSectors
            + ",\"propose\":" + v.proposeMinutes + ",\"signs\":" + v.signs + ",\"reinforceHigh\":" + v.reinforceHigh + ",\"reinforceCritical\":" + v.reinforceCritical
            + ",\"reinforceHold\":" + v.reinforceHold + ",\"reinforceMax\":" + v.reinforceMax + ",\"reinforceSkills\":" + list(v.reinforceSkills) + ",\"meeting\":" + str(v.meetingPoint) + ",\"lostRadius\":" + v.lostRadius + ",\"lostWiden\":" + v.lostWiden
            + ",\"events\":" + eventsJson(v) + ",\"shifts\":" + list(v.shiftChanges) + "}}";
    }

    /** @return names of the venue's fire zones as a JSON array */
    private static String fireZoneList(VenueProfile v) {
        StringBuilder sb = new StringBuilder("[");
        for (String[] z : v.fireZones) sb.append(sb.length() > 1 ? "," : "").append(str(z[0]));
        return sb.append(']').toString();
    }

    /** @return the venue's scheduled events as JSON */
    private static String eventsJson(VenueProfile v) {
        StringBuilder sb = new StringBuilder("[");
        for (String[] e : v.events) sb.append(sb.length() > 1 ? "," : "").append("{\"time\":").append(str(e[0])).append(",\"minutes\":").append(e[1])
            .append(",\"zone\":").append(str(e[2])).append(",\"name\":").append(str(e[3])).append('}');
        return sb.append(']').toString();
    }

    /** @return the sampled readings of one area: seconds ago and people counted */
    private String readings(String id) {
        Area a = m.findArea(id);
        if (a == null) return err("unknown area " + id);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"area\":" + str(a.id) + ",\"capacity\":" + a.capacity + ",\"readings\":[");
        for (int k = 0; k < a.readings.size(); k++) {
            Reading r = a.readings.get(k);
            sb.append(k > 0 ? "," : "").append("{\"s\":").append(java.time.Duration.between(r.time, m.now()).getSeconds()).append(",\"n\":").append(r.occupancy).append('}');
        }
        return sb.append("]}").toString();
    }

    /** @return the evacuation in progress with its plan, or a preview of the plan for {@code zone} */
    private String evacuation(String zone) {
        StringBuilder sb = new StringBuilder("{\"active\":" + str(m.evacuationZone()) + ",\"zone\":" + str(m.evacuationZone() != null ? m.evacuationZone() : zone) + ",\"rows\":[");
        int k = 0;
        for (String[] r : m.evacuationRows(zone)) {
            sb.append(k++ > 0 ? "," : "").append("{\"from\":").append(str(r[0])).append(",\"exit\":").append(str(r[1])).append(",\"path\":").append(str(r[2]))
              .append(",\"min\":").append(str(r[3])).append(",\"inZone\":").append("zone".equals(r[4]))
              .append(",\"people\":").append(str(r[5])).append(",\"clear\":").append(str(r[6])).append('}');
        }
        return sb.append("],\"clearMinutes\":").append(clearAfter(zone)).append('}').toString();
    }

    /** @return estimated minutes to clear for the plan just listed (the live one, or the preview worked out by evacuationRows) */
    private int clearAfter(String zone) {
        int worst = 0;
        for (String[] r : m.evacuationRows(zone)) if ("zone".equals(r[4]) && r[6] != null && r[6].matches("\\d+") && m.evacuationClearMinutesKnown()) worst = Math.max(worst, Integer.parseInt(r[6]));
        return worst;
    }

    /**
     * @return the venue layout as JSON: drawing size, each area's position and crowd level, exits with their
     *         flow, links with capacity and lifts, fire zones; "map" is false when the venue has no layout
     */
    private String layout() {
        VenueProfile v = m.venue();
        if (v == null || v.positions.isEmpty()) return "{\"map\":false}";
        StringBuilder sb = new StringBuilder("{\"map\":true,\"image\":").append(v.mapFile != null && v.mapBytes() != null).append(",\"w\":").append(v.mapWidth).append(",\"h\":").append(v.mapHeight)
            .append(",\"scale\":").append(v.scale).append(",\"areas\":[");
        int k = 0;
        for (String[] p : v.positions) {
            Area a = m.findArea(p[0]);
            if (a == null) continue;
            String shape = v.shapeOf(a.id);
            sb.append(k++ > 0 ? "," : "").append("{\"id\":").append(str(a.id)).append(shape == null ? "" : ",\"shape\":[" + shape.trim().replace(' ', ',') + "]").append(",\"x\":").append(p[1]).append(",\"y\":").append(p[2]).append(",\"w\":").append(p[3]).append(",\"h\":").append(p[4])
              .append(",\"level\":").append(str(a.level.name())).append(",\"pct\":").append(a.percent()).append(",\"restricted\":").append(a.restricted).append(",\"evacuating\":").append(a.evacuating)
              .append(",\"exit\":").append(v.exits.contains(a.id)).append(",\"flow\":").append(m.exitFlow(a.id)).append(",\"zone\":").append(str(m.fireZoneOf(a.id))).append('}');
        }
        sb.append("],\"links\":[");
        k = 0;
        for (String[] l : v.links) {
            sb.append(k++ > 0 ? "," : "").append("{\"a\":").append(str(l[0].toUpperCase())).append(",\"b\":").append(str(l[1].toUpperCase()))
              .append(",\"capacity\":").append(m.linkCapacity(l[0], l[1])).append(",\"lift\":").append(m.isLift(l[0], l[1])).append('}');
        }
        sb.append("],\"zones\":[");
        k = 0;
        for (String[] z : v.fireZones) sb.append(k++ > 0 ? "," : "").append(str(z[0]));
        return sb.append("]}").toString();
    }

    // console page files, loaded once from the jar (crowdwatch/net/web/)
    private static final crowdwatch.ds.AVLTree<String, String> RESOURCES = new crowdwatch.ds.AVLTree<>();

    /** @return a console file from the jar, cached after the first read */
    private static synchronized String resource(String name) {
        String s = RESOURCES.search(name);
        if (s != null) return s;
        try (java.io.InputStream in = ApiServer.class.getResourceAsStream("web/" + name)) {
            s = in == null ? "missing " + name : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { s = "cannot read " + name; }
        RESOURCES.insert(name, s);
        return s;
    }

    /**
     * Everything a unit's device needs: the unit, its current task with the walking route,
     * its newest messages first, and the evacuation zone if one is in progress.
     * Also records that the device is online.
     *
     * @return JSON; {"ok":false,...} if the unit is unknown
     */
    private String unit(String id) {
        Unit u = m.findUnit(id);
        if (u == null) return err("unknown unit " + id);
        m.devicePoll(u.id);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"id\":").append(str(u.id)).append(",\"name\":").append(str(u.name))
            .append(",\"status\":").append(str(u.status.name())).append(",\"area\":").append(str(u.area.id)).append(",\"task\":");
        Incident t = m.activeIncident(u.id);
        if (t == null) sb.append("null");
        else {
            CrowdMonitor.Route r = m.responderRoute(u.area.id, t.area.id);
            String way = u.area == t.area ? "you are there" : r.found() ? r.path + " (" + r.walk + " min)" : "ask the control room";
            sb.append("{\"id\":").append(t.id).append(",\"category\":").append(str(t.category.code)).append(",\"label\":").append(str(t.category.label))
              .append(",\"priority\":").append(str(t.priority.name())).append(",\"status\":").append(str(t.status.name()))
              .append(",\"area\":").append(str(t.area.id)).append(",\"areaName\":").append(str(t.area.name)).append(",\"details\":").append(str(t.details))
              .append(",\"acknowledged\":").append(t.acknowledged != null).append(",\"route\":").append(str(way)).append('}');
        }
        sb.append(",\"messages\":[");
        for (int k = u.inbox.size() - 1; k >= 0; k--) {
            UnitMessage msg = u.inbox.get(k);
            if (k < u.inbox.size() - 1) sb.append(',');
            sb.append("{\"time\":").append(str(msg.time.toString())).append(",\"kind\":").append(str(msg.kind.name()))
              .append(",\"incident\":").append(msg.incidentId).append(",\"text\":").append(str(msg.text)).append('}');
        }
        sb.append("],\"lookouts\":[");
        int k = 0;
        for (Incident l : m.lostCases()) {
            if (l.foundAt != null) continue;
            sb.append(k++ > 0 ? "," : "").append("{\"id\":").append(l.id).append(",\"details\":").append(str(l.details)).append(",\"area\":").append(str(l.area.id))
              .append(",\"minutes\":").append(l.minutesOpen(m.now())).append('}');
        }
        if (t != null) sb.append("],\"lostTask\":").append(t.lostCase() && t.foundAt == null); else sb.append("],\"lostTask\":false");
        return sb.append(",\"evacuation\":").append(str(m.evacuationZone())).append('}').toString();
    }

    /** @return who did something: the officer, or the unit (scout devices send their unit ID) */
    private static String by(AVLTree<String, String> q) {
        String o = q.search("officer");
        return o != null && !o.isBlank() ? o : q.search("unit");
    }

    /** @return the evacuation proposal as JSON, or null */
    private String proposalJson() {
        String[] p = m.proposal();
        return p == null ? "null" : "{\"zone\":" + str(p[0]) + ",\"reason\":" + str(p[1]) + ",\"time\":" + str(p[2]) + "}";
    }

    /** @return number of areas whose sensor data is stale or suspect */
    private int staleCount() {
        int n = 0;
        for (Area a : m.areaTree().inOrder()) if (a.staleSince != null || a.suspect != null) n++;
        return n;
    }

    /** @return open lost-person cases where the person is still missing */
    private int missingCount() {
        int n = 0;
        for (Incident i : m.lostCases()) if (i.foundAt == null) n++;
        return n;
    }

    /** @return a JSON array of strings */
    private static String list(crowdwatch.ds.LinkedList<String> l) {
        StringBuilder sb = new StringBuilder("[");
        for (String s : l) sb.append(sb.length() > 1 ? "," : "").append(str(s));
        return sb.append(']').toString();
    }

    /** @return open lost-person cases with their search areas and found state */
    private String lost() {
        StringBuilder sb = new StringBuilder("[");
        for (Incident i : m.lostCases()) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":").append(i.id).append(",\"details\":").append(str(i.details)).append(",\"area\":").append(str(i.area.id))
              .append(",\"areaName\":").append(str(i.area.name)).append(",\"priority\":").append(str(i.priority.name())).append(",\"status\":").append(str(i.status.name()))
              .append(",\"unit\":").append(str(i.unitId)).append(",\"minutes\":").append(i.minutesOpen(m.now())).append(",\"reported\":").append(str(i.reported.format(HistoryEntry.FORMAT)))
              .append(",\"contact\":").append(str(i.contact)).append(",\"language\":").append(str(i.language))
              .append(",\"found\":").append(i.foundAt != null).append(",\"foundWhere\":").append(str(i.foundWhere)).append(",\"foundBy\":").append(str(i.foundBy))
              .append(",\"search\":").append(list(m.searchAreas(i))).append(",\"radius\":").append(i.searchMinutes)
              .append(",\"meeting\":").append(str(m.meetingFor(i.area.id))).append('}');
        }
        return sb.append(']').toString();
    }

    /** @return public messages: drafts awaiting a decision first, then the latest decided ones */
    private String announcements() {
        StringBuilder sb = new StringBuilder("[");
        int k = 0;
        for (crowdwatch.core.AutoRules.Announcement a : m.announcements()) {
            if (k++ == 30) break;
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":").append(a.id).append(",\"time\":").append(str(a.time.format(HistoryEntry.FORMAT))).append(",\"ref\":").append(str(a.ref))
              .append(",\"text\":").append(str(a.text)).append(",\"status\":").append(str(a.status)).append(",\"by\":").append(str(a.decidedBy)).append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * Health check for monitoring tools (no key needed, no venue data): "ok", "degraded" with the
     * reasons, or "fail" (HTTP 503) when no venue is loaded or saving has stopped for 5 minutes.
     */
    private String health() {
        crowdwatch.ds.LinkedList<String> problems = new crowdwatch.ds.LinkedList<>();
        boolean fail = m.venue() == null;
        if (fail) problems.addLast("no venue loaded");
        else {
            if (m.officer() == null || m.officerStatus().contains("OVERDUE")) problems.addLast("officer check-in overdue");
            int stale = staleCount();
            if (stale > 0) problems.addLast(stale + " area(s) with stale or suspect sensor data");
            int unreachable = 0;
            for (Unit u : m.unitTree().inOrder()) if (u.status == crowdwatch.model.Enums.UnitStatus.UNREACHABLE) unreachable++;
            if (unreachable > 0) problems.addLast(unreachable + " unit(s) unreachable");
            if (m.evacuationZone() != null) problems.addLast("evacuation in progress");
            java.time.LocalDateTime saved = m.lastSaved();
            if (saved != null && java.time.Duration.between(saved, m.now()).toMinutes() >= 5) { fail = true; problems.addLast("not saved for " + java.time.Duration.between(saved, m.now()).toMinutes() + " min"); }
        }
        return "{\"status\":" + str(fail ? "fail" : problems.isEmpty() ? "ok" : "degraded") + ",\"problems\":" + list(problems)
            + ",\"time\":" + str(m.now().withNano(0).toString()) + ",\"lastSaved\":" + str(m.lastSaved() == null ? null : m.lastSaved().withNano(0).toString()) + "}";
    }

    /** @return JSON for the control room: automation, officer, incidents awaiting review, evacuation and its plan */
    private String status() {
        StringBuilder sb = new StringBuilder("{\"automation\":").append(m.automation()).append(",\"officer\":").append(str(m.officerStatus()))
            .append(",\"overdue\":").append(m.officerStatus().contains("OVERDUE") || m.officer() == null).append(",\"review\":[");
        Incident[] rv = m.awaitingReview();
        for (int k = 0; k < rv.length; k++)
            sb.append(k > 0 ? "," : "").append("{\"id\":").append(rv[k].id).append(",\"area\":").append(str(rv[k].area.id))
              .append(",\"details\":").append(str(rv[k].details)).append(",\"unit\":").append(rv[k].unitId == null ? "null" : str(rv[k].unitId)).append('}');
        sb.append("],\"evacuation\":").append(str(m.evacuationZone())).append(",\"plan\":[");
        int k = 0;
        for (String line : m.evacuationPlan()) sb.append(k++ > 0 ? "," : "").append(str(line));
        return sb.append("]}").toString();
    }

    /** POST /api/automation: on=1 or on=0, with the officer's name (counts as a check-in). */
    private Result automation(AVLTree<String, String> q) {
        Result in = m.officerCheckIn(q.search("officer"));
        if (!in.ok) return in;
        boolean on = !"0".equals(q.search("on"));
        m.setAutomation(on);
        return Result.ok("Automatic dispatch switched " + (on ? "ON" : "OFF") + " by " + m.officer() + ".");
    }

    /** @return JSON array of the 50 newest alerts */
    private String alerts() {
        StringBuilder sb = new StringBuilder("[");
        int n = 0;
        for (Alert a : m.alerts()) {
            if (n++ == 50) break;
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"time\":").append(str(a.time.toString())).append(",\"kind\":").append(str(a.kind.name())).append(",\"ref\":").append(str(a.ref)).append(",\"text\":").append(str(a.text)).append('}');
        }
        return sb.append(']').toString();
    }

    /**
     * @param q parameters from, to and avoid (avoid=0 gives the shortest walk; anything else the crowd-aware route)
     * @return JSON with found, walkMinutes and path
     */
    private String route(AVLTree<String, String> q) {
        CrowdMonitor.Route r = m.route(q.search("from"), q.search("to"), !"0".equals(q.search("avoid")));
        StringBuilder sb = new StringBuilder("{\"found\":").append(r.found()).append(",\"walkMinutes\":").append(r.found() ? r.walk : -1).append(",\"path\":[");
        int i = 0;
        for (String s : r.path) sb.append(i++ > 0 ? "," : "").append(str(s));
        return sb.append("]}").toString();
    }

    /** @return the source parameter as an enum, SENSOR if missing */
    private static Source source(AVLTree<String, String> q) {
        String s = q.search("source");
        return s == null ? Source.SENSOR : Source.valueOf(s.trim().toUpperCase());
    }

    /**
     * Parses a number parameter. A leading letter is dropped so "I1001" works as an incident ID.
     *
     * @throws IllegalArgumentException if the parameter is missing or not a number (reply 400)
     */
    static int num(String s) {
        if (s == null) throw new IllegalArgumentException("missing number");
        return Integer.parseInt(s.trim().replaceAll("^[A-Za-z]", ""));
    }

    /** Switches on personal logins, device codes and the audit log (null switches them off). */
    public void setAccounts(Accounts a) { accounts = a; }

    /** @return the accounts in use, or null */
    public Accounts accounts() { return accounts; }

    private static Accounts.Who accountsWho(String name, Accounts.Role role, String unit) {
        // Who has a package-private constructor; this keeps SYSTEM a constant
        return new Accounts.Who(name, role, unit);
    }

    /** @return the token of an {@code Authorization: Bearer} header, or null */
    private static String bearer(HttpExchange ex) {
        String a = header(ex, "Authorization");
        return a != null && a.regionMatches(true, 0, "Bearer ", 0, 7) ? a.substring(7).trim() : null;
    }

    // name of the session cookie of the plain dashboard
    static final String COOKIE = "cw_session";

    /** @return the value of a cookie, or null */
    private static String cookie(HttpExchange ex, String name) {
        for (String h : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of()))
            for (String part : h.split(";")) {
                String t = part.trim();
                if (t.startsWith(name + "=")) return t.substring(name.length() + 1);
            }
        return null;
    }

    /**
     * Sign-in and sign-out of the plain dashboard (no JavaScript needed): a form post that sets or
     * clears an HttpOnly, SameSite=Strict session cookie (Secure with HTTPS) and goes back to /classic.
     */
    private void classicSign(HttpExchange ex, Accounts acc, String endpoint, AVLTree<String, String> q) throws IOException {
        String flags = "; Path=/; HttpOnly; SameSite=Strict" + (ex instanceof com.sun.net.httpserver.HttpsExchange ? "; Secure" : "");
        String back = "/classic";
        if (endpoint.equals("POST /classic/logout")) {
            if (acc != null) acc.logout(cookie(ex, COOKIE));
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Max-Age=0" + flags);
        } else if (acc != null) {
            Accounts.Login in = acc.login(q.search("user"), q.search("password"), q.search("code"), m.now());
            if (in.token != null) ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + in.token + "; Max-Age=" + Accounts.SESSION_HOURS * 3600 + flags);
            else back = "/classic?msg=" + java.net.URLEncoder.encode(in.needCode ? "Enter the code from your authenticator app" : "Wrong user name or password, or the account is locked", StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().add("Location", back);
        send(ex, 303, "text/plain", "");
    }

    /** @return who is calling: a signed-in user, a scout device code, the scout key, or null */
    private Accounts.Who identify(Accounts acc, HttpExchange ex, String given) {
        if (acc != null) {
            Accounts.Who w = acc.session(bearer(ex), m.now());
            if (w != null) return w;
            w = acc.session(cookie(ex, COOKIE), m.now());   // the plain dashboard signs in with a cookie
            if (w != null) return w;
            w = acc.device(given);
            if (w != null) return w;
        }
        if (scoutKey != null && scoutKey.equals(given)) return new Accounts.Who("scout key", null, null);
        return null;
    }

    /**
     * Checks the caller's rights for an endpoint. For a signed-in user the officer name is
     * always the user's own (a typed name is ignored), so nobody can act in someone else's
     * name. A device code may act only for its own unit.
     *
     * @return null if allowed, otherwise the reason for 403
     */
    private String permit(Accounts.Who who, String endpoint, AVLTree<String, String> q) {
        if (who.role == null) {   // scout key or device code
            if (!in(endpoint, SCOUT)) return "a scout device cannot use " + endpoint;
            if (who.unit == null) return null;
            switch (endpoint) {
                case "GET /api/unit" -> q.insert("id", who.unit);
                case "POST /api/ack", "POST /api/sos", "POST /api/foundperson", "POST /api/message", "POST /api/position", "POST /api/duty" -> { q.insert("unit", who.unit); q.delete("officer"); }
                case "GET /api/messages" -> q.insert("unit", who.unit);
                case "POST /api/onscene", "POST /api/resolve", "POST /api/found" -> {
                    Incident i;
                    synchronized (m) { i = m.findIncident(num(q.search("id"))); }
                    if (i == null || !who.unit.equalsIgnoreCase(i.unitId)) return "this device belongs to " + who.unit + " and can only act on its own tasks";
                    q.insert("unit", who.unit);
                    q.delete("officer");
                }
                default -> { }
            }
            return null;
        }
        if (who != SYSTEM) q.insert("officer", who.name);
        Accounts.Role need;
        if (in(endpoint, ADMIN_ONLY)) need = Accounts.Role.ADMIN;
        else if (in(endpoint, OFFICER_GETS)) need = Accounts.Role.OFFICER;
        else if (endpoint.startsWith("GET ")) need = Accounts.Role.OPERATOR;
        else if (in(endpoint, OPERATOR_POSTS)) need = Accounts.Role.OPERATOR;
        else need = Accounts.Role.OFFICER;
        return who.atLeast(need) ? null : "your role " + who.role + " cannot use " + endpoint + " (needs " + need + ")";
    }

    // parameters that are never written to the audit log
    private static final String[] SECRET = { "password", "key", "token", "back" };

    /** Writes one change to the audit log: who, which endpoint, its parameters, the result. */
    private void audit(Accounts.Who who, String endpoint, AVLTree<String, String> q, boolean ok, String result) {
        Accounts acc = accounts;
        if (acc == null) return;
        StringBuilder sb = new StringBuilder();
        for (String k : q.keysInOrder()) if (!in(k, SECRET)) sb.append(k).append('=').append(q.search(k)).append(' ');
        String role = who == null ? "-" : who.role != null ? who.role.name() : who.unit != null ? "DEVICE" : "SCOUT";
        acc.record(m.now(), who == null ? "?" : who.name, role, endpoint.substring(endpoint.indexOf(' ') + 1).replaceFirst("^/api/", ""), sb.toString().trim() + " -> " + result, ok);
    }

    /** POST /api/login: user and password; replies with a session token, or 401. */
    private void login(HttpExchange ex, Accounts acc, AVLTree<String, String> q) throws IOException {
        String type = "application/json";
        if (acc == null || !acc.hasUsers()) { send(ex, 404, type, err("personal logins are not switched on for this server")); return; }
        Accounts.Login in = acc.login(q.search("user"), q.search("password"), q.search("code"), m.now());
        if (in.needCode) { send(ex, 401, type, "{\"ok\":false,\"needCode\":true,\"message\":" + str(q.search("code") == null ? "enter the 6-digit code from your authenticator app" : "wrong code from the authenticator app") + "}"); return; }
        String token = in.token;
        if (token == null) { send(ex, 401, type, err("wrong user name or password, or the account is locked or switched off")); return; }
        Accounts.Who w = acc.session(token, m.now());
        send(ex, 200, type, "{\"ok\":true,\"token\":" + str(token) + ",\"name\":" + str(w.name) + ",\"role\":" + str(w.role.name())
            + ",\"hours\":" + Accounts.SESSION_HOURS + "}");
    }

    /** GET /api/me: who the caller is, and whether the server asks for a login. */
    private String me(Accounts.Who who, Accounts acc) {
        boolean logins = acc != null && acc.hasUsers();
        if (who == null) return "{\"signedIn\":false,\"logins\":" + logins + ",\"keyRequired\":" + (apiKey != null) + "}";
        String role = who.role != null ? who.role.name() : who.unit != null ? "DEVICE" : "SCOUT";
        return "{\"signedIn\":true,\"logins\":" + logins + ",\"name\":" + str(who.name) + ",\"role\":" + str(role) + ",\"unit\":" + str(who.unit)
            + ",\"system\":" + (who == SYSTEM) + ",\"twoStep\":" + (acc != null && acc.user(who.name) != null && acc.user(who.name).twoStep()) + "}";
    }

    /**
     * User accounts (admin), device codes (officer) and the audit log (officer).
     *
     * @return { HTTP code, JSON body }
     */
    private String[] accountsEndpoint(Accounts acc, Accounts.Who who, String endpoint, AVLTree<String, String> q) {
        String done;
        switch (endpoint) {
            case "GET /api/users" -> {
                StringBuilder sb = new StringBuilder("[");
                for (Accounts.User u : acc.users()) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append("{\"name\":").append(str(u.name)).append(",\"role\":").append(str(u.role.name())).append(",\"active\":").append(u.active).append(",\"twoStep\":").append(u.twoStep()).append(",\"directory\":").append(acc.isDirectoryUser(u.name)).append('}');
                }
                return new String[] { "200", sb.append(']').toString() };
            }
            case "POST /api/users" -> {
                String name = q.search("name");
                String pw = q.search("password");
                if (pw != null && pw.isEmpty()) pw = null;
                Accounts.Role role;
                try { role = Accounts.Role.valueOf(String.valueOf(q.search("role")).trim().toUpperCase()); }
                catch (IllegalArgumentException e) { role = acc.user(name) == null ? null : acc.user(name).role; }
                done = "1".equals(q.search("directory")) ? acc.setDirectoryUser(name, role) : acc.setUser(name, role, pw);
                if (done == null && "off".equals(q.search("twostep"))) done = acc.stopTwoStep(name);
                if (done == null && q.search("active") != null) done = acc.setActive(name, !"0".equals(q.search("active")) && !"false".equals(q.search("active")));
                if (done == null) done = "OK: user " + name + " saved as " + acc.user(name).role + (acc.user(name).active ? "" : " (switched off)");
            }
            case "POST /api/devices/code" -> {
                String unit;
                synchronized (m) { Unit u = m.findUnit(q.search("unit")); unit = u == null ? null : u.id; }
                if (unit == null) done = "unknown unit " + q.search("unit");
                else {
                    String code = acc.issueDevice(unit);
                    audit(who, endpoint, q, true, "new device code issued for " + unit);
                    return new String[] { "200", "{\"ok\":true,\"unit\":" + str(unit) + ",\"code\":" + str(code) + ",\"message\":" + str("Device code for " + unit + ": " + code + " (the unit's earlier code no longer works)") + "}" };
                }
            }
            case "POST /api/twostep/start" -> {
                String secret = acc.startTwoStep(who.name);
                if (secret == null) return new String[] { "422", err("two-step sign-in is for personal users only") };
                return new String[] { "200", "{\"ok\":true,\"secret\":" + str(secret) + ",\"link\":" + str(Accounts.twoStepLink(who.name, secret))
                    + ",\"message\":" + str("Add CrowdWatch to your authenticator app with this key, then enter the code it shows") + "}" };
            }
            case "POST /api/twostep/confirm" -> {
                String r = acc.confirmTwoStep(who.name, q.search("code"), m.now());
                done = r == null ? "OK: two-step sign-in is on for " + who.name + "; from now on a sign-in also needs the app's code" : r;
            }
            case "POST /api/twostep/off" -> {
                Accounts.User u = acc.user(who.name);
                if (u == null || !u.twoStep()) done = "two-step sign-in is not on";
                else if (!Totp.verify(u.totp, q.search("code"), m.now())) done = "enter the current code from your app to switch it off";
                else { String r = acc.stopTwoStep(who.name); done = r == null ? "OK: two-step sign-in switched off for " + who.name : r; }
            }
            case "POST /api/users/reset" -> {
                String code = acc.issueReset(q.search("name"), m.now());
                if (code == null) done = "no such user, or the user signs in with the company directory";
                else {
                    audit(who, endpoint, q, true, "reset code issued for " + q.search("name"));
                    return new String[] { "200", "{\"ok\":true,\"code\":" + str(code) + ",\"minutes\":" + Accounts.RESET_MINUTES + ",\"message\":" + str("Reset code for " + q.search("name") + ": " + code
                        + " - valid " + Accounts.RESET_MINUTES + " minutes. Give it to the user in person or by phone; they choose a new password with it on the sign-in page.") + "}" };
                }
            }
            case "POST /api/devices/revoke" -> done = acc.revokeDevice(String.valueOf(q.search("unit"))) ? "OK: the device code of " + q.search("unit") + " was cancelled" : "unit " + q.search("unit") + " has no device code";
            case "GET /api/audit" -> {
                StringBuilder sb = new StringBuilder("[");
                for (Accounts.Entry e : acc.recentAudit(200)) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append("{\"time\":").append(str(e.at.withNano(0).toString())).append(",\"user\":").append(str(e.user)).append(",\"role\":").append(str(e.role))
                      .append(",\"action\":").append(str(e.action)).append(",\"summary\":").append(str(e.summary)).append(",\"ok\":").append(e.ok).append('}');
                }
                return new String[] { "200", sb.append(']').toString() };
            }
            default -> { return new String[] { "404", err("unknown endpoint " + endpoint) }; }
        }
        boolean ok = done.startsWith("OK");
        audit(who, endpoint, q, ok, done);
        return new String[] { ok ? "200" : "422", "{\"ok\":" + ok + ",\"message\":" + str(ok ? done.substring(4) : done) + "}" };
    }

    /** @return the first value of a request header, or null */
    private static String header(HttpExchange ex, String name) { return ex.getRequestHeaders().getFirst(name); }

    /**
     * Parses URL-encoded parameters ({@code a=1&b=2}) into an AVL tree. A key without '=' gets an empty value.
     */
    static AVLTree<String, String> params(String raw) {
        AVLTree<String, String> p = new AVLTree<>();
        if (raw == null || raw.isEmpty()) return p;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            if (!k.isEmpty()) p.insert(k, v);
        }
        return p;
    }

    /**
     * Writes a string as a JSON string literal, escaping quotes, backslashes and control characters.
     * @return the quoted string, or null (unquoted) for a null value
     */
    static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    /** @return a JSON error object */
    private static String err(String msg) { return "{\"ok\":false,\"message\":" + str(msg) + "}"; }

    /**
     * Sends the response. A 303 redirect has no body. CORS is open so other web pages can call the API.
     */
    private static void sendBytes(HttpExchange ex, int code, String type, byte[] b) throws IOException {
        if (ex instanceof com.sun.net.httpserver.HttpsExchange) ex.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        ex.close();
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (ex instanceof com.sun.net.httpserver.HttpsExchange) ex.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, code == 303 ? -1 : b.length);
        if (code != 303) try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        ex.close();
    }

}
