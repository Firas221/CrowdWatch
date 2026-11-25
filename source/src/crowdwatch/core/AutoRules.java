package crowdwatch.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import crowdwatch.ds.AVLTree;
import crowdwatch.ds.Graph;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.*;
import crowdwatch.model.Enums.*;

/**
 * The further automatic rules of {@link CrowdMonitor}, kept in their own class so each rule is
 * easy to find. Every method is called by the monitor while it holds its lock.
 * <ol>
 * <li><b>Sensor health</b>: an area whose sensor has been silent for the venue's {@code stale:} time is
 *     marked stale; a count that jumps by more than {@code jump:} percent of capacity within a minute is
 *     marked suspect. Both raise a SENSOR alert and (with automation on) ask the nearest free unit to count.</li>
 * <li><b>Follow-through</b>: a unit that acknowledged but has not arrived after {@code onscene:} minutes is
 *     reminded, then the incident is escalated and a backup unit is sent; an automatic crowd incident is
 *     closed once its area has stayed below HIGH for {@code autoclose:} minutes; an incident that has
 *     waited {@code maxwait:} minutes for a qualified unit gets the nearest free unit as a stand-in.</li>
 * <li><b>Crowd flow</b>: when an area turns HIGH, units in the areas that lead into it are told to hold
 *     people back; free units are sent ahead of time to areas expected to reach CRITICAL soon; every
 *     sector keeps at least one free unit.</li>
 * <li><b>Scheduled events</b>: before a peak from the venue's {@code event:} lines, the zone's thresholds are
 *     lowered and units are positioned.</li>
 * <li><b>Evacuation proposal</b>: an area that stays CRITICAL and keeps rising, or a fire alarm, makes the
 *     system <i>propose</i> an evacuation; only an officer can start it.</li>
 * <li><b>Lost persons</b>: a lookout goes to every unit, the search area widens with time, the case is
 *     escalated at 15 and 30 minutes, and a found person is brought to the meeting point.</li>
 * <li><b>Shifts</b>: units go off and on duty with their working hours, and a handover report is written
 *     at every shift change.</li>
 * <li><b>Public signs</b>: messages for screens and the PA are drafted and sent only after an officer approves.</li>
 * </ol>
 * Rules that send or move units only act while automatic dispatch is ON; sensor health, shifts and
 * handover reports always run.
 */
public final class AutoRules {
    /** A message for public screens or the PA, drafted by the system or written by an officer. */
    public static final class Announcement {
        public final int id;
        public final LocalDateTime time;
        // what it is about: an area, a zone or an incident
        public final String ref;
        public final String text;
        // PROPOSED, SENT, REJECTED or EXPIRED
        public String status = "PROPOSED";
        public String decidedBy;
        public LocalDateTime decided;

        Announcement(int id, LocalDateTime time, String ref, String text) { this.id = id; this.time = time; this.ref = ref; this.text = text; }
    }

    /** A drafted announcement that nobody decides on expires after this many minutes. */
    public static final int ANNOUNCEMENT_MINUTES = 15;
    /** A unit sent ahead of time is free for other staging after this many minutes. */
    public static final int STAGE_MINUTES = 30;
    /** A sector gets a covering unit at most this often. */
    public static final int COVER_COOLDOWN_MINUTES = 10;
    /** Two counts closer together than this many seconds can make a suspect jump. */
    public static final int JUMP_SECONDS = 60;
    /** Minutes after which a lost-person case is escalated, and after which authorities are suggested. */
    public static final int LOST_ESCALATE_MINUTES = 15, LOST_AUTHORITIES_MINUTES = 30;
    /** Announcements kept in memory. */
    public static final int MAX_ANNOUNCEMENTS = 100;

    private final CrowdMonitor m;
    private final LinkedList<Announcement> announcements = new LinkedList<>();
    private int nextAnnouncement = 1;
    private String proposedZone, proposalReason;
    private LocalDateTime proposedAt;
    // sector -> last time a unit was sent to cover it
    private final AVLTree<String, LocalDateTime> covered = new AVLTree<>();
    // "event index|date|phase" -> done, so each event phase acts once a day
    private final AVLTree<String, String> eventsDone = new AVLTree<>();
    // "date time" of shift changes already handled
    private final AVLTree<String, String> shiftsDone = new AVLTree<>();
    // handover reports {time, text}, newest first (at most 20)
    private final LinkedList<String[]> handovers = new LinkedList<>();
    // folder for handover report files, or null
    Path reportFolder;
    // alerts counted for the next handover report start after this time
    private LocalDateTime lastHandover;

    AutoRules(CrowdMonitor m) { this.m = m; }

    /** Forgets everything (a new venue was loaded). */
    void reset() {
        announcements.clear();
        proposedZone = null; proposalReason = null; proposedAt = null;
        covered.clear(); eventsDone.clear(); shiftsDone.clear(); handovers.clear();
        lastHandover = null;
    }

    private VenueProfile v() { return m.venue(); }
    private LocalDateTime now() { return m.now(); }
    private boolean auto() { return m.automation(); }

    // ================================================================ 1. sensor health

    /** Called after every count: notes fresh sensor data, and checks for an impossible jump. */
    void onReading(Area a, int before, LocalDateTime previous, int count, Source source) {
        boolean sensor = source == Source.SENSOR || source == Source.SIMULATION || source == Source.IMPORT;
        if (!sensor) {
            // a count by a person is trusted: it settles a suspect reading
            if (a.suspect != null) {
                a.suspect = null;
                m.alert(AlertKind.SENSOR, a.id, a.id + " count confirmed by " + source + ": " + count + " people");
            }
            return;
        }
        a.lastSensor = now();
        if (a.staleSince != null) {
            a.staleSince = null;
            m.alert(AlertKind.SENSOR, a.id, "Sensor data from " + a.id + " is coming in again");
        }
        if (previous == null || a.capacity <= 0) return;
        long secs = Duration.between(previous, now()).getSeconds();
        int change = Math.abs(count - before);
        if (secs <= JUMP_SECONDS && change * 100 > v().jumpPercent * a.capacity && a.suspect == null) {
            a.suspect = "count jumped from " + before + " to " + count + " in " + secs + " s";
            String asked = askToCount(a, "the sensor count jumped from " + before + " to " + count + " in " + secs + " s");
            m.alert(AlertKind.SENSOR, a.id, a.id + " sensor count looks wrong: " + a.suspect + "." + asked);
        }
    }

    /** Asks the nearest free unit to count an area by eye (automation only). @return text for the alert */
    private String askToCount(Area a, String why) {
        if (!auto()) return " Send staff to count.";
        Unit u = m.bestUnit(a, null, null, null);
        if (u == null) return " No free unit to count it: check the cameras.";
        m.notifyUnit(u, MessageKind.CHECK, null, "COUNT CHECK at " + a.id + " (" + a.name + "): " + why + ". Count the people there and send the number to the control room.");
        u.history.addLast(new HistoryEntry(now(), "Asked to count " + a.id));
        return " " + u.id + " asked to count.";
    }

    /** Marks areas whose sensors went silent. */
    private void checkSensors(LocalDateTime t, StringBuilder done) {
        for (Area a : m.areaTree().inOrder()) {
            if (a.lastSensor == null || a.staleSince != null) continue;
            long min = Duration.between(a.lastSensor, t).toMinutes();
            if (min < v().staleMinutes) continue;
            a.staleSince = t;
            String asked = askToCount(a, "no sensor data for " + min + " min");
            m.alert(AlertKind.SENSOR, a.id, "No sensor data from " + a.id + " for " + min + " min: the count (" + a.occupancy + ") may be out of date." + asked);
            done.append("STALE ").append(a.id).append(' ');
        }
    }

