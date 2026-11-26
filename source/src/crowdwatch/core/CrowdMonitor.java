package crowdwatch.core;

import java.time.Duration;
import java.time.LocalDateTime;
import crowdwatch.ds.*;
import crowdwatch.model.*;
import crowdwatch.model.Enums.*;

/**
 * The heart of CrowdWatch: holds the venue's data and applies every rule.
 * <p>
 * All input paths (the Swing GUI, the web API, sensor feeds, the database loader) call the
 * public methods of this class, and all output goes through alerts and
 * {@link MonitorListener}s. The class knows nothing about airports or mosques; everything
 * venue-specific comes from a {@link VenueProfile}.
 * <p>
 * <b>Data structures used</b>
 * <ul>
 * <li>AVL trees for areas, units, incidents and categories by ID, and for the distance cache</li>
 * <li>a graph of areas and walking links, searched with Dijkstra</li>
 * <li>one FIFO queue per priority for incidents waiting for a unit</li>
 * <li>linked lists for alerts and listeners</li>
 * <li>merge sort for the reports</li>
 * </ul>
 * <b>Thread safety:</b> every public method that reads or changes data is synchronized on
 * this object, so feeds and API requests can run on their own threads. Code that walks the
 * trees directly (through {@link #areaTree()} and similar accessors) must hold the same lock,
 * i.e. use {@code synchronized (monitor) { ... }}. Listeners are called on a separate event
 * thread, outside the lock (see {@link MonitorListener}).
 */
public class CrowdMonitor {
    // rising to this level or above raises a CROWD alert; dropping below it raises an all-clear
    public static final CrowdLevel ALERT_LEVEL = CrowdLevel.HIGH;
    // how far back the trend rate looks
    public static final int TREND_WINDOW_MINUTES = 10;
    // raise a TREND alert if CRITICAL is expected within this many minutes
    public static final int TREND_WARNING_MINUTES = 10;
    /** Number of alerts kept; the oldest is dropped when a new one arrives, so memory and save time stay bounded. */
    public static final int MAX_ALERTS = 1000;
    /** Longest note or reason accepted on resolve and reassign (it is stored in the history). */
    public static final int MAX_NOTE = 300;
    /** Units within this many minutes' walk of an SOS are alerted as possible backup. */
    public static final int SOS_NEARBY_MINUTES = 5;
    /** After a TREND alert, the same area raises no new TREND alert for this many minutes (stops repeated alerts from jumpy sensor counts). */
    public static final int TREND_COOLDOWN_MINUTES = 10;
    /** A level only drops when the occupancy is this many percentage points below its threshold (stops a level flipping at the boundary). */
    public static final int HYSTERESIS_PERCENT = 2;
    /** Archived incidents kept waiting for the database at most; with no database the oldest are dropped. */
    public static final int MAX_PENDING_ARCHIVE = 10_000;
    /** Category code of SOS incidents; built in, so every venue has it. */
    public static final String SOS_CODE = "SOS";

    // time source (fixed in tests)
    private final Clock clock;
    // the loaded venue
    private VenueProfile venue;
    // MODERATE / HIGH / CRITICAL start percentages, copied from the venue
    private int[] thresholds = { 50, 75, 90 };
    // area ID -> area
    private final AVLTree<String, Area> areas = new AVLTree<>();
    // unit ID -> unit
    private final AVLTree<String, Unit> units = new AVLTree<>();
    // incident number -> incident (sequential keys, so the AVL balancing matters)
    private final AVLTree<Integer, Incident> incidents = new AVLTree<>();
    // category code -> category
    private final AVLTree<String, Category> categories = new AVLTree<>();
    // areas and walking links
    private final Graph map = new Graph();
    // waiting incidents, one queue per priority, indexed by Priority.ordinal()
    private final Queue<Incident>[] queues = newQueues();
    // the newest MAX_ALERTS alerts, newest first
    private final LinkedList<Alert> alerts = new LinkedList<>();
    // registered output hooks
    private final LinkedList<MonitorListener> listeners = new LinkedList<>();
    // area ID -> Dijkstra result from that area; cleared when areas or links change
    private final AVLTree<String, Graph.Paths> distanceCache = new AVLTree<>();
    // delivers listener calls on its own thread, outside this monitor's lock
    private final EventDispatcher events = new EventDispatcher();
    // true while an onChange delivery is queued, so a burst of changes is delivered once
    private boolean changePending;
    // distance cache statistics (shown in the GUI and checked by the tests)
    private int cacheHits, cacheMisses;
    // number given to the next reported incident
    private int nextIncidentId = 1001;
    // built-in SOS category: CRITICAL, any unit may respond
    private final Category sos = new Category(SOS_CODE, "SOS - person in danger", Priority.CRITICAL, null, false);
    // automatic dispatch on crowd levels (the officer can switch it off)
    private boolean automation = true;
    // security officer: last name checked in, time of the check-in, time of the last SUPERVISION alert
    private String officer;
    private LocalDateTime officerCheckIn, supervisionAlerted;
    // evacuation in progress: zone (area ID or sector), who ordered it and when; null when none
    private String evacuationZone, evacuationBy;
    private LocalDateTime evacuationStarted;
    // evacuation plan, one row per area: {area, exit, path, walk minutes, people, minutes to clear}
    private final LinkedList<String[]> evacuationPlan = new LinkedList<>();
    // estimated minutes until the last person is out, for the current plan (0 if not known)
    private int evacuationClear;
    // layout: "A|B" (IDs in order) -> {people per minute the link can carry (0 = no limit), 1 if it is a lift}
    private final AVLTree<String, int[]> linkInfo = new AVLTree<>();
    // layout: exit area -> people per minute it lets out
    private final AVLTree<String, Integer> exitFlow = new AVLTree<>();
    // delivery channels for unit messages (radio gateway, console relay)
    private final LinkedList<UnitChannel> channels = new LinkedList<>();
    // resolved incidents taken out of memory, waiting to be written to the archive table by the next save
    private LinkedList<Incident> pendingArchive = new LinkedList<>();
    // incidents archived since the venue was created
    private int archivedCount;
    // the further automatic rules (sensor health, follow-ups, flow, lost persons, shifts, signs ...)
    final AutoRules rules = new AutoRules(this);
    // when the database last saved this monitor, and the newest backup folder (set by Database)
    private LocalDateTime lastSaved;
    private String lastBackup;

    /**
     * Creates an empty monitor. Call {@link #loadVenue} before using it.
     *
     * @param clock time source; {@link Clock#SYSTEM} in the application, a fixed clock in tests
     */
    public CrowdMonitor(Clock clock) { this.clock = clock; }

    /** Creates one empty queue for each priority level. */
    @SuppressWarnings("unchecked")
    private static Queue<Incident>[] newQueues() {
        Queue<Incident>[] q = (Queue<Incident>[]) new Queue[Priority.values().length];
        for (int i = 0; i < q.length; i++) q[i] = new Queue<>();
        return q;
    }

    /** @return the current time from the clock */
    public LocalDateTime now() { return clock.now(); }
    /** @return the loaded venue profile */
    public VenueProfile venue() { return venue; }
    /** @return the venue's name, or "No venue" before one is loaded */
    public String venueName() { return venue == null ? "No venue" : venue.name; }
    /** @return the three level thresholds in percent (MODERATE, HIGH, CRITICAL) */
    public int[] thresholds() { return thresholds; }
    /** Direct access to the area tree. Hold the monitor's lock while using it. */
    public AVLTree<String, Area> areaTree() { return areas; }
    /** Direct access to the unit tree. Hold the monitor's lock while using it. */
    public AVLTree<String, Unit> unitTree() { return units; }
    /** Direct access to the incident tree. Hold the monitor's lock while using it. */
    public AVLTree<Integer, Incident> incidentTree() { return incidents; }
    /** Direct access to the category tree. Hold the monitor's lock while using it. */
    public AVLTree<String, Category> categoryTree() { return categories; }
    /** Direct access to the area graph. Hold the monitor's lock while using it. */
    public Graph map() { return map; }
    /** @return the waiting queue for one priority level */
    public Queue<Incident> queue(Priority p) { return queues[p.ordinal()]; }
    /** @return the newest alerts (at most {@link #MAX_ALERTS}), newest first */
    public LinkedList<Alert> alerts() { return alerts; }
    /** @return the number the next incident will get */
    public int nextIncidentId() { return nextIncidentId; }
    /** Sets the next incident number (used when loading from the database). */
    public void setNextIncidentId(int id) { nextIncidentId = id; }
    /** @return how many distance lookups were answered from the cache */
    public int cacheHits() { return cacheHits; }
    /** @return how many distance lookups needed a new Dijkstra run */
    public int cacheMisses() { return cacheMisses; }

    /**
     * Shortest walking times from one area to every other area, with caching.
     * <p>
     * The first request for an area runs Dijkstra (O((V+E) log V)) and stores the result in an
     * AVL tree. Later requests for the same area are an O(log V) lookup. The cache is cleared
     * whenever an area or link is added, because the distances may change.
     *
     * @param areaId starting area
     * @return distances and paths from that area
     */
    public synchronized Graph.Paths distancesFrom(String areaId) {
        Graph.Paths p = distanceCache.search(areaId);
        if (p != null) { cacheHits++; return p; }
        cacheMisses++;
        p = map.shortestPaths(areaId);
        distanceCache.insert(areaId, p);
        return p;
    }

    /** Registers an output hook that is told about every alert and change. */
    public synchronized void addListener(MonitorListener l) { listeners.addLast(l); }
    /** Unregisters an output hook. */
    public synchronized void removeListener(MonitorListener l) { listeners.remove(l); }

    /**
     * Tells every listener that something changed (the GUI refreshes).
     * The call is queued for the event thread; if one is already queued, nothing more is added,
     * so a burst of sensor updates causes a single onChange.
     */
    void changed() {
        if (listeners.isEmpty() || changePending) return;
        changePending = true;
        events.post(() -> {
            MonitorListener[] ls;
            synchronized (this) { changePending = false; ls = listeners.toArray(new MonitorListener[0]); }
            for (MonitorListener l : ls) l.onChange();
        });
    }

    /**
     * Records a new alert at the front of the alert list and queues it for every listener.
     * Only the newest {@link #MAX_ALERTS} alerts are kept.
     * Listeners are called on the event thread, after this method returns, never under the
     * monitor's lock, so a slow listener cannot hold up the monitor.
     */
    void alert(AlertKind kind, String ref, String text) {
        Alert a = new Alert(now(), kind, ref, text);
        alerts.addFirst(a);
        if (alerts.size() > MAX_ALERTS) alerts.removeLast();
        if (listeners.isEmpty()) return;
        MonitorListener[] ls = listeners.toArray(new MonitorListener[0]);
        events.post(() -> { for (MonitorListener l : ls) l.onAlert(a); });
    }

