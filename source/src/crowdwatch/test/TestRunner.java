package crowdwatch.test;

import java.time.LocalDateTime;
import crowdwatch.core.*;
import crowdwatch.db.Database;
import crowdwatch.ds.*;
import crowdwatch.feed.CsvFeed;
import crowdwatch.feed.SimulatedFeed;
import crowdwatch.model.*;
import crowdwatch.model.Enums.*;

/**
 * Runs all 47 test cases and prints the evidence: for each case the expected and actual
 * result, PASS or FAIL, and traces of the data structures at work.
 * <p>
 * A fixed clock (1 December 2026, 09:00) is used, and {@link #later} moves it forward, so
 * time-based rules such as the trend warning give the same result on every run. The program
 * exits with code 1 if any case fails.
 * <p>
 * Parts: A data structures, B airport rules, C stadium, Umrah and a mall on the same code,
 * D network API, E Derby database (including saving under load and a second program on the same
 * database), F real-world robustness (turnstile-rate trend, unit positions, alert limit, slow
 * listeners, input limits). The database part starts a second Java process for TC-41.
 */
public class TestRunner {
    // counts of passed and failed cases, and the number of the last case
    private static int passed, failed, n;
    // the fixed clock's current time
    private static LocalDateTime time;

    /** Compares a result with the expected text, counts it and prints the case. */
    private static void check(String title, String expected, Object actual) {
        String a = String.valueOf(actual);
        boolean ok = expected.equals(a);
        if (ok) passed++; else failed++;
        System.out.printf("TC-%02d  %s%n  Expected: %s%n  Actual:   %s%n  Result:   %s%n%n", ++n, title, expected, a, ok ? "PASS" : "FAIL");
    }

    /** Prints extra evidence (for example an AVL search path) under a case. */
    private static void trace(String title, Object lines) {
        System.out.println("  Trace - " + title + ":");
        if (lines instanceof LinkedList<?> l) for (Object o : l) System.out.println("    " + o);
        else for (String s : String.valueOf(lines).split("\\n")) System.out.println("    " + s);
    }

    /** Resets the clock and returns a monitor loaded with a built-in venue and its sample data. */
    private static CrowdMonitor venue(String code) {
        time = LocalDateTime.of(2026, 12, 1, 9, 0);
        CrowdMonitor m = new CrowdMonitor(() -> time);
        m.setAutomation(false);   // parts A-F test the rules one at a time; part G tests automation
        m.loadVenue(VenueProfile.builtIn(code), true);
        return m;
    }

    /** Like {@link #venue} but with automatic dispatch switched on (part G). */
    private static CrowdMonitor autoVenue(String code) {
        time = LocalDateTime.of(2026, 12, 1, 9, 0);
        CrowdMonitor m = new CrowdMonitor(() -> time);
        m.loadVenue(VenueProfile.builtIn(code), true);
        return m;
    }

    /** @return the units (other than the one assigned) that got an SOS_NEARBY message for an incident, e.g. " A02 A05" */
    private static String nearby(CrowdMonitor m, int incident) {
        StringBuilder sb = new StringBuilder();
        for (Unit u : m.unitTree().inOrder())
            for (int k = 0; k < u.inbox.size(); k++)
                if (u.inbox.get(k).kind == MessageKind.SOS_NEARBY && u.inbox.get(k).incidentId == incident) { sb.append(' ').append(u.id); break; }
        return sb.toString();
    }

    /** @return how many units have a message containing the given text */
    private static int told(CrowdMonitor m, String text) {
        int n = 0;
        for (Unit u : m.unitTree().inOrder())
            for (int k = 0; k < u.inbox.size(); k++) if (u.inbox.get(k).text.contains(text)) { n++; break; }
        return n;
    }

    /** @return how many alerts of a kind the monitor holds */
    private static int countAlerts(CrowdMonitor m, AlertKind kind) {
        int n = 0;
        for (Alert a : m.alerts()) if (a.kind == kind) n++;
        return n;
    }

    /** Moves the fixed clock forward. */
    private static void later(int minutes) { time = time.plusMinutes(minutes); }

    /**
     * @return the unit ID from an "assigned to X" message, "QUEUED" if the incident waits, otherwise the message
     */
    private static String assigned(Result r) {
        String s = r.message;
        int k = s.indexOf("assigned to ");
        return k < 0 ? (s.contains("waits") ? "QUEUED" : s) : s.substring(k + 12).split(" ")[0];
    }

    /** Runs every test case in order and prints the totals. */
    public static void main(String[] args) throws Exception {
        System.out.println("CROWDWATCH - TEST EVIDENCE AND TRACES");
        System.out.println("=====================================\n");
        System.out.println("PART A - DATA STRUCTURES\n");

        AVLTree<String, String> bst = new AVLTree<>();
        for (String k : new String[] { "M", "D", "T", "B", "F", "R", "W" }) bst.insert(k, k);
        trace("AVL shape", bst.shape());
        bst.search("F");
        trace("search(F)", bst.lastPath());
        check("AVL search and in-order traversal", "[B -> D -> F -> M -> R -> T -> W]", bst.keysInOrder());
        bst.delete("M");
        trace("delete(M) - two children", bst.lastPath());
        check("AVL delete of the root keeps the order", "[B -> D -> F -> R -> T -> W] height 3", bst.keysInOrder() + " height " + bst.height());

        AVLTree<Integer, Integer> seq = new AVLTree<>();
        for (int k = 1001; k <= 2000; k++) seq.insert(k, k);
        AVLTree<Integer, Integer> small = new AVLTree<>();
        for (int k = 1; k <= 7; k++) small.insert(k, k);
        trace("AVL after inserting 1..7 in order (a plain BST would be a 7-node chain)", small.shape());
        check("1000 sequential incident IDs stay balanced (plain BST height would be 1000)", "height 10, search path 10 nodes or fewer",
            "height " + seq.height() + ", search path " + (seq.search(2000) != null && seq.lastPath().size() <= 10 ? "10 nodes or fewer" : seq.lastPath().size() + " nodes"));
        for (int k = 1001; k <= 1900; k++) seq.delete(k);
        check("AVL stays balanced after 900 deletions", "100 keys, height 7", seq.size() + " keys, height " + seq.height());

        CircularBuffer<Integer> ring = new CircularBuffer<>(3);
        for (int k = 1; k <= 5; k++) ring.add(k);
        check("Circular buffer keeps the newest readings and overwrites the oldest", "[3, 4, 5]", ring);

        Queue<String> q = new Queue<>();
        q.enqueue("I1"); q.enqueue("I2"); q.enqueue("I3");
        String first = q.dequeue();
        q.remove("I3");
        check("Queue is FIFO and supports removal of a cancelled item", "I1 | front [I2] rear", first + " | " + q);

        MinHeap<Integer> heap = new MinHeap<>(Integer::compare);
        for (int v : new int[] { 42, 7, 19, 3, 25, 11 }) heap.insert(v);
        StringBuilder polled = new StringBuilder();
        while (!heap.isEmpty()) polled.append(heap.poll()).append(' ');
        check("Min-heap polls in ascending order", "3 7 11 19 25 42", polled.toString().trim());

        String[][] people = { { "C", "2" }, { "A", "1" }, { "D", "2" }, { "B", "1" } };
        LinkedList<String> mt = new LinkedList<>();
        MergeSort.sort(people, (x, y) -> x[1].compareTo(y[1]), mt);
        StringBuilder order = new StringBuilder();
        for (String[] p : people) order.append(p[0]);
        check("Merge sort is stable (equal keys keep input order)", "ABCD", order);

        Graph g = new Graph();
        for (String v : new String[] { "S", "A", "B", "T" }) g.addNode(v);
        g.connect("S", "A", 1); g.connect("A", "T", 6); g.connect("S", "B", 2); g.connect("B", "T", 2);
        Graph.Paths gp = g.shortestPaths("S");
        trace("Dijkstra visit order from S", gp.visitOrder);
        check("Dijkstra finds the cheaper two-hop path", "[S -> B -> T] cost 4", gp.pathTo("T") + " cost " + gp.distanceTo("T"));

        System.out.println("PART B - AIRPORT PROFILE\n");
        CrowdMonitor m = venue("airport");
        check("Airport profile loads areas, links, categories and units", "16 areas, 19 links, 6 categories, 8 units",
            m.areaTree().size() + " areas, " + m.map().edgeCount() + " links, " + m.categoryTree().size() + " categories, " + m.unitTree().size() + " units");

        Result r = m.updateOccupancy("SEC-1", 432, Source.SENSOR);
        check("Occupancy 432/450 (96%) at security is CRITICAL and raises an alert", "CRITICAL CROWD", m.findArea("SEC-1").level + " " + m.alerts().getFirst().kind);

        check("Lost person at GATE-B goes to the nearest unit with the lost-person skill", "A05",
            assigned(m.reportIncident("LOST_PERSON", "GATE-B", "Boy, 7, red cap", "+20 100 000 0000", "English")));
        check("Medical at GATE-A goes to the nearest first-aid unit", "A03", assigned(m.reportIncident("MEDICAL", "GATE-A", "Fainted passenger", "", null)));

        check("Restricted area refused without approval", "false", m.moveUnit("A02", "APRON", false).ok);
        check("Restricted area allowed with approval", "true", m.moveUnit("A02", "APRON", true).ok);
        m.moveUnit("A02", "SEC-1", true);

        m.setUnitStatus("A04", UnitStatus.OFF_DUTY);
        m.setUnitStatus("A07", UnitStatus.OFF_DUTY);
        r = m.reportIncident("PRM", "GATE-C", "Wheelchair to gate C4", "", "German");
        r = m.reportIncident("SECURITY", "SEC-2", "Unattended bag", "", null);
        String bag = assigned(r);
        r = m.reportIncident("SECURITY", "CHECKIN-2", "Second bag", "", null);
        check("Third concurrent need with no free unit waits in its priority queue", "PRM->A08, bag->A02, second bag QUEUED",
            "PRM->" + m.findIncident(1003).unitId + ", bag->" + bag + ", second bag " + assigned(r));
        trace("queues", "CRITICAL " + m.queue(Priority.CRITICAL) + "\nNORMAL   " + m.queue(Priority.NORMAL));

        m.setUnitStatus("A08", UnitStatus.OFF_DUTY);
        later(5);
        r = m.reportIncident("INFO", "GATE-C", "Where is prayer room", "", null);
        m.setUnitStatus("A06", UnitStatus.OFF_DUTY);
        m.setUnitStatus("A01", UnitStatus.OFF_DUTY);
        Incident info = m.findIncident(1006);
        check("Low-priority request with any unit free is still assigned", "ASSIGNED", info.status);

        m.resolve(1006, "Directions given");
        m.setUnitStatus("A07", UnitStatus.AVAILABLE);
        check("Freed unit serves the CRITICAL queue first", "I1005 ASSIGNED to A07", "I1005 " + m.findIncident(1005).status + " to " + m.findIncident(1005).unitId);

        check("Unit with an open incident cannot go off duty", "false", m.setUnitStatus("A05", UnitStatus.OFF_DUTY).ok);
        check("On scene is refused for a resolved incident", "false", m.confirmOnScene(1006).ok);
        m.confirmOnScene(1001);
        later(18);
        r = m.resolve(1001, "Reunited with mother");
        Incident lost = m.findIncident(1001);
        check("Resolving a personal-data incident erases details and contact", Incident.ERASED + " / " + Incident.ERASED + " / 23 min",
            lost.details + " / " + lost.contact + " / " + lost.minutesOpen(time) + " min");
        trace("incident I1001 history (linked list)", lost.history);

        for (Unit u : m.unitTree().inOrder()) if (u.status == UnitStatus.AVAILABLE) m.setUnitStatus(u.id, UnitStatus.OFF_DUTY);
        r = m.reportIncident("INFO", "TRAIN", "Lift broken?", "", null);
        int waiting = m.nextIncidentId() - 1;
        m.escalate(waiting);
        m.escalate(waiting);
        check("Escalation moves a waiting incident up the queues", "HIGH [I" + waiting + "]", m.findIncident(waiting).priority + " " + m.queue(Priority.HIGH).toString().replace("front ", "").replace(" rear", ""));

        CrowdMonitor rt = venue("airport");
        CrowdMonitor.Route plain = rt.route("CHECKIN-1", "GATE-C", false);
        check("Shortest public route never passes the restricted apron", "[CHECKIN-1 -> SEC-1 -> PASS-1 -> DUTYFREE-1 -> GATE-B -> GATE-C] 20 min", plain.path + " " + plain.walk + " min");
        rt.updateOccupancy("SEC-1", 440, Source.SENSOR);
        CrowdMonitor.Route smart = rt.route("CHECKIN-1", "GATE-C", true);
        trace("crowd-aware Dijkstra visit order", smart.visitOrder);
        check("Crowd-aware route avoids the CRITICAL checkpoint", "[CHECKIN-1 -> CURB-1 -> ARRIVALS-1 -> TRAIN -> CURB-2 -> CHECKIN-2 -> SEC-2 -> GATE-C] 22 min", smart.path + " " + smart.walk + " min");

        CsvFeed csv = new CsvFeed(java.nio.file.Path.of("unused.csv"));
        int ok = csv.apply(rt, "area,count,source\nGATE-A,1500,SENSOR\nGATE-B,abc\nNOPE,10\nTRAIN,200\n");
        check("CSV adapter applies valid rows and logs bad ones", "2 applied, 2 problems, GATE-A HIGH", ok + " applied, " + csv.log().size() + " problems, GATE-A " + rt.findArea("GATE-A").level);

        int[] seen = { 0 };
        rt.addListener(new MonitorListener() { public void onAlert(Alert a) { seen[0]++; } });
        rt.updateOccupancy("GATE-B", 1190, Source.SENSOR);
        rt.updateOccupancy("GATE-B", 100, Source.STAFF);   // a staff count: a sensor dropping 1,090 people at once would be flagged as suspect
        rt.awaitListeners(2000);   // listeners run on the event thread
        check("External listener receives the alert and the all-clear", "2", seen[0]);

        int before = 0, after = 0;
        for (Area a : rt.areaTree().inOrder()) before += a.occupancy;
        new SimulatedFeed(1000, 42).tick(rt);
        boolean sim = false;
        for (Area a : rt.areaTree().inOrder()) { after += a.occupancy; if (a.source == Source.SIMULATION) sim = true; }
        check("Simulated sensor tick updates areas through the same API", "true", sim && before != after);

        CrowdMonitor tr = venue("airport");
        later(5);
        tr.updateOccupancy("GATE-A", 1100, Source.SENSOR);
        boolean early = false;
        for (Alert a : tr.alerts()) if (a.kind == AlertKind.TREND) early = true;
        later(2);
        Result tw = tr.updateOccupancy("GATE-A", 1200, Source.SENSOR);
        Area ga = tr.findArea("GATE-A");
        trace("GATE-A readings (circular buffer)", ga.readings.toString());
        check("Trend warning: GATE-A at 75% but rising ~43/min -> CRITICAL in about 8 min, before the HIGH alert",
            "no warning at 10.5 min, TREND at 8 min, level MODERATE", (early ? "warned early" : "no warning at 10.5 min") + ", " + tr.alerts().getFirst().kind + " at " + tr.minutesToCritical(ga) + " min, level " + ga.level);

        CrowdMonitor dc = venue("airport");
        for (int k = 0; k < 5; k++) { dc.reportIncident("INFO", "TRAIN", "q" + k, "", null); dc.resolve(dc.nextIncidentId() - 1, ""); }
        int misses = dc.cacheMisses();
        check("Distance cache: Dijkstra runs once per area, later dispatches reuse it", "1 miss, hits > 0", (misses == 1 ? "1 miss" : misses + " misses") + ", hits " + (dc.cacheHits() > 0 ? "> 0" : "0"));
        dc.connect("TRAIN", "GATE-C", 6);
        dc.reportIncident("INFO", "TRAIN", "after new link", "", null);
        check("Cache is rebuilt when the layout changes", "2", dc.cacheMisses());

        System.out.println("PART C - SAME CODE, OTHER VENUES\n");
        CrowdMonitor st = venue("stadium");
        check("Stadium: lost child in the East Stand goes to the nearest steward team", "S01", assigned(st.reportIncident("LOST_CHILD", "STAND-E", "Girl, 5, blue shirt", "", null)));
        st.updateOccupancy("TURN-N", 1150, Source.SENSOR);
        check("Stadium: turnstile at 96% is CRITICAL", "CRITICAL", st.findArea("TURN-N").level);

        CrowdMonitor um = venue("umrah");
        check("Umrah: lost pilgrim at the Masa'a goes to Mataf Team 5", "T05", assigned(um.reportIncident("LOST_PERSON", "MASAA", "Elderly man, white ihram", "", "Urdu")));
        check("Umrah: Makkah and Madinah are separate graphs (no cross-city dispatch)", "true", um.route("MATAF", "SALAM", false).path.isEmpty());

        CrowdMonitor mall = new CrowdMonitor(() -> time);
        mall.loadVenue(VenueProfile.parse("venue: Test Mall\ncode: mall\nthresholds: 40 70 90\narea: ENT | Entrance | Ground | ENTRANCE | 100\narea: FOOD | Food Court | Level 1 | HALL | 200\nlink: ENT FOOD 2\ncategory: LOST | Lost child | CRITICAL | lost-person | personal\nunit: G1 | Guard 1 | ENT | lost-person\n"), true);
        mall.updateOccupancy("FOOD", 150, Source.SENSOR);
        check("A new venue defined only by a text profile works with no code change", "HIGH, G1", mall.findArea("FOOD").level + ", " + assigned(mall.reportIncident("LOST", "FOOD", "Boy", "", null)));

        System.out.println("PART D - NETWORK API (MULTI-TERMINAL)\n");
        CrowdMonitor srv = venue("airport");
        crowdwatch.net.ApiServer api = new crowdwatch.net.ApiServer(srv, "secret");
        int port = api.start(0);
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY).build();
        java.util.function.BiFunction<String, String, String> post = (path, body) -> {
            try {
                java.net.http.HttpRequest rq = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
                java.net.http.HttpResponse<String> rs = http.send(rq, java.net.http.HttpResponse.BodyHandlers.ofString());
                return rs.statusCode() + " " + rs.body();
            } catch (Exception e) { return e.toString(); }
        };
        String denied = post.apply("/api/occupancy", "area=GATE-B&count=1150").substring(0, 3);
        String okPost = post.apply("/api/occupancy", "key=secret&area=GATE-B&count=1150").substring(0, 3);
        String inc = post.apply("/api/incidents", "key=secret&category=MEDICAL&area=GATE-B&details=Chest+pain");
        String areasJson = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/areas")).header("X-Api-Key", "secret").build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        int page = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
        api.stop();
        trace("POST /api/incidents response", inc);
        check("Remote terminal: changes need the API key, then update the shared monitor",
            "401 without key, 200 with key, GATE-B CRITICAL, I1001 -> A03, dashboard 200",
            denied + " without key, " + okPost + " with key, GATE-B " + srv.findArea("GATE-B").level + ", I1001 -> " + srv.findIncident(1001).unitId + ", dashboard " + page);
        check("GET /api/areas returns JSON with level, trend and time-to-critical", "true", areasJson.startsWith("[{") && areasJson.contains("\"id\":\"GATE-B\"") && areasJson.contains("minutesToCritical"));