    // ================================================================ 2. follow-through

    /** Unit acknowledged but did not arrive; automatic crowd incidents that can close; long waits. */
    private void followThrough(LocalDateTime t, StringBuilder done) {
        long arrive = v().onSceneMinutes * 60L;
        for (Incident i : m.incidentTree().inOrder()) {
            if (i.status != IncidentStatus.ASSIGNED || i.acknowledged == null || i.followUps >= 2) continue;
            if (Duration.between(i.acknowledged, t).getSeconds() < arrive * (i.followUps + 1)) continue;
            Unit u = m.findUnit(i.unitId);
            if (u == null) continue;
            if (i.followUps == 0) {
                i.followUps = 1;
                m.notifyUnit(u, MessageKind.REMINDER, i, "Not on scene yet for " + i + " at " + i.area.id + ": tell the control room where you are, or press On scene when you arrive.");
                i.history.addLast(new HistoryEntry(t, u.id + " not on scene " + v().onSceneMinutes + " min after acknowledging: reminder sent"));
                m.alert(AlertKind.FOLLOW_UP, i.toString(), u.id + " acknowledged " + i + " but is not on scene after " + v().onSceneMinutes + " min - reminder sent");
                done.append("NOT_ARRIVED ").append(i).append(' ');
            } else {
                i.followUps = 2;
                if (i.priority != Priority.CRITICAL) m.raise(i, Priority.values()[i.priority.ordinal() - 1], u.id + " still not on scene");
                Unit b = m.bestUnit(i.area, i.category.skill, i.language, u);
                if (b == null) b = m.bestUnit(i.area, null, i.language, u);
                String extra;
                if (b != null) {
                    CrowdMonitor.Route r = m.route(b.area.id, i.area.id, false, false);
                    m.notifyUnit(b, MessageKind.BACKUP, i, "BACKUP for " + i + " " + i.category.label + " at " + i.area.id + ": " + u.id + " has not arrived. Route: "
                        + (b.area == i.area ? "you are there" : r.found() ? r.path + " (" + r.walk + " min)" : "ask the control room") + ". Tell the control room when you are there.");
                    b.history.addLast(new HistoryEntry(t, "Sent as backup for " + i));
                    extra = "; " + b.id + " sent as backup";
                } else extra = "; no unit free for backup";
                i.history.addLast(new HistoryEntry(t, u.id + " still not on scene: escalated" + extra));
                m.alert(AlertKind.FOLLOW_UP, i.toString(), u.id + " still not on scene at " + i + " after " + 2 * v().onSceneMinutes + " min: escalated" + extra);
                done.append("BACKUP ").append(i).append(' ');
            }
        }
        // automatic crowd incidents whose area has calmed down
        for (Area a : m.areaTree().inOrder()) {
            if (a.autoIncident == 0 || a.belowHighSince == null) continue;
            Incident i = m.findIncident(a.autoIncident);
            if (i == null || !i.open() || !i.automatic) continue;
            long min = Duration.between(a.belowHighSince, t).toMinutes();
            if (min < v().autoCloseMinutes) continue;
            m.resolve(i.id, "Closed automatically: " + a.id + " below HIGH for " + min + " min" + (i.reviewedBy == null ? " (no officer review)" : ""));
            a.autoIncident = 0;
            m.alert(AlertKind.CLEARED, i.toString(), i + " closed automatically: " + a.id + " has been below HIGH for " + min + " min");
            done.append("AUTOCLOSED ").append(i).append(' ');
        }
        // incidents that waited too long for a qualified unit
        for (Incident i : m.incidentTree().inOrder()) {
            if (i.status != IncidentStatus.WAITING || i.waitHandled) continue;
            if (Duration.between(i.reported, t).toMinutes() < v().maxWaitMinutes) continue;
            i.waitHandled = true;
            Unit u = m.bestUnit(i.area, null, i.language, null);
            if (u != null && i.category.skill != null && !i.category.skill.isBlank()) {
                m.queue(i.priority).remove(i);
                m.assign(i, u);
                i.history.addLast(new HistoryEntry(t, "Waited " + v().maxWaitMinutes + " min with no unit with '" + i.category.skill + "' free: " + u.id + " sent as a stand-in"));
                m.alert(AlertKind.NO_UNIT, i.toString(), i + " waited " + v().maxWaitMinutes + " min: " + u.id + " sent as a stand-in (no '" + i.category.skill + "' unit free)");
                done.append("STANDIN ").append(i).append(' ');
            } else {
                if (i.priority != Priority.CRITICAL) m.raise(i, Priority.values()[i.priority.ordinal() - 1], "waited " + v().maxWaitMinutes + " min");
                m.alert(AlertKind.NO_UNIT, i.toString(), i + " has waited " + v().maxWaitMinutes + " min and no unit at all is free: call units back on duty");
                done.append("LONGWAIT ").append(i).append(' ');
            }
        }
    }

    // ================================================================ 3. crowd flow

    /** Called when an area's level changes: holds or releases the flow into it, drafts a sign. @return text for the result */
    String onLevel(Area a, CrowdLevel before, CrowdLevel level) {
        if (v() == null) return "";
        String flow = flow(a, before, level);
        return level.compareTo(before) > 0 ? flow + reinforce(a) : flow;
    }

    /** Holds or releases the flow into an area when it crosses HIGH, and drafts a sign. */
    private String flow(Area a, CrowdLevel before, CrowdLevel level) {
        boolean high = level.compareTo(CrowdMonitor.ALERT_LEVEL) >= 0, wasHigh = before.compareTo(CrowdMonitor.ALERT_LEVEL) >= 0;
        if (high && !wasHigh && !a.flowHeld && auto() && v().holdFlow && !a.restricted) {
            LinkedList<String> told = new LinkedList<>();
            for (Unit u : unitsNextTo(a)) {
                m.notifyUnit(u, MessageKind.HOLD, null, "HOLD FLOW into " + a.id + " (" + a.name + ", " + a.percent() + "% " + level + "): slow people going from "
                    + u.area.id + " towards " + a.id + " - queue them or offer another way. You will be told when it is normal again.");
                told.addLast(u.id);
            }
            a.flowHeld = true;
            if (!told.isEmpty()) m.alert(AlertKind.FLOW, a.id, "Flow into " + a.id + " held: " + join(told) + " told to slow people coming in");
            if (v().signs) draft(a.id, a.name + " is very busy. Please wait here or follow staff directions" + alternative(a) + ".");
            return told.isEmpty() ? "" : " FLOW: " + told.size() + " unit(s) told to hold people back.";
        }
        if (!high && a.flowHeld) {
            a.flowHeld = false;
            for (Unit u : unitsNextTo(a)) m.notifyUnit(u, MessageKind.HOLD, null, "FLOW NORMAL into " + a.id + ": it is back to " + level + ". Let people through as usual.");
            m.alert(AlertKind.FLOW, a.id, "Flow into " + a.id + " back to normal (" + level + ")");
            for (Announcement an : announcements) if (an.status.equals("PROPOSED") && a.id.equals(an.ref)) { an.status = "EXPIRED"; an.decided = now(); }
        }
        return "";
    }

