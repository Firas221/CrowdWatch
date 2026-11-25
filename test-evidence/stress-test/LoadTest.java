import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Load generator for the CrowdWatch headless server. Usage: LoadTest <port> <apiKey> <serverPid> <phase...> */
public class LoadTest {
    static String base, key, scoutKey = System.getProperty("scoutKey");
    static long pid;
    static final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(5)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    static final Random rnd = new Random(42);
    static final int AREAS = 20;
    static final String[] CATS = { "LOST_PERSON", "MEDICAL", "CROWD", "WHEELCHAIR", "INFO" };

    // ---------------------------------------------------------------- statistics
    static final Map<String, List<Long>> lat = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> errs = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> codes = new ConcurrentHashMap<>();
    static void reset() { lat.clear(); errs.clear(); codes.clear(); }
    static void rec(String ep, long micros) { lat.computeIfAbsent(ep, k -> Collections.synchronizedList(new ArrayList<>())).add(micros); }
    static void err(String ep, String why) { errs.computeIfAbsent(ep + " " + why, k -> new AtomicInteger()).incrementAndGet(); }

    static String area() { return String.format("A%02d-%02d", rnd.nextInt(AREAS), rnd.nextInt(AREAS)); }
    static String unit() { return String.format("U%03d", rnd.nextInt(250)); }