    /**
     * Waits until every queued listener call has been delivered (used by the tests and
     * before shutting down). Do not call it while holding the monitor's lock.
     *
     * @param timeoutMillis longest time to wait
     * @return true if everything was delivered in time
     */
    public boolean awaitListeners(long timeoutMillis) {
        try { return events.awaitIdle(timeoutMillis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    /**
     * Replaces all data with a venue profile.
     * <p>
     * Clears every tree, the graph, the queues, the alerts and the distance cache, then builds
     * areas, links and categories from the profile.
     *
     * @param p    the venue
     * @param seed true to also create the sample units and starting counts from the profile;
     *             false when the database is about to restore its own units and incidents
     */
    public synchronized void loadVenue(VenueProfile p, boolean seed) {
        venue = p;
        thresholds = p.thresholds.clone();
        distanceCache.clear(); areas.clear(); units.clear(); incidents.clear(); categories.clear(); map.clear(); alerts.clear();
        for (Queue<Incident> q : queues) q.clear();
        nextIncidentId = 1001;
        archivedCount = 0;
        pendingArchive = new LinkedList<>();
        officer = null; officerCheckIn = null; supervisionAlerted = null;
        evacuationZone = null; evacuationBy = null; evacuationStarted = null; evacuationPlan.clear();
        rules.reset();
        gateBaseline = 0; gateIn = 0; gateOut = 0; gateCounting = false; countMismatchSince = null; countMismatchAlerted = false;
        for (String[] a : p.areas) {
            boolean restricted = a.length > 5 && a[5].equalsIgnoreCase("restricted");
            addArea(a[0], a[1], a[2], a[3], Integer.parseInt(a[4]), restricted);
        }
        linkInfo.clear();
        exitFlow.clear();
        for (String[] pos : p.positions) if (findArea(pos[0]) == null) throw new IllegalArgumentException("pos: unknown area " + pos[0]);
        for (String[] l : p.links) {
            int minutes = l[2].equalsIgnoreCase("auto") ? autoMinutes(p, l[0], l[1]) : Integer.parseInt(l[2]);
            if (!connect(l[0], l[1], minutes)) throw new IllegalArgumentException("link: cannot link " + l[0] + " and " + l[1]);
            int capacity = 0, lift = 0;
            for (int k = 3; k < l.length; k++) {
                if (l[k].equalsIgnoreCase("lift")) lift = 1;
                else if (l[k].matches("\\d+")) capacity = Integer.parseInt(l[k]);
                else if (!l[k].equalsIgnoreCase("stairs") && !l[k].isEmpty()) throw new IllegalArgumentException("link " + l[0] + " " + l[1] + ": unknown option '" + l[k] + "' (people per minute, stairs or lift)");
            }
            if (capacity > 0 || lift > 0) linkInfo.insert(linkKey(l[0], l[1]), new int[] { capacity, lift });
        }
        for (String[] f : p.exitFlows) {
            if (!p.exits.contains(f[0])) throw new IllegalArgumentException("exitflow: " + f[0] + " is not on the exit: line");
            exitFlow.insert(f[0], Integer.parseInt(f[1]));
        }
        for (String[] c : p.categories) {
            addCategory(c[0], c.length > 1 ? c[1] : c[0], Priority.valueOf(c[2].toUpperCase()), c.length > 3 ? c[3] : null, c.length > 4 && c[4].equalsIgnoreCase("personal"));
        }
        if (p.autoCategory != null && categories.search(p.autoCategory) == null)
            throw new IllegalArgumentException("auto: category " + p.autoCategory + " is not defined in the venue file");
        for (String e : p.exits) if (findArea(e) == null) throw new IllegalArgumentException("exit: unknown area " + e);
        if (p.meetingPoint != null) for (String mp : p.meetingPoint.split("\\s+")) if (findArea(mp) == null) throw new IllegalArgumentException("meeting: unknown area " + mp);
        for (String[] z : p.fireZones) for (int k = 1; k < z.length; k++) if (findArea(z[k]) == null) throw new IllegalArgumentException("zone " + z[0] + ": unknown area " + z[k]);
        for (String[] ev : p.events) if (zoneAreas(ev[2]).isEmpty()) throw new IllegalArgumentException("event: no area or sector called " + ev[2]);
        if (seed) {
            alert(AlertKind.SYSTEM, p.code, "Venue loaded: " + p.name);
            for (String[] u : p.units) addUnit(u[0], u[1], u[2], u.length > 3 ? u[3].split(",") : new String[0]);
            for (String[] o : p.occupancy) updateOccupancy(o[0], Integer.parseInt(o[1]), Source.OFFICIAL);
        }
        changed();
    }

    /**
     * Adds an area to the tree and the graph. IDs are stored in upper case.
     *
     * @return the new area
     */
    public synchronized Area addArea(String id, String name, String sector, String type, int capacity, boolean restricted) {
        Area a = new Area(id.trim().toUpperCase(), name.trim(), sector.trim(), type.trim().toUpperCase(), capacity, restricted);
        areas.insert(a.id, a);
        map.addNode(a.id);
        distanceCache.clear();
        return a;
    }

    /**
     * Adds a two-way walking link between two areas and clears the distance cache.
     *
     * @return false if an area is unknown, the link exists already, or the time is not positive
     */
    public synchronized boolean connect(String a, String b, int walkMinutes) {
        distanceCache.clear();
        return map.connect(norm(a), norm(b), walkMinutes);
    }

    /**
     * Adds an incident category. The code is stored in upper case.
     *
     * @return the new category
     */
    public synchronized Category addCategory(String code, String label, Priority priority, String skill, boolean personal) {
        Category c = new Category(code.trim().toUpperCase(), label.trim(), priority, skill, personal);
        categories.insert(c.code, c);
        return c;
    }

    /**
     * @param id area ID (not case-sensitive)
     * @return the area, or null if there is none
     */
    public synchronized Area findArea(String id) { return id == null ? null : areas.search(norm(id)); }
    /**
     * @param id unit ID (not case-sensitive)
     * @return the unit, or null if there is none
     */
    public synchronized Unit findUnit(String id) { return id == null ? null : units.search(norm(id)); }
    /**
     * @param id incident number, e.g. 1001
     * @return the incident, or null if there is none
     */
    public synchronized Incident findIncident(int id) { return incidents.search(id); }
    /**
     * @param code category code (not case-sensitive)
     * @return the category, or null if there is none
     */
    public synchronized Category findCategory(String code) {
        if (code == null) return null;
        Category c = categories.search(norm(code));
        return c == null && SOS_CODE.equals(norm(code)) ? sos : c;
    }

    /**
     * Adds a response unit at an area. A new unit is free at once, so any waiting incident it
     * can take is dispatched to it.
     *
     * @param skills skills and languages, e.g. "Arabic", "first-aid"
     * @return refused if the ID or name is missing, the ID is taken, or the area is unknown
     */
    public synchronized Result addUnit(String id, String name, String areaId, String... skills) {
        if (id == null || id.isBlank() || name == null || name.isBlank()) return Result.fail("Unit ID and name are required.");
        if (findUnit(id) != null) return Result.fail("Unit " + norm(id) + " already exists.");
        Area a = findArea(areaId);
        if (a == null) return Result.fail("Unknown area: " + areaId);
        Unit u = new Unit(norm(id), name.trim(), a);
        for (String s : skills) if (s != null && !s.isBlank()) u.skills.addLast(s.trim());
        u.history.addLast(new HistoryEntry(now(), "Unit created at " + a.id));
        units.insert(u.id, u);
        String extra = dispatchWaiting();
        changed();
        return Result.ok("Unit " + u.id + " added at " + a.name + "." + extra);
    }

    /**
     * Moves a unit to another area.
     *
     * @param approved supervisor approval; required to enter a restricted area
     * @return refused if the unit or area is unknown, it is already there, the area is restricted
     *         without approval, or the area cannot be reached from where the unit is
     */
    public synchronized Result moveUnit(String unitId, String areaId, boolean approved) { return moveUnit(unitId, areaId, approved ? "supervisor" : null); }

    /**
     * Moves a unit; a restricted area needs the name of the person who approved it, which is kept
     * in the unit's history.
     *
     * @param approvedBy who approved entering a restricted area, or null if nobody did
     */
    public synchronized Result moveUnit(String unitId, String areaId, String approvedBy) {
        boolean approved = approvedBy != null && !approvedBy.isBlank();
        Unit u = findUnit(unitId);
        if (u == null) return Result.fail("No unit " + unitId + ".");
        Area a = findArea(areaId);
        if (a == null) return Result.fail("Unknown area: " + areaId);
        if (a == u.area) return Result.fail("Unit " + u.id + " is already at " + a.id + ".");
        if (a.restricted && !approved) return Result.fail(a.name + " is restricted: supervisor approval is required.");
        if (distancesFrom(u.area.id).distanceTo(a.id) >= Graph.INF) return Result.fail(a.id + " cannot be reached from " + u.area.id + ".");
        String from = u.area.id;
        u.area = a;
        u.history.addLast(new HistoryEntry(now(), "Moved " + from + " -> " + a.id + (a.restricted ? " (approved by " + approvedBy + ")" : "")));
        changed();
        return Result.ok("Unit " + u.id + " moved from " + from + " to " + a.id + (a.restricted ? " (approved by " + approvedBy + ")" : "") + ".");
    }

    /**
     * Sets a unit AVAILABLE or OFF_DUTY.
     * <p>
     * A unit that is handling an incident cannot change status (it must resolve or reassign it
     * first), and BUSY can only be set by an assignment. A unit that becomes AVAILABLE immediately
     * takes the next waiting incident it is suitable for.
     *
     * @return the outcome, including any incident dispatched from a queue
     */
    public synchronized Result setUnitStatus(String unitId, UnitStatus status) {
        Unit u = findUnit(unitId);
        if (u == null) return Result.fail("No unit " + unitId + ".");
        if (u.status == status) return Result.fail("Unit " + u.id + " is already " + status + ".");
        if (activeIncident(u.id) != null) return Result.fail("Unit " + u.id + " is handling " + activeIncident(u.id) + "; resolve or reassign it first.");
        if (status == UnitStatus.BUSY) return Result.fail("A unit becomes BUSY only through an assignment.");
        u.status = status;
        u.history.addLast(new HistoryEntry(now(), "Status " + status));
        String extra = status == UnitStatus.AVAILABLE ? dispatchWaiting() : "";
        changed();
        return Result.ok("Unit " + u.id + " is now " + status + "." + extra);
    }

    /** @return the open incident assigned to this unit, or null if it has none */
    public synchronized Incident activeIncident(String unitId) {
        for (Incident i : incidents.inOrder()) if (i.open() && unitId.equals(i.unitId)) return i;
        return null;
    }

    /**
     * The new level for an area's count, with hysteresis: a level only drops once the occupancy
     * is at least {@link #HYSTERESIS_PERCENT} points below the threshold of the current level, so a
     * count hovering around 80% does not flip HIGH / MODERATE (and raise alerts) on every reading.
     * Rising is immediate.
     */
    private CrowdLevel levelWithHysteresis(Area a) {
        // during a scheduled event the area's thresholds are lower: the same as a higher percentage
        int pct = a.percent() + a.thresholdOffset;
        CrowdLevel next = levelFor(pct);
        if (next.compareTo(a.level) >= 0 || a.level == CrowdLevel.LOW) return next;
        int threshold = thresholds[a.level.ordinal() - 1];   // MODERATE -> thresholds[0], HIGH -> [1], CRITICAL -> [2]
        return pct > threshold - HYSTERESIS_PERCENT ? a.level : next;
    }

    /**
     * Converts an occupancy percentage to a crowd level using the venue's three thresholds.
     * For the sample airport (60/80/95): below 60 LOW, 60-79 MODERATE, 80-94 HIGH, 95 and above CRITICAL.
     */
    public synchronized CrowdLevel levelFor(int percent) {
        if (percent >= thresholds[2]) return CrowdLevel.CRITICAL;
        if (percent >= thresholds[1]) return CrowdLevel.HIGH;
        if (percent >= thresholds[0]) return CrowdLevel.MODERATE;
        return CrowdLevel.LOW;
    }

    /**
     * Sets the people count of an area, for example from a camera head count or a staff count.
     * <p>
     * The count is sampled into the area's circular buffer (see {@link Area#record}), the level is recomputed
     * (raising a CROWD alert or an all-clear if it crosses HIGH), and the trend is checked.
     *
     * @param count  number of people, must not be negative
     * @param source where the count came from
     * @return the outcome; the message mentions a CROWD or TREND alert if one was raised
     */
    public synchronized Result updateOccupancy(String areaId, int count, Source source) {
        Area a = findArea(areaId);
        if (a == null) return Result.fail("Unknown area: " + areaId);
        if (count < 0) return Result.fail("Occupancy cannot be negative.");
        int before = a.occupancy;
        LocalDateTime previous = a.updated;
        a.occupancy = count;
        a.record(now(), count);
        Result r = applyLevel(a, levelWithHysteresis(a), source, a.id + " occupancy " + count + "/" + a.capacity + " (" + a.percent() + "%)");
        rules.onReading(a, before, previous, count, source);
        String trend = checkTrend(a);
        return trend == null ? r : Result.ok(r.message + trend);
    }

    /**
     * @return how fast the area is filling over the last {@link #TREND_WINDOW_MINUTES} minutes, in people per minute
     */
    public synchronized double ratePerMinute(Area a) { return a.ratePerMinute(now(), TREND_WINDOW_MINUTES); }

    /**
     * @return predicted minutes until the area reaches CRITICAL at the current rate; 0 if already there, -1 if not rising
     */
    public synchronized int minutesToCritical(Area a) { return a.minutesTo(thresholds[2] - a.thresholdOffset, ratePerMinute(a)); }

    /**
     * Trend warning: raises one TREND alert when an area that is not yet CRITICAL is expected to
     * reach CRITICAL within {@link #TREND_WARNING_MINUTES} minutes.
     * The {@code trendWarned} flag stops the same warning repeating on every reading; it is reset
     * as soon as the area is no longer rising towards CRITICAL.
     *
     * If the venue's {@code auto:} line ends with "trend", the warning also starts automatic dispatch.
     * After a TREND alert the area is quiet for {@link #TREND_COOLDOWN_MINUTES} minutes, even if the
     * rise stops and starts again (sensor counts often jump up and down).
     *
     * @return text describing the new TREND alert (and any automatic dispatch), or null if none was raised
     */
    private String checkTrend(Area a) {
        int eta = minutesToCritical(a);
        boolean soon = a.level != CrowdLevel.CRITICAL && eta > 0 && eta <= TREND_WARNING_MINUTES;
        if (!soon) { a.trendWarned = false; return null; }
        if (a.trendWarned) return null;
        a.trendWarned = true;
        if (a.lastTrendAlert != null && Duration.between(a.lastTrendAlert, now()).toMinutes() < TREND_COOLDOWN_MINUTES) return null;
        a.lastTrendAlert = now();
        alert(AlertKind.TREND, a.id, a.name + " rising " + Math.round(ratePerMinute(a)) + " people/min - CRITICAL expected in about " + eta + " min");
        String auto = venue != null && venue.autoOnTrend ? autoDispatch(a, true) : "";
        changed();
        return " TREND: " + a.name + " expected CRITICAL in about " + eta + " min." + auto;
    }

    /**
     * Automatic dispatch: when an area reaches the venue's {@code auto:} level (or gets a TREND
     * warning, if configured), reports the venue's crowd category there and sends the nearest
     * suitable unit, exactly as if a person had reported it.
     * <p>
     * Only one automatic incident per area is open at a time; while it is open, further readings
     * do not create new ones, and reaching CRITICAL raises its priority to CRITICAL instead.
     * The incident stays open when the area calms down: a security officer reviews it and closes it.
     *
     * @param trend true if called because of a TREND warning
     * @return text describing what was done, or an empty string
     */
    private String autoDispatch(Area a, boolean trend) {
        if (!automation || venue == null || venue.autoCategory == null || a.restricted) return "";
        if (!trend && a.level.compareTo(CrowdLevel.valueOf(venue.autoLevel)) < 0) return "";
        Incident open = a.autoIncident == 0 ? null : findIncident(a.autoIncident);
        if (open != null && open.open()) {
            if (a.level == CrowdLevel.CRITICAL && open.priority != Priority.CRITICAL) {
                raise(open, Priority.CRITICAL, a.id + " reached CRITICAL");
                return " AUTO: " + open + " raised to CRITICAL.";
            }
            return "";
        }
        Category c = findCategory(venue.autoCategory);
        String why = trend ? "rising fast, CRITICAL expected in about " + minutesToCritical(a) + " min" : a.level.name();
        Incident i = create(c, a, "Automatic: " + a.name + " at " + a.percent() + "% (" + why + ")", "", null);
        i.automatic = true;
        if (a.level == CrowdLevel.CRITICAL) i.priority = Priority.CRITICAL;
        a.autoIncident = i.id;
        String outcome = dispatchNew(i);
        alert(AlertKind.AUTO_DISPATCH, i.toString(), "Automatic " + c.label + " at " + a.id + " (" + why + "): " + i + " " + (outcome.startsWith(" and ") ? outcome.substring(5) : outcome.substring(2)) + " Officer review needed.");
        return " AUTO: " + i + outcome;
    }

    /** Name used for "outside the venue" in {@link #movePeople}: a gate counter at the venue boundary. */
    public static final String OUTSIDE = "OUTSIDE";

    // gate counting: total in the areas when the count was last checked by hand, and people in and out since
    private long gateBaseline, gateIn, gateOut;
    private boolean gateCounting;
    // when the areas' total and the gate counts started to disagree (null while they agree)
    private LocalDateTime countMismatchSince;
    private boolean countMismatchAlerted;

    /**
     * People counted walking from one area to another, for example by a door counter between two
     * zones. Both areas change together, so a person is never counted in both (or in neither), as can
     * happen when a counter sends "+1 to B" and "-1 to A" as two separate updates and one is lost.
     * <p>
     * Use {@link #OUTSIDE} as {@code from} for people entering the venue through an outer gate and as
     * {@code to} for people leaving it; these totals feed the count check ({@link #countCheck}).
     * The two areas must have a walking link (or be an exit and OUTSIDE), which catches a counter
     * wired to the wrong pair of areas.
     *
     * @param people how many people crossed, 1 to 10,000
     * @return the outcome; it says so if {@code from} had fewer people than crossed (its count was too low)
     */
    public synchronized Result movePeople(String fromId, String toId, int people, Source source) {
        boolean fromOut = OUTSIDE.equalsIgnoreCase(fromId == null ? "" : fromId.trim()), toOut = OUTSIDE.equalsIgnoreCase(toId == null ? "" : toId.trim());
        if (people <= 0 || people > 10_000) return Result.fail("The number of people must be 1 to 10,000.");
        if (fromOut && toOut) return Result.fail("Choose at least one area.");
        Area from = fromOut ? null : findArea(fromId), to = toOut ? null : findArea(toId);
        if (!fromOut && from == null) return Result.fail("Unknown area: " + fromId);
        if (!toOut && to == null) return Result.fail("Unknown area: " + toId);
        if (from == to) return Result.fail("From and to are the same area.");
        if (from != null && to != null && !linked(from.id, to.id))
            return Result.fail(from.id + " and " + to.id + " have no walking link: check which areas this counter is between.");
        if ((fromOut && !venue.exits.contains(to.id)) || (toOut && !venue.exits.contains(from.id)))
            return Result.fail((fromOut ? to.id : from.id) + " is not an exit: people can only enter or leave the venue through an exit area.");
        String note = "";
        // the first gate count starts the check from the people already counted inside
        if ((fromOut || toOut) && !gateCounting) { gateBaseline = peopleCounted(); gateCounting = true; }
        if (from != null) {
            if (from.occupancy < people) note = " " + from.id + " had only " + from.occupancy + " counted, so its count was too low: recount it.";
            updateOccupancy(from.id, Math.max(0, from.occupancy - people), source);
        } else { gateIn += people; gateCounting = true; }
        if (to != null) updateOccupancy(to.id, to.occupancy + people, source);
        else { gateOut += people; gateCounting = true; }
        changed();
        return Result.ok(people + " moved " + (fromOut ? "in through " + to.id : toOut ? "out through " + from.id : from.id + " -> " + to.id) + "." + note);
    }

    /** @return true if two areas have a direct walking link */
    private boolean linked(String a, String b) {
        for (Graph.Edge e : map.neighbours(map.indexOf(a))) if (map.name(e.to).equals(b)) return true;
        return false;
    }

    /** @return the people counted in all areas now */
    public synchronized long peopleCounted() {
        long n = 0;
        for (Area a : areas.inOrder()) n += a.occupancy;
        return n;
    }

    /**
     * The count check: with gate counting in use (people in and out through {@link #OUTSIDE}), the people
     * the gates say are inside should equal the total of all areas.
     *
     * @return {expected, counted, difference}, or null if gates are not being counted
     */
    public synchronized long[] countCheck() {
        if (!gateCounting) return null;
        long expected = gateBaseline + gateIn - gateOut, counted = peopleCounted();
        return new long[] { expected, counted, counted - expected };
    }

    /** After a full recount by hand: the areas' total becomes the starting point for the gate counts again. */
    public synchronized Result resetCountCheck(String by) {
        Result in = officerCheckIn(by);
        if (!in.ok) return in;
        gateBaseline = peopleCounted();
        gateIn = 0;
        gateOut = 0;
        countMismatchSince = null;
        countMismatchAlerted = false;
        alert(AlertKind.SENSOR, "count", "Count check reset by " + officer + " at " + gateBaseline + " people on site");
        changed();
        return Result.ok("Count check reset: " + gateBaseline + " people on site is the new starting point.");
    }

    /** @return {baseline, in, out, 1 if gate counting is in use} (saved with the database) */
    public synchronized long[] gateCounts() { return new long[] { gateBaseline, gateIn, gateOut, gateCounting ? 1 : 0 }; }

    /** Restores the gate counts after loading from the database. */
    public synchronized void restoreGateCounts(long baseline, long in, long out, boolean counting) { gateBaseline = baseline; gateIn = in; gateOut = out; gateCounting = counting; }

    /**
     * Called by the 15-second check: raises one SENSOR alert when the areas' total and the gate counts
     * have disagreed by more than the venue's {@code reconcile:} percentage (at least 50 people) for
     * {@link #COUNT_MISMATCH_MINUTES} minutes, and one more when they agree again.
     */
    String checkCounts(LocalDateTime t) {
        long[] c = countCheck();
        if (c == null || venue.reconcilePercent <= 0) return "";
        long allowed = Math.max(50, Math.abs(c[0]) * venue.reconcilePercent / 100);
        if (Math.abs(c[2]) <= allowed) {
            if (countMismatchAlerted) alert(AlertKind.SENSOR, "count", "Counts agree again: " + c[1] + " in the areas, gates say " + c[0]);
            countMismatchSince = null;
            countMismatchAlerted = false;
            return "";
        }
        if (countMismatchSince == null) countMismatchSince = t;
        if (countMismatchAlerted || Duration.between(countMismatchSince, t).toMinutes() < COUNT_MISMATCH_MINUTES) return "";
        countMismatchAlerted = true;
        alert(AlertKind.SENSOR, "count", "Counts do not add up: " + c[1] + " people in the areas, but the gates say " + c[0] + " are inside ("
            + (c[2] > 0 ? c[2] + " too many: someone is counted twice, for example overlapping cameras or a counter that only adds" : -c[2] + " missing: an area is not counted or a counter is lost")
            + "). Recount, then reset the count check.");
        return "COUNT_MISMATCH ";
    }

    /** Minutes the counts must disagree before the control room is told (short spikes while people walk between counters are normal). */
    public static final int COUNT_MISMATCH_MINUTES = 5;

    /**
     * Adds or removes people from the current count, as a turnstile or door counter would.
     * The count never goes below zero.
     *
     * @param delta positive for people entering, negative for people leaving
     */
    public synchronized Result adjustOccupancy(String areaId, int delta, Source source) {
        Area a = findArea(areaId);
        if (a == null) return Result.fail("Unknown area: " + areaId);
        return updateOccupancy(a.id, Math.max(0, a.occupancy + delta), source);
    }

    /** Records a crowd level observed by staff, without a people count (the original Umra Tech way). */
    public synchronized Result recordLevel(String areaId, CrowdLevel level, Source source) {
        Area a = findArea(areaId);
        if (a == null) return Result.fail("Unknown area: " + areaId);
        if (level == null) return Result.fail("Choose a crowd level.");
        return applyLevel(a, level, source, a.id + " observed " + level);
    }

    /**
     * Sets an area's level and raises alerts when it crosses {@link #ALERT_LEVEL}:
     * a CROWD alert when it rises to HIGH or CRITICAL (or from HIGH to CRITICAL),
     * and a CLEARED alert when it drops back below HIGH.
     */
    Result applyLevel(Area a, CrowdLevel level, Source source, String what) {
        CrowdLevel before = a.level;
        a.level = level;
        a.source = source;
        a.updated = now();
        if (level.compareTo(ALERT_LEVEL) < 0) { if (a.belowHighSince == null) a.belowHighSince = now(); } else a.belowHighSince = null;
        if (level == CrowdLevel.CRITICAL) { if (a.criticalSince == null) a.criticalSince = now(); } else a.criticalSince = null;
        String note = "";
        if (level.compareTo(ALERT_LEVEL) >= 0 && level.compareTo(before) > 0) {
            note = " ALERT: " + a.name + " is " + level + ".";
            alert(AlertKind.CROWD, a.id, a.name + " (" + a.sector + ") reached " + level + " - " + a.percent() + "% via " + source);
        } else if (level.compareTo(ALERT_LEVEL) < 0 && before.compareTo(ALERT_LEVEL) >= 0) {
            alert(AlertKind.CLEARED, a.id, a.name + " back to " + level);
            Incident auto = a.autoIncident == 0 ? null : findIncident(a.autoIncident);
            if (auto != null && auto.open())
                auto.history.addLast(new HistoryEntry(now(), a.id + " back to " + level + "; officer to confirm and close"));
        }
        if (level.compareTo(before) > 0) note += autoDispatch(a, false);
        note += rules.onLevel(a, before, level);
        changed();
        return Result.ok("Recorded " + what + "." + note);
    }

    /**
     * Dispatch rule: chooses the best available unit for an incident.
     * <p>
     * Only AVAILABLE units with the required skill that can reach the area are considered.
     * Each gets a score of walking minutes x 10, plus 5 if it does not speak the reporter's
     * language. The lowest score wins, so language decides only between units that are equally
     * close (walk times are whole minutes). Walking times come from the cached Dijkstra result.
     *
     * @param area     where help is needed
     * @param skill    required skill, or null for any unit
     * @param language reporter's language, or null
     * @return the chosen unit, or null if none is suitable
     */
    public synchronized Unit bestUnit(Area area, String skill, String language) { return bestUnit(area, skill, language, null); }

    /**
     * {@link #bestUnit(Area, String, String)} that never chooses {@code exclude} (for example the
     * unit that pressed SOS, or the one that just failed to answer).
     */
    Unit bestUnit(Area area, String skill, String language, Unit exclude) {
        Graph.Paths p = distancesFrom(area.id);
        Unit best = null;
        long bestScore = Long.MAX_VALUE;
        for (Unit u : units.inOrder()) {
            if (u == exclude || u.status != UnitStatus.AVAILABLE || !u.has(skill)) continue;
            int d = p.distanceTo(u.area.id);
            if (d >= Graph.INF) continue;
            // the language penalty (5) is half a minute, so a nearer unit always wins
            long score = d * 10L + (language != null && !u.has(language) ? 5 : 0);
            if (score < bestScore) { bestScore = score; best = u; }
        }
        return best;
    }

    /**
     * Records a new incident and dispatches it straight away.
     * <p>
     * If a suitable unit is free, the incident is assigned to it. Otherwise it waits at the
     * end of the queue for its priority and a NO_UNIT alert is raised.
     *
     * @param categoryCode category code from the venue profile, e.g. LOST_PERSON
     * @param areaId       where help is needed
     * @param details      description (required for personal-data categories)
     * @param contact      reporter's phone or other contact
     * @param language     reporter's language, or blank
     * @return the outcome: assigned to which unit, or the queue position
     */
    public synchronized Result reportIncident(String categoryCode, String areaId, String details, String contact, String language) {
        Category c = findCategory(categoryCode);
        if (c == null) return Result.fail("Unknown incident category: " + categoryCode);
        Area a = findArea(areaId);
        if (a == null) return Result.fail("The location is required.");
        if (c.personalData && (details == null || details.isBlank())) return Result.fail(c.label + " needs a description.");
        Result tooLong = tooLong("Details", details, 400);
        if (tooLong == null) tooLong = tooLong("Contact", contact, 120);
        if (tooLong == null) tooLong = tooLong("Language", language, 40);
        if (tooLong != null) return tooLong;
        String lang = language == null || language.isBlank() ? null : language.trim();
        Incident i = create(c, a, details, contact, lang);
        String outcome = dispatchNew(i);
        outcome += rules.onReported(i);
        changed();
        return Result.ok(i + " recorded" + outcome);
    }

    /** Creates an incident, gives it the next number and stores it in the incident tree. */
    Incident create(Category c, Area a, String details, String contact, String lang) {
        Incident i = new Incident(nextIncidentId++, c, c.priority, a, details == null ? "" : details.trim(), contact == null ? "" : contact.trim(), lang, now());
        i.history.addLast(new HistoryEntry(now(), c.label + " reported at " + a.id + " (" + c.priority + ")"));
        incidents.insert(i.id, i);
        return i;
    }

    /**
     * Sends a new incident to the best unit, or puts it in its priority queue if none is suitable.
     *
     * @return the end of the result sentence: " and assigned to U at AREA." or ". No suitable unit is free, ..."
     */
    String dispatchNew(Incident i) {
        Unit u = bestUnit(i.area, i.category.skill, i.language);
        if (u != null) {
            assign(i, u);
            return " and assigned to " + u.id + " at " + u.area.id + ".";
        }
        queue(i.priority).enqueue(i);
        i.history.addLast(new HistoryEntry(now(), "No suitable unit free: queued at " + i.priority));
        alert(AlertKind.NO_UNIT, i.toString(), i + " " + i.category.label + " at " + i.area.id + " waiting (" + i.priority + " queue, position " + queue(i.priority).size() + ")");
        return ". No suitable unit is free, so it waits in the " + i.priority + " queue (position " + queue(i.priority).size() + ").";
    }

    /** Links an incident and a unit: the incident becomes ASSIGNED and the unit BUSY. */
    void assign(Incident i, Unit u) {
        u.stagedFor = null;
        if (u.reinforcing != null && !u.reinforcing.equals(i.area.id)) { u.reinforcing = null; u.home = null; }   // it leaves for another task
        i.status = IncidentStatus.ASSIGNED;
        i.unitId = u.id;
        u.status = UnitStatus.BUSY;
        i.history.addLast(new HistoryEntry(now(), "Assigned to " + u.id));
        u.history.addLast(new HistoryEntry(now(), "Assigned " + i + " " + i.category.label + " at " + i.area.id));
        i.notified = now();
        i.acknowledged = null;
        i.reminders = 0;
        notifyUnit(u, i.category == sos ? MessageKind.SOS : MessageKind.TASK, i, taskText(i, u));
        i.history.addLast(new HistoryEntry(now(), "Task sent to " + u.id + "'s device" + (channels.isEmpty() ? "" : " and radio")));
    }

    /**
     * The task as it is shown on the unit's device or read out on the radio: incident, priority,
     * place, walking route from where the unit is, and details.
     */
    String taskText(Incident i, Unit u) {
        Route r = route(u.area.id, i.area.id, false, false);
        String way = u.area == i.area ? "you are there" : r.found() ? r.path + " (" + r.walk + " min)" : "no public route, ask the control room";
        return (i.category == sos ? "SOS " : "TASK ") + i + " " + i.priority + ": " + i.category.label + " at " + i.area.id + " (" + i.area.name + "). Route: "
            + way + "." + (i.details.isEmpty() ? "" : " Details: " + i.details + ".") + " Acknowledge on your device or by radio.";
    }

    /**
     * Sends a message to a unit: stores it in the unit's inbox (shown on its device) and queues it
     * for every delivery channel. Channels run on the event thread, outside the lock; a failure
     * raises a DELIVERY alert.
     */
    void notifyUnit(Unit u, MessageKind kind, Incident i, String text) {
        UnitMessage msg = new UnitMessage(now(), kind, i == null ? 0 : i.id, text);
        u.inbox.add(msg);
        if (channels.isEmpty()) return;
        UnitChannel[] cs = channels.toArray(new UnitChannel[0]);
        events.post(() -> {
            for (UnitChannel c : cs) {
                try { c.deliver(u, msg); }
                catch (Exception e) { deliveryFailed(c, u, msg, e); }
            }
        });
    }

    /** Called on the event thread when a channel could not deliver: tells the control room. */
    private synchronized void deliveryFailed(UnitChannel c, Unit u, UnitMessage msg, Exception e) {
        alert(AlertKind.DELIVERY, u.id, msg.kind + " for " + u.id + " not delivered via " + c.name() + " (" + e.getMessage() + "): reach the unit another way");
        changed();
    }

    /**
     * Registers a delivery channel (radio gateway, console relay) for unit messages.
     * Every open task that has not been acknowledged yet is sent to the new channel at once, so a
     * task created before the channel was connected (for example at startup) still reaches the unit.
     */
    public synchronized void addChannel(UnitChannel c) {
        channels.addLast(c);
        for (Incident i : incidents.inOrder()) {
            if (i.status != IncidentStatus.ASSIGNED || i.acknowledged != null) continue;
            Unit u = findUnit(i.unitId);
            if (u == null) continue;
            UnitMessage msg = new UnitMessage(now(), i.category == sos ? MessageKind.SOS : MessageKind.TASK, i.id, taskText(i, u));
            events.post(() -> {
                try { c.deliver(u, msg); }
                catch (Exception e) { deliveryFailed(c, u, msg, e); }
            });
        }
    }

    /** Hands a public message (signs, PA) to every delivery channel, on the event thread. */
    void announceToChannels(String text) {
        if (channels.isEmpty()) return;
        UnitChannel[] cs = channels.toArray(new UnitChannel[0]);
        events.post(() -> {
            for (UnitChannel c : cs) {
                try { c.announce(text); }
                catch (Exception e) { synchronized (this) { alert(AlertKind.DELIVERY, "signs", "Public message not delivered via " + c.name() + " (" + e.getMessage() + ")"); changed(); } }
            }
        });
    }

    /** Unregisters a delivery channel. */
    public synchronized void removeChannel(UnitChannel c) { channels.remove(c); }

    /**
     * Frees the unit of an incident (back to AVAILABLE) and notes why in its history.
     *
     * @return the freed unit, or null if the incident had none
     */
    Unit release(Incident i, String why) {
        if (i.unitId == null) return null;
        Unit u = findUnit(i.unitId);
        if (u != null) {
            u.status = UnitStatus.AVAILABLE;
            if (u.offAfterTask) {           // the shift ended during the task
                u.offAfterTask = false;
                u.shiftOff = true;
                u.status = UnitStatus.OFF_DUTY;
                u.history.addLast(new HistoryEntry(now(), "Shift over: OFF_DUTY after " + i));
            }
            u.history.addLast(new HistoryEntry(now(), why + " (" + i + ")"));
            boolean done = why.equals("Resolved");
            notifyUnit(u, done ? MessageKind.RELEASED : MessageKind.CANCELLED, i,
                done ? i + " closed. You are free for the next task." : "STAND DOWN from " + i + ": " + why + ". Wait for your next task.");
        }
        return u;
    }

    /**
     * Moves an incident's unit to the incident area, because that is where it now is.
     * Without this, the unit would still be treated as standing at its old area, and later
     * "nearest unit" decisions would use wrong walking times.
     */
    private void arrive(Incident i) {
        Unit u = i.unitId == null ? null : findUnit(i.unitId);
        if (u == null || u.area == i.area) return;
        u.history.addLast(new HistoryEntry(now(), "Arrived at " + i.area.id + " from " + u.area.id + " for " + i));
        u.area = i.area;
    }

    /**
     * Marks that the assigned unit has arrived. Only an ASSIGNED incident can go ON_SCENE.
     * The unit's location becomes the incident area.
     */
    public synchronized Result confirmOnScene(int id) {
        Incident i = findIncident(id);
        if (i == null) return Result.fail("No incident I" + id + ".");
        if (i.status != IncidentStatus.ASSIGNED) return Result.fail(i + " is " + i.status + "; only an ASSIGNED incident can be marked on scene.");
        i.status = IncidentStatus.ON_SCENE;
        i.onScene = now();
        i.history.addLast(new HistoryEntry(now(), i.unitId + " on scene"));
        if (i.acknowledged == null) i.acknowledged = now();
        Unit su = findUnit(i.unitId);
        if (su != null) contact(su);
        arrive(i);
        changed();
        return Result.ok(i.unitId + " is on scene at " + i + ".");
    }

    /**
     * Closes an incident.
     * <p>
     * The incident leaves its queue if it was waiting. Its unit is now at the incident area,
     * becomes free and takes the next waiting incident, and for personal-data categories the details and contact are replaced
     * by {@link Incident#ERASED}.
     *
     * @param note optional closing note for the history
     */
    public synchronized Result resolve(int id, String note) {
        Incident i = findIncident(id);
        if (i == null) return Result.fail("No incident I" + id + ".");
        if (!i.open()) return Result.fail(i + " is already resolved.");
        if (tooLong("Note", note, MAX_NOTE) != null) return tooLong("Note", note, MAX_NOTE);
        if (i.status == IncidentStatus.WAITING) queue(i.priority).remove(i);
        i.status = IncidentStatus.RESOLVED;
        i.resolved = now();
        if (i.category.personalData) { i.details = Incident.ERASED; i.contact = Incident.ERASED; }
        i.history.addLast(new HistoryEntry(now(), "Resolved after " + i.minutesOpen(now()) + " min" + (note == null || note.isBlank() ? "" : ": " + note.trim()) + (i.category.personalData ? "; personal data erased" : "")));
        arrive(i);
        release(i, "Resolved");
        String extra = dispatchWaiting();
        changed();
        return Result.ok(i + " resolved after " + i.minutesOpen(now()) + " min." + (i.category.personalData ? " Personal data erased." : "") + extra);
    }

    /**
     * Hands an open incident to a different available unit that has the required skill.
     * If the incident was waiting it leaves its queue; if it had a unit, that unit becomes free.
     *
     * @param reason why it was reassigned (written to the history)
     */
    public synchronized Result reassign(int id, String unitId, String reason) {
        Incident i = findIncident(id);
        if (i == null) return Result.fail("No incident I" + id + ".");
        if (!i.open()) return Result.fail(i + " is resolved.");
        Unit nu = findUnit(unitId);
        if (nu == null) return Result.fail("No unit " + unitId + ".");
        if (tooLong("Reason", reason, MAX_NOTE) != null) return tooLong("Reason", reason, MAX_NOTE);
        if (nu.status != UnitStatus.AVAILABLE) return Result.fail("Unit " + nu.id + " is not available.");
        if (!nu.has(i.category.skill)) return Result.fail("Unit " + nu.id + " lacks the skill '" + i.category.skill + "'.");
        String old = i.unitId;
        if (old == null) queue(i.priority).remove(i); else release(i, "Handed over");
        i.onScene = null;
        assign(i, nu);
        i.history.addLast(new HistoryEntry(now(), "Reassigned " + (old == null ? "from queue" : old) + " -> " + nu.id + ": " + (reason == null || reason.isBlank() ? "no reason given" : reason.trim())));
        String extra = dispatchWaiting();
        changed();
        return Result.ok(i + " reassigned " + (old == null ? "from the queue" : "from " + old) + " to " + nu.id + "." + extra);
    }

    /**
     * Raises an open incident's priority by one level (LOW -&gt; NORMAL -&gt; HIGH -&gt; CRITICAL).
     * A waiting incident moves to the rear of the higher queue.
     */
    public synchronized Result escalate(int id) {
        Incident i = findIncident(id);
        if (i == null) return Result.fail("No incident I" + id + ".");
        if (!i.open()) return Result.fail(i + " is resolved.");
        if (i.priority == Priority.CRITICAL) return Result.fail(i + " is already CRITICAL.");
        Priority from = i.priority;
        // enum order is CRITICAL, HIGH, NORMAL, LOW, so one level up is ordinal - 1
        Priority to = Priority.values()[from.ordinal() - 1];
        raise(i, to, null);
        changed();
        return Result.ok(i + " escalated from " + from + " to " + to + ".");
    }

    /**
     * Raises an incident's priority: a waiting incident moves to the rear of the higher queue,
     * an assigned unit is told, and an ESCALATED alert is raised.
     *
     * @param why reason written to the history, or null
     */
    void raise(Incident i, Priority to, String why) {
        Priority from = i.priority;
        if (i.status == IncidentStatus.WAITING) { queue(from).remove(i); queue(to).enqueue(i); }
        i.priority = to;
        i.history.addLast(new HistoryEntry(now(), "Escalated " + from + " -> " + to + (why == null ? "" : ": " + why)));
        alert(AlertKind.ESCALATED, i.toString(), i + " escalated to " + to + (why == null ? "" : " (" + why + ")"));
        Unit u = i.unitId == null ? null : findUnit(i.unitId);
        if (u != null) notifyUnit(u, MessageKind.TASK, i, "PRIORITY RAISED to " + to + " for " + i + " at " + i.area.id + (why == null ? "" : ": " + why) + ".");
    }

    /**
     * Gives waiting incidents to free units, CRITICAL queue first, then HIGH, NORMAL and LOW,
     * first in first out within each queue.
     * <p>
     * After each assignment the scan starts again from the CRITICAL queue, because the unit just
     * used may have been the only one that could serve a higher-priority incident. It stops when a
     * full pass assigns nothing. An incident that no free unit can serve is skipped, so it does not
     * block the ones behind it.
     *
     * @return text describing each assignment made, or an empty string
     */
    public synchronized String dispatchWaiting() {
        StringBuilder sb = new StringBuilder();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Queue<Incident> q : queues) {
                for (Incident i : q) {
                    Unit u = bestUnit(i.area, i.category.skill, i.language);
                    if (u == null) continue;
                    q.remove(i);
                    assign(i, u);
                    sb.append(" Next from queue: ").append(i).append(" -> ").append(u.id).append('.');
                    progress = true;
                    // the queue was changed while iterating it, so stop and rescan from CRITICAL
                    break;
                }
                if (progress) break;
            }
        }
        return sb.toString();
    }

    /** @return the total number of incidents waiting in all queues */
    public synchronized int waitingCount() {
        int n = 0;
        for (Queue<Incident> q : queues) n += q.size();
        return n;
    }

    /** A route between two areas for signage or staff guidance. */
    public static final class Route {
        // area IDs from start to destination; empty if there is no route
        public final LinkedList<String> path;
        // total edge cost used by Dijkstra (weighted by crowd level in crowd-aware mode)
        public final int cost;
        // real walking minutes along the path
        public final int walk;
        // order in which Dijkstra settled the areas
        public final LinkedList<String> visitOrder;

        /** Creates a route result (built by {@link CrowdMonitor#route}). */
        Route(LinkedList<String> path, int cost, int walk, LinkedList<String> visitOrder) {
            this.path = path;
            this.cost = cost;
            this.walk = walk;
            this.visitOrder = visitOrder;
        }

        /** @return true if a route exists */
        public boolean found() { return !path.isEmpty(); }
    }

    /**
     * Cost multiplier for entering an area in crowd-aware routing:
     * LOW x1, MODERATE x2, HIGH x4, CRITICAL x10.
     */
    public synchronized int congestionFactor(CrowdLevel level) {
        return switch (level) { case LOW -> 1; case MODERATE -> 2; case HIGH -> 4; case CRITICAL -> 10; };
    }

    /**
     * Finds a walking route between two areas with Dijkstra.
     * <p>
     * Restricted areas are never passed through (unless one is the destination). In
     * crowd-aware mode, each link's walk time is multiplied by the congestion factor of the area
     * it enters, so the route goes around HIGH and CRITICAL areas when a reasonable detour exists.
     * This runs a fresh Dijkstra (no cache) because the costs depend on live crowd levels.
     *
     * @param avoidCrowds true for the crowd-aware route, false for the shortest walk
     * @return the route; {@link Route#found()} is false if there is none
     */
    public synchronized Route route(String fromId, String toId, boolean avoidCrowds) { return route(fromId, toId, avoidCrowds, true); }

    /**
     * Route with a choice about the evacuation zone: public routes avoid it, while a responder
     * sent to a task inside the zone may pass through it.
     */
    Route route(String fromId, String toId, boolean avoidCrowds, boolean avoidEvacuation) {
        Area from = findArea(fromId), to = findArea(toId);
        if (from == null || to == null) return new Route(new LinkedList<>(), Graph.INF, Graph.INF, new LinkedList<>());
        Graph.Paths p = map.shortestPaths(from.id, (u, v, w) -> {
            Area next = areas.search(map.name(v));
            if ((next.restricted || (avoidEvacuation && next.evacuating)) && next != to) return Graph.INF;
            return avoidCrowds ? w * congestionFactor(next.level) : w;
        });
        LinkedList<String> path = p.pathTo(to.id);
        int walk = 0;
        String prev = null;
        // add up the real walk minutes along the path (the Dijkstra cost may be weighted)
        for (String id : path) {
            if (prev != null) for (Graph.Edge e : map.neighbours(map.indexOf(prev))) if (map.name(e.to).equals(id)) walk += e.weight;
            prev = id;
        }
        return new Route(path, p.distanceTo(to.id), path.isEmpty() ? Graph.INF : walk, p.visitOrder);
    }

    /** @return open incidents sorted by priority, then oldest first (merge sort, stable) */
    public synchronized Incident[] openIncidentsByPriority() {
        LinkedList<Incident> open = new LinkedList<>();
        for (Incident i : incidents.inOrder()) if (i.open()) open.addLast(i);
        Incident[] a = open.toArray(new Incident[0]);
        MergeSort.sort(a, (x, y) -> {
            int c = x.priority.compareTo(y.priority);
            return c != 0 ? c : x.reported.compareTo(y.reported);
        });
        return a;
    }

    /** @return all incidents sorted by time reported (merge sort) */
    public synchronized Incident[] incidentsByTime() {
        Incident[] a = incidents.inOrder().toArray(new Incident[0]);
        MergeSort.sort(a, (x, y) -> x.reported.compareTo(y.reported));
        return a;
    }

    /** @return areas sorted by level (CRITICAL first), then occupancy %, then ID (merge sort) */
    public synchronized Area[] areasByLoad() {
        Area[] a = areas.inOrder().toArray(new Area[0]);
        MergeSort.sort(a, (x, y) -> {
            int c = y.level.compareTo(x.level);
            if (c != 0) return c;
            c = Integer.compare(y.percent(), x.percent());
            return c != 0 ? c : x.id.compareTo(y.id);
        });
        return a;
    }

    /** @return units sorted by status, then sector, then ID (merge sort) */
    public synchronized Unit[] unitsSorted() {
        Unit[] a = units.inOrder().toArray(new Unit[0]);
        MergeSort.sort(a, (x, y) -> {
            int c = x.status.compareTo(y.status);
            if (c != 0) return c;
            c = x.area.sector.compareTo(y.area.sector);
            return c != 0 ? c : x.id.compareTo(y.id);
        });
        return a;
    }

    /**
     * @return a short text summary: people on site, hot areas, units, incidents, average time to resolve and queue sizes
     */
    public synchronized String summary() {
        int avail = 0, busy = 0, off = 0, open = 0, done = 0, people = 0, cap = 0, hot = 0;
        long total = 0;
        int unreachable = 0;
        for (Unit u : units.inOrder()) {
            if (u.status == UnitStatus.AVAILABLE) avail++; else if (u.status == UnitStatus.BUSY) busy++;
            else if (u.status == UnitStatus.UNREACHABLE) unreachable++; else off++;
        }
        for (Incident i : incidents.inOrder()) {
            if (i.open()) open++; else { done++; total += i.minutesOpen(now()); }
        }
        for (Area a : areas.inOrder()) {
            people += a.occupancy;
            cap += a.capacity;
            if (a.level.compareTo(ALERT_LEVEL) >= 0) hot++;
        }
        StringBuilder q = new StringBuilder();
        for (Priority p : Priority.values()) q.append(q.length() == 0 ? "" : ", ").append(p).append(' ').append(queue(p).size());
        return "Venue: " + venueName() + "\n"
            + "People on site: " + people + " of " + cap + " capacity (" + (cap == 0 ? 0 : Math.round(people * 100.0 / cap)) + "%)\n"
            + "Areas at HIGH or CRITICAL: " + hot + " of " + areas.size() + "\n"
            + "Units: " + units.size() + " (" + avail + " available, " + busy + " busy, " + off + " off duty" + (unreachable > 0 ? ", " + unreachable + " UNREACHABLE" : "") + ")\n"
            + "Incidents: " + open + " open, " + done + " resolved" + (done > 0 ? ", average " + total / done + " min to resolve" : "") + (archivedCount > 0 ? ", " + archivedCount + " archived" : "") + "\n"
            + "Waiting queues: " + q + "\n"
            + "Automatic dispatch: " + (venue == null || venue.autoCategory == null ? "not configured" : automation ? "ON (" + venue.autoCategory + " at " + venue.autoLevel + (venue.autoOnTrend ? " or TREND" : "") + ")" : "OFF") + "\n"
            + "Security officer: " + officerStatus() + "; automatic incidents awaiting review: " + awaitingReview().length
            + (evacuationZone == null ? "" : "\nEVACUATION IN PROGRESS: " + evacuationZone + " (ordered by " + evacuationBy + " at " + evacuationStarted.format(HistoryEntry.FORMAT) + ")");
    }

    // ================================================================ automation and emergency operations

    /**
     * Switches automatic dispatch on or off (for example during a drill or a planned event the
     * officer wants to handle by hand). Crowd alerts are still raised when it is off.
     */
    public synchronized void setAutomation(boolean on) {
        if (automation != on) alert(AlertKind.SYSTEM, "automation", "Automatic dispatch switched " + (on ? "ON" : "OFF") + (officer == null ? "" : " (" + officer + ")"));
        automation = on;
        changed();
    }

    /** @return true if crowd levels start automatic dispatch */
    public synchronized boolean automation() { return automation; }

    /**
     * A security officer confirms they are watching the system. The venue's {@code supervision:}
     * line says how often this is required; if it is missed, the watchdog raises a SUPERVISION alert.
     *
     * @param name officer's name or badge number
     */
    public synchronized Result officerCheckIn(String name) {
        if (name == null || name.isBlank()) return Result.fail("Officer name is required.");
        if (tooLong("Officer name", name, 40) != null) return tooLong("Officer name", name, 40);
        officer = name.trim();
        officerCheckIn = now();
        supervisionAlerted = null;
        changed();
        int waiting = awaitingReview().length;
        return Result.ok(officer + " checked in at " + officerCheckIn.format(HistoryEntry.FORMAT) + ". Next check-in due within "
            + supervisionMinutes() + " min." + (waiting > 0 ? " " + waiting + " automatic incident(s) await your review." : ""));
    }

    /** @return the officer's name and how long ago they checked in, or "none checked in" / "OVERDUE" */
    public synchronized String officerStatus() {
        if (officerCheckIn == null) return "none checked in";
        long ago = Duration.between(officerCheckIn, now()).toMinutes();
        return officer + ", checked in " + ago + " min ago" + (ago >= supervisionMinutes() ? " - OVERDUE" : "");
    }

    /** @return name of the last officer who checked in, or null */
    public synchronized String officer() { return officer; }

    // minutes between required officer check-ins
    private int supervisionMinutes() { return venue == null ? 15 : venue.supervisionMinutes; }

    /** @return open automatic incidents that no officer has reviewed yet, oldest first */
    public synchronized Incident[] awaitingReview() {
        LinkedList<Incident> out = new LinkedList<>();
        for (Incident i : incidents.inOrder()) if (i.automatic && i.reviewedBy == null && i.open()) out.addLast(i);
        return out.toArray(new Incident[0]);
    }

    /**
     * The officer reviews an automatic incident: either confirms the dispatch, or declares a false
     * alarm, which resolves the incident and frees its unit. A review also counts as a check-in.
     *
     * @param confirmed true to confirm, false for a false alarm
     * @param note      optional remark for the history
     */
    public synchronized Result review(int id, String officerName, boolean confirmed, String note) {
        Incident i = findIncident(id);
        if (i == null) return Result.fail("No incident I" + id + ".");
        if (!i.automatic) return Result.fail(i + " was reported by a person; only automatic incidents need review.");
        if (i.reviewedBy != null) return Result.fail(i + " was already reviewed by " + i.reviewedBy + ".");
        Result in = officerCheckIn(officerName);
        if (!in.ok) return in;
        if (tooLong("Note", note, 200) != null) return tooLong("Note", note, 200);
        i.reviewedBy = officer;
        String extra = note == null || note.isBlank() ? "" : ": " + note.trim();
        i.history.addLast(new HistoryEntry(now(), "Reviewed by " + officer + " - " + (confirmed ? "dispatch confirmed" : "false alarm") + extra));
        if (!confirmed) return resolve(id, "False alarm (" + officer + ")" + extra);
        changed();
        return Result.ok(i + " confirmed by " + officer + ".");
    }

    /**
     * Called when a unit acknowledges its current task on its device (or the control room
     * records a radio acknowledgement). Stops the reminders for that task.
     */
    public synchronized Result acknowledge(String unitId) {
        Unit u = findUnit(unitId);
        if (u == null) return Result.fail("No unit " + unitId + ".");
        contact(u);
        Incident i = activeIncident(u.id);
        if (i == null) { changed(); return Result.ok(u.id + " has no open task."); }
        if (i.acknowledged != null) return Result.ok(i + " was already acknowledged.");
        i.acknowledged = now();
        i.history.addLast(new HistoryEntry(now(), u.id + " acknowledged the task"));
        changed();
        return Result.ok(u.id + " acknowledged " + i + ".");
    }

    /** Records that a unit's device asked for its task list (shows the device is online). */
    public synchronized void devicePoll(String unitId) {
        Unit u = findUnit(unitId);
        if (u != null) u.lastContact = now();
    }

    /**
     * A person on the unit answered (acknowledge, on scene, SOS). An UNREACHABLE unit becomes
     * AVAILABLE again and can take waiting incidents.
     */
    private void contact(Unit u) {
        u.lastContact = now();
        if (u.status == UnitStatus.UNREACHABLE) {
            u.status = UnitStatus.AVAILABLE;
            u.history.addLast(new HistoryEntry(now(), "Answered again: AVAILABLE"));
            alert(AlertKind.SYSTEM, u.id, u.id + " is reachable again");
            dispatchWaiting();
        }
    }

    /**
     * Time-based checks, run every 15 seconds by the {@link Watchdog}:
     * <ol>
     * <li>Supervision: if no security officer has checked in within the venue's supervision time,
     *     raise a SUPERVISION alert (repeated once per supervision period until someone checks in).</li>
     * <li>Archive: incidents resolved longer ago than the venue's archive time (24 hours by default)
     *     leave memory and wait for the next save, which writes them to the archive table. This
     *     keeps memory, save time and request times from growing without limit.</li>
     * <li>Acknowledgement: a unit that has not acknowledged its task within the venue's ack time
     *     gets a reminder and a NO_ACK alert is raised. If it stays silent for the same time again,
     *     it is marked UNREACHABLE and the task goes to the next best unit (or back to its queue).</li>
     * </ol>
     * @return short text listing what was done (empty if nothing), for logs and tests
     */
    public synchronized String tick() {
        if (venue == null) return "";
        LocalDateTime t = now();
        StringBuilder done = new StringBuilder();
        int sup = supervisionMinutes();
        boolean overdue = officerCheckIn == null || Duration.between(officerCheckIn, t).toMinutes() >= sup;
        if (overdue && (supervisionAlerted == null || Duration.between(supervisionAlerted, t).toMinutes() >= sup)) {
            supervisionAlerted = t;
            int waiting = awaitingReview().length;
            alert(AlertKind.SUPERVISION, "officer", (officerCheckIn == null ? "No security officer has checked in"
                : "The security officer on watch (" + officer + ") has not checked in for " + Duration.between(officerCheckIn, t).toMinutes() + " min")
                + (waiting > 0 ? "; " + waiting + " automatic incident(s) await review" : "") + ". An officer must check in now.");
            done.append("SUPERVISION ");
        }
        long ackSeconds = (venue.ackMinutes) * 60L;
        for (Incident i : incidents.inOrder()) {
            if (i.status != IncidentStatus.ASSIGNED || i.acknowledged != null || i.notified == null) continue;
            if (Duration.between(i.notified, t).getSeconds() < ackSeconds) continue;
            Unit u = findUnit(i.unitId);
            if (u == null) continue;
            if (i.reminders == 0) {
                i.reminders = 1;
                i.notified = t;
                notifyUnit(u, MessageKind.REMINDER, i, "REMINDER - not acknowledged: " + taskText(i, u));
                i.history.addLast(new HistoryEntry(t, "No acknowledgement from " + u.id + " after " + venue.ackMinutes + " min: reminder sent"));
                alert(AlertKind.NO_ACK, i.toString(), u.id + " has not acknowledged " + i + " at " + i.area.id + " - reminder sent; call the unit by radio");
                done.append("REMINDER ").append(i).append(' ');
            } else {
                release(i, "No answer");
                u.status = UnitStatus.UNREACHABLE;
                u.history.addLast(new HistoryEntry(t, "Marked UNREACHABLE: did not acknowledge " + i));
                i.unitId = null;
                i.onScene = null;
                i.status = IncidentStatus.WAITING;
                i.history.addLast(new HistoryEntry(t, u.id + " did not answer twice: marked UNREACHABLE, task reassigned"));
                Unit next = bestUnit(i.area, i.category.skill, i.language, u);
                String where;
                if (next != null) { assign(i, next); where = "reassigned to " + next.id; }
                else { queue(i.priority).enqueue(i); where = "no other unit free, waiting in the " + i.priority + " queue"; }
                alert(AlertKind.NO_ACK, i.toString(), u.id + " did not answer " + i + " twice: marked UNREACHABLE, " + where);
                done.append("REASSIGNED ").append(i).append(' ');
            }
        }
        int moved = 0;
        long archiveSeconds = venue.archiveMinutes * 60L;
        for (Incident i : incidents.inOrder()) {
            if (i.open() || i.resolved == null || Duration.between(i.resolved, t).getSeconds() < archiveSeconds) continue;
            incidents.delete(i.id);
            pendingArchive.addLast(i);
            archivedCount++;
            moved++;
        }
        while (pendingArchive.size() > MAX_PENDING_ARCHIVE) pendingArchive.remove(pendingArchive.getFirst());
        if (moved > 0) done.append("ARCHIVED ").append(moved).append(' ');
        done.append(checkCounts(t));
        rules.tick(t, done);
        if (done.length() > 0) changed();
        return done.toString().trim();
    }

    /**
     * Hands the incidents waiting for the archive to the database and empties the list.
     * Called by {@link crowdwatch.db.Database#save} while it holds the monitor's lock.
     */
    public synchronized LinkedList<Incident> takeArchive() {
        LinkedList<Incident> out = pendingArchive;
        pendingArchive = new LinkedList<>();
        return out;
    }

    /** Puts incidents back in the archive queue after a failed save, so they are written next time. */
    public synchronized void returnArchive(LinkedList<Incident> list) {
        LinkedList<Incident> merged = new LinkedList<>();
        for (Incident i : list) merged.addLast(i);
        for (Incident i : pendingArchive) merged.addLast(i);
        pendingArchive = merged;
    }

    /** @return number of incidents archived since the venue was created */
    public synchronized int archivedCount() { return archivedCount; }

    /** Sets the archived count (used when loading from the database). */
    public synchronized void setArchivedCount(int n) { archivedCount = n; }

    /**
     * SOS: someone is in danger. Creates a CRITICAL incident (built-in category SOS) and:
     * <ol>
     * <li>sends the nearest available unit (never the unit that raised the SOS);</li>
     * <li>if no unit is free, takes the nearest unit away from a NORMAL or LOW task, which goes
     *     back to its queue;</li>
     * <li>alerts every other unit within {@link #SOS_NEARBY_MINUTES} minutes' walk as backup;</li>
     * <li>raises an SOS alert for the control room.</li>
     * </ol>
     *
     * @param areaId     where help is needed; may be blank when {@code fromUnit} is given (its area is used)
     * @param fromUnit   ID of the unit that pressed SOS on its device, or null if reported otherwise
     * @param text       short description, may be empty
     */
    public synchronized Result sos(String areaId, String fromUnit, String text) {
        Unit reporter = null;
        if (fromUnit != null && !fromUnit.isBlank()) {
            reporter = findUnit(fromUnit);
            if (reporter == null) return Result.fail("No unit " + fromUnit + ".");
            contact(reporter);
        }
        Area a = (areaId == null || areaId.isBlank()) && reporter != null ? reporter.area : findArea(areaId);
        if (a == null) return Result.fail("The SOS location is required.");
        if (tooLong("SOS text", text, 300) != null) return tooLong("SOS text", text, 300);
        String what = "SOS" + (reporter != null ? " from unit " + reporter.id : "") + (text == null || text.isBlank() ? "" : ": " + text.trim());
        Incident i = create(sos, a, what, "", null);
        Unit u = bestUnit(a, null, null, reporter);
        String how = "";
        if (u == null) {
            u = preempt(a, reporter, i);
            if (u != null) how = " (taken from a lower-priority task)";
        }
        String outcome;
        if (u != null) { assign(i, u); outcome = "assigned to " + u.id + how; }
        else {
            queue(Priority.CRITICAL).enqueue(i);
            i.history.addLast(new HistoryEntry(now(), "No unit could be sent: first in the CRITICAL queue"));
            outcome = "NO UNIT AVAILABLE - waiting in the CRITICAL queue";
        }
        Graph.Paths p = distancesFrom(a.id);
        int nearby = 0;
        for (Unit o : units.inOrder()) {
            if (o == u || o == reporter || o.status == UnitStatus.OFF_DUTY) continue;
            int d = p.distanceTo(o.area.id);
            if (d > SOS_NEARBY_MINUTES) continue;
            notifyUnit(o, MessageKind.SOS_NEARBY, i, "SOS NEARBY at " + a.id + " (" + a.name + "), " + d + " min from you: " + what
                + ". Assist if you can and tell the control room.");
            nearby++;
        }
        alert(AlertKind.SOS, i.toString(), what + " at " + a.name + ": " + outcome + "; " + nearby + " nearby unit(s) alerted");
        changed();
        return Result.ok(i + " SOS at " + a.id + ": " + outcome + ". " + nearby + " nearby unit(s) alerted.");
    }

    /**
     * Frees the nearest unit that is busy with a NORMAL or LOW incident so it can answer an SOS.
     * That incident goes back to the rear of its own queue, and the unit is told to stand down.
     *
     * @return the freed unit, or null if none could be taken
     */
    private Unit preempt(Area a, Unit exclude, Incident sosIncident) {
        Graph.Paths p = distancesFrom(a.id);
        Unit best = null;
        Incident bestTask = null;
        int bestD = Graph.INF;
        for (Unit u : units.inOrder()) {
            if (u == exclude || u.status != UnitStatus.BUSY) continue;
            Incident task = activeIncident(u.id);
            if (task == null || task.priority.compareTo(Priority.NORMAL) < 0) continue;
            int d = p.distanceTo(u.area.id);
            if (d < bestD) { bestD = d; best = u; bestTask = task; }
        }
        if (best == null) return null;
        release(bestTask, "Pulled away for SOS " + sosIncident);
        bestTask.unitId = null;
        bestTask.onScene = null;
        bestTask.status = IncidentStatus.WAITING;
        queue(bestTask.priority).enqueue(bestTask);
        bestTask.history.addLast(new HistoryEntry(now(), best.id + " was pulled away for SOS " + sosIncident + "; waiting again"));
        return best;
    }

    /**
     * Starts an evacuation of a zone: one area (by ID) or a whole sector (by name).
     * <p>
     * The zone's areas are marked as evacuating, so public routes avoid them. An evacuation plan
     * is worked out for every public area: the nearest exit (from the venue's {@code exit:} line)
     * that is outside the zone, by a crowd-aware route that does not pass through the zone (people
     * inside the zone may cross it on their way out). Units inside the zone are told which exit to
     * guide people to; every other unit is told to keep the exits clear and stay out of the zone.
     *
     * @param zone      area ID or sector name
     * @param orderedBy officer's name (counts as a check-in)
     */
    public synchronized Result evacuate(String zone, String orderedBy) {
        if (venue == null || venue.exits.isEmpty()) return Result.fail("No exits are defined: add an 'exit:' line to the venue file.");
        if (evacuationZone != null) return Result.fail("An evacuation of " + evacuationZone + " is already in progress. End it first.");
        if (zone == null || zone.isBlank()) return Result.fail("Choose the area or sector to evacuate.");
        LinkedList<Area> inZone = zoneAreas(zone);
        if (inZone.isEmpty()) return Result.fail("No area, sector or fire zone called " + zone + ".");
        Result in = officerCheckIn(orderedBy);
        if (!in.ok) return in;
        for (Area a : inZone) a.evacuating = true;
        evacuationZone = zone.trim();
        evacuationBy = officer;
        evacuationStarted = now();
        buildEvacuationPlan();
        int told = 0;
        for (Unit u : units.inOrder()) {
            if (u.status == UnitStatus.OFF_DUTY) continue;
            String[] row = planFor(u.area.id);
            String text = u.area.evacuating && row != null
                ? "EVACUATE " + evacuationZone + ": guide everyone from " + u.area.id + " to exit " + row[1] + " via " + row[2] + " (" + row[3] + " min). Then report at " + row[1] + "."
                : "EVACUATION of " + evacuationZone + " in progress: keep exits " + exitList() + " clear and do not enter the zone unless sent.";
            notifyUnit(u, MessageKind.EVACUATE, null, text);
            told++;
        }
        alert(AlertKind.EVACUATION, evacuationZone, "EVACUATION of " + evacuationZone + " (" + inZone.size() + " area(s)) ordered by " + officer + ". Plan sent to " + told + " unit(s).");
        rules.onEvacuation(evacuationZone);
        changed();
        return Result.ok("Evacuation of " + evacuationZone + " started: " + inZone.size() + " area(s), plan sent to " + told + " unit(s).");
    }

    /** Ends the evacuation: areas are open again and every unit is told. */
    public synchronized Result endEvacuation(String by) {
        if (evacuationZone == null) return Result.fail("No evacuation is in progress.");
        Result in = officerCheckIn(by);
        if (!in.ok) return in;
        for (Area a : areas.inOrder()) a.evacuating = false;
        String zone = evacuationZone;
        evacuationZone = null; evacuationBy = null; evacuationStarted = null;
        evacuationPlan.clear();
        for (Unit u : units.inOrder()) if (u.status != UnitStatus.OFF_DUTY) notifyUnit(u, MessageKind.BROADCAST, null, "Evacuation of " + zone + " ENDED by " + officer + ". Resume normal duties.");
        alert(AlertKind.EVACUATION, zone, "Evacuation of " + zone + " ended by " + officer);
        changed();
        return Result.ok("Evacuation of " + zone + " ended.");
    }

    /**
     * Works out the evacuation plan: an exit for every public area, by a route that never enters the
     * zone (people inside it may cross it on the way out), never uses a lift and avoids crowded areas.
     * <p>
     * Without layout data each area goes to its nearest exit. When the venue gives exit flows
     * ({@code exitflow:}) or link capacities, the plan also spreads people over the exits: areas are
     * planned largest crowd first, and each takes the exit where its people would be out soonest -
     * walking time plus the time to queue through the exit and through every narrow link on the way,
     * given everyone already sent there. A crowd of more than {@link #SPLIT_PEOPLE} people is planned
     * in groups (at most {@link #SPLIT_PARTS}), so a large courtyard can empty through several exits
     * at once; its rows are listed biggest group first. The plan then estimates, for every area, how long until its
     * last person is out, and the longest of these is the time to clear the venue.
     * One Dijkstra run per area, O(V (V+E) log V) in total, which is small for a venue.
     * If every exit is inside the zone, exits in the zone are used as a last resort.
     */
    private void buildEvacuationPlan() {
        evacuationPlan.clear();
        evacuationClear = 0;
        boolean capacityAware = !exitFlow.isEmpty() || linkInfo.size() > 0;
        Area[] order = areas.inOrder().toArray(new Area[0]);
        MergeSort.sort(order, (x, y) -> x.occupancy != y.occupancy ? Integer.compare(y.occupancy, x.occupancy) : x.id.compareTo(y.id));
        AVLTree<String, Integer> exitLoad = new AVLTree<>(), linkLoad = new AVLTree<>();
        // area ID -> its rows (a large crowd may be split over several exits)
        AVLTree<String, LinkedList<String[]>> rows = new AVLTree<>();
        // only areas connected to the zone are planned: a separate site (Madinah when Makkah is evacuated) is not affected
        AVLTree<String, Boolean> connected = new AVLTree<>();
        for (Area z : areas.inOrder()) {
            if (!z.evacuating) continue;
            Graph.Paths zp = distancesFrom(z.id);
            for (Area b : areas.inOrder()) if (zp.distanceTo(b.id) < Graph.INF) connected.insert(b.id, true);
        }
        for (Area a : order) {
            if (a.restricted) continue;
            if (!connected.isEmpty() && connected.search(a.id) == null) continue;
            boolean inside = a.evacuating;
            Graph.Paths p = map.shortestPaths(a.id, (u, v, w) -> {
                Area next = areas.search(map.name(v));
                if (next.restricted) return Graph.INF;
                if (next.evacuating && !inside) return Graph.INF;
                int[] li = linkInfo.search(linkKey(map.name(u), map.name(v)));
                if (li != null && li[1] == 1) return Graph.INF;            // lifts are never used in an evacuation
                return w * congestionFactor(next.level);
            });
            // with capacities known, a big crowd is planned in up to SPLIT_PARTS groups that may take different exits
            int parts = capacityAware ? Math.max(1, Math.min(SPLIT_PARTS, (a.occupancy + SPLIT_PEOPLE - 1) / SPLIT_PEOPLE)) : 1;
            LinkedList<String[]> mine = new LinkedList<>();
            int left = a.occupancy;
            for (int part = 0; part < parts; part++) {
                int group = part == parts - 1 ? left : a.occupancy / parts;
                left -= group;
                String[] best = null;
                LinkedList<String> bestPath = null;
                for (int pass = 0; pass < 2 && best == null; pass++) {
                    double bestScore = Double.MAX_VALUE;
                    for (String e : venue.exits) {
                        Area ex = findArea(e);
                        if (ex == null || (pass == 0 && ex.evacuating)) continue;
                        int cost = p.distanceTo(ex.id);
                        if (cost >= Graph.INF) continue;
                        LinkedList<String> path = p.pathTo(ex.id);
                        double score = capacityAware ? cost + queueMinutes(path, ex.id, group, exitLoad, linkLoad) : cost;
                        if (score < bestScore) {
                            bestScore = score;
                            bestPath = path;
                            best = new String[] { a.id, ex.id, path.toString(), String.valueOf(walkMinutes(path)), "0", "" };
                        }
                    }
                }
                if (best == null) { mine.addLast(new String[] { a.id, "-", "no route to an exit: send staff", "-", String.valueOf(group), "-" }); continue; }
                add(exitLoad, best[1], group);
                String prev = null;
                for (String id : bestPath) { if (prev != null) add(linkLoad, linkKey(prev, id), group); prev = id; }
                String[] same = null;
                for (String[] r : mine) if (r[1].equals(best[1]) && r[2].equals(best[2])) same = r;
                if (same != null) same[4] = String.valueOf(Integer.parseInt(same[4]) + group);
                else { best[4] = String.valueOf(group); mine.addLast(best); }
            }
            // the biggest group first, so the main exit of an area is its first row
            String[][] sorted = mine.toArray(new String[0][]);
            MergeSort.sort(sorted, (x, y) -> Integer.compare(Integer.parseInt(y[4]), Integer.parseInt(x[4])));
            LinkedList<String[]> list = new LinkedList<>();
            for (String[] r : sorted) list.addLast(r);
            rows.insert(a.id, list);
        }
        // time until each group's last person is out, with everyone's loads known
        for (Area a : areas.inOrder()) {
            LinkedList<String[]> list = rows.search(a.id);
            if (list == null) continue;
            for (String[] r : list) {
                if (!r[1].equals("-")) {
                    LinkedList<String> path = new LinkedList<>();
                    for (String id : r[2].replaceAll("[\\[\\]]", "").split(" -> ")) if (!id.isBlank()) path.addLast(id.trim());
                    int clear = (int) Math.ceil(Integer.parseInt(r[3]) + queueMinutes(path, r[1], 0, exitLoad, linkLoad));
                    r[5] = String.valueOf(clear);
                    // the time to clear is for the zone being evacuated; other areas only get their way out
                    if (a.evacuating) evacuationClear = Math.max(evacuationClear, clear);
                }
                evacuationPlan.addLast(r);
            }
        }
        if (!capacityAware) evacuationClear = 0;
    }

    /**
     * Minutes spent queueing on the way out: the slowest of the exit and every link with a known
     * capacity on the path, given the people already sent that way plus {@code extra} more.
     */
    private double queueMinutes(LinkedList<String> path, String exit, int extra, AVLTree<String, Integer> exitLoad, AVLTree<String, Integer> linkLoad) {
        double worst = 0;
        Integer flow = exitFlow.search(exit), load = exitLoad.search(exit);
        if (flow != null && flow > 0) worst = ((load == null ? 0 : load) + extra) / (double) flow;
        String prev = null;
        for (String id : path) {
            if (prev != null) {
                String key = linkKey(prev, id);
                int[] li = linkInfo.search(key);
                if (li != null && li[0] > 0) {
                    Integer l = linkLoad.search(key);
                    worst = Math.max(worst, ((l == null ? 0 : l) + extra) / (double) li[0]);
                }
            }
            prev = id;
        }
        return worst;
    }

    /** A crowd is split for the evacuation plan in groups of about this many people ... */
    public static final int SPLIT_PEOPLE = 5000;
    /** ... into at most this many groups, each of which may use a different exit. */
    public static final int SPLIT_PARTS = 4;

    private static void add(AVLTree<String, Integer> t, String key, int n) { Integer v = t.search(key); t.insert(key, (v == null ? 0 : v) + n); }

    /**
     * The evacuation plan as rows {area, exit, path, walk minutes, "zone" or ""}: the current plan
     * if an evacuation is in progress, otherwise a preview for {@code zone} (worked out exactly as
     * {@link #evacuate} would, without marking anything or telling any unit).
     *
     * @param zone area ID or sector name to preview; ignored while an evacuation is in progress
     * @return the rows; empty if no exits are defined or the zone is unknown
     */
    public synchronized LinkedList<String[]> evacuationRows(String zone) {
        LinkedList<String[]> out = new LinkedList<>();
        if (venue == null || venue.exits.isEmpty()) return out;
        LinkedList<String[]> saved = new LinkedList<>();
        for (String[] r : evacuationPlan) saved.addLast(r);
        int savedClear = evacuationClear;
        LinkedList<Area> marked = new LinkedList<>();
        if (evacuationZone == null) {
            if (zone == null || zone.isBlank()) return out;
            for (Area a : zoneAreas(zone)) { a.evacuating = true; marked.addLast(a); }
            if (marked.isEmpty()) return out;
            buildEvacuationPlan();
        }
        for (String[] r : evacuationPlan) {
            Area a = findArea(r[0]);
            out.addLast(new String[] { r[0], r[1], r[2], r[3], a != null && a.evacuating ? "zone" : "", r[4], r[5] });
        }
        if (evacuationZone == null) {
            for (Area a : marked) a.evacuating = false;
            evacuationPlan.clear();
            for (String[] r : saved) evacuationPlan.addLast(r);
            evacuationClear = savedClear;
        }
        return out;
    }

    /** @return the plan row for an area, or null */
    private String[] planFor(String areaId) {
        for (String[] r : evacuationPlan) if (r[0].equals(areaId)) return r;
        return null;
    }

    /** @return the venue's exits separated by commas */
    private String exitList() {
        StringBuilder sb = new StringBuilder();
        for (String e : venue.exits) sb.append(sb.length() == 0 ? "" : ", ").append(e);
        return sb.toString();
    }

    /** @return real walking minutes along a path of area IDs */
    private int walkMinutes(LinkedList<String> path) {
        int walk = 0;
        String prev = null;
        for (String id : path) {
            if (prev != null) for (Graph.Edge e : map.neighbours(map.indexOf(prev))) if (map.name(e.to).equals(id)) walk += e.weight;
            prev = id;
        }
        return walk;
    }

    /**
     * Route for a responder: like {@link #route} with the shortest walk, but allowed through an
     * evacuation zone (responders may have to go in).
     */
    public synchronized Route responderRoute(String fromId, String toId) { return route(fromId, toId, false, false); }

    /** @return when the officer last checked in, or null */
    public synchronized LocalDateTime officerCheckInTime() { return officerCheckIn; }

    /** @return who ordered the current evacuation, or null */
    public synchronized String evacuationBy() { return evacuationBy; }

    /** @return when the current evacuation started, or null */
    public synchronized LocalDateTime evacuationStarted() { return evacuationStarted; }

    /**
     * Restores the officer check-in and an evacuation in progress after loading from the database.
     * The evacuation flags of the areas are re-applied from the zone and the plan is worked out again.
     */
    public synchronized void restoreState(String officerName, LocalDateTime checkIn, String zone, String by, LocalDateTime started) {
        officer = officerName;
        officerCheckIn = checkIn;
        evacuationZone = zone;
        evacuationBy = by;
        evacuationStarted = started;
        if (zone != null) {
            LinkedList<Area> z = zoneAreas(zone);
            for (Area a : areas.inOrder()) a.evacuating = z.contains(a);
            buildEvacuationPlan();
        }
        changed();
    }

    /** @return the zone being evacuated, or null if none */
    public synchronized String evacuationZone() { return evacuationZone; }

    /**
     * @return the current evacuation plan as text lines "AREA -&gt; EXIT via [path] (N min)";
     *         empty when no evacuation is in progress
     */
    public synchronized LinkedList<String> evacuationPlan() {
        LinkedList<String> out = new LinkedList<>();
        for (String[] r : evacuationPlan)
            out.addLast(r[0].equals(r[1]) ? r[0] + " is an exit: keep it clear"
                : r[0] + (findArea(r[0]).evacuating ? " (zone)" : "") + " -> " + r[1] + " via " + r[2] + " (" + r[3] + " min"
                    + (evacuationClear > 0 && !r[5].equals("-") ? ", " + r[4] + " people out in about " + r[5] + " min" : "") + ")");
        return out;
    }

    /**
     * Sends a message to every unit on duty (for example "Gates C1-C4 closed, redirect to C5").
     *
     * @param by officer's name (counts as a check-in)
     */
    public synchronized Result broadcast(String text, String by) {
        if (text == null || text.isBlank()) return Result.fail("Message text is required.");
        if (tooLong("Message", text, 300) != null) return tooLong("Message", text, 300);
        Result in = officerCheckIn(by);
        if (!in.ok) return in;
        int n = 0;
        for (Unit u : units.inOrder()) if (u.status != UnitStatus.OFF_DUTY) { notifyUnit(u, MessageKind.BROADCAST, null, "FROM CONTROL (" + officer + "): " + text.trim()); n++; }
        alert(AlertKind.BROADCAST, "all", officer + " to all units: " + text.trim());
        changed();
        return Result.ok("Message sent to " + n + " unit(s).");
    }

    /**
     * Length check for text typed by users, matching the database column sizes.
     *
     * @return a refusal if the text is longer than {@code max} characters, otherwise null
     */
    static Result tooLong(String field, String text, int max) {
        return text != null && text.trim().length() > max ? Result.fail(field + ": too long (" + text.trim().length() + " characters; at most " + max + ").") : null;
    }

    // IDs and codes are compared in upper case without surrounding spaces
    static String norm(String s) { return s == null ? null : s.trim().toUpperCase(); }

    /** @return the areas of a zone: the area with that ID, or every area of the sector with that name */
    public synchronized LinkedList<Area> zoneAreas(String zone) {
        LinkedList<Area> out = new LinkedList<>();
        if (zone == null || zone.isBlank()) return out;
        for (Area a : areas.inOrder()) if (a.id.equalsIgnoreCase(zone.trim()) || a.sector.equalsIgnoreCase(zone.trim())) out.addLast(a);
        if (out.isEmpty() && venue != null)
            for (String[] z : venue.fireZones) if (z[0].equalsIgnoreCase(zone.trim())) for (int k = 1; k < z.length; k++) { Area a = areas.search(z[k]); if (a != null && !out.contains(a)) out.addLast(a); }
        return out;
    }

    /** @return the name of the fire zone an area belongs to, or null */
    public synchronized String fireZoneOf(String areaId) {
        if (venue == null) return null;
        for (String[] z : venue.fireZones) for (int k = 1; k < z.length; k++) if (z[k].equalsIgnoreCase(areaId)) return z[0];
        return null;
    }

    /** @return the key of a link in {@link #linkInfo}: the two IDs in alphabetical order */
    private static String linkKey(String a, String b) {
        String x = norm(a), y = norm(b);
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }

    /** @return people per minute a link can carry, 0 if no limit is known */
    public synchronized int linkCapacity(String a, String b) { int[] i = linkInfo.search(linkKey(a, b)); return i == null ? 0 : i[0]; }

    /** @return true if a link is a lift (never used in an evacuation) */
    public synchronized boolean isLift(String a, String b) { int[] i = linkInfo.search(linkKey(a, b)); return i != null && i[1] == 1; }

    /** @return people per minute an exit lets out, 0 if not known */
    public synchronized int exitFlow(String exitId) { Integer f = exitFlow.search(norm(exitId)); return f == null ? 0 : f; }

    /** @return estimated minutes until everyone is out, for the current evacuation plan (0 if not known) */
    public synchronized int evacuationClearMinutes() { return evacuationClear; }

    /** @return true if the venue has exit flows or link capacities, so clearing times can be estimated */
    public synchronized boolean evacuationClearMinutesKnown() { return !exitFlow.isEmpty() || linkInfo.size() > 0; }

    /**
     * Walking minutes between two areas worked out from the drawing: the straight distance between the
     * centres of their positions, times the venue's scale, at {@link VenueProfile#WALK_METRES_PER_MINUTE}
     * (rounded up, at least 1 minute).
     */
    private static int autoMinutes(VenueProfile p, String a, String b) {
        if (p.scale <= 0) throw new IllegalArgumentException("link " + a + " " + b + " auto: add a 'scale:' line (metres per map unit)");
        double[] ca = centre(p, a), cb = centre(p, b);
        if (ca == null || cb == null) throw new IllegalArgumentException("link " + a + " " + b + " auto: both areas need a 'pos:' line");
        double metres = Math.hypot(ca[0] - cb[0], ca[1] - cb[1]) * p.scale;
        return Math.max(1, (int) Math.ceil(metres / VenueProfile.WALK_METRES_PER_MINUTE));
    }

    /** @return the centre of an area's position on the drawing, or null */
    private static double[] centre(VenueProfile p, String id) {
        for (String[] pos : p.positions)
            if (pos[0].equalsIgnoreCase(id)) return new double[] { Double.parseDouble(pos[1]) + Double.parseDouble(pos[3]) / 2, Double.parseDouble(pos[2]) + Double.parseDouble(pos[4]) / 2 };
        return null;
    }

    /** @return the built-in SOS category */
    Category sosCategory() { return sos; }

    /** @return the walking minutes along a path (package use) */
    int walk(LinkedList<String> path) { return walkMinutes(path); }

    /** @return the venue's exits separated by commas (package use) */
    String exits() { return exitList(); }

    /** Records a successful save and the newest backup folder (called by the database). */
    public synchronized void noteSaved(LocalDateTime when, String backup) { lastSaved = when; if (backup != null) lastBackup = backup; }

    /** @return when the database last saved, or null if never in this run */
    public synchronized LocalDateTime lastSaved() { return lastSaved; }

    /** @return the newest database backup folder, or null */
    public synchronized String lastBackup() { return lastBackup; }

    // ================================================================ further automation (see AutoRules)

    /** A lost person was found: the lookout stops and the person is brought to the meeting point. */
    public synchronized Result found(int id, String areaId, String by) { return rules.found(id, areaId, by); }
    /** Someone found an unaccompanied person: a unit looks after them and open lost cases are listed for matching. */
    public synchronized Result foundPerson(String areaId, String details, String contact, String by) { return rules.foundPerson(areaId, details, contact, by); }
    /** A fire or other alarm panel reports an alarm in an area. */
    public synchronized Result alarm(String areaId, String what) { return rules.alarm(areaId, what); }
    /** The officer starts or dismisses the proposed evacuation. */
    public synchronized Result decideProposal(boolean start, String by) { return rules.decideProposal(start, by); }
    /** The officer approves or rejects a drafted public announcement. */
    public synchronized Result decideAnnouncement(int id, boolean approve, String by) { return rules.decideAnnouncement(id, approve, by); }
    /** The officer sends a public announcement of their own (signs, PA). */
    public synchronized Result announce(String text, String by) { return rules.announce(text, by); }
    /** @return the evacuation proposal {zone, reason, time}, or null if none */
    public synchronized String[] proposal() { return rules.proposal(); }
    /** @return drafted announcements waiting for a decision, oldest first */
    public synchronized AutoRules.Announcement[] pendingAnnouncements() { return rules.pending(); }
    /** @return every announcement still in memory (newest first) */
    public synchronized LinkedList<AutoRules.Announcement> announcements() { return rules.announcements(); }
    /** @return open lost-person cases, oldest first */
    public synchronized Incident[] lostCases() { return rules.lostCases(); }
    /** @return the areas searched for a lost-person case (within its search radius of where the person was last seen) */
    public synchronized LinkedList<String> searchAreas(Incident i) { return rules.searchAreas(i); }
    /** @return the handover report for the shift now, as text */
    public synchronized String handoverReport() { return rules.handoverReport(); }
    /** @return the reports written at shift changes, newest first, as {time, text} */
    public synchronized LinkedList<String[]> handovers() { return rules.handovers(); }
    /** Folder where handover reports are written as text files (null: memory only). */
    public synchronized void setReportFolder(java.nio.file.Path folder) { rules.reportFolder = folder; }
    /** @return names of the scheduled events whose preparation or peak is running now */
    public synchronized LinkedList<String> activeEvents() { return rules.activeEvents(); }
    /** @return the venue's meeting point for found persons, or null */
    public synchronized String meetingPoint() { return venue == null ? null : venue.meetingPoint; }

    /**
     * @return the meeting point for a found person last seen at {@code areaId}: the nearest of the venue's
     *         meeting points that can be reached from there (each site, such as Makkah and Madinah, has its
     *         own), or the area itself if none can be reached
     */
    public synchronized String meetingFor(String areaId) {
        Area a = findArea(areaId);
        if (a == null || venue == null || venue.meetingPoint == null) return areaId;
        Graph.Paths p = distancesFrom(a.id);
        String best = null;
        int bestD = Graph.INF;
        for (String mp : venue.meetingPoint.split("\\s+")) { int d = p.distanceTo(mp); if (d < bestD) { bestD = d; best = mp; } }
        return best == null ? a.id : best;
    }
}