    /**
     * @return ", or use X" for another access point (gate, entrance or checkpoint) of the same type in the
     *         same sector that is LOW: a real alternative, such as another gate; "" for halls, stands and
     *         other areas, where a neighbour of the same type is not something people can switch to
     */
    private String alternative(Area a) {
        if (!a.type.equals("GATE") && !a.type.equals("ENTRANCE") && !a.type.equals("CHECKPOINT")) return "";
        for (Area b : m.areaTree().inOrder())
            if (b != a && !b.restricted && !b.evacuating && b.type.equals(a.type) && b.sector.equals(a.sector) && b.level == CrowdLevel.LOW && m.distancesFrom(a.id).distanceTo(b.id) <= 10)
                return ", or use " + b.name;
        return "";
    }

    /** @return units on duty standing in areas with a walking link to {@code a} */
    private LinkedList<Unit> unitsNextTo(Area a) {
        LinkedList<Unit> out = new LinkedList<>();
        Graph g = m.map();
        for (Graph.Edge e : g.neighbours(g.indexOf(a.id))) {
            String n = g.name(e.to);
            for (Unit u : m.unitTree().inOrder())
                if (u.area.id.equals(n) && (u.status == UnitStatus.AVAILABLE || u.status == UnitStatus.BUSY)) out.addLast(u);
        }
        return out;
    }

    // ================================================================ 3b. reinforcement

    /** @return how many units a crowded area should have: the venue's number for HIGH or CRITICAL, 0 below HIGH */
    int target(Area a) {
        if (v() == null || a.restricted || a.evacuating) return 0;
        return a.level == CrowdLevel.CRITICAL ? v().reinforceCritical : a.level == CrowdLevel.HIGH ? v().reinforceHigh : 0;
    }

    /** @return units on duty at the area, sent there, or busy with a task there */
    int unitsAt(Area a) {
        int n = 0;
        for (Unit u : m.unitTree().inOrder()) {
            if (u.status != UnitStatus.AVAILABLE && u.status != UnitStatus.BUSY) continue;
            Incident task = u.status == UnitStatus.BUSY ? m.activeIncident(u.id) : null;
            if (task != null) { if (task.area == a) n++; continue; }   // a busy unit counts where its task is
            if (u.area == a || a.id.equals(u.stagedFor) || a.id.equals(u.reinforcing)) n++;
        }
        return n;
    }

    /** @return true if the unit has one of the skills preferred for reinforcement (crowd-control or security by default) */
    private boolean reinforcer(Unit u) {
        if (v().reinforceSkills.isEmpty()) return u.has("crowd-control") || u.has("security");
        for (String s : v().reinforceSkills) if (u.has(s)) return true;
        return false;
    }

    /**
     * Reinforcement: brings a crowded area up to the venue's number of units (2 at HIGH, 4 at
     * CRITICAL by default). The nearest free units with a preferred skill are sent first; at CRITICAL
     * other free units are sent too if no skilled one is left (never a first-aid unit, which stays free
     * for injuries), and a sector's last free unit may be taken. At HIGH only units within the venue's
     * {@code reinforcemax:} walk (10 minutes) are sent; at CRITICAL any distance. Each unit is told to go there and remembers its own post, to return to later.
     *
     * @return text for the result, or "" if nothing was sent
     */
    String reinforce(Area a) {
        if (!auto() || v() == null) return "";
        int want = target(a), have = unitsAt(a);
        if (want <= have) return "";
        boolean critical = a.level == CrowdLevel.CRITICAL;
        LinkedList<String> sent = new LinkedList<>();
        boolean tooFar = false;
        for (int k = have; k < want; k++) {
            Unit best = null;
            long bestScore = Long.MAX_VALUE;
            for (Unit u : m.unitTree().inOrder()) {
                if (u.status != UnitStatus.AVAILABLE || u.reinforcing != null || u.stagedFor != null || u.area == a) continue;
                if (u.area.evacuating) continue;
                boolean skilled = reinforcer(u);
                if (!skilled && !critical) continue;
                if (!skilled && u.has("first-aid")) continue;   // medical units stay free for injuries
                if (!critical && !u.area.sector.equals(a.sector) && freeIn(u.area.sector) <= 1) continue;
                // walking minutes on the route the unit is actually given (never through staff-only areas)
                CrowdMonitor.Route way = m.route(u.area.id, a.id, false, false);
                if (!way.found()) continue;
                int d = way.walk;
                // at HIGH a unit further away would arrive after the peak and leave its own area uncovered
                if (!critical && v().reinforceMax > 0 && d > v().reinforceMax) { tooFar = true; continue; }
                long score = d * 10L + (skilled ? 0 : 1000);
                if (score < bestScore) { bestScore = score; best = u; }
            }
            if (best == null) break;
            CrowdMonitor.Route r = m.route(best.area.id, a.id, false, false);
            m.notifyUnit(best, MessageKind.MOVE, null, "REINFORCE " + a.id + " (" + a.name + ", " + a.percent() + "% " + a.level + "): go there now"
                + (r.found() ? " via " + r.path + " (" + r.walk + " min)" : "") + ". Help keep people moving and safe; report to the unit in charge there. You will be told when to return to " + best.area.id + ".");
            best.history.addLast(new HistoryEntry(now(), "Sent as reinforcement " + best.area.id + " -> " + a.id + " (" + a.level + ")"));
            if (best.home == null) best.home = best.area.id;
            best.area = a;
            best.reinforcing = a.id;
            sent.addLast(best.id);
        }
        int now = unitsAt(a);
        String shortBy = now >= want ? "" : tooFar ? "; no other suitable unit within " + v().reinforceMax + " min walk - send one from further away if needed, or call units back on duty"
            : "; no more free units: call units back on duty";
        if (sent.isEmpty()) {
            // say once per level that the area is short, so the officer can decide
            String key = a.id + "|" + a.level;
            if (!key.equals(a.reinforceShortNoted)) {
                a.reinforceShortNoted = key;
                m.alert(AlertKind.REPOSITION, a.id, a.id + " (" + a.level + ") has " + now + " of " + want + " units" + shortBy);
            }
            return "";
        }
        a.reinforceShortNoted = now < want ? a.id + "|" + a.level : null;
        m.alert(AlertKind.REPOSITION, a.id, "Reinforcement for " + a.id + " (" + a.level + ", " + a.percent() + "%): " + join(sent) + " sent - " + now + " of " + want + " units there" + shortBy);
        m.changed();
        return " REINFORCE: " + join(sent) + " sent to " + a.id + " (" + now + " of " + want + ").";
    }

    /** Sends reinforcements back to their own posts once their area has stayed below HIGH long enough. */
    private void releaseReinforcements(LocalDateTime t, StringBuilder done) {
        for (Unit u : m.unitTree().inOrder()) {
            if (u.reinforcing == null) continue;
            Area a = m.findArea(u.reinforcing);
            boolean calm = a == null || a.level.compareTo(CrowdMonitor.ALERT_LEVEL) < 0 && a.belowHighSince != null
                && Duration.between(a.belowHighSince, t).toMinutes() >= v().reinforceHold;
            if (!calm || u.status == UnitStatus.BUSY) continue;      // a unit busy with a task is released when it is free
            String home = u.home;
            u.reinforcing = null;
            u.home = null;
            if (u.status != UnitStatus.AVAILABLE) continue;
            Area back = home == null ? null : m.findArea(home);
            m.notifyUnit(u, MessageKind.MOVE, null, "STAND DOWN reinforcement at " + (a == null ? "?" : a.id) + ": it has been calm for " + v().reinforceHold + " min. "
                + (back != null && back != u.area ? "Return to your post at " + back.id + "." : "Stay where you are."));
            u.history.addLast(new HistoryEntry(t, "Reinforcement at " + (a == null ? "?" : a.id) + " over" + (back != null ? ": back to " + back.id : "")));
            if (back != null) u.area = back;
            m.alert(AlertKind.REPOSITION, u.id, u.id + " released from reinforcement at " + (a == null ? "?" : a.id) + (back != null ? ", back to " + back.id : ""));
            done.append("RELEASED ").append(u.id).append(' ');
        }
    }