    static String get(String ep, String path, int timeoutSec) { return getAs(ep, path, timeoutSec, key); }
    static String getAs(String ep, String path, int timeoutSec, String k) {
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("X-Api-Key", k).timeout(Duration.ofSeconds(timeoutSec)).build(), HttpResponse.BodyHandlers.ofString());
            rec(ep, (System.nanoTime() - t0) / 1000);
            if (r.statusCode() != 200) err(ep, "HTTP " + r.statusCode());
            return r.body();
        } catch (Exception e) { err(ep, e.getClass().getSimpleName() + ": " + e.getMessage()); return null; }
    }
    static String post(String ep, String path, String body, int timeoutSec) { return postAs(ep, path, body, timeoutSec, key); }
    static String postAs(String ep, String path, String body, int timeoutSec, String k) {
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-Api-Key", k)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            rec(ep, (System.nanoTime() - t0) / 1000);
            if (r.statusCode() != 200 && r.statusCode() != 422) err(ep, "HTTP " + r.statusCode());
            return r.body();
        } catch (Exception e) { err(ep, e.getClass().getSimpleName() + ": " + e.getMessage()); return null; }
    }
    static String enc(String s) { return URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

    static long pct(List<Long> l, double p) { return l.get(Math.min(l.size() - 1, (int) Math.ceil(p * l.size()) - 1)); }
    static void report(String title, double seconds) {
        System.out.println("\n== " + title + " ==");
        System.out.printf("%-22s %8s %8s %8s %8s %8s %9s%n", "endpoint", "count", "req/s", "p50 ms", "p95 ms", "p99 ms", "max ms");
        long total = 0;
        for (String ep : new TreeSet<>(lat.keySet())) {
            List<Long> l = new ArrayList<>(lat.get(ep));
            Collections.sort(l);
            total += l.size();
            System.out.printf("%-22s %8d %8.1f %8.1f %8.1f %8.1f %9.1f%n", ep, l.size(), l.size() / seconds, pct(l, .5) / 1000.0, pct(l, .95) / 1000.0, pct(l, .99) / 1000.0, l.get(l.size() - 1) / 1000.0);
        }
        System.out.printf("total %d requests, %.0f req/s%n", total, total / seconds);
        if (errs.isEmpty()) System.out.println("errors: none");
        else errs.forEach((k, v) -> System.out.println("ERROR " + k + ": " + v.get()));
    }

    // ---------------------------------------------------------------- server process metrics
    static long cpuTicks() {
        try { String[] f = Files.readString(Path.of("/proc/" + pid + "/stat")).split("\\) ")[1].split(" "); return Long.parseLong(f[11]) + Long.parseLong(f[12]); }
        catch (Exception e) { return -1; }
    }
    static String rss() {
        try { for (String l : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) if (l.startsWith("VmRSS") || l.startsWith("Threads")) System.out.print("");
              String s = ""; for (String l : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) if (l.startsWith("VmRSS")) s += l.replaceAll("\\s+", " ") + " "; else if (l.startsWith("Threads")) s += l.replaceAll("\\s+", " ");
              return s; }
        catch (Exception e) { return "?"; }
    }
    static void serverLine(String label, long ticks0, double seconds) {
        long t = cpuTicks();
        System.out.printf("server %s: CPU %.0f%% of one core (avg), %s%n", label, (t - ticks0) / seconds, rss());
    }

    // ---------------------------------------------------------------- phases
    public static void main(String[] a) throws Exception {
        base = "http://127.0.0.1:" + a[0];
        key = a[1];
        pid = Long.parseLong(a[2]);
        for (int i = 3; i < a.length; i++) {
            switch (a[i]) {
                case "baseline" -> baseline();
                case "venue" -> venue(90);
                case "ramp" -> ramp();
                case "slow" -> slow();
                case "growth" -> growth();
                case "flood" -> flood();
            }
        }
        System.exit(0);
    }

    static void baseline() {
        reset();
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            get("GET /api/areas", "/api/areas", 10);
            get("GET /api/summary", "/api/summary", 10);
            get("GET /api/unit", "/api/unit?id=" + unit(), 10);
            get("GET /api/status", "/api/status", 10);
            get("GET /", "/", 10);
            post("POST /api/occupancy", "/api/occupancy", "area=" + area() + "&delta=1", 10);
        }
        report("BASELINE - one client, one request at a time", (System.nanoTime() - t0) / 1e9);
    }

    /** A busy venue: 250 scout devices, 20 dashboards, 400 sensors, incident reports and an officer. */
    static void venue(int seconds) throws Exception {
        reset();
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        AtomicInteger acks = new AtomicInteger(), resolves = new AtomicInteger(), reports = new AtomicInteger(), reviews = new AtomicInteger(), sos = new AtomicInteger();
        for (int u = 0; u < 250; u++) {
            String id = String.format("U%03d", u);
            ex.submit(() -> {
                Random r = new Random(id.hashCode());
                long onSceneAt = 0;
                try { Thread.sleep(r.nextInt(3000)); } catch (InterruptedException e) { return; }
                while (!stop.get()) {
                    String js = getAs("GET /api/unit (scout)", "/api/unit?id=" + id, 10, scoutKey);
                    if (js != null && js.contains("\"task\":{")) {
                        String task = js.substring(js.indexOf("\"task\":{"));
                        String tid = task.replaceAll("(?s)^\"task\":\\{\"id\":(\\d+).*", "$1");
                        if (task.contains("\"acknowledged\":false")) { postAs("POST /api/ack", "/api/ack", "unit=" + id, 10, scoutKey); acks.incrementAndGet(); }
                        else if (task.contains("\"status\":\"ASSIGNED\"")) { postAs("POST /api/onscene", "/api/onscene", "id=" + tid, 10, scoutKey); onSceneAt = System.currentTimeMillis(); }
                        else if (task.contains("\"status\":\"ON_SCENE\"") && System.currentTimeMillis() - onSceneAt > 6000) {
                            postAs("POST /api/resolve", "/api/resolve", "id=" + tid + "&note=done", 10, scoutKey); resolves.incrementAndGet(); }
                    }
                    if (r.nextInt(2000) == 0) { postAs("POST /api/sos", "/api/sos", "unit=" + id + "&text=stress", 10, scoutKey); sos.incrementAndGet(); }
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                }
            });
        }
        for (int d = 0; d < 20; d++) ex.submit(() -> {
            while (!stop.get()) {
                get("GET /api/summary", "/api/summary", 10); get("GET /api/areas", "/api/areas", 10); get("GET /api/incidents", "/api/incidents", 10);
                get("GET /api/alerts", "/api/alerts", 10); get("GET /api/status", "/api/status", 10);
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        });
        for (int s = 0; s < 40; s++) {
            int first = s * 10;
            ex.submit(() -> {
                Random r = new Random(first);
                while (!stop.get()) {
                    for (int k = first; k < first + 10; k++)
                        post("POST /api/occupancy", "/api/occupancy", "area=" + String.format("A%02d-%02d", k / AREAS, k % AREAS) + "&delta=" + (r.nextInt(23) - 10), 10);
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
            });
        }
        ex.submit(() -> {
            Random r = new Random(1);
            while (!stop.get()) {
                post("POST /api/incidents", "/api/incidents", "category=" + CATS[r.nextInt(CATS.length)] + "&area=" + area() + "&details=stress+test&language=English", 10);
                reports.incrementAndGet();
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        });
        ex.submit(() -> {
            while (!stop.get()) {
                post("POST /api/checkin", "/api/checkin", "officer=Officer+Load", 10);
                String st = get("GET /api/status", "/api/status", 10);
                if (st != null) for (String part : st.split("\\{\"id\":")) {
                    if (!Character.isDigit(part.isEmpty() ? 'x' : part.charAt(0))) continue;
                    post("POST /api/review", "/api/review", "id=" + part.replaceAll("^(\\d+).*", "$1") + "&officer=Officer+Load&decision=confirm", 10);
                    reviews.incrementAndGet();
                }
                try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
            }
        });
        long ticks0 = cpuTicks();
        long t0 = System.nanoTime();
        for (int i = 0; i < seconds / 15; i++) { Thread.sleep(15000); System.out.println("  t=" + (i + 1) * 15 + "s  " + rss()); }
        stop.set(true);
        ex.shutdown();
        ex.awaitTermination(30, TimeUnit.SECONDS);
        double secs = (System.nanoTime() - t0) / 1e9;
        report("REALISTIC BUSY VENUE - 250 scout devices, 20 dashboards, 400 sensors (1/s each), 2 reports/s, officer", secs);
        System.out.printf("scouts: %d acks, %d resolved, %d SOS; %d incidents reported, %d automatic dispatches reviewed%n", acks.get(), resolves.get(), sos.get(), reports.get(), reviews.get());
        serverLine("during phase", ticks0, secs);
        System.out.println("summary after phase:\n" + get("GET /api/summary", "/api/summary", 10));
    }

    /** Closed-loop clients hammering a mixed workload, ramping up. */
    static void ramp() throws Exception {
        for (int n : (System.getProperty("steps") != null ? java.util.Arrays.stream(System.getProperty("steps").split(",")).mapToInt(Integer::parseInt).toArray() : new int[] { 16, 64, 256, 1024 })) {
            reset();
            AtomicBoolean stop = new AtomicBoolean();
            ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
            for (int c = 0; c < n; c++) ex.submit(() -> {
                Random r = new Random();
                while (!stop.get()) {
                    switch (r.nextInt(4)) {
                        case 0 -> get("GET /api/areas", "/api/areas", 15);
                        case 1 -> get("GET /api/unit", "/api/unit?id=" + String.format("U%03d", r.nextInt(250)), 15);
                        case 2 -> get("GET /api/summary", "/api/summary", 15);
                        default -> post("POST /api/occupancy", "/api/occupancy", "area=" + String.format("A%02d-%02d", r.nextInt(AREAS), r.nextInt(AREAS)) + "&delta=" + (r.nextInt(21) - 10), 15);
                    }
                }
            });
            long ticks0 = cpuTicks();
            long t0 = System.nanoTime();
            Thread.sleep(15000);
            stop.set(true);
            ex.shutdown();
            ex.awaitTermination(30, TimeUnit.SECONDS);
            double secs = (System.nanoTime() - t0) / 1e9;
            report("RAMP - " + n + " clients sending requests back to back", secs);
            serverLine("during step", ticks0, secs);
        }
    }

    /** Clients that open a connection and send the request very slowly. */
    static void slow() throws Exception {
        for (int n : new int[] { 4, 50, 128, 300, 600 }) {
            List<Socket> socks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Socket s = new Socket(Proxy.NO_PROXY);
                s.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(base.substring(base.lastIndexOf(':') + 1))));
                s.getOutputStream().write("GET /api/areas HTTP/1.1\r\nHost: x\r\n".getBytes());   // never finishes the headers
                socks.add(s);
            }
            Thread.sleep(1000);
            reset();
            long t0 = System.nanoTime();
            String r = get("GET /api/summary", "/api/summary", 8);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("\n== SLOW CLIENTS: " + n + " connections sending half a request and waiting ==");
            System.out.println("normal request: " + (r == null ? "FAILED after " + ms + " ms " + errs.keySet() : "answered in " + ms + " ms"));
            for (Socket s : socks) s.close();
            Thread.sleep(1500);
        }
    }

    /** Many incidents over time: how do request times and memory change? */
    static void growth() throws Exception {
        int[] targets = { 2000, 10000, 30000 };
        int done = 0;
        for (int target : targets) {
            ExecutorService ex = Executors.newFixedThreadPool(16);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = done; i < target; i++) fs.add(ex.submit(() -> {
                String r = post("POST /api/incidents", "/api/incidents", "category=INFO&area=" + area() + "&details=history+" + rnd.nextInt(), 30);
                if (r != null && r.contains("recorded")) {
                    String id = r.replaceAll(".*\"I(\\d+) recorded.*", "$1");
                    post("POST /api/resolve", "/api/resolve", "id=" + id + "&note=closed", 30);
                }
            }));
            long t0 = System.nanoTime();
            for (Future<?> f : fs) f.get();
            ex.shutdown();
            double fill = (System.nanoTime() - t0) / 1e9;
            done = target;
            reset();
            long t1 = System.nanoTime();
            for (int i = 0; i < 30; i++) {
                get("GET /api/summary", "/api/summary", 30);
                get("GET /api/unit", "/api/unit?id=" + unit(), 30);
                get("GET /api/incidents", "/api/incidents", 30);
                get("GET /api/status", "/api/status", 30);
                post("POST /api/occupancy", "/api/occupancy", "area=" + area() + "&delta=1", 30);
                post("POST /api/incidents", "/api/incidents", "category=INFO&area=" + area() + "&details=probe", 30);
            }
            report("GROWTH - after " + target + " incidents in the system (filled at " + Math.round((target - (target == 2000 ? 0 : target == 10000 ? 2000 : 10000)) / fill) + " incidents/s)", (System.nanoTime() - t1) / 1e9);
            System.out.println("server: " + rss());
        }
    }

    /** Thousands of idle connections. */
    static void flood() throws Exception {
        List<Socket> socks = new ArrayList<>();
        int port = Integer.parseInt(base.substring(base.lastIndexOf(':') + 1));
        int opened = 0;
        String fail = "";
        for (int i = 0; i < 5000; i++) {
            try { Socket s = new Socket(Proxy.NO_PROXY); s.connect(new InetSocketAddress("127.0.0.1", port), 2000); socks.add(s); opened++; }
            catch (Exception e) { fail = e.toString(); break; }
        }
        Thread.sleep(2000);
        reset();
        long t0 = System.nanoTime();
        String r = get("GET /api/summary", "/api/summary", 10);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("\n== IDLE CONNECTION FLOOD ==");
        System.out.println("opened " + opened + " idle connections" + (fail.isEmpty() ? "" : " (then: " + fail + ")") + "; normal request: " + (r == null ? "FAILED " + errs.keySet() : "answered in " + ms + " ms") + "; server " + rss());
        for (Socket s : socks) try { s.close(); } catch (Exception ignored) { }
    }
}