        System.out.println("PART E - DATABASE (BONUS)\n");
        if (Database.driverAvailable()) {
            java.io.File dir = new java.io.File("build/test-db");
            deleteTree(dir);
            Database db = new Database("build/test-db/airport");
            db.save(m);
            CrowdMonitor back = new CrowdMonitor(() -> time);
            db.load(back);
            check("Derby save then load restores venue, units, incidents, queues and history",
                m.unitTree().size() + " units, " + m.incidentTree().size() + " incidents, " + m.waitingCount() + " waiting, I1001 " + m.findIncident(1001).history.size() + " history",
                back.unitTree().size() + " units, " + back.incidentTree().size() + " incidents, " + back.waitingCount() + " waiting, I1001 " + back.findIncident(1001).history.size() + " history");

            // a busy day: 20,000 sensor readings and 3,000 incidents, then save while sensors keep reporting
            CrowdMonitor busy = venue("airport");
            for (int k = 0; k < 20000; k++) { time = time.plusSeconds(4); busy.updateOccupancy("SEC-1", k % 2 == 0 ? 440 : 300, Source.SENSOR); }
            for (int k = 0; k < 3000; k++) { busy.reportIncident("INFO", "GATE-A", "q" + k, "", null); busy.resolve(busy.nextIncidentId() - 1, ""); }
            Database big = new Database("build/test-db/airport");
            long[] worst = { 0 };
            boolean[] saving = { true };
            Thread sensor = new Thread(() -> {
                while (saving[0]) {
                    long t0 = System.nanoTime();
                    busy.adjustOccupancy("GATE-B", 1, Source.SENSOR);
                    worst[0] = Math.max(worst[0], (System.nanoTime() - t0) / 1_000_000);
                }
            });
            sensor.start();
            long s0 = System.nanoTime();
            big.save(busy);
            long saveMs = (System.nanoTime() - s0) / 1_000_000;
            saving[0] = false;
            sensor.join();
            trace("save of a busy day", "alerts kept " + busy.alerts().size() + ", save took " + saveMs + " ms, longest sensor wait during the save " + worst[0] + " ms");
            check("Saving a busy day does not stop the sensors (monitor locked only to copy the data)",
                "alerts kept 1000, longest sensor wait under 300 ms",
                "alerts kept " + busy.alerts().size() + ", longest sensor wait " + (worst[0] < 300 ? "under 300 ms" : worst[0] + " ms"));

            String wrong;
            try { db.save(venue("stadium")); wrong = "saved"; } catch (IllegalStateException e) { wrong = "refused"; }
            check("A venue cannot be saved into another venue's database", "refused", wrong);

            // a second CrowdWatch program for the same venue while this one has the database open
            java.io.File lockDir = new java.io.File("build/test-db/second").getAbsoluteFile();
            Database held = new Database(new java.io.File(lockDir, "crowdwatch-data/airport").getPath());
            held.check();
            StringBuilder cp = new StringBuilder();   // absolute paths: the second program runs in another folder
            for (String e : System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                cp.append(cp.length() == 0 ? "" : java.io.File.pathSeparator).append(new java.io.File(e).getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", cp.toString(),
                "crowdwatch.gui.App", "--server", "airport", "0").directory(lockDir).redirectErrorStream(true);
            Process second = pb.start();
            String out = new String(second.getInputStream().readAllBytes());
            boolean ended = second.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            String line = "";
            for (String s : out.split("\\R")) if (s.startsWith("ERROR") || (line.isEmpty() && !s.isBlank() && !s.startsWith("Picked up"))) line = s;
            trace("second program's output", line);
            check("A second program cannot open a database in use: it stops instead of showing sample data",
                "exit 1, already in use", (ended ? "exit " + second.exitValue() : "still running") + (out.contains("already in use") ? ", already in use" : ", no message"));
        } else {
            System.out.println("Derby driver not on the classpath - database tests skipped.\n");
        }

        System.out.println("PART F - REAL-WORLD ROBUSTNESS\n");
        CrowdMonitor tt = venue("airport");
        Area gateA = tt.findArea("GATE-A");
        tt.updateOccupancy("GATE-A", gateA.capacity * 70 / 100, Source.SENSOR);
        String firstTrend = null;
        for (int k = 0; k < 600 && firstTrend == null; k++) {
            time = time.plusNanos(200_000_000L);   // one person every 0.2 s = 300 per minute
            if (tt.adjustOccupancy("GATE-A", 1, Source.SENSOR).message.contains("TREND")) firstTrend = gateA.level + " " + gateA.percent() + "%";
        }
        trace("GATE-A readings after the turnstile run (about one stored per 10 s)", gateA.readings.toString());
        check("Turnstile sending one count per person (5/s): TREND warning comes before CRITICAL",
            "TREND while not CRITICAL", firstTrend == null ? "no TREND alert" : "TREND while " + (firstTrend.startsWith("CRITICAL") ? "already " : "not ") + "CRITICAL");
        trace("level when the TREND alert was raised", String.valueOf(firstTrend));

        CrowdMonitor mv = venue("airport");
        mv.reportIncident("MEDICAL", "GATE-A", "Fainted", "", null);
        int med = mv.nextIncidentId() - 1;
        String medUnit = mv.findIncident(med).unitId, from = mv.findUnit(medUnit).area.id;
        mv.confirmOnScene(med);
        mv.resolve(med, "");
        check("A unit is at the incident area after arriving, so the next dispatch uses its real position",
            "A03 DUTYFREE-1 -> GATE-A, next walk 0 min",
            medUnit + " " + from + " -> " + mv.findUnit(medUnit).area.id + ", next walk " + mv.distancesFrom(mv.findUnit(medUnit).area.id).distanceTo("GATE-A") + " min");

        CrowdMonitor cap = venue("airport");
        for (int k = 0; k < 3000; k++) cap.updateOccupancy("SEC-1", k % 2 == 0 ? 440 : 300, Source.SENSOR);
        check("Only the newest 1000 alerts are kept, newest first", "1000, newest CLEARED", cap.alerts().size() + ", newest " + cap.alerts().getFirst().kind);

        CrowdMonitor slow = venue("airport");
        slow.addListener(new MonitorListener() { public void onAlert(Alert a) { try { Thread.sleep(2000); } catch (InterruptedException e) { } } });
        long q0 = System.nanoTime();
        slow.updateOccupancy("SEC-1", 440, Source.SENSOR);
        slow.updateOccupancy("SEC-1", 100, Source.SENSOR);
        slow.updateOccupancy("GATE-B", 1150, Source.SENSOR);
        long qMs = (System.nanoTime() - q0) / 1_000_000;
        check("A slow listener (2 s per alert, like an SMS gateway) does not hold up the monitor",
            "3 updates in under 100 ms", "3 updates in " + (qMs < 100 ? "under 100 ms" : qMs + " ms"));

        CrowdMonitor lim = venue("airport");
        Result longDetails = lim.reportIncident("MEDICAL", "GATE-A", "x".repeat(401), "", null);
        lim.reportIncident("MEDICAL", "GATE-A", "ok", "", null);
        Result longNote = lim.resolve(lim.nextIncidentId() - 1, "n".repeat(301));
        check("Text longer than the database columns is refused with a clear message (so saving cannot break)",
            "false false", longDetails.ok + " " + longNote.ok);
        trace("refusal message", longDetails.message);

        crowdwatch.net.ApiServer api2 = new crowdwatch.net.ApiServer(venue("airport"), null);
        int port2 = api2.start(0);
        int big413 = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port2 + "/api/incidents"))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("details=" + "y".repeat(70_000))).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
        api2.stop();
        check("The API refuses request bodies over 64 KB", "413", big413);

        System.out.println("PART G - AUTOMATION, SCOUT DEVICES, SOS AND EVACUATION\n");
        // automatic dispatch: the airport's sample counts already put SEC-1 at 88% (HIGH)
        CrowdMonitor au = autoVenue("airport");
        Incident ai = au.findIncident(1001);
        trace("alerts after loading the airport with automation on", au.alerts().getFirst());
        check("Automation: SEC-1 at HIGH reports crowd control automatically and sends the nearest crowd-control unit",
            "I1001 CROWD at SEC-1 -> A02, automatic, TASK on A02's device",
            ai + " " + ai.category.code + " at " + ai.area.id + " -> " + ai.unitId + ", " + (ai.automatic ? "automatic" : "manual") + ", " + au.findUnit("A02").inbox.last().kind + " on A02's device");

        au.updateOccupancy("SEC-1", 420, Source.SENSOR);
        au.updateOccupancy("SEC-1", 400, Source.SENSOR);
        au.updateOccupancy("SEC-1", 440, Source.SENSOR);
        int autos = 0;
        for (Incident x : au.incidentTree().inOrder()) if (x.automatic) autos++;
        check("Automation: more HIGH readings make no duplicates; reaching CRITICAL raises the same incident to CRITICAL",
            "1 automatic incident, I1001 CRITICAL", autos + " automatic incident, I1001 " + ai.priority);

        au.updateOccupancy("SEC-1", 200, Source.SENSOR);
        String stillOpen = ai.open() ? "open after the area calmed" : "closed by itself";
        Result fa = au.review(1001, "Officer Khalid", false, "queue was a tour group");
        check("Automation: the incident stays open until an officer reviews it; a false alarm closes it and frees the unit",
            "open after the area calmed, RESOLVED, A02 AVAILABLE, reviewed by Officer Khalid",
            stillOpen + ", " + ai.status + ", A02 " + au.findUnit("A02").status + ", reviewed by " + ai.reviewedBy);
        trace("I1001 history", ai.history);

        CrowdMonitor at = autoVenue("airport");
        Area ga2 = at.findArea("GATE-A");
        at.updateOccupancy("GATE-A", ga2.capacity * 70 / 100, Source.SENSOR);
        String autoTrend = null;
        for (int k = 0; k < 600 && autoTrend == null; k++) {
            time = time.plusNanos(200_000_000L);
            if (at.adjustOccupancy("GATE-A", 1, Source.SENSOR).message.contains("AUTO")) autoTrend = ga2.level + " " + ga2.percent() + "%";
        }
        Incident gaInc = ga2.autoIncident == 0 ? null : at.findIncident(ga2.autoIncident);
        check("Automation on a TREND warning: crowd control is sent before GATE-A becomes HIGH",
            "sent while MODERATE, unit A05", autoTrend == null ? "not sent" : "sent while " + autoTrend.split(" ")[0] + ", unit " + gaInc.unitId);

        // security officer supervision
        CrowdMonitor sv = venue("stadium");
        String s1 = sv.tick();
        sv.officerCheckIn("Officer Sara");
        later(10);
        String s2 = sv.tick();
        later(6);
        String s3 = sv.tick();
        String s4 = sv.tick();
        check("Supervision: alarm when no officer has checked in, none within 15 min of a check-in, alarm again when overdue (once)",
            "SUPERVISION | - | SUPERVISION | -", s1 + " | " + (s2.isEmpty() ? "-" : s2) + " | " + s3 + " | " + (s4.isEmpty() ? "-" : s4));
        trace("supervision alert", sv.alerts().getFirst());

        // acknowledgement: reminder after 2 min, reassignment after 4 min
        CrowdMonitor ak = venue("airport");
        ak.officerCheckIn("Officer Sara");   // an officer is on watch, so only the acknowledgement rule acts
        ak.reportIncident("MEDICAL", "GATE-A", "Collapsed passenger", "", null);
        Incident med2 = ak.findIncident(1001);
        later(2);
        String k1 = ak.tick();
        later(2);
        String k2 = ak.tick();
        String unreachable = ak.findUnit("A03").status.name();
        ak.acknowledge("A03");
        check("Unit that does not acknowledge: reminder at 2 min, task reassigned at 4 min and the unit marked UNREACHABLE until it answers",
            "REMINDER I1001 | REASSIGNED I1001 -> A08 | A03 UNREACHABLE -> AVAILABLE",
            k1 + " | " + k2 + " -> " + med2.unitId + " | A03 " + unreachable + " -> " + ak.findUnit("A03").status);
        trace("A03 device inbox", ak.findUnit("A03").inbox.toString().replace("], ", "]\n"));

        CrowdMonitor ok2 = venue("airport");
        ok2.officerCheckIn("Officer Sara");
        ok2.reportIncident("MEDICAL", "GATE-A", "Fainted", "", null);
        later(1);
        ok2.acknowledge("A03");
        later(10);
        check("A task acknowledged on the device gets no reminders", "no action, I1001 acknowledged",
            (ok2.tick().isEmpty() ? "no action" : "action") + ", I1001 " + (ok2.findIncident(1001).acknowledged != null ? "acknowledged" : "not acknowledged"));

        // SOS
        CrowdMonitor sosm = venue("airport");
        Result sr = sosm.sos("DUTYFREE-1", null, "Fight, one person injured");
        Incident sosInc = sosm.findIncident(1001);
        check("SOS from the control room: CRITICAL, nearest unit sent, units within 5 min alerted as backup",
            "CRITICAL SOS -> A03, nearby alerted: A02 A05",
            sosInc.priority + " " + sosInc.category.code + " -> " + sosInc.unitId + ", nearby alerted:" + nearby(sosm, 1001));
        trace("SOS result", sr.message);
        Result devSos = sosm.sos("", "A05", "Threatened by a passenger");
        Incident devInc = sosm.findIncident(1002);
        check("SOS from a scout's device uses the scout's position and never sends the scout who asked for help",
            "at GATE-A, not A05", "at " + devInc.area.id + ", " + (devInc.unitId != null && !devInc.unitId.equals("A05") ? "not A05" : "sent to " + devInc.unitId));

        CrowdMonitor pre = venue("airport");
        for (int k = 0; k < 8; k++) pre.reportIncident("INFO", "GATE-A", "question " + k, "", null);
        Result ps = pre.sos("GATE-A", null, "Child trapped in a door");
        check("SOS when every unit is busy: the nearest unit doing a LOW task is pulled away; that task waits again",
            "SOS -> A05 (taken from a lower-priority task), LOW queue 1",
            "SOS -> " + pre.findIncident(1009).unitId + (ps.message.contains("taken from") ? " (taken from a lower-priority task)" : "") + ", LOW queue " + pre.queue(Priority.LOW).size());

        // evacuation
        CrowdMonitor ev = venue("airport");
        Result er = ev.evacuate("Airside T1", "Officer Khalid");
        String gaPlan = "";
        for (String line : ev.evacuationPlan()) if (line.startsWith("GATE-A")) gaPlan = line;
        trace("evacuation plan", ev.evacuationPlan());
        CrowdMonitor.Route detour = ev.route("CHECKIN-1", "GATE-C", false);
        check("Evacuation of a sector: plan to the nearest exit outside the zone, avoiding the crowded checkpoint; public routes avoid the zone",
            "GATE-A -> CURB-1 14 min, 900 people out in about 20 min via BAGGAGE-1, detour avoids PASS-1 and DUTYFREE-1, A03 told to guide to CURB-1",
            "GATE-A -> " + gaPlan.split(" -> ")[1].split(" ")[0] + " " + gaPlan.substring(gaPlan.lastIndexOf('(') + 1, gaPlan.lastIndexOf(')'))
                + (gaPlan.contains("BAGGAGE-1") ? " via BAGGAGE-1" : "") + ", detour " + (detour.path.contains("PASS-1") || detour.path.contains("DUTYFREE-1") ? "enters the zone" : "avoids PASS-1 and DUTYFREE-1")
                + ", A03 told to guide to " + (ev.findUnit("A03").inbox.last().text.contains("to exit CURB-1") ? "CURB-1" : "?"));
        trace("result", er.message);
        ev.endEvacuation("Officer Khalid");
        check("Ending the evacuation reopens the zone and tells every unit", "no zone, route via PASS-1, 8 units told",
            (ev.evacuationZone() == null ? "no zone" : "zone") + ", route via " + (ev.route("CHECKIN-1", "GATE-C", false).path.contains("PASS-1") ? "PASS-1" : "detour") + ", " + told(ev, "ENDED") + " units told");

        Result bc = ev.broadcast("Gates C1-C4 closed, send passengers to C5", "Officer Khalid");
        check("Message to all units reaches every unit on duty", "8 units", told(ev, "Gates C1-C4") + " units");

        // radio gateway delivery
        com.sun.net.httpserver.HttpServer gw = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        LinkedList<String> received = new LinkedList<>();
        gw.createContext("/send", ex -> { received.addLast(new String(ex.getRequestBody().readAllBytes())); ex.sendResponseHeaders(200, -1); ex.close(); });
        gw.start();
        CrowdMonitor rg = venue("airport");
        rg.addChannel(new crowdwatch.net.HttpGatewayChannel("http://127.0.0.1:" + gw.getAddress().getPort() + "/send"));
        rg.reportIncident("MEDICAL", "GATE-A", "Fainted", "", null);
        rg.awaitListeners(5000);
        gw.stop(0);
        String got = received.isEmpty() ? "" : received.getFirst();
        trace("JSON received by the radio gateway", got);
        CrowdMonitor rf = venue("airport");
        rf.addChannel(new crowdwatch.net.HttpGatewayChannel("http://127.0.0.1:1/send"));
        rf.reportIncident("MEDICAL", "GATE-A", "Fainted", "", null);
        rf.awaitListeners(10000);
        String failKind = "";
        for (Alert x : rf.alerts()) if (x.kind == AlertKind.DELIVERY) failKind = "DELIVERY alert";
        check("Radio gateway: the task reaches the gateway as JSON; if the gateway is down the control room is warned",
            "A03 TASK received, DELIVERY alert", (got.contains("\"unit\":\"A03\"") && got.contains("\"kind\":\"TASK\"") ? "A03 TASK received" : "not received") + ", " + failKind);

        CrowdMonitor late = autoVenue("airport");   // A02 is dispatched automatically while the venue loads
        LinkedList<String> lateGot = new LinkedList<>();
        late.addChannel(new UnitChannel() {
            public String name() { return "test radio"; }
            public void deliver(Unit u, UnitMessage msg) { lateGot.addLast(u.id + " " + msg.kind); }
        });
        late.awaitListeners(2000);
        check("A radio channel connected after a task was created still receives that unacknowledged task",
            "[A02 TASK]", lateGot.toString());

        // scout device API
        CrowdMonitor sc = venue("airport");
        crowdwatch.net.ApiServer api3 = new crowdwatch.net.ApiServer(sc, "k3y");
        int port3 = api3.start(0);
        java.util.function.Function<String, String> get = path -> {
            try { return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port3 + path)).header("X-Api-Key", "k3y").build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body(); }
            catch (Exception x) { return x.toString(); }
        };
        java.util.function.BiFunction<String, String, String> post3 = (path, body) -> {
            try {
                return String.valueOf(http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port3 + path))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("X-Api-Key", "k3y").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            } catch (Exception x) { return x.toString(); }
        };
        sc.reportIncident("MEDICAL", "GATE-A", "Fainted", "", null);
        String before3 = get.apply("/api/unit?id=A03");
        String ack3 = post3.apply("/api/ack", "unit=A03");
        String after3 = get.apply("/api/unit?id=A03");
        String sos3 = post3.apply("/api/sos", "unit=A05&text=help");
        int scoutPage = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port3 + "/scout")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
        api3.stop();
        trace("GET /api/unit?id=A03 after acknowledging", after3.length() > 300 ? after3.substring(0, 300) + "..." : after3);
        check("Scout device: sees its task, acknowledges it, can send SOS; the device page loads",
            "task I1001, ack 200, acknowledged, SOS 200, page 200",
            (before3.contains("\"id\":1001") ? "task I1001" : "no task") + ", ack " + ack3 + ", " + (after3.contains("\"acknowledged\":true") ? "acknowledged" : "not acknowledged")
                + ", SOS " + sos3 + ", page " + scoutPage);

        if (Database.driverAvailable()) {
            CrowdMonitor ds = autoVenue("airport");
            ds.officerCheckIn("Officer Sara");
            ds.review(1001, "Officer Sara", true, "");
            ds.evacuate("GATE-C", "Officer Sara");
            Database dbg = new Database("build/test-db/airport");
            dbg.save(ds);
            CrowdMonitor back2 = new CrowdMonitor(() -> time);
            dbg.load(back2);
            Incident bi = back2.findIncident(1001);
            check("Database keeps automation state: automatic incident and its review, officer check-in, evacuation and its plan",
                "I1001 automatic, reviewed by Officer Sara, officer Officer Sara, evacuating GATE-C, plan " + ds.evacuationPlan().size() + " areas",
                bi + " " + (bi.automatic ? "automatic" : "manual") + ", reviewed by " + bi.reviewedBy + ", officer " + back2.officer() + ", evacuating " + back2.evacuationZone() + ", plan " + back2.evacuationPlan().size() + " areas");

            // a database written by the previous version (without the automation columns) is upgraded when opened
            java.io.File oldDir = new java.io.File("build/test-db/old/airport").getAbsoluteFile();
            try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:derby:" + oldDir.getPath() + ";create=true"); java.sql.Statement os = c.createStatement()) {
                os.executeUpdate("CREATE TABLE venue (id INT PRIMARY KEY, code VARCHAR(40), name VARCHAR(120), profile CLOB)");
                os.executeUpdate("CREATE TABLE area_state (id VARCHAR(40) PRIMARY KEY, occupancy INT, level VARCHAR(10), source VARCHAR(12), updated TIMESTAMP)");
                os.executeUpdate("CREATE TABLE units (id VARCHAR(20) PRIMARY KEY, name VARCHAR(80), area VARCHAR(40), status VARCHAR(12))");
                os.executeUpdate("CREATE TABLE unit_skills (unit_id VARCHAR(20) REFERENCES units(id), seq INT, skill VARCHAR(40))");
                os.executeUpdate("CREATE TABLE incidents (id INT PRIMARY KEY, category VARCHAR(40), priority VARCHAR(10), area VARCHAR(40), details VARCHAR(400), contact VARCHAR(120), language VARCHAR(40), reported TIMESTAMP, status VARCHAR(10), unit_id VARCHAR(20), on_scene TIMESTAMP, resolved TIMESTAMP, queue_pos INT)");
                os.executeUpdate("CREATE TABLE history (owner_type CHAR(1), owner_id VARCHAR(20), seq INT, happened_at TIMESTAMP, text VARCHAR(400))");
                os.executeUpdate("CREATE TABLE alerts (seq INT, happened_at TIMESTAMP, kind VARCHAR(12), ref VARCHAR(40), text VARCHAR(400))");
                os.executeUpdate("CREATE TABLE counters (name VARCHAR(20) PRIMARY KEY, value INT)");
                try (java.sql.PreparedStatement ps2 = c.prepareStatement("INSERT INTO venue VALUES (1, 'airport', 'Sample International Airport', ?)")) { ps2.setString(1, VenueProfile.builtIn("airport").text); ps2.executeUpdate(); }
                os.executeUpdate("INSERT INTO units VALUES ('A03', 'Medical Responder 1', 'DUTYFREE-1', 'AVAILABLE')");
                os.executeUpdate("INSERT INTO incidents VALUES (1001, 'MEDICAL', 'CRITICAL', 'GATE-A', 'old case', '', NULL, CURRENT_TIMESTAMP, 'WAITING', NULL, NULL, NULL, 0)");
                os.executeUpdate("INSERT INTO counters VALUES ('incident', 1002)");
            }
            CrowdMonitor old = new CrowdMonitor(() -> time);
            Database oldDb = new Database(oldDir.getPath());
            String oldLoad;
            try { oldLoad = oldDb.load(old) ? "loaded" : "empty"; } catch (Exception x) { oldLoad = "failed: " + x.getMessage(); }
            oldDb.save(old);
            check("A database saved by the previous version is upgraded automatically and still loads",
                "loaded, 1 unit, I1001 waiting, saved again", old.unitTree().size() > 0 ? oldLoad + ", " + old.unitTree().size() + " unit, I1001 " + (old.findIncident(1001) == null ? "missing" : old.findIncident(1001).status.name().toLowerCase()) + ", saved again" : oldLoad);
        }

        System.out.println("PART H - HARDENING FROM THE STRESS TEST\n");
        // TREND cool-down
        CrowdMonitor cd = venue("airport");
        int[] counts = { 1100, 1150, 1200, 900, 1300, 900 };
        for (int k = 0; k < counts.length; k++) { if (k > 0) later(1); cd.updateOccupancy("GATE-A", counts[k], Source.SENSOR); }
        int trends1 = countAlerts(cd, AlertKind.TREND);
        later(8);
        cd.updateOccupancy("GATE-A", 1300, Source.SENSOR);
        int trends2 = countAlerts(cd, AlertKind.TREND);
        check("TREND cool-down: a rise that stops and starts again within 10 minutes raises one TREND alert; after 10 minutes it may warn again",
            "1 alert by minute 5, 2 by minute 13", trends1 + " alert by minute 5, " + trends2 + " by minute 13");

        // hysteresis
        CrowdMonitor hy = venue("airport");
        hy.updateOccupancy("SEC-1", 300, Source.SENSOR);
        hy.updateOccupancy("SEC-1", 365, Source.SENSOR);
        String l1 = hy.findArea("SEC-1").level.name();
        hy.updateOccupancy("SEC-1", 355, Source.SENSOR);
        String l2 = hy.findArea("SEC-1").level.name();
        hy.updateOccupancy("SEC-1", 350, Source.SENSOR);
        String l3 = hy.findArea("SEC-1").level.name();
        int before2 = hy.alerts().size();
        for (int k = 0; k < 10; k++) { hy.updateOccupancy("SEC-1", 365, Source.SENSOR); hy.updateOccupancy("SEC-1", 355, Source.SENSOR); }
        check("Hysteresis: HIGH starts at 80%, but only ends below 78%; 10 swings between 79% and 81% give one alert, not 20",
            "81% HIGH, 79% HIGH, 78% MODERATE, swings: 1 alert", "81% " + l1 + ", 79% " + l2 + ", 78% " + l3 + ", swings: " + (hy.alerts().size() - before2) + " alert");

        // archive (memory)
        CrowdMonitor ar = venue("airport");
        ar.officerCheckIn("Officer Sara");
        ar.reportIncident("INFO", "TRAIN", "old question", "", null);
        ar.resolve(1001, "answered");
        later(23 * 60);
        ar.officerCheckIn("Officer Sara");
        String a1 = ar.tick().isEmpty() ? "kept" : "?";
        later(2 * 60);
        ar.officerCheckIn("Officer Sara");
        String a2 = ar.tick();
        check("Archive: a resolved incident stays 24 hours, then leaves memory (the summary still counts it)",
            "kept at 23 h | ARCHIVED 1 at 25 h, I1001 not in memory, summary: 1 archived",
            a1 + " at 23 h | " + a2 + " at 25 h, I1001 " + (ar.findIncident(1001) == null ? "not in memory" : "still in memory") + ", summary: " + (ar.summary().contains("1 archived") ? "1 archived" : "no archive count"));

        if (Database.driverAvailable()) {
            Database adb = new Database("build/test-db/archive/airport");
            adb.save(ar);
            adb.save(ar);   // a second save must not write the archived incident again
            int rows = -1;
            try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:derby:" + new java.io.File("build/test-db/archive/airport").getAbsolutePath());
                 java.sql.Statement st2 = c.createStatement(); java.sql.ResultSet rs = st2.executeQuery("SELECT COUNT(*) FROM incident_archive")) { rs.next(); rows = rs.getInt(1); }
            CrowdMonitor ab = new CrowdMonitor(() -> time);
            adb.load(ab);
            check("Archive in the database: written once to incident_archive, not loaded back into memory, count kept",
                "1 archive row, I1001 not loaded, 1 archived", rows + " archive row, I1001 " + (ab.findIncident(1001) == null ? "not loaded" : "loaded") + ", " + ab.archivedCount() + " archived");
        }

        // network hardening
        CrowdMonitor hs = venue("airport");
        crowdwatch.net.ApiServer api4 = new crowdwatch.net.ApiServer(hs, "ctl", "sc0ut", 10);
        int port4 = api4.start(0);
        java.util.function.BiFunction<String, String, Integer> code = (path, k) -> {
            try {
                java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port4 + path)).timeout(java.time.Duration.ofSeconds(5));
                if (k != null) b.header("X-Api-Key", k);
                return http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
            } catch (Exception x) { return -1; }
        };
        java.util.function.BiFunction<String, String, Integer> postCode = (path, k) -> {
            try {
                return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port4 + path)).header("X-Api-Key", k)
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString("zone=GATE-C&officer=x")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode();
            } catch (Exception x) { return -1; }
        };
        check("Keys: reading needs a key; the scout key opens only what a scout device needs",
            "no key 401, control 200, scout unit 200, scout areas 403, scout evacuate 403, pages 200",
            "no key " + code.apply("/api/areas", null) + ", control " + code.apply("/api/areas", "ctl") + ", scout unit " + code.apply("/api/unit?id=A03", "sc0ut")
                + ", scout areas " + code.apply("/api/areas", "sc0ut") + ", scout evacuate " + postCode.apply("/api/evacuate", "sc0ut") + ", pages " + code.apply("/scout", null));

        int limited = 0, ctlLimited = 0;
        for (int k = 0; k < 100; k++) if (code.apply("/api/unit?id=A03", "sc0ut") == 429) limited++;
        for (int k = 0; k < 100; k++) if (code.apply("/api/unit?id=A03", "ctl") == 429) ctlLimited++;
        check("Rate limit (10/s with bursts of 40 in this test): a device sending too fast gets 429; the control key is not limited",
            "scout: some 429, control: 0", "scout: " + (limited > 0 ? "some 429" : "none") + ", control: " + ctlLimited);

        String sum1 = null, sum2 = null, sum3 = null;
        try {
            java.util.function.Supplier<String> sumGet = () -> {
                try { return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port4 + "/api/summary")).header("X-Api-Key", "ctl").build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body(); }
                catch (Exception x) { return x.toString(); }
            };
            sum1 = sumGet.get();
            hs.reportIncident("INFO", "TRAIN", "changed directly in the monitor", "", null);
            sum2 = sumGet.get();
            postCode.apply("/api/checkin", "ctl");
            sum3 = sumGet.get();
        } catch (Exception x) { }
        check("Read cache: dashboard data is reused for half a second; a change through the API refreshes it at once",
            "second read cached, read after an API change fresh", (sum1 != null && sum1.equals(sum2) ? "second read cached" : "second read fresh") + ", read after an API change " + (sum3 != null && !sum3.equals(sum1) ? "fresh" : "stale"));

        java.util.List<java.net.Socket> slowSocks = new java.util.ArrayList<>();
        for (int k = 0; k < 40; k++) {
            java.net.Socket sk = new java.net.Socket(java.net.Proxy.NO_PROXY);
            sk.connect(new java.net.InetSocketAddress("127.0.0.1", port4));
            sk.getOutputStream().write("GET /api/areas HTTP/1.1\r\nHost: x\r\n".getBytes());
            slowSocks.add(sk);
        }
        Thread.sleep(500);
        long sl0 = System.nanoTime();
        int slowCode = code.apply("/api/summary", "ctl");
        long slowMs = (System.nanoTime() - sl0) / 1_000_000;
        for (java.net.Socket sk : slowSocks) sk.close();
        long nd0 = System.nanoTime();
        for (int k = 0; k < 20; k++) code.apply("/api/unit?id=A0" + (k % 8 + 1), "ctl");
        long avgMs = (System.nanoTime() - nd0) / 20_000_000;
        api4.stop();
        check("Slow clients: 40 connections sending half a request do not block others; small replies are not delayed by 40 ms",
            "200 under 2 s, small replies under 20 ms", slowCode + (slowMs < 2000 ? " under 2 s" : " after " + slowMs + " ms") + ", small replies " + (avgMs < 20 ? "under 20 ms" : avgMs + " ms"));

        // console relay for radio operators
        CrowdMonitor rl = venue("airport");
        java.io.ByteArrayOutputStream relayOut = new java.io.ByteArrayOutputStream();
        ConsoleRelay relay = new ConsoleRelay(new java.io.PrintStream(relayOut, true), () -> time);
        rl.addChannel(relay);
        rl.devicePoll("A03");   // A03's phone is online
        rl.reportIncident("MEDICAL", "GATE-A", "Fainted", "", null);   // to A03
        rl.reportIncident("PRM", "GATE-C", "Wheelchair", "", null);    // to A08, no device
        rl.resolve(1002, "");
        rl.awaitListeners(2000);
        String printed = relayOut.toString().trim().replaceAll("\\s+\\(.*", "");
        check("Radio console relay prints only for units without an online device, and never 'task closed' notes",
            "1x RADIO >> A08 printed once, 2 skipped", printed.split("\n").length + "x " + printed.split(" \\(")[0] + " printed once, " + relay.skipped() + " skipped");

        // ---------------------------------------------------------------- I. web console
        System.out.println("--- I. Web console (the browser control room at /) ---");
        CrowdMonitor wc = venue("airport");
        wc.officerCheckIn("Officer Test");
        crowdwatch.net.ApiServer api5 = new crowdwatch.net.ApiServer(wc, "ctl", "sc0ut", 50);
        int port5 = api5.start(0);
        java.util.function.BiFunction<String, String, String[]> call = (path, form) -> {
            try {
                java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port5 + path)).timeout(java.time.Duration.ofSeconds(5));
                if (!path.startsWith("/console") && !path.equals("/") && !path.equals("/classic")) b.header("X-Api-Key", "ctl");
                if (form != null) b.header("Content-Type", "application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString(form + "&officer=Officer+Test"));
                java.net.http.HttpResponse<String> resp = http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                return new String[] { String.valueOf(resp.statusCode()), resp.body(), resp.headers().firstValue("Content-Type").orElse("") };
            } catch (Exception x) { return new String[] { "-1", x.toString(), "" }; }
        };
        String[] home = call.apply("/", null), js = call.apply("/console.js", null), css = call.apply("/console.css", null), classic = call.apply("/classic", null);
        check("The web console page and its files are served without a key; the old dashboard moved to /classic",
            "/ 200 html with login, js 200, css 200, /classic 200",
            "/ " + home[0] + (home[2].startsWith("text/html") && home[1].contains("loginForm") && home[1].contains("/console.js") ? " html with login" : " WRONG") + ", js " + js[0]
                + (js[2].contains("javascript") && js[1].contains("/api/overview") ? "" : " WRONG") + ", css " + css[0] + ", /classic " + classic[0] + (classic[1].contains("CrowdWatch") ? "" : " WRONG"));
        String[] noKey;
        try { noKey = new String[] { String.valueOf(http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port5 + "/api/overview")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()) }; }
        catch (Exception x) { noKey = new String[] { "-1" }; }
        wc.reportIncident("PRM", "GATE-A", "Wheelchair needed at A4", "", null);
        crowdwatch.model.Enums.Priority prBefore = wc.findIncident(1001).priority;
        String ov = call.apply("/api/overview", null)[1];
        String un = call.apply("/api/units", null)[1];
        String ve = call.apply("/api/venue", null)[1];
        trace("GET /api/overview", ov);
        check("GET /api/overview, /api/units and /api/venue give the console everything in three replies; the data still needs the key",
            "no key 401; overview people, levels, incidents, units, officer; units with task and device; venue thresholds, categories, sectors",
            "no key " + noKey[0] + "; overview " + (ov.contains("\"people\":") && ov.contains("\"levels\":{") && ov.contains("\"open\":1") && ov.contains("\"units\":{") && ov.contains("Officer Test") ? "people, levels, incidents, units, officer" : "WRONG " + ov)
                + "; units " + (un.startsWith("[{") && un.contains("\"task\":{\"id\":1001") && un.contains("\"online\":") ? "with task and device" : "WRONG")
                + "; venue " + (ve.contains("\"thresholds\":[") && ve.contains("\"categories\":[{") && ve.contains("Airside T1") ? "thresholds, categories, sectors" : "WRONG"));
        String det = call.apply("/api/incident?id=1001", null)[1];
        String unit1 = wc.findIncident(1001).unitId;
        String other = null;
        for (Unit u : wc.unitsSorted()) if (u.status == UnitStatus.AVAILABLE && !u.id.equals(unit1) && u.has(wc.findIncident(1001).category.skill)) { other = u.id; break; }
        String[] re = call.apply("/api/reassign", "id=1001&unit=" + other + "&reason=closer");
        String[] es = call.apply("/api/escalate", "id=1001");
        String[] missing = call.apply("/api/incident?id=999", null);
        check("Incident details for the side panel, and reassign / escalate from the browser",
            "details with route and history; reassign 200 to " + other + "; escalate 200 to " + crowdwatch.model.Enums.Priority.values()[Math.max(0, prBefore.ordinal() - 1)] + "; unknown id ok:false",
            "details " + (det.contains("\"route\":") && det.contains("\"history\":[{") && det.contains("\"device\":") ? "with route and history" : "WRONG " + det)
                + "; reassign " + re[0] + " to " + wc.findIncident(1001).unitId + "; escalate " + es[0] + " to " + wc.findIncident(1001).priority
                + "; unknown id " + (missing[1].contains("\"ok\":false") ? "ok:false" : "WRONG"));
        String pv = call.apply("/api/evacuation?zone=Airside+T1", null)[1];
        boolean marked = false;
        for (Area a : wc.areaTree().inOrder()) if (a.evacuating) marked = true;
        call.apply("/api/evacuate", "zone=Airside+T1");
        String act5 = call.apply("/api/evacuation", null)[1];
        call.apply("/api/evacuate/end", "x=1");
        check("Evacuation preview shows the plan without starting anything; during an evacuation it shows the live plan",
            "preview rows with exits, nothing marked, no evacuation; live plan for Airside T1; ended",
            "preview " + (pv.contains("\"active\":null") && pv.contains("\"exit\":\"CURB-1\"") && pv.contains("\"inZone\":true") ? "rows with exits" : "WRONG " + pv)
                + ", " + (marked ? "areas MARKED" : "nothing marked") + ", " + (wc.evacuationZone() == null && !act5.contains("\"active\":null") ? "no evacuation" : "STILL ON")
                + "; live plan for " + (act5.contains("\"active\":\"Airside T1\"") && act5.contains("\"rows\":[{") ? "Airside T1" : "WRONG") + "; " + (wc.evacuationZone() == null ? "ended" : "not ended"));
        wc.updateOccupancy("SEC-1", 300, Source.SENSOR);
        String rd = call.apply("/api/readings?area=SEC-1", null)[1];
        api5.stop();
        check("GET /api/readings gives an area's recent counts so the console chart starts with history",
            "ok, capacity 450, latest reading 300", (rd.contains("\"ok\":true") ? "ok" : "WRONG") + ", capacity " + (rd.contains("\"capacity\":450") ? "450" : "?")
                + ", latest reading " + (rd.contains("\"n\":300}]") ? "300" : "WRONG " + rd));

        // ---------------------------------------------------------------- J. further automation
        System.out.println("--- J. Further automation (sensor health, follow-up, flow, events, proposals, lost persons, shifts, signs) ---");

        // sensor health: stale data
        CrowdMonitor sh = autoVenue("airport");
        sh.officerCheckIn("Officer Sara");
        sh.updateOccupancy("GATE-B", 300, Source.SENSOR);
        later(4);
        String st1 = sh.tick();
        later(2);
        String st2 = sh.tick();
        boolean staleFlag = sh.findArea("GATE-B").staleSince != null;
        int countAsked = told(sh, "COUNT CHECK at GATE-B");
        later(1);
        sh.updateOccupancy("GATE-B", 310, Source.SENSOR);
        check("Sensor health: an area whose sensor is silent for 5 min is marked stale and a unit is asked to count; new data clears it",
            "fresh at 4 min, STALE at 6 min, 1 unit asked, cleared", (st1.contains("STALE") ? "STALE" : "fresh") + " at 4 min, " + (st2.contains("STALE GATE-B") && staleFlag ? "STALE" : "fresh")
                + " at 6 min, " + countAsked + " unit asked, " + (sh.findArea("GATE-B").staleSince == null ? "cleared" : "still stale"));

        // sensor health: impossible jump
        CrowdMonitor jp = autoVenue("airport");
        jp.updateOccupancy("GATE-B", 300, Source.SENSOR);
        time = time.plusSeconds(5);
        jp.updateOccupancy("GATE-B", 1000, Source.SENSOR);
        String sus = jp.findArea("GATE-B").suspect;
        int asked2 = told(jp, "COUNT CHECK at GATE-B");
        jp.updateOccupancy("GATE-B", 950, Source.STAFF);
        check("Sensor health: a count jumping 700 people in 5 s is suspect and checked by a unit; a staff count settles it",
            "suspect (count jumped from 300 to 1000 in 5 s), 1 unit asked, settled", (sus == null ? "not suspect" : "suspect (" + sus + ")") + ", " + asked2 + " unit asked, "
                + (jp.findArea("GATE-B").suspect == null ? "settled" : "still suspect"));

        // follow-through: acknowledged but not on scene
        CrowdMonitor fo = autoVenue("airport");
        fo.officerCheckIn("Officer Sara");
        fo.reportIncident("MEDICAL", "GATE-A", "Collapsed passenger", "", null);
        int medId = fo.nextIncidentId() - 1;
        String jMedUnit = fo.findIncident(medId).unitId;
        fo.acknowledge(jMedUnit);
        later(10);
        fo.officerCheckIn("Officer Sara");
        String f1 = fo.tick();
        later(10);
        fo.officerCheckIn("Officer Sara");
        String f2 = fo.tick();
        int backups = 0;
        String backupUnit = "-";
        for (Unit u : fo.unitTree().inOrder()) for (int k = 0; k < u.inbox.size(); k++) if (u.inbox.get(k).kind == MessageKind.BACKUP && u.inbox.get(k).incidentId == medId) { backups++; backupUnit = u.id; }
        check("Follow-through: a unit that acknowledged but is not on scene is reminded at 10 min; at 20 min a backup unit is sent",
            "reminder, backup sent (1 unit, not " + jMedUnit + ")", (f1.contains("NOT_ARRIVED I" + medId) ? "reminder" : "no reminder") + ", " + (f2.contains("BACKUP I" + medId) ? "backup sent" : "no backup")
                + " (" + backups + " unit, " + (backupUnit.equals(jMedUnit) ? "SAME unit" : "not " + jMedUnit) + ")");

        // follow-through: automatic crowd incident closes when the area calms down
        CrowdMonitor ac = autoVenue("airport");
        ac.officerCheckIn("Officer Sara");
        int secInc = ac.findArea("SEC-1").autoIncident;
        ac.updateOccupancy("SEC-1", 250, Source.STAFF);
        later(5);
        ac.officerCheckIn("Officer Sara");
        String c1 = ac.tick();
        later(5);
        ac.officerCheckIn("Officer Sara");
        String c2 = ac.tick();
        check("Follow-through: the automatic crowd incident at SEC-1 closes itself once SEC-1 has been below HIGH for 10 min",
            "open at 5 min, closed at 10 min", (c1.contains("AUTOCLOSED") ? "closed" : "open") + " at 5 min, "
                + (secInc > 0 && !ac.findIncident(secInc).open() && c2.contains("AUTOCLOSED I" + secInc) ? "closed" : "open") + " at 10 min");

        // follow-through: long wait gets a stand-in
        CrowdMonitor lw = autoVenue("airport");
        lw.officerCheckIn("Officer Sara");
        lw.reportIncident("PRM", "GATE-C", "Wheelchair needed", "", null);
        lw.reportIncident("PRM", "ARRIVALS-1", "Wheelchair needed", "", null);
        lw.reportIncident("PRM", "GATE-B", "Wheelchair needed", "", null);
        Incident third = null;
        for (Incident i : lw.incidentTree().inOrder()) if (i.area.id.equals("GATE-B")) third = i;
        String w0 = third.status.name();
        later(10);
        lw.officerCheckIn("Officer Sara");
        String w1 = lw.tick();
        check("Follow-through: an incident that waits 10 min for a wheelchair unit gets the nearest free unit as a stand-in",
            "WAITING, then ASSIGNED to a stand-in", w0 + ", then " + third.status + (w1.contains("STANDIN " + third) && !lw.findUnit(third.unitId).has("wheelchair") ? " to a stand-in" : " (" + third.unitId + ")"));

        // crowd flow: hold flow into a HIGH area, and a public sign drafted
        CrowdMonitor hf = autoVenue("airport");
        java.io.ByteArrayOutputStream pa = new java.io.ByteArrayOutputStream();
        hf.addChannel(new ConsoleRelay(new java.io.PrintStream(pa, true), () -> time));
        hf.officerCheckIn("Officer Sara");
        hf.updateOccupancy("GATE-A", 1300, Source.STAFF);
        int held = told(hf, "HOLD FLOW into GATE-A");
        boolean flag = hf.findArea("GATE-A").flowHeld;
        AutoRules.Announcement gateDraft = null;
        int gateDrafts = 0;
        for (AutoRules.Announcement a : hf.pendingAnnouncements()) if (a.ref.equals("GATE-A")) { gateDraft = a; gateDrafts++; }
        Result sent = gateDraft != null ? hf.decideAnnouncement(gateDraft.id, true, "Officer Sara") : Result.fail("no draft");
        hf.updateOccupancy("GATE-A", 700, Source.STAFF);
        hf.awaitListeners(2000);
        check("Crowd flow: GATE-A at HIGH makes the units next to it hold people back; a public sign is drafted and sent only when approved; normal again later",
            "held by 1 unit(s), 1 draft, approved and sent to PA, flow normal", "held by " + held + " unit(s), " + gateDrafts + " draft, " + (sent.ok && pa.toString().contains("SIGNS/PA >> Gates A1-A8 is very busy") ? "approved and sent to PA" : "not sent: " + sent.message)
                + ", " + (flag && !hf.findArea("GATE-A").flowHeld && told(hf, "FLOW NORMAL into GATE-A") == held ? "flow normal" : "still held"));

        // reinforcement: more units for a crowded area, back to their posts when it is calm
        CrowdMonitor rein = autoVenue("airport");
        rein.officerCheckIn("Officer Sara");
        int r0 = AutoRulesProbe.unitsAt(rein, "GATE-A");
        rein.updateOccupancy("GATE-A", 1300, Source.STAFF);
        int rHigh = AutoRulesProbe.unitsAt(rein, "GATE-A");
        String highNote = rein.alerts().getFirst().text;
        trace("alert at HIGH", highNote);
        StringBuilder skilledHigh = new StringBuilder();
        for (Unit u : rein.unitTree().inOrder()) if ("GATE-A".equals(u.reinforcing)) skilledHigh.append(u.has("crowd-control") || u.has("security") ? "skilled " : "UNSKILLED ");
        rein.updateOccupancy("GATE-A", 1550, Source.STAFF);
        int rCrit = AutoRulesProbe.unitsAt(rein, "GATE-A");
        StringBuilder homes = new StringBuilder();
        for (Unit u : rein.unitTree().inOrder()) if ("GATE-A".equals(u.reinforcing)) homes.append(u.id).append(" from ").append(u.home).append("; ");
        trace("reinforcements at GATE-A", homes.toString());
        rein.updateOccupancy("GATE-A", 600, Source.STAFF);
        later(5);
        rein.officerCheckIn("Officer Sara");
        String rel1 = rein.tick();
        later(5);
        rein.officerCheckIn("Officer Sara");
        String rel2 = rein.tick();
        int stillThere = 0, back = 0;
        for (Unit u : rein.unitTree().inOrder()) if ("GATE-A".equals(u.reinforcing)) stillThere++;
        StringBuilder medics = new StringBuilder();
        for (Unit u : rein.unitTree().inOrder()) if (u.has("first-aid") && !(u.has("crowd-control") || u.has("security")) && u.history.toString().contains("reinforcement")) medics.append(u.id);
        for (Unit u : rein.unitTree().inOrder()) for (int k = 0; k < u.inbox.size(); k++) if (u.inbox.get(k).text.startsWith("STAND DOWN reinforcement at GATE-A")) back++;
        check("Reinforcement: GATE-A crowded brings units in (2 at HIGH within 10 min walk, 4 at CRITICAL from anywhere, crowd-control and security first); they return to their posts 10 min after it calms",
            "LOW 1 unit, HIGH 1 (none within 10 min, A07 at 15 min not sent), CRITICAL 4, no medic taken, kept at 5 min, all released at 10 min, 0 left",
            "LOW " + r0 + " unit, HIGH " + rHigh + " (" + (skilledHigh.length() == 0 && highNote.contains("within 10 min") ? "none within 10 min, A07 at 15 min not sent" : skilledHigh.toString().trim()) + "), CRITICAL " + rCrit + ", " + (medics.length() == 0 ? "no medic taken" : "medic " + medics + " taken")
                + ", " + (rel1.contains("RELEASED") ? "released early" : "kept") + " at 5 min, " + (back == rCrit - 1 ? "all" : String.valueOf(back)) + " released at 10 min, " + stillThere + " left");

        // crowd flow: pre-positioning before CRITICAL
        CrowdMonitor pp = autoVenue("airport");
        pp.officerCheckIn("Officer Sara");
        pp.updateOccupancy("GATE-B", 500, Source.SENSOR);
        later(1);
        pp.updateOccupancy("GATE-B", 540, Source.SENSOR);
        int eta = pp.minutesToCritical(pp.findArea("GATE-B"));
        String pt = pp.tick();
        String staged = "-";
        for (Unit u : pp.unitTree().inOrder()) if ("GATE-B".equals(u.stagedFor)) staged = u.id + " at " + u.area.id;
        check("Crowd flow: GATE-B expected CRITICAL in 15 min (no TREND alert yet) gets a free unit sent ahead",
            "15 min, staged A05 at GATE-B", eta + " min, " + (pt.contains("STAGED") ? "staged " : "not staged ") + staged);

        // crowd flow: every sector keeps a free unit
        CrowdMonitor cv = autoVenue("airport");
        cv.officerCheckIn("Officer Sara");
        String cvt = cv.tick();
        String atTrain = "-";
        for (Unit u : cv.unitTree().inOrder()) if (u.area.id.equals("TRAIN")) atTrain = u.id + " (" + u.inbox.last().kind + ")";
        check("Crowd flow: the Landside sector (rail station) has no free unit, so one is moved there from a sector with two or more",
            "COVER Landside, A04 (MOVE)", (cvt.contains("COVER Landside") ? "COVER Landside" : cvt) + ", " + atTrain);

        // scheduled event
        CrowdMonitor evm = autoVenue("airport");
        evm.officerCheckIn("Officer Sara");
        String lv0 = evm.findArea("GATE-A").level.name();
        time = time.withHour(17).withMinute(30);
        evm.officerCheckIn("Officer Sara");
        String e1 = evm.tick();
        String lv1 = evm.findArea("GATE-A").level.name() + " (" + evm.activeEvents().size() + " active)";
        time = time.withHour(19).withMinute(31);
        evm.officerCheckIn("Officer Sara");
        String e2 = evm.tick();
        check("Scheduled event: 30 min before the 18:00 departure bank, Airside T1 levels start 10 points earlier (GATE-A 56% becomes MODERATE); normal after it",
            "LOW -> MODERATE (1 active) -> LOW, event started and ended", lv0 + " -> " + lv1 + " -> " + evm.findArea("GATE-A").level + ", "
                + (e1.contains("EVENT Evening departure bank") ? "event started" : "not started") + " and " + (e2.contains("EVENT_END") ? "ended" : "not ended"));

        // evacuation proposal from a crowd that stays CRITICAL
        CrowdMonitor pr = autoVenue("airport");
        pr.officerCheckIn("Officer Sara");
        pr.updateOccupancy("GATE-C", 1720, Source.STAFF);
        String[] noProp = pr.proposal();
        for (int k = 1; k <= 10; k++) { later(1); pr.updateOccupancy("GATE-C", 1720 + k * 5, Source.SENSOR); pr.officerCheckIn("Officer Sara"); pr.tick(); }
        String[] prop = pr.proposal();
        String notYet = pr.evacuationZone() == null ? "not started" : "STARTED";
        Result go = pr.decideProposal(true, "Officer Sara");
        check("Evacuation proposal: GATE-C CRITICAL and still rising for 10 min is PROPOSED; nothing starts until the officer decides",
            "none, then proposed for GATE-C, not started, started by the officer", (noProp == null ? "none" : "already") + ", then " + (prop == null ? "no proposal" : "proposed for " + prop[0])
                + ", " + notYet + ", " + (go.ok && "GATE-C".equals(pr.evacuationZone()) ? "started by the officer" : go.message));

        // fire alarm
        CrowdMonitor fam = autoVenue("airport");
        fam.officerCheckIn("Officer Sara");
        Result al = fam.alarm("CHECKIN-2", "Fire alarm");
        String[] faProp = fam.proposal();
        Incident faInc = fam.findIncident(fam.nextIncidentId() - 1);
        Result dis = fam.decideProposal(false, "Officer Sara");
        check("Fire alarm input: a CRITICAL check is sent at once and evacuation of the fire zone is proposed; the officer can dismiss it",
            "SOS assigned, proposed Terminal 2 (its fire zone), dismissed", (faInc != null && faInc.category.code.equals("SOS") && faInc.unitId != null ? "SOS assigned" : al.message)
                + ", proposed " + (faProp == null ? "-" : faProp[0] + ("Terminal 2".equals(faProp[0]) ? " (its fire zone)" : "")) + ", " + (dis.ok && fam.proposal() == null ? "dismissed" : dis.message));

        // lost person: lookout and search area
        CrowdMonitor lp = autoVenue("airport");
        java.io.ByteArrayOutputStream radio = new java.io.ByteArrayOutputStream();
        lp.addChannel(new ConsoleRelay(new java.io.PrintStream(radio, true), () -> time));
        lp.officerCheckIn("Officer Sara");
        Result lr = lp.reportIncident("LOST_PERSON", "GATE-A", "Boy, 7 years, red cap, speaks Arabic", "+966 5x", "Arabic");
        int lid = lp.nextIncidentId() - 1;
        Incident lostI = lp.findIncident(lid);
        int onDuty = 0;
        for (Unit u : lp.unitTree().inOrder()) if (u.status != UnitStatus.OFF_DUTY) onDuty++;
        int lookouts = told(lp, "LOOKOUT I" + lid);
        lp.awaitListeners(2000);
        long radioLines = radio.toString().lines().filter(s -> s.contains("ALL UNITS: LOOKOUT I" + lid)).count();
        trace("lookout as read out on the radio", radio.toString().lines().filter(s -> s.contains("ALL UNITS")).findFirst().orElse("-"));
        check("Lost person: a lookout goes to every other unit (once on the radio), with the search area and the meeting point",
            "lookout to " + (onDuty - 1) + " units, radio 1 line, search GATE-A, GATE-B, DUTYFREE-1, meeting ARRIVALS-1",
            "lookout to " + lookouts + " units, radio " + radioLines + " line, search " + String.join(", ", lp.searchAreas(lostI).toArray(new String[0]))
                + ", meeting " + (lr.message.contains("wait at ARRIVALS-1") ? "ARRIVALS-1" : lr.message));

        // lost person: widening search and escalation
        later(5);
        lp.officerCheckIn("Officer Sara");
        String l5 = lp.tick();
        later(10);
        lp.officerCheckIn("Officer Sara");
        String l15 = lp.tick();
        later(15);
        lp.officerCheckIn("Officer Sara");
        String l30 = lp.tick();
        boolean paDraft = false;
        for (AutoRules.Announcement a : lp.pendingAnnouncements()) if (a.ref.equals("I" + lid)) paDraft = true;
        check("Lost person: the search widens every 5 min; at 15 min every exit is asked to check; at 30 min authorities are suggested and a PA message drafted",
            "widened, 15-min alert, 30-min alert, PA draft awaiting approval", (l5.contains("WIDENED I" + lid) ? "widened" : "not widened") + ", " + (l15.contains("LOST15 I" + lid) ? "15-min alert" : "-")
                + ", " + (l30.contains("LOST30 I" + lid) ? "30-min alert" : "-") + ", " + (paDraft ? "PA draft awaiting approval" : "no draft"));

        // lost person: found
        Result fd = lp.found(lid, "PASS-1", "A03");
        int standDown = told(lp, "STAND DOWN lookout I" + lid);
        boolean draftGone = true;
        for (AutoRules.Announcement a : lp.pendingAnnouncements()) if (a.ref.equals("I" + lid)) draftGone = false;
        String escortMsg = lp.findUnit("A03").inbox.last().text;
        Result rs = lp.resolve(lid, "Reunited with father at ARRIVALS-1");
        check("Lost person found at PASS-1 by A03: lookout stopped, A03 brings him to ARRIVALS-1, PA draft withdrawn, details erased when reunited",
            "found, stand down to " + (onDuty - 2) + " units, A03 to ARRIVALS-1, draft withdrawn, erased", (fd.ok ? "found" : fd.message) + ", stand down to " + standDown + " units, "
                + (escortMsg.contains("bring them to ARRIVALS-1") ? "A03 to ARRIVALS-1" : escortMsg) + ", " + (draftGone ? "draft withdrawn" : "draft kept") + ", "
                + (rs.ok && lostI.details.equals(Incident.ERASED) ? "erased" : "kept"));

        // lost person: someone found an unaccompanied child; matching with open cases
        CrowdMonitor fp = autoVenue("airport");
        fp.officerCheckIn("Officer Sara");
        fp.reportIncident("LOST_PERSON", "CHECKIN-1", "Girl, 5, pink dress", "", null);
        Result fpr = fp.foundPerson("", "Small girl in a pink dress, crying", "", "A08");
        int girl = fp.nextIncidentId() - 2;
        Incident fpi = fp.findIncident(girl + 1);
        check("Found person reported by a scout at GATE-C: recorded as already found and compared with the open lost case",
            "recorded as found at GATE-C, compared with the girl's case", (fpi != null && "GATE-C".equals(fpi.foundWhere) ? "recorded as found at GATE-C" : fpr.message) + ", " + (fpr.message.contains("Compare with: I" + girl + " (Girl, 5") ? "compared with the girl's case" : "no match: " + fpr.message));

        check("Lost person: children and vulnerable adults are recognised from the description and handled as CRITICAL",
            "true true true false false", AutoRules.vulnerable("Boy, 7, red cap") + " " + AutoRules.vulnerable("man aged 80, dementia") + " " + AutoRules.vulnerable("woman, 6yo daughter with her")
                + " " + AutoRules.vulnerable("woman in black abaya, 35 years") + " " + AutoRules.vulnerable("man with a blue bag"));

        // shifts and handover
        String shiftText = VenueProfile.builtIn("airport").text
            .replace("unit: A07 | Security Team Bravo | SEC-2 | Arabic, English, security", "unit: A07 | Security Team Bravo | SEC-2 | Arabic, English, security | 06:00-08:30")
            .replace("unit: A02 | Security Team Alpha | SEC-1 | Arabic, English, security, crowd-control", "unit: A02 | Security Team Alpha | SEC-1 | Arabic, English, security, crowd-control | 06:00-09:30");
        time = LocalDateTime.of(2026, 12, 1, 9, 0);
        CrowdMonitor sf = new CrowdMonitor(() -> time);
        sf.setAutomation(false);   // shifts run even with automatic dispatch off
        sf.loadVenue(VenueProfile.parse(shiftText), true);
        sf.officerCheckIn("Officer Sara");
        sf.reportIncident("SECURITY", "SEC-1", "Unattended bag", "", null);
        String secUnit = sf.findIncident(sf.nextIncidentId() - 1).unitId;
        String sh1 = sf.tick();
        String a07 = sf.findUnit("A07").status.name();
        time = time.withHour(9).withMinute(31);
        sf.officerCheckIn("Officer Sara");
        String sh2 = sf.tick();
        sf.resolve(sf.nextIncidentId() - 1, "Bag owner found");
        String a02 = sf.findUnit("A02").status.name();
        time = time.plusDays(1).withHour(6).withMinute(1);
        sf.officerCheckIn("Officer Sara");
        String sh3 = sf.tick();
        check("Shifts: A07 (06:00-08:30) is signed off; A02 finishes its task first; both come back on duty at 06:00 next day",
            "A07 OFF_DUTY, A02 off after task, A02 OFF_DUTY, both on", "A07 " + a07 + ", " + secUnit + (sh2.contains("OFF_AFTER_TASK A02") ? " off after task" : " ?") + ", A02 " + a02
                + ", " + (sh3.contains("ON A07") && sh3.contains("ON A02") ? "both on" : sh3) + (sh1.contains("OFF A07") ? "" : " (A07 not signed off at 09:00)"));

        java.io.File repDir = new java.io.File("build/test-reports");
        deleteTree(repDir);
        CrowdMonitor ho = autoVenue("airport");
        ho.setReportFolder(repDir.toPath());
        ho.officerCheckIn("Officer Sara");
        ho.reportIncident("LOST_PERSON", "GATE-A", "Boy, 7, red cap", "", null);
        time = time.withHour(14).withMinute(5);
        ho.officerCheckIn("Officer Sara");
        String h1 = ho.tick();
        String h2 = ho.tick();
        String[] files = repDir.list();
        String rep = ho.handovers().isEmpty() ? "" : ho.handovers().getFirst()[1];
        trace("handover report (first lines)", rep.lines().limit(12).reduce("", (x, y) -> x + y + "\n"));
        check("Handover: at the 14:00 shift change a report is written once (file and memory) with open incidents and lost persons",
            "HANDOVER 14:00 once, 1 file, has incidents and lost persons", (h1.contains("HANDOVER 14:00") && !h2.contains("HANDOVER") ? "HANDOVER 14:00 once" : h1 + "|" + h2) + ", "
                + (files == null ? 0 : files.length) + " file, " + (rep.contains("OPEN INCIDENTS") && rep.contains("I" + (ho.nextIncidentId() - 1)) && rep.contains("MISSING") ? "has incidents and lost persons" : "missing parts"));

        // backup, health and the new API endpoints
        if (Database.driverAvailable()) {
            java.io.File bdir = new java.io.File("build/test-db/backups/health");
            deleteTree(bdir);
            CrowdMonitor hm = autoVenue("airport");
            Database hdb = new Database("build/test-db/health");
            hm.venue();
            String backup = "-";
            try {
                // the database folder name must match the venue code, so use a copy of the airport called "health"
                CrowdMonitor hv = new CrowdMonitor(() -> time);
                hv.loadVenue(VenueProfile.parse(VenueProfile.builtIn("airport").text.replace("code: airport", "code: health")), true);
                hdb.save(hv);
                backup = hv.lastBackup() != null && new java.io.File(hv.lastBackup(), "health").isDirectory() ? "backup made" : "no backup";
                hdb.save(hv);
            } catch (Exception x) { backup = x.getMessage(); }
            check("Backup: the first save of the day copies the database to backups/<venue>/<date>",
                "backup made, 1 day kept", backup + ", " + (bdir.list() == null ? 0 : bdir.list().length) + " day kept");
        }
        CrowdMonitor hz = autoVenue("airport");
        crowdwatch.net.ApiServer api6 = new crowdwatch.net.ApiServer(hz, "ctl", "sc0ut", 50);
        int port6 = api6.start(0);
        java.util.function.BiFunction<String, String[], String[]> call6 = (path, kv) -> {
            try {
                java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port6 + path)).timeout(java.time.Duration.ofSeconds(5));
                if (kv != null && kv.length > 0 && kv[0] != null) b.header("X-Api-Key", kv[0]);
                if (kv != null && kv.length > 1) b.header("Content-Type", "application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString(kv[1]));
                java.net.http.HttpResponse<String> resp = http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                return new String[] { String.valueOf(resp.statusCode()), resp.body() };
            } catch (Exception x) { return new String[] { "-1", x.toString() }; }
        };
        String[] hl1 = call6.apply("/health", null);
        hz.officerCheckIn("Officer Sara");
        String[] hl2 = call6.apply("/health", null);
        check("GET /health (no key, no venue data): degraded with the reason while no officer is on watch, ok after a check-in",
            "200 degraded (officer check-in overdue), 200 ok", hl1[0] + " " + (hl1[1].contains("\"degraded\"") && hl1[1].contains("officer check-in overdue") ? "degraded (officer check-in overdue)" : hl1[1])
                + ", " + hl2[0] + " " + (hl2[1].contains("\"status\":\"ok\"") ? "ok" : hl2[1]));
        hz.reportIncident("LOST_PERSON", "GATE-A", "Boy, 7, red cap", "", null);
        String[] lostJson = call6.apply("/api/lost", new String[] { "ctl" });
        int lid6 = hz.nextIncidentId() - 1;
        String[] scoutFound = call6.apply("/api/found", new String[] { "sc0ut", "id=" + lid6 + "&unit=A08" });
        String[] scoutAlarm = call6.apply("/api/alarm", new String[] { "sc0ut", "area=GATE-A&text=Fire" });
        String[] handover = call6.apply("/api/handover", new String[] { "ctl" });
        api6.stop();
        check("API: lost cases with search areas; a scout can press Found (scout key) but cannot raise an alarm; handover report as text",
            "lost with search, found 200 at GATE-C, alarm 403, handover text", (lostJson[1].contains("\"search\":[\"GATE-A\"") ? "lost with search" : lostJson[1]) + ", found " + scoutFound[0]
                + " at " + hz.findIncident(lid6).foundWhere + ", alarm " + scoutAlarm[0] + ", " + (handover[1].startsWith("CROWDWATCH HANDOVER REPORT") ? "handover text" : handover[1]));

        // ---------------------------------------------------------------- K. venue layout and evacuation planning
        System.out.println("--- K. Venue layout: drawing, positions, exit flows, corridor capacity, lifts, fire zones ---");
        StringBuilder layoutReport = new StringBuilder();
        for (String vcode : VenueProfile.BUILT_IN) {
            VenueProfile vp = VenueProfile.builtIn(vcode);
            CrowdMonitor lm = new CrowdMonitor(() -> time);
            lm.setAutomation(false);
            lm.loadVenue(vp, true);
            int placed = 0;
            for (Area a : lm.areaTree().inOrder()) for (String[] vpos : vp.positions) if (vpos[0].equals(a.id)) { placed++; break; }
            byte[] img = vp.mapBytes();
            boolean png = img != null && img.length > 8 && img[1] == 'P' && img[2] == 'N' && img[3] == 'G';
            layoutReport.append(vcode).append(' ').append(placed == lm.areaTree().size() ? "all areas placed" : placed + "/" + lm.areaTree().size()).append(png ? ", PNG" : ", no drawing").append("; ");
        }
        java.nio.file.Path tpl = java.nio.file.Path.of("../examples/template.venue");
        if (!java.nio.file.Files.exists(tpl)) tpl = java.nio.file.Path.of("examples/template.venue");
        String mallNote;
        try {
            VenueProfile mallVp = VenueProfile.fromFile(tpl);
            CrowdMonitor mm = new CrowdMonitor(() -> time);
            mm.setAutomation(false);
            mm.loadVenue(mallVp, true);
            mallNote = "mall " + (mallVp.mapBytes() != null ? "drawing found" : "no drawing") + ", ENT-N to ATRIUM " + mm.route("ENT-N", "ATRIUM", false).walk + " min from the drawing";
        } catch (Exception x) { mallNote = "mall: " + x.getMessage(); }
        check("Layout: every built-in venue has a drawing and every area placed on it; the template venue finds its drawing and works out walking times",
            "airport all areas placed, PNG; stadium all areas placed, PNG; umrah all areas placed, PNG; mall drawing found, ENT-N to ATRIUM 2 min from the drawing",
            layoutReport + mallNote);

        CrowdMonitor mk = new CrowdMonitor(() -> time);
        mk.setAutomation(false);
        mk.loadVenue(VenueProfile.builtIn("umrah"), true);
        check("Umrah layout: walking times come from the drawing (1 unit = 1 m, 60 m a minute in a crowd)",
            "MATAF to KA-GATE 5 min, MASAA to MARWA-GATE 5 min, Makkah and Madinah not connected",
            "MATAF to KA-GATE " + mk.route("MATAF", "KA-GATE", false).walk + " min, MASAA to MARWA-GATE " + mk.route("MASAA", "MARWA-GATE", false).walk + " min, "
                + (mk.route("MATAF", "SALAM", false).found() ? "CONNECTED" : "Makkah and Madinah not connected"));

        String hallText = String.join("\n", "venue: Test hall", "code: hall", "thresholds: 50 75 90", "exit: NEAR FAR",
            "area: HALL | Main hall | A | HALL | 10000", "area: NEAR | Near door | A | EXIT | 5000", "area: FAR | Far doors | A | EXIT | 5000", "area: UPPER | Upper floor | A | HALL | 500",
            "link: HALL NEAR 1", "link: HALL FAR 4", "link: UPPER HALL 3 100 stairs", "link: UPPER NEAR 1 50 lift",
            "exitflow: NEAR 1000", "exitflow: FAR 2000", "zone: Hall zone | HALL UPPER", "occupancy: HALL 6000", "occupancy: UPPER 200");
        CrowdMonitor hl = new CrowdMonitor(() -> time);
        hl.setAutomation(false);
        hl.loadVenue(VenueProfile.parse(hallText), true);
        StringBuilder split = new StringBuilder();
        String upperPath = "-";
        int zoneClear = 0;
        for (String[] row : hl.evacuationRows("Hall zone")) {
            if (row[0].equals("HALL")) split.append(row[4 + 1]).append(" to ").append(row[1]).append(", ");
            if (row[0].equals("UPPER")) upperPath = row[2];
            if ("zone".equals(row[4]) && row[6].matches("\\d+")) zoneClear = Math.max(zoneClear, Integer.parseInt(row[6]));
        }
        trace("hall plan", hl.evacuationRows("Hall zone").toString());
        check("Evacuation with exit flows: 6,000 people split over both exits (all through the near door would take 7 min); the lift is never used (the upper floor walks 4 min down the stairs and queues behind 3,000 at the near door: clear in 8 min); fire zone by name",
            "3000 to NEAR, 3000 to FAR, zone clear in 8 min, upper floor via [UPPER -> HALL -> NEAR]",
            split + "zone clear in " + zoneClear + " min, upper floor via " + upperPath);

        CrowdMonitor mz = new CrowdMonitor(() -> time);
        mz.setAutomation(false);
        mz.loadVenue(VenueProfile.builtIn("umrah"), true);
        boolean madinahPlanned = false, mataSplit = false;
        java.util.Set<String> matafExits = new java.util.HashSet<>();
        for (String[] row : mz.evacuationRows("Haram Mataf")) {
            Area ra = mz.findArea(row[0]);
            if (ra != null && ra.sector.equals("Madinah")) madinahPlanned = true;
            if (row[0].equals("MATAF")) matafExits.add(row[1]);
        }
        mz.setAutomation(true);
        mz.reportIncident("LOST_PERSON", "OLD-MOSQUE", "Elderly man, 78, white ihram", "", "Urdu");
        String meetMadinah = mz.meetingFor("OLD-MOSQUE"), meetMakkah = mz.meetingFor("MATAF");
        check("Umrah: evacuating the Mataf plans Makkah only, spreads the Mataf crowd over several exits; each city has its own meeting point for lost pilgrims",
            "Madinah not planned, Mataf uses 2 exits, meeting SALAM in Madinah and KA-GATE in Makkah",
            (madinahPlanned ? "Madinah PLANNED" : "Madinah not planned") + ", Mataf uses " + matafExits.size() + " exits, meeting " + meetMadinah + " in Madinah and " + meetMakkah + " in Makkah");

        CrowdMonitor la = venue("umrah");
        crowdwatch.net.ApiServer api7 = new crowdwatch.net.ApiServer(la, "ctl", "sc0ut", 50);
        int port7 = api7.start(0);
        java.util.function.BiFunction<String, String, String[]> get7 = (path, k) -> {
            try {
                java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port7 + path)).timeout(java.time.Duration.ofSeconds(5));
                if (k != null) b.header("X-Api-Key", k);
                java.net.http.HttpResponse<byte[]> resp = http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                return new String[] { String.valueOf(resp.statusCode()), resp.headers().firstValue("Content-Type").orElse(""), new String(resp.body(), java.nio.charset.StandardCharsets.ISO_8859_1) };
            } catch (Exception x) { return new String[] { "-1", "", x.toString() }; }
        };
        String[] lay = get7.apply("/api/layout", "sc0ut"), mapNoKey = get7.apply("/api/map", null), mapImg = get7.apply("/api/map", "ctl"), ev7 = get7.apply("/api/evacuation?zone=Haram+Mataf", "ctl");
        api7.stop();
        check("API: the layout (scout key allowed, for the route map on the phone), the drawing (needs a key) and the plan with the time to clear the zone",
            "layout 200 with 26 areas, map without key 401, map 200 image/png, plan clear in minutes",
            "layout " + lay[0] + " with " + (lay[2].split("\"id\":").length - 1) + " areas, map without key " + mapNoKey[0] + ", map " + mapImg[0] + " " + mapImg[1]
                + ", plan " + (ev7[2].matches(".*\"clearMinutes\":[1-9][0-9]*}.*") ? "clear in minutes" : ev7[2].substring(Math.max(0, ev7[2].length() - 60))));

        // ---------------------------------------------------------------- L. counting people moving between zones
        System.out.println("--- L. People moving between zones, and the count check against the gates ---");
        CrowdMonitor mvm = venue("airport");
        long total0 = mvm.peopleCounted();
        Result mv1 = mvm.movePeople("CHECKIN-1", "SEC-1", 30, Source.SENSOR);
        Result mv2 = mvm.movePeople("CHECKIN-1", "GATE-C", 5, Source.SENSOR);
        Result mv3 = mvm.movePeople("OUTSIDE", "GATE-A", 5, Source.SENSOR);
        int checkin = mvm.findArea("CHECKIN-1").occupancy, sec = mvm.findArea("SEC-1").occupancy;
        boolean same = mvm.peopleCounted() == total0;
        Result mv4 = mvm.movePeople("CURB-1", "CHECKIN-1", 10, Source.SENSOR);
        check("Moving people: one door count changes both zones together, so nobody is counted twice; counters wired to the wrong areas are refused",
            "CHECKIN-1 950, SEC-1 425, total unchanged; no link refused; entering not at an exit refused; too few in CURB-1 noted",
            "CHECKIN-1 " + checkin + ", SEC-1 " + sec + ", total " + (same ? "unchanged" : "CHANGED") + "; "
                + (!mv2.ok && mv2.message.contains("no walking link") ? "no link refused" : mv2.message) + "; " + (!mv3.ok && mv3.message.contains("not an exit") ? "entering not at an exit refused" : mv3.message)
                + "; " + (mv4.ok && mv4.message.contains("recount") ? "too few in CURB-1 noted" : mv4.message));

        CrowdMonitor cc = venue("airport");
        cc.officerCheckIn("Officer Sara");
        long[] cc0 = cc.countCheck();
        cc.movePeople("OUTSIDE", "CURB-1", 100, Source.SENSOR);
        cc.movePeople("CURB-1", "CHECKIN-1", 60, Source.SENSOR);
        cc.movePeople("CURB-1", "OUTSIDE", 40, Source.SENSOR);
        long[] cc1 = cc.countCheck();
        String t1 = cc.tick();
        cc.updateOccupancy("GATE-B", 300, Source.SENSOR);     // a camera that also sees part of GATE-A: 300 people counted twice
        String t2 = cc.tick();
        later(5);
        cc.officerCheckIn("Officer Sara");
        String t3 = cc.tick();
        String alertText = "-";
        for (Alert ca : cc.alerts()) if (ca.text.startsWith("Counts do not add up")) { alertText = ca.text; break; }
        trace("count check alert", alertText);
        Result rs2 = cc.resetCountCheck("Officer Sara");
        long[] cc2 = cc.countCheck();
        check("Count check: gates say how many are inside; when the areas add up to 300 more for 5 min the control room is told someone is counted twice; a reset after a recount clears it",
            "not in use before gate counts, agrees (difference 0), quiet at first, alert after 5 min (300 too many), reset to difference 0",
            (cc0 == null ? "not in use before gate counts" : "in use") + ", " + (cc1 != null && cc1[2] == 0 && t1.isEmpty() ? "agrees (difference 0)" : "difference " + (cc1 == null ? "?" : cc1[2]))
                + ", " + (t2.contains("COUNT_MISMATCH") ? "alert at once" : "quiet at first") + ", " + (t3.contains("COUNT_MISMATCH") && alertText.contains("300 too many") ? "alert after 5 min (300 too many)" : "no alert: " + t3)
                + ", " + (rs2.ok && cc2 != null && cc2[2] == 0 ? "reset to difference 0" : rs2.message));

        System.out.println("PART M - PERSONAL LOGINS, ROLES, DEVICE CODES, AUDIT LOG, HTTPS AND ARABIC\n");
        java.nio.file.Path accFile = java.nio.file.Path.of("build", "test-accounts", "accounts.txt");
        deleteTree(accFile.getParent().toFile());
        crowdwatch.net.Accounts acc = new crowdwatch.net.Accounts(accFile);
        String r1 = acc.setUser("x", crowdwatch.net.Accounts.Role.ADMIN, "longenough");
        String r2 = acc.setUser("admin", crowdwatch.net.Accounts.Role.ADMIN, "short");
        String r3 = acc.setUser("admin", crowdwatch.net.Accounts.Role.ADMIN, "Admin-pass-1");
        String r4 = acc.setUser("admin", crowdwatch.net.Accounts.Role.VIEWER, null);
        String r5 = acc.setActive("admin", false);
        acc.setUser("sara", crowdwatch.net.Accounts.Role.OFFICER, "Sara-pass-1");
        acc.setUser("omar", crowdwatch.net.Accounts.Role.OPERATOR, "Omar-pass-1");
        acc.setUser("vera", crowdwatch.net.Accounts.Role.VIEWER, "Vera-pass-1");
        String fileText = java.nio.file.Files.readString(accFile);
        crowdwatch.net.Accounts reread = new crowdwatch.net.Accounts(accFile);
        check("Accounts: names and password length are checked, the last administrator cannot be demoted or switched off, the file holds hashes only and reads auditBack",
            "short name refused, short password refused, admin added, demote refused, switch-off refused, no password in file, 4 users read back, login OK",
            (r1 != null ? "short name refused" : "accepted") + ", " + (r2 != null ? "short password refused" : "accepted") + ", " + (r3 == null ? "admin added" : r3)
                + ", " + (r4 != null ? "demote refused" : "demoted") + ", " + (r5 != null ? "switch-off refused" : "switched off")
                + ", " + (fileText.contains("Sara-pass-1") || fileText.contains("Admin-pass-1") ? "PASSWORD IN FILE" : "no password in file")
                + ", " + reread.users().length + " users read back, " + (reread.login("SARA", "Sara-pass-1", LocalDateTime.of(2026, 12, 1, 9, 0)) != null ? "login OK" : "login failed"));

        LocalDateTime lt = LocalDateTime.of(2026, 12, 1, 9, 0);
        int wrong = 0;
        for (int k = 0; k < crowdwatch.net.Accounts.MAX_FAILURES; k++) if (acc.login("omar", "guess" + k, lt) == null) wrong++;
        String lockedRight = acc.login("omar", "Omar-pass-1", lt.plusMinutes(1));
        String afterLock = acc.login("omar", "Omar-pass-1", lt.plusMinutes(crowdwatch.net.Accounts.LOCK_MINUTES + 1));
        String tok = acc.login("vera", "Vera-pass-1", lt);
        crowdwatch.net.Accounts.Who sw1 = acc.session(tok, lt.plusHours(1));
        crowdwatch.net.Accounts.Who sw2 = acc.session(tok, lt.plusHours(crowdwatch.net.Accounts.SESSION_HOURS + 1));
        check("Logins: 5 wrong passwords lock the name for 5 minutes (even the right password is refused), a session lasts 12 hours",
            "5 refused, locked: refused, after 5 min: OK, session vera VIEWER, expired after 12 h",
            wrong + " refused, locked: " + (lockedRight == null ? "refused" : "accepted") + ", after 5 min: " + (afterLock != null ? "OK" : "refused")
                + ", session " + (sw1 == null ? "none" : sw1.name + " " + sw1.role) + ", " + (sw2 == null ? "expired after 12 h" : "still valid"));

        // the server with personal logins and no control key
        CrowdMonitor asrv = venue("airport");
        crowdwatch.net.ApiServer aapi = new crowdwatch.net.ApiServer(asrv, null, null, 0);
        crowdwatch.ds.LinkedList<String> auditRows = new crowdwatch.ds.LinkedList<>();
        acc.addSink(e -> auditRows.addLast(e.toString()));
        aapi.setAccounts(acc);
        int aport = aapi.start(0);
        java.net.http.HttpClient hc = java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY).build();
        String base = "http://127.0.0.1:" + aport;
        String noLogin = call(hc, base + "/api/areas", null);
        String me0 = call(hc, base + "/api/me", null);
        String badLogin = call(hc, base + "/api/login", "user=sara&password=nope");
        String tVera = token(call(hc, base + "/api/login", "user=vera&password=Vera-pass-1"));
        String tOmar = token(call(hc, base + "/api/login", "user=omar&password=Omar-pass-1"));
        String tSara = token(call(hc, base + "/api/login", "user=sara&password=Sara-pass-1"));
        String tAdmin = token(call(hc, base + "/api/login", "user=admin&password=Admin-pass-1"));
        String vRead = call(hc, base + "/api/areas", null, "Authorization", "Bearer " + tVera);
        String vWrite = call(hc, base + "/api/occupancy", "area=GATE-B&count=900", "Authorization", "Bearer " + tVera);
        String oWrite = call(hc, base + "/api/occupancy", "area=GATE-B&count=900", "Authorization", "Bearer " + tOmar);
        String oEvac = call(hc, base + "/api/evacuate", "zone=GATE-B", "Authorization", "Bearer " + tOmar);
        String sCheck = call(hc, base + "/api/checkin", "officer=Mallory", "Authorization", "Bearer " + tSara);
        String officerNow = asrv.officer();
        String sUsers = call(hc, base + "/api/users", null, "Authorization", "Bearer " + tSara);
        String aAdd = call(hc, base + "/api/users", "name=nadia&role=OPERATOR&password=Nadia-pass-1", "Authorization", "Bearer " + tAdmin);
        String aList = call(hc, base + "/api/users", null, "Authorization", "Bearer " + tAdmin);
        check("Server with personal logins: nothing without signing in; a viewer reads but cannot change; an operator enters counts but cannot evacuate; an officer's name is always their own; only an admin manages users",
            "401, logins on, wrong password 401, 4 tokens, viewer read 200, viewer write 403, operator count 200, operator evacuate 403, check-in 200 as sara (not Mallory), officer users 403, admin adds nadia 200 and sees 5 users",
            code(noLogin) + ", " + (me0.contains("\"logins\":true") ? "logins on" : me0) + ", wrong password " + code(badLogin) + ", "
                + ((tVera != null ? 1 : 0) + (tOmar != null ? 1 : 0) + (tSara != null ? 1 : 0) + (tAdmin != null ? 1 : 0)) + " tokens, viewer read " + code(vRead)
                + ", viewer write " + code(vWrite) + ", operator count " + code(oWrite) + ", operator evacuate " + code(oEvac) + ", check-in " + code(sCheck) + " as " + officerNow
                + (officerNow != null && !officerNow.contains("Mallory") ? " (not Mallory)" : "") + ", officer users " + code(sUsers) + ", admin adds nadia " + code(aAdd)
                + " and sees " + (aList.split("\"name\"").length - 1) + " users");

        // device codes: a phone that can act only for its own unit
        Result ri = asrv.reportIncident("MEDICAL", "GATE-B", "Fainted", null, null);
        int mine = asrv.nextIncidentId() - 1;
        String mineUnit = asrv.findIncident(mine).unitId;
        Result otherInc = asrv.reportIncident("SECURITY", "CHECKIN-1", "Unattended bag", null, null);
        int theirs = asrv.nextIncidentId() - 1;
        String theirUnit = asrv.findIncident(theirs).unitId;
        String codeJson = call(hc, base + "/api/devices/code", "unit=" + mineUnit, "Authorization", "Bearer " + tSara);
        String dcode = codeJson.replaceAll(".*\"code\":\"([A-Z0-9]+)\".*", "$1");
        String opCode = call(hc, base + "/api/devices/code", "unit=" + mineUnit, "Authorization", "Bearer " + tOmar);
        String devUnit = call(hc, base + "/api/unit?id=" + theirUnit, null, "X-Api-Key", dcode);
        String devAck = call(hc, base + "/api/ack", "unit=" + theirUnit, "X-Api-Key", dcode);
        String devOther = call(hc, base + "/api/onscene", "id=" + theirs, "X-Api-Key", dcode);
        String devOwn = call(hc, base + "/api/onscene", "id=" + mine, "X-Api-Key", dcode);
        String devAreas = call(hc, base + "/api/areas", null, "X-Api-Key", dcode);
        acc.revokeDevice(mineUnit);
        String devAfter = call(hc, base + "/api/unit?id=" + mineUnit, null, "X-Api-Key", dcode);
        check("Device codes: an officer issues a code for one unit; the phone sees and acts only for that unit; a cancelled code stops working",
            "code issued, operator refused 403, phone sees " + mineUnit + " (not " + theirUnit + "), ack 200 for its own unit, other unit's task 403, own task on scene 200, control-room data 403, after cancel 401",
            (ri.ok && otherInc.ok && dcode.length() == 10 ? "code issued" : "no code: " + codeJson) + ", operator refused " + code(opCode)
                + ", phone sees " + devUnit.replaceAll(".*\"id\":\"([A-Z0-9-]+)\".*", "$1") + (devUnit.contains("\"id\":\"" + theirUnit + "\"") ? "" : " (not " + theirUnit + ")")
                + ", ack " + code(devAck) + (asrv.findIncident(mine).acknowledged != null ? " for its own unit" : " for ?") + ", other unit's task " + code(devOther)
                + ", own task on scene " + code(devOwn) + ", control-room data " + code(devAreas) + ", after cancel " + code(devAfter));

        String auditJson = call(hc, base + "/api/audit", null, "Authorization", "Bearer " + tSara);
        String vAudit = call(hc, base + "/api/audit", null, "Authorization", "Bearer " + tVera);
        String allAudit = String.join("\n", auditRows.toArray(new String[0]));
        trace("audit log (newest 6)", String.join("\n", java.util.Arrays.copyOfRange(allAudit.split("\n"), Math.max(0, auditRows.size() - 6), auditRows.size())));
        check("Audit log: every login, change and refusal is written with the user and role; passwords are never written; officers read it, viewers cannot",
            "failed login logged, omar refused evacuate logged, sara checkin logged, device ack logged, no passwords, officer 200, viewer 403",
            (allAudit.contains("sara (-)") || allAudit.contains("sara (OFFICER)  login  REFUSED") ? "failed login logged" : "failed login missing") + ", "
                + (allAudit.contains("omar (OPERATOR)  evacuate  REFUSED") ? "omar refused evacuate logged" : "missing") + ", "
                + (allAudit.contains("sara (OFFICER)  checkin  OK") ? "sara checkin logged" : "missing") + ", "
                + (allAudit.contains("(DEVICE)  ack  OK") ? "device ack logged" : "missing") + ", "
                + (allAudit.contains("pass-1") ? "PASSWORD WRITTEN" : "no passwords") + ", officer " + code(auditJson) + ", viewer " + code(vAudit));
        aapi.stop();

        if (Database.driverAvailable()) {
            deleteTree(new java.io.File("build/test-db/audit"));
            Database adbx = new Database("build/test-db/audit/airport");
            crowdwatch.net.Accounts.Sink sink = adbx.auditSink();
            crowdwatch.net.Accounts acc2 = new crowdwatch.net.Accounts(null);
            acc2.addSink(sink);
            acc2.record(lt, "sara", "OFFICER", "evacuate", "zone=GATE-B -> Evacuation started", true);
            acc2.record(lt.plusMinutes(1), "omar", "OPERATOR", "evacuate", "zone=GATE-B -> needs OFFICER", false);
            String[] auditBack = adbx.readAudit(10);
            check("Audit log in the venue's database (table AUDIT): rows are written at once and read back newest first",
                "2 rows, newest omar REFUSED, then sara OK", auditBack.length + " rows, newest " + (auditBack.length > 0 && auditBack[0].contains("omar") && auditBack[0].contains("REFUSED") ? "omar REFUSED" : "?")
                    + ", then " + (auditBack.length > 1 && auditBack[1].contains("sara") && auditBack[1].contains(" OK ") ? "sara OK" : "?"));
        }

        // HTTPS with a self-signed certificate made by keytool
        java.io.File ksFile = new java.io.File("build/test-tls/server.p12");
        deleteTree(ksFile.getParentFile());
        char[] ksPw = "test-store-pass".toCharArray();
        String httpsResult;
        try {
            crowdwatch.net.Tls.selfSigned(ksFile, ksPw, "localhost", "127.0.0.1");
            crowdwatch.net.ApiServer sapi = new crowdwatch.net.ApiServer(venue("airport"), "secret");
            int sport = sapi.start(0, crowdwatch.net.Tls.context(ksFile, ksPw));
            java.net.http.HttpClient tc = java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY).sslContext(crowdwatch.net.Tls.trusting(ksFile, ksPw)).build();
            java.net.http.HttpResponse<String> hr = tc.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://localhost:" + sport + "/api/areas")).header("X-Api-Key", "secret").build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            String plainHttp;
            try { plainHttp = code(call(hc, "http://127.0.0.1:" + sport + "/health", null)); } catch (RuntimeException e) { plainHttp = "refused"; }
            String untrusted;
            try {
                java.net.http.HttpClient distrustClient = java.net.http.HttpClient.newBuilder().proxy(java.net.http.HttpClient.Builder.NO_PROXY).build();
                distrustClient.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://localhost:" + sport + "/health")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                untrusted = "accepted";
            } catch (java.io.IOException e) { untrusted = "rejected"; }
            httpsResult = "HTTPS " + hr.statusCode() + ", " + (sapi.secure() ? "secure" : "plain") + ", HSTS " + hr.headers().firstValue("Strict-Transport-Security").isPresent()
                + ", plain HTTP on that port " + (plainHttp.startsWith("2") ? "answered" : "not answered") + ", unknown certificate " + untrusted;
            sapi.stop();
        } catch (java.io.IOException e) { httpsResult = "failed: " + e.getMessage(); }
        check("HTTPS: a self-signed certificate is made with keytool; the server answers over TLS with Strict-Transport-Security; plain HTTP gets nothing; a client that does not trust the certificate is refused",
            "HTTPS 200, secure, HSTS true, plain HTTP on that port not answered, unknown certificate rejected", httpsResult);

        // Arabic screens
        crowdwatch.net.ApiServer lapi = new crowdwatch.net.ApiServer(venue("airport"), "secret");
        int lport = lapi.start(0);
        String i18n = call(hc, "http://127.0.0.1:" + lport + "/i18n.js", null);
        String consolePage = call(hc, "http://127.0.0.1:" + lport + "/", null);
        String scoutHtml = call(hc, "http://127.0.0.1:" + lport + "/scout", null);
        lapi.stop();
        check("Arabic screens: the language file is served without a key and switches the page to right to left; the console and the scout phone load it and have a language button",
            "i18n.js 200, rtl, Arabic text, console loads it, scout loads it, both have the button",
            "i18n.js " + code(i18n) + ", " + (i18n.contains("'rtl'") ? "rtl" : "no rtl") + ", " + (i18n.contains("الإخلاء") ? "Arabic text" : "no Arabic")
                + ", " + (consolePage.contains("/i18n.js") ? "console loads it" : "console misses it") + ", " + (scoutHtml.contains("/i18n.js") ? "scout loads it" : "scout misses it")
                + ", " + (consolePage.contains("data-lang-btn") && scoutHtml.contains("data-lang-btn") ? "both have the button" : "button missing"));

        if (Database.driverAvailable()) Database.shutdown();

        System.out.println("=====================================");
        System.out.println("TOTAL: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** Sends a GET (body null) or a form POST with optional header name/value pairs. @return "status body" */
    private static String call(java.net.http.HttpClient c, String url, String body, String... headers) {
        try {
            java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url));
            for (int k = 0; k + 1 < headers.length; k += 2) b.header(headers[k], headers[k + 1]);
            if (body != null) b.header("Content-Type", "application/x-www-form-urlencoded").POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
            java.net.http.HttpResponse<String> r = c.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            return r.statusCode() + " " + r.body();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** @return the status code of a {@link #call} reply */
    private static String code(String reply) { return reply.substring(0, 3); }

    /** @return the session token of a login reply, or null */
    private static String token(String reply) {
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(reply);
        return mt.find() ? mt.group(1) : null;
    }

    /** Deletes a folder and everything in it (removes the previous test database). */
    private static void deleteTree(java.io.File f) {
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteTree(k);
        f.delete();
    }
}