    /** Sends free units ahead of time to areas expected to reach CRITICAL soon. */
    private void preposition(LocalDateTime t, StringBuilder done) {
        int horizon = v().prepositionMinutes;
        if (horizon <= 0) return;
        for (Unit u : m.unitTree().inOrder())
            if (u.stagedFor != null && u.stagedAt != null && Duration.between(u.stagedAt, t).toMinutes() >= STAGE_MINUTES) u.stagedFor = null;
        for (Area a : m.areaTree().inOrder()) {
            if (a.restricted || a.level == CrowdLevel.CRITICAL || a.evacuating) continue;
            int eta = m.minutesToCritical(a);
            if (eta <= 0 || eta > horizon) continue;
            if (hasUnit(a)) continue;
            Unit u = freeUnitFor(a);
            if (u == null) continue;
            stage(u, a, "MOVE TO " + a.id + " (" + a.name + "): crowd expected to reach CRITICAL in about " + eta + " min. Be there before it does; no task yet.");
            m.alert(AlertKind.REPOSITION, a.id, u.id + " sent ahead to " + a.id + ": CRITICAL expected in about " + eta + " min");
            done.append("STAGED ").append(u.id).append(' ');
        }
    }

    /** @return true if a unit on duty is at, or on its way to, the area */
    private boolean hasUnit(Area a) {
        for (Unit u : m.unitTree().inOrder()) {
            if (u.status == UnitStatus.OFF_DUTY || u.status == UnitStatus.UNREACHABLE) continue;
            if (u.area == a || a.id.equals(u.stagedFor) || a.id.equals(u.reinforcing)) return true;
            Incident task = u.status == UnitStatus.BUSY ? m.activeIncident(u.id) : null;
            if (task != null && task.area == a) return true;   // already on its way there with a task
        }
        return false;
    }

    /** @return the nearest free unit that is not staged and not the last free unit of its own sector, or null */
    private Unit freeUnitFor(Area a) {
        Graph.Paths p = m.distancesFrom(a.id);
        Unit best = null;
        int bestD = Graph.INF;
        for (Unit u : m.unitTree().inOrder()) {
            if (u.status != UnitStatus.AVAILABLE || u.stagedFor != null || u.reinforcing != null) continue;
            if (!u.area.sector.equals(a.sector) && freeIn(u.area.sector) <= 1) continue;
            int d = p.distanceTo(u.area.id);
            if (d < bestD) { bestD = d; best = u; }
        }
        return best;
    }

    /** @return free units standing in a sector */
    private int freeIn(String sector) {
        int n = 0;
        for (Unit u : m.unitTree().inOrder()) if (u.status == UnitStatus.AVAILABLE && u.area.sector.equals(sector)) n++;
        return n;
    }

    /** Moves a unit to an area and tells its device; it counts as being there from now on. */
    private void stage(Unit u, Area a, String text) {
        m.notifyUnit(u, MessageKind.MOVE, null, text);
        u.history.addLast(new HistoryEntry(now(), "Sent ahead " + u.area.id + " -> " + a.id + " (automatic)"));
        u.area = a;
        u.stagedFor = a.id;
        u.stagedAt = now();
    }

    /** Keeps at least one free unit in every sector that has public areas. */
    private void coverSectors(LocalDateTime t, StringBuilder done) {
        if (!v().coverSectors) return;
        LinkedList<String> sectors = new LinkedList<>();
        for (Area a : m.areaTree().inOrder()) if (!a.restricted && !sectors.contains(a.sector)) sectors.addLast(a.sector);
        for (String s : sectors) {
            if (freeIn(s) > 0) continue;
            LocalDateTime last = covered.search(s);
            if (last != null && Duration.between(last, t).toMinutes() < COVER_COOLDOWN_MINUTES) continue;
            // the sector's busiest public area is where a covering unit is most useful
            Area target = null;
            for (Area a : m.areaTree().inOrder()) if (a.sector.equals(s) && !a.restricted && !a.evacuating && (target == null || a.percent() > target.percent())) target = a;
            if (target == null) continue;
            Graph.Paths p = m.distancesFrom(target.id);
            Unit best = null;
            int bestD = Graph.INF;
            for (Unit u : m.unitTree().inOrder()) {
                if (u.status != UnitStatus.AVAILABLE || u.stagedFor != null || u.reinforcing != null || freeIn(u.area.sector) < 2) continue;
                int d = p.distanceTo(u.area.id);
                if (d < bestD) { bestD = d; best = u; }
            }
            if (best == null) continue;
            covered.insert(s, t);
            String from = best.area.sector;
            stage(best, target, "MOVE TO " + target.id + " (" + target.name + "): " + s + " has no free unit. Cover it until told otherwise.");
            m.alert(AlertKind.REPOSITION, target.id, best.id + " moved from " + from + " to cover " + s + " (no free unit there)");
            done.append("COVER ").append(s).append(' ');
        }
    }

    // ================================================================ 4. scheduled events

    /** Prepares for scheduled peaks and ends them. */
    private void events(LocalDateTime t, StringBuilder done) {
        int k = 0;
        for (String[] ev : v().events) {
            int idx = k++;
            LocalDateTime start = LocalDateTime.of(t.toLocalDate(), LocalTime.parse(ev[0]));
            LocalDateTime prep = start.minusMinutes(v().eventLead), end = start.plusMinutes(Integer.parseInt(ev[1]));
            String day = t.toLocalDate().toString();
            LinkedList<Area> zone = m.zoneAreas(ev[2]);
            if (!t.isBefore(prep) && t.isBefore(end) && eventsDone.search(idx + "|" + day + "|start") == null) {
                eventsDone.insert(idx + "|" + day + "|start", "done");
                int placed = 0;
                for (Area a : zone) {
                    a.thresholdOffset = v().eventOffset;
                    m.applyLevel(a, levelNow(a), a.source == null ? Source.OFFICIAL : a.source, a.id + " event thresholds");
                }
                if (auto()) {
                    for (Area a : zone) {
                        if (a.restricted || hasUnit(a)) continue;
                        Unit u = freeUnitFor(a);
                        if (u == null) break;
                        stage(u, a, "MOVE TO " + a.id + " for " + ev[3] + " at " + ev[0] + ": be in position before the peak.");
                        placed++;
                    }
                }
                m.alert(AlertKind.EVENT, ev[2], "Peak expected: " + ev[3] + " at " + ev[0] + " in " + ev[2] + ". Levels there start " + v().eventOffset
                    + " points earlier until " + end.toLocalTime() + (auto() ? "; " + placed + " unit(s) sent ahead" : ""));
                done.append("EVENT ").append(ev[3]).append(' ');
            }
            if (!t.isBefore(end) && eventsDone.search(idx + "|" + day + "|start") != null && eventsDone.search(idx + "|" + day + "|end") == null) {
                eventsDone.insert(idx + "|" + day + "|end", "done");
                for (Area a : zone) {
                    a.thresholdOffset = 0;
                    m.applyLevel(a, levelNow(a), a.source == null ? Source.OFFICIAL : a.source, a.id + " normal thresholds");
                }
                m.alert(AlertKind.EVENT, ev[2], ev[3] + " over: normal levels again in " + ev[2]);
                done.append("EVENT_END ").append(ev[3]).append(' ');
            }
        }
    }

    /** @return the level the area should have now (thresholds and event offset applied, no hysteresis) */
    private CrowdLevel levelNow(Area a) { return m.levelFor(a.percent() + a.thresholdOffset); }

    /** @return names of events whose preparation or peak is running */
    LinkedList<String> activeEvents() {
        LinkedList<String> out = new LinkedList<>();
        if (v() == null) return out;
        LocalDateTime t = now();
        for (String[] ev : v().events) {
            LocalDateTime start = LocalDateTime.of(t.toLocalDate(), LocalTime.parse(ev[0]));
            if (!t.isBefore(start.minusMinutes(v().eventLead)) && t.isBefore(start.plusMinutes(Integer.parseInt(ev[1])))) out.addLast(ev[3] + " at " + ev[0] + " (" + ev[2] + ")");
        }
        return out;
    }

    // ================================================================ 5. evacuation proposal and alarms

    /** Proposes an evacuation for an area that stays CRITICAL and keeps rising. */
    private void propose(LocalDateTime t, StringBuilder done) {
        if (proposedZone != null) {
            // withdraw a crowd-based proposal once the area is no longer CRITICAL
            Area a = m.findArea(proposedZone);
            if (a != null && a.level != CrowdLevel.CRITICAL && proposalReason.startsWith("CRITICAL")) {
                m.alert(AlertKind.PROPOSAL, proposedZone, "Evacuation proposal for " + proposedZone + " withdrawn: it is " + a.level + " now");
                proposedZone = null; proposalReason = null; proposedAt = null;
            }
            return;
        }
        if (m.evacuationZone() != null || v().exits.isEmpty()) return;
        for (Area a : m.areaTree().inOrder()) {
            if (a.criticalSince == null || a.restricted) continue;
            long min = Duration.between(a.criticalSince, t).toMinutes();
            if (min < v().proposeMinutes || m.ratePerMinute(a) <= 0) continue;
            setProposal(a.id, "CRITICAL for " + min + " min and still rising (" + a.percent() + "%)");
            done.append("PROPOSED ").append(a.id).append(' ');
            return;
        }
    }

    /** Records an evacuation proposal for a zone and tells the control room. */
    private void setProposal(String zone, String reason) {
        proposedZone = zone; proposalReason = reason; proposedAt = now();
        m.alert(AlertKind.PROPOSAL, zone, "EVACUATION PROPOSED for " + zone + ": " + reason + ". An officer must decide: start it or dismiss it.");
        m.changed();
    }

    /** A fire or other alarm: an SOS-level check is sent at once and an evacuation of the area's sector is proposed. */
    Result alarm(String areaId, String what) {
        Area a = m.findArea(areaId);
        if (a == null) return Result.fail("Unknown area: " + areaId);
        if (CrowdMonitor.tooLong("Alarm text", what, 120) != null) return CrowdMonitor.tooLong("Alarm text", what, 120);
        String kind = what == null || what.isBlank() ? "Fire alarm" : what.trim();
        m.alert(AlertKind.ALARM, a.id, kind.toUpperCase() + " at " + a.id + " (" + a.name + ")");
        Result r = m.sos(a.id, null, kind + " - check and report");
        // the fire zone (compartment) the alarm is in, if the layout defines one; otherwise the whole sector
        String zone = m.fireZoneOf(a.id) != null ? m.fireZoneOf(a.id) : a.sector;
        if (m.evacuationZone() == null && !v().exits.isEmpty()) setProposal(zone, kind + " at " + a.id);
        return Result.ok(kind + " at " + a.id + ": " + r.message + (proposedZone != null ? " Evacuation of " + proposedZone + " proposed." : ""));
    }

    /** The officer starts or dismisses the proposed evacuation. */
    Result decideProposal(boolean start, String by) {
        if (proposedZone == null) return Result.fail("No evacuation is proposed.");
        String zone = proposedZone;
        if (start) {
            Result r = m.evacuate(zone, by);
            if (r.ok) { proposedZone = null; proposalReason = null; proposedAt = null; }
            return r;
        }
        Result in = m.officerCheckIn(by);
        if (!in.ok) return in;
        proposedZone = null; proposalReason = null; proposedAt = null;
        m.alert(AlertKind.PROPOSAL, zone, "Evacuation proposal for " + zone + " dismissed by " + m.officer());
        m.changed();
        return Result.ok("Evacuation proposal for " + zone + " dismissed.");
    }

    /** @return {zone, reason, time} of the current proposal, or null */
    String[] proposal() {
        return proposedZone == null ? null : new String[] { proposedZone, proposalReason, proposedAt.format(HistoryEntry.FORMAT) };
    }

    /** An evacuation started: the proposal is settled and a public message is drafted. */
    void onEvacuation(String zone) {
        proposedZone = null; proposalReason = null; proposedAt = null;
        if (v().signs) draft(zone, "Attention please. For your safety, please leave " + zone + " calmly by the nearest exit (" + m.exits() + "). Follow staff directions. Do not use lifts.");
    }

    // ================================================================ 6. lost persons

    /** Called for every new incident; starts the lost-person workflow for lost-person categories. @return text for the result */
    String onReported(Incident i) {
        if (!i.lostCase() || i.foundAt != null || !auto()) return "";
        i.searchMinutes = v().lostRadius;
        String extra = "";
        if (vulnerable(i.details) && i.priority != Priority.CRITICAL) {
            m.raise(i, Priority.CRITICAL, "child or vulnerable person");
            extra = " Raised to CRITICAL (child or vulnerable person).";
        }
        int n = lookout(i, "LOOKOUT");
        String meet = meeting(i);
        i.history.addLast(new HistoryEntry(now(), "Lookout sent to " + n + " unit(s); search " + join(searchAreas(i)) + "; reporter to wait at " + meet));
        m.alert(AlertKind.LOST, i.toString(), "Lost person " + i + " last seen at " + i.area.id + ": lookout sent to " + n + " unit(s), search area " + join(searchAreas(i))
            + ", reporter to wait at " + meet);
        return " LOOKOUT sent to " + n + " unit(s); ask the reporter to wait at " + meet + "." + extra;
    }

    /** Sends the description to every unit on duty except the one on the case; units at exits are asked to check people leaving. */
    private int lookout(Incident i, String head) {
        LinkedList<String> search = searchAreas(i);
        int n = 0;
        for (Unit u : m.unitTree().inOrder()) {
            if (u.status == UnitStatus.OFF_DUTY || u.id.equals(i.unitId)) continue;
            boolean atExit = v().exits.contains(u.area.id);
            boolean inSearch = search.contains(u.area.id);
            m.notifyUnit(u, MessageKind.LOOKOUT, i, head + " " + i + ": " + i.details + " - last seen at " + i.area.id + " " + i.reported.toLocalTime().withNano(0).withSecond(0)
                + (inSearch ? ". YOU ARE IN THE SEARCH AREA: search around " + u.area.id : "") + (atExit ? ". YOU ARE AT AN EXIT: check everyone leaving" : "")
                + ". If found, press Found on your device or call the control room; bring the person to " + meeting(i) + ".");
            n++;
        }
        return n;
    }

    /** @return the meeting point for a case: the venue's, or the area where the person was reported */
    private String meeting(Incident i) { return m.meetingFor(i.area.id); }

    /** @return areas within the case's search radius of where the person was last seen, nearest first */
    LinkedList<String> searchAreas(Incident i) {
        LinkedList<String> out = new LinkedList<>();
        if (i.searchMinutes <= 0) return out;
        Graph.Paths p = m.distancesFrom(i.area.id);
        for (int d = 0; d <= i.searchMinutes; d++)
            for (Area a : m.areaTree().inOrder()) if (p.distanceTo(a.id) == d && !a.restricted) out.addLast(a.id);
        return out;
    }

    /** Widens the search and escalates lost-person cases that are still open. */
    private void lostCasesTick(LocalDateTime t, StringBuilder done) {
        for (Incident i : m.incidentTree().inOrder()) {
            if (!i.open() || !i.lostCase() || i.foundAt != null || i.searchMinutes <= 0) continue;
            long min = Duration.between(i.reported, t).toMinutes();
            int wanted = v().lostRadius + (int) (min / v().lostWiden) * v().lostWiden;
            if (wanted > i.searchMinutes) {
                int before = searchAreas(i).size();
                i.searchMinutes = wanted;
                if (searchAreas(i).size() > before) {
                    lookout(i, "LOOKOUT UPDATE (missing " + min + " min, search widened)");
                    i.history.addLast(new HistoryEntry(t, "Search widened to " + wanted + " min walk: " + join(searchAreas(i))));
                    done.append("WIDENED ").append(i).append(' ');
                }
            }
            if (i.lostSteps == 0 && min >= LOST_ESCALATE_MINUTES) {
                i.lostSteps = 1;
                if (i.priority != Priority.CRITICAL) m.raise(i, Priority.values()[i.priority.ordinal() - 1], "still missing after " + min + " min");
                m.alert(AlertKind.LOST, i.toString(), i + " still missing after " + min + " min: search widened to " + searchAreas(i).size() + " areas; ask every exit to check");
                done.append("LOST15 ").append(i).append(' ');
            } else if (i.lostSteps == 1 && min >= LOST_AUTHORITIES_MINUTES) {
                i.lostSteps = 2;
                m.alert(AlertKind.LOST, i.toString(), i + " missing for " + min + " min: consider calling the police / authorities and a public announcement");
                if (v().signs) draft(i.toString(), "Attention please: we are looking for " + i.details + ", last seen near " + i.area.name + ". If you see this person, please tell any member of staff.");
                done.append("LOST30 ").append(i).append(' ');
            }
        }
    }

    /** Records that a lost person was found; tells every unit and brings the person to the meeting point. */
    Result found(int id, String areaId, String by) {
        Incident i = m.findIncident(id);
        if (i == null) return Result.fail("No incident I" + id + ".");
        if (!i.lostCase()) return Result.fail(i + " is not a lost-person case.");
        if (!i.open()) return Result.fail(i + " is already closed.");
        if (i.foundAt != null) return Result.fail(i + " was already found at " + i.foundWhere + ".");
        Unit finder = by == null ? null : m.findUnit(by);
        Area a = areaId == null || areaId.isBlank() ? (finder != null ? finder.area : i.area) : m.findArea(areaId);
        if (a == null) return Result.fail("Unknown area: " + areaId);
        if (CrowdMonitor.tooLong("Found by", by, 40) != null) return CrowdMonitor.tooLong("Found by", by, 40);
        i.foundAt = now();
        i.foundWhere = a.id;
        i.foundBy = by == null || by.isBlank() ? "control room" : by.trim();
        String meet = meeting(i);
        i.history.addLast(new HistoryEntry(now(), "FOUND at " + a.id + " by " + i.foundBy + " after " + i.minutesOpen(now()) + " min; bring to " + meet));
        for (Unit u : m.unitTree().inOrder())
            if (u.status != UnitStatus.OFF_DUTY && !u.id.equals(i.unitId) && u != finder) m.notifyUnit(u, MessageKind.LOOKOUT, i, "STAND DOWN lookout " + i + ": found at " + a.id + ". Thank you.");
        Unit escort = finder != null && finder.status != UnitStatus.OFF_DUTY ? finder : i.unitId != null ? m.findUnit(i.unitId) : null;
        if (i.unitId == null) {
            // nobody was on the case yet: the finder (or the nearest free unit) takes it
            Unit u = finder != null && finder.status == UnitStatus.AVAILABLE ? finder : m.bestUnit(a, null, i.language, null);
            if (u != null) {
                if (i.status == IncidentStatus.WAITING) m.queue(i.priority).remove(i);
                m.assign(i, u);
                escort = u;
            }
        }
        Unit onCase = i.unitId == null ? null : m.findUnit(i.unitId);
        if (onCase != null && onCase != escort)
            m.notifyUnit(onCase, MessageKind.TASK, i, i + " FOUND at " + a.id + " by " + i.foundBy + ": go to " + meet + " to reunite the person with the reporter, then resolve.");
        if (escort != null) {
            CrowdMonitor.Route r = m.route(a.id, meet, false, false);
            m.notifyUnit(escort, MessageKind.TASK, i, "FOUND " + i + " at " + a.id + ": stay with the person and bring them to " + meet
                + (a.id.equals(meet) ? "" : r.found() ? " via " + r.path + " (" + r.walk + " min)" : "") + ". Check who they are with the reporter, then resolve.");
        }
        for (Announcement an : announcements) if (an.status.equals("PROPOSED") && i.toString().equals(an.ref)) { an.status = "EXPIRED"; an.decided = now(); }
        m.alert(AlertKind.LOST, i.toString(), i + " FOUND at " + a.id + " by " + i.foundBy + " after " + i.minutesOpen(now()) + " min" + (escort != null ? "; " + escort.id + " brings them to " + meet : "; send a unit to " + a.id));
        m.changed();
        return Result.ok(i + " found at " + a.id + ". Lookout stopped" + (escort != null ? "; " + escort.id + " brings the person to " + meet : "") + ". Resolve the case when they are reunited.");
    }

    /** Someone found an unaccompanied person: a lost-person case is opened as already found, and open cases are listed to compare. */
    Result foundPerson(String areaId, String details, String contact, String by) {
        Unit finder = by == null ? null : m.findUnit(by);
        Area a = (areaId == null || areaId.isBlank()) && finder != null ? finder.area : m.findArea(areaId);
        if (a == null) return Result.fail("Where was the person found? Choose an area.");
        if (details == null || details.isBlank()) return Result.fail("Describe the person who was found.");
        Category c = null;
        for (Category k : m.categoryTree().inOrder()) if (k.skill != null && k.skill.equalsIgnoreCase("lost-person")) { c = k; break; }
        if (c == null) return Result.fail("This venue has no lost-person category.");
        if (CrowdMonitor.tooLong("Details", details, 380) != null) return CrowdMonitor.tooLong("Details", details, 380);
        if (CrowdMonitor.tooLong("Contact", contact, 120) != null) return CrowdMonitor.tooLong("Contact", contact, 120);
        Incident i = m.create(c, a, "FOUND PERSON: " + details.trim(), contact, null);
        i.foundAt = now();
        i.foundWhere = a.id;
        i.foundBy = by == null || by.isBlank() ? "control room" : by.trim();
        String outcome = m.dispatchNew(i);
        StringBuilder open = new StringBuilder();
        for (Incident o : lostCases()) if (o != i && o.foundAt == null) open.append(open.length() == 0 ? "" : "; ").append(o).append(" (").append(o.details).append(", last seen ").append(o.area.id).append(')');
        i.history.addLast(new HistoryEntry(now(), "Unaccompanied person found at " + a.id + "; open lost cases to compare: " + (open.length() == 0 ? "none" : open)));
        m.alert(AlertKind.LOST, i.toString(), "Unaccompanied person found at " + a.id + ": " + details.trim() + ". Compare with " + (open.length() == 0 ? "no open lost case" : open));
        m.changed();
        return Result.ok(i + " recorded" + outcome + (open.length() == 0 ? " No open lost-person case to compare with." : " Compare with: " + open + "."));
    }

    /** @return open lost-person cases, oldest first */
    Incident[] lostCases() {
        LinkedList<Incident> out = new LinkedList<>();
        for (Incident i : m.incidentTree().inOrder()) if (i.open() && i.lostCase()) out.addLast(i);
        return out.toArray(new Incident[0]);
    }

    /**
     * @return true if a description mentions a child or a vulnerable adult: words such as child, boy,
     *         girl, baby, elderly, dementia or wheelchair, or an age of 12 or under or 75 or over
     */
    public static boolean vulnerable(String text) {
        if (text == null) return false;
        String t = " " + text.toLowerCase().replaceAll("[^a-z0-9]+", " ") + " ";
        for (String w : new String[] { " child ", " children ", " kid ", " boy ", " girl ", " baby ", " toddler ", " infant ", " son ", " daughter ",
            " elderly ", " old man ", " old woman ", " dementia ", " alzheimer ", " disabled ", " wheelchair ", " autistic ", " autism ", " blind " })
            if (t.contains(w)) return true;
        // ages written as "7 years", "7yo", "7 y" or "age 7" / "aged 80"
        for (String re : new String[] { " (\\d{1,3}) ?(?:y|yo|yr|yrs|year|years) ", " aged? (\\d{1,3}) " }) {
            java.util.regex.Matcher age = java.util.regex.Pattern.compile(re).matcher(t);
            while (age.find()) {
                int n = Integer.parseInt(age.group(1));
                if (n <= 12 || n >= 75 && n < 120) return true;
            }
        }
        return false;
    }

    // ================================================================ 7. shifts and handover

    /** Puts units on and off duty with their working hours and writes a handover report at shift changes. */
    private void shifts(LocalDateTime t, StringBuilder done) {
        LocalTime now = t.toLocalTime();
        for (String[] row : v().units) {
            if (row.length < 5 || row[4].isBlank()) continue;
            Unit u = m.findUnit(row[0]);
            if (u == null) continue;
            u.shift = row[4].trim();
            boolean on = inShift(u.shift, now);
            if (!on && u.status == UnitStatus.AVAILABLE) {
                u.status = UnitStatus.OFF_DUTY;
                u.shiftOff = true;
                u.stagedFor = null;
                u.history.addLast(new HistoryEntry(t, "Shift " + u.shift + " over: OFF_DUTY"));
                m.notifyUnit(u, MessageKind.SHIFT, null, "Your shift (" + u.shift + ") is over. Thank you - you are signed off.");
                m.alert(AlertKind.SHIFT, u.id, u.id + " signed off: shift " + u.shift + " over");
                done.append("OFF ").append(u.id).append(' ');
            } else if (!on && u.status == UnitStatus.BUSY && !u.offAfterTask) {
                u.offAfterTask = true;
                m.notifyUnit(u, MessageKind.SHIFT, null, "Your shift (" + u.shift + ") is over: finish your current task, then you will be signed off.");
                done.append("OFF_AFTER_TASK ").append(u.id).append(' ');
            } else if (on && u.status == UnitStatus.OFF_DUTY && u.shiftOff) {
                u.shiftOff = false;
                u.status = UnitStatus.AVAILABLE;
                u.history.addLast(new HistoryEntry(t, "Shift " + u.shift + " started: AVAILABLE"));
                m.notifyUnit(u, MessageKind.SHIFT, null, "Your shift (" + u.shift + ") has started. You are on duty at " + u.area.id + ".");
                m.alert(AlertKind.SHIFT, u.id, u.id + " on duty: shift " + u.shift);
                m.dispatchWaiting();
                done.append("ON ").append(u.id).append(' ');
            } else if (on && u.offAfterTask) u.offAfterTask = false;
        }
        for (String s : v().shiftChanges) {
            LocalDateTime at = LocalDateTime.of(t.toLocalDate(), LocalTime.parse(s));
            String key = t.toLocalDate() + " " + s;
            // handle a change once, within 30 minutes after its time (not again after a restart later in the day)
            if (t.isBefore(at) || Duration.between(at, t).toMinutes() > 30 || shiftsDone.search(key) != null) continue;
            shiftsDone.insert(key, "done");
            String text = handoverReport();
            handovers.addFirst(new String[] { t.withNano(0).toString(), text });
            while (handovers.size() > 20) handovers.removeLast();
            lastHandover = t;
            String file = writeReport(t, text);
            m.alert(AlertKind.SHIFT, "handover", "Shift change " + s + ": handover report ready" + (file == null ? "" : " (" + file + ")"));
            done.append("HANDOVER ").append(s).append(' ');
        }
    }

    /** @return true if a time is inside "HH:MM-HH:MM" (a shift may run past midnight) */
    static boolean inShift(String shift, LocalTime t) {
        try {
            String[] p = shift.split("-");
            LocalTime a = LocalTime.parse(VenueProfile.time(p[0])), b = LocalTime.parse(VenueProfile.time(p[1]));
            return a.isBefore(b) ? !t.isBefore(a) && t.isBefore(b) : !t.isBefore(a) || t.isBefore(b);
        } catch (RuntimeException e) {
            return true;   // a shift we cannot read never signs a unit off
        }
    }

    /** Writes a report to the report folder. @return the file name, or null */
    private String writeReport(LocalDateTime t, String text) {
        if (reportFolder == null) return null;
        try {
            Files.createDirectories(reportFolder);
            Path f = reportFolder.resolve("handover-" + t.toLocalDate() + "-" + String.format("%02d%02d", t.getHour(), t.getMinute()) + ".txt");
            Files.writeString(f, text, StandardCharsets.UTF_8);
            return f.getFileName().toString();
        } catch (Exception e) {
            m.alert(AlertKind.SYSTEM, "handover", "Handover report not written: " + e.getMessage());
            return null;
        }
    }

    /** @return the handover report for now: situation, open incidents, lost persons, units, alerts since the last handover */
    String handoverReport() {
        LocalDateTime t = now();
        StringBuilder sb = new StringBuilder();
        sb.append("CROWDWATCH HANDOVER REPORT - ").append(m.venueName()).append(" - ").append(t.toLocalDate()).append(' ').append(t.toLocalTime().withNano(0)).append("\n\n");
        sb.append(m.summary()).append("\n\n");
        sb.append("AREAS AT HIGH OR CRITICAL\n");
        int hot = 0;
        for (Area a : m.areasByLoad()) {
            if (a.level.compareTo(CrowdMonitor.ALERT_LEVEL) < 0) continue;
            hot++;
            sb.append("  ").append(a.id).append("  ").append(a.percent()).append("%  ").append(a.level).append(a.flowHeld ? "  flow held" : "").append('\n');
        }
        if (hot == 0) sb.append("  none\n");
        long[] cc = m.countCheck();
        if (cc != null) sb.append("\nCOUNT CHECK: areas ").append(cc[1]).append(", gates ").append(cc[0]).append(", difference ").append(cc[2]).append('\n');
        sb.append("\nSENSORS\n");
        int bad = 0;
        for (Area a : m.areaTree().inOrder()) {
            if (a.staleSince == null && a.suspect == null) continue;
            bad++;
            sb.append("  ").append(a.id).append(a.staleSince != null ? "  no data since " + a.lastSensor.toLocalTime().withNano(0) : "").append(a.suspect != null ? "  suspect: " + a.suspect : "").append('\n');
        }
        if (bad == 0) sb.append("  all reporting\n");
        sb.append("\nOPEN INCIDENTS (most urgent first)\n");
        Incident[] open = m.openIncidentsByPriority();
        for (Incident i : open)
            sb.append("  ").append(i).append("  ").append(i.priority).append("  ").append(i.category.label).append(" at ").append(i.area.id).append("  ").append(i.status)
              .append(i.unitId == null ? "" : " (" + i.unitId + ")").append("  open ").append(i.minutesOpen(t)).append(" min").append(i.automatic && i.reviewedBy == null ? "  NEEDS REVIEW" : "").append('\n');
        if (open.length == 0) sb.append("  none\n");
        sb.append("\nLOST PERSONS\n");
        Incident[] lost = lostCases();
        for (Incident i : lost)
            sb.append("  ").append(i).append("  ").append(i.foundAt != null ? "FOUND at " + i.foundWhere + ", to be reunited" : "MISSING " + i.minutesOpen(t) + " min, last seen " + i.area.id).append('\n');
        if (lost.length == 0) sb.append("  none\n");
        sb.append("\nUNITS\n");
        for (Unit u : m.unitsSorted()) {
            Incident task = m.activeIncident(u.id);
            sb.append("  ").append(u.id).append("  ").append(u.status).append(" at ").append(u.area.id).append(u.shift != null ? "  shift " + u.shift : "").append(task != null ? "  on " + task : "").append('\n');
        }
        if (proposedZone != null) sb.append("\nEVACUATION PROPOSED for ").append(proposedZone).append(": ").append(proposalReason).append(" - awaiting a decision\n");
        Announcement[] p = pending();
        if (p.length > 0) {
            sb.append("\nPUBLIC MESSAGES AWAITING APPROVAL\n");
            for (Announcement a : p) sb.append("  #").append(a.id).append("  ").append(a.text).append('\n');
        }
        LinkedList<String> ev = activeEvents();
        if (!ev.isEmpty()) sb.append("\nSCHEDULED PEAKS NOW: ").append(join(ev)).append('\n');
        AVLTree<String, Integer> kinds = new AVLTree<>();
        int total = 0;
        for (Alert a : m.alerts()) {
            if (lastHandover != null && !a.time.isAfter(lastHandover)) break;
            Integer n = kinds.search(a.kind.name());
            kinds.insert(a.kind.name(), n == null ? 1 : n + 1);
            total++;
        }
        sb.append("\nALERTS ").append(lastHandover == null ? "IN MEMORY" : "SINCE " + lastHandover.toLocalTime().withNano(0)).append(": ").append(total);
        for (String k : kinds.keysInOrder()) sb.append("  ").append(k).append(' ').append(kinds.search(k));
        sb.append('\n');
        return sb.toString();
    }

    /** @return handover reports written at shift changes, newest first */
    LinkedList<String[]> handovers() { return handovers; }

    // ================================================================ 8. public signs and PA

    /** Drafts a public message for an officer to approve (no duplicate of a pending draft about the same thing). */
    private void draft(String ref, String text) {
        for (Announcement a : announcements) if (a.status.equals("PROPOSED") && a.ref.equals(ref)) return;
        Announcement a = new Announcement(nextAnnouncement++, now(), ref, text);
        announcements.addFirst(a);
        while (announcements.size() > MAX_ANNOUNCEMENTS) announcements.removeLast();
        m.alert(AlertKind.ANNOUNCE, "#" + a.id, "Public message drafted (#" + a.id + "), approve or reject: " + text);
    }

    /** Expires drafts nobody decided on. */
    private void expireDrafts(LocalDateTime t) {
        for (Announcement a : announcements)
            if (a.status.equals("PROPOSED") && Duration.between(a.time, t).toMinutes() >= ANNOUNCEMENT_MINUTES) { a.status = "EXPIRED"; a.decided = t; }
    }

    /** The officer approves (sends) or rejects a drafted message. */
    Result decideAnnouncement(int id, boolean approve, String by) {
        Announcement a = null;
        for (Announcement x : announcements) if (x.id == id) a = x;
        if (a == null) return Result.fail("No public message #" + id + ".");
        if (!a.status.equals("PROPOSED")) return Result.fail("Message #" + id + " is already " + a.status + ".");
        Result in = m.officerCheckIn(by);
        if (!in.ok) return in;
        a.decidedBy = m.officer();
        a.decided = now();
        if (!approve) {
            a.status = "REJECTED";
            m.changed();
            return Result.ok("Message #" + id + " rejected.");
        }
        a.status = "SENT";
        send(a.text);
        m.alert(AlertKind.ANNOUNCE, "#" + a.id, "Public message #" + a.id + " approved by " + a.decidedBy + " and sent: " + a.text);
        m.changed();
        return Result.ok("Message #" + id + " sent to the public signs and PA.");
    }

    /** The officer sends a message of their own. */
    Result announce(String text, String by) {
        if (text == null || text.isBlank()) return Result.fail("Message text is required.");
        if (CrowdMonitor.tooLong("Message", text, 300) != null) return CrowdMonitor.tooLong("Message", text, 300);
        Result in = m.officerCheckIn(by);
        if (!in.ok) return in;
        Announcement a = new Announcement(nextAnnouncement++, now(), "officer", text.trim());
        a.status = "SENT";
        a.decidedBy = m.officer();
        a.decided = now();
        announcements.addFirst(a);
        send(a.text);
        m.alert(AlertKind.ANNOUNCE, "#" + a.id, "Public message by " + a.decidedBy + ": " + a.text);
        m.changed();
        return Result.ok("Message sent to the public signs and PA.");
    }

    /** Hands a public message to every channel (radio gateway, console relay) on the event thread. */
    private void send(String text) { m.announceToChannels(text); }

    /** @return drafts waiting for a decision, oldest first */
    Announcement[] pending() {
        LinkedList<Announcement> out = new LinkedList<>();
        for (Announcement a : announcements) if (a.status.equals("PROPOSED")) out.addFirst(a);
        return out.toArray(new Announcement[0]);
    }

    /** @return all announcements in memory, newest first */
    LinkedList<Announcement> announcements() { return announcements; }

    // ================================================================ the 15-second check

    /** Runs every rule that depends on time. Called by {@link CrowdMonitor#tick}. */
    void tick(LocalDateTime t, StringBuilder done) {
        if (v() == null) return;
        checkSensors(t, done);
        shifts(t, done);
        expireDrafts(t);
        if (!auto()) return;
        followThrough(t, done);
        lostCasesTick(t, done);
        events(t, done);
        releaseReinforcements(t, done);
        for (Area a : m.areaTree().inOrder()) if (target(a) > 0) { String r = reinforce(a); if (!r.isEmpty()) done.append("REINFORCED ").append(a.id).append(' '); }
        preposition(t, done);
        coverSectors(t, done);
        propose(t, done);
    }

    /** @return the items separated by commas, or "none" for an empty list */
    private static String join(LinkedList<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(sb.length() == 0 ? "" : ", ").append(s);
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** @return today's date (for tests) */
    static LocalDate today(LocalDateTime t) { return t.toLocalDate(); }
}
