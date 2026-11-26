package crowdwatch.net;

import crowdwatch.core.CrowdMonitor;
import crowdwatch.core.VenueProfile;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.*;
import crowdwatch.model.Enums.IncidentStatus;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import static crowdwatch.net.ApiServer.err;
import static crowdwatch.net.ApiServer.str;

/**
 * What the consoles read: the overview, areas, incidents, units, alerts, the venue and its
 * layout, lost persons, public messages, one incident, an area's readings, the evacuation plan,
 * the control-room status and walking routes, as JSON.
 * <p>
 * One copy for both web servers: the JDK server ({@link ApiServer}) and the Spring Boot server's
 * controllers call these methods, through {@link ApiServer#read}, which holds the monitor's lock
 * while they run and caches the replies for half a second. Call them only that way.
 */
public final class ConsoleData {
    private final CrowdMonitor m;
    private final BooleanSupplier drill;
    private final Supplier<java.nio.file.Path> dataFolder;

    ConsoleData(CrowdMonitor m, BooleanSupplier drill, Supplier<java.nio.file.Path> dataFolder) {
        this.m = m;
        this.drill = drill;
        this.dataFolder = dataFolder;
    }

    /**
     * Parses a number parameter. A leading letter is dropped so "I1001" works as an incident ID.
     *
     * @throws IllegalArgumentException if it is missing or not a number (reply 400)
     */
    public static int number(String s) { return ApiServer.num(s); }

    /** @return JSON array of areas (busiest first) with level, trend rate and minutes to CRITICAL */
    public String areas() {
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
    public String incidents() {
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
    public static String deviceText(Incident i) {
        if (!i.open()) return "closed";
        if (i.unitId == null) return "waiting in the " + i.priority + " queue";
        if (i.status == IncidentStatus.ON_SCENE) return "on scene";
        if (i.acknowledged != null) return "acknowledged";
        return i.reminders > 0 ? "not acknowledged - reminder sent" : "sent, not yet acknowledged";
    }

    /** @return true if a unit's device has been in contact within the last 30 seconds */
    public boolean online(Unit u) {
        return u.lastContact != null && java.time.Duration.between(u.lastContact, m.now()).getSeconds() < crowdwatch.core.ConsoleRelay.DEVICE_ONLINE_SECONDS;
    }

    /**
     * Everything the console's overview needs in one reply: people and capacity, areas per level,
     * open incidents per priority, units per status and devices online, officer, automation,
     * dispatches awaiting review, evacuation and the archive count.
     */
    public String overview() {
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
            + ",\"drill\":" + drill.getAsBoolean() + ",\"heat\":" + heatJson() + ",\"sites\":" + (dataFolder.get() != null && java.nio.file.Files.exists(dataFolder.get().resolve("sites.txt"))) + "}";
    }

    /** @return the weather and heat stress as JSON, or null without a recent reading */
    public String heatJson() {
        double[] w = m.records.weatherNow(m.now());
        if (w == null) return "null";
        return "{\"temp\":" + Math.round(w[0] * 10) / 10.0 + ",\"humidity\":" + Math.round(w[1]) + ",\"index\":" + Math.round(w[2]) + ",\"band\":" + str(m.records.heatBandNow(m.now()))
            + ",\"outdoor\":" + list(toList(m.outdoorAreas())) + "}";
    }

    public static LinkedList<String> toList(String[] a) {
        LinkedList<String> l = new LinkedList<>();
        for (String x : a) l.addLast(x);
        return l;
    }

    /** @return the count check as JSON {expected, counted, difference, ok}, or null if gates are not counted */
    public String countCheckJson() {
        long[] c = m.countCheck();
        if (c == null) return "null";
        long allowed = Math.max(50, Math.abs(c[0]) * Math.max(0, m.venue().reconcilePercent) / 100);
        return "{\"expected\":" + c[0] + ",\"counted\":" + c[1] + ",\"difference\":" + c[2] + ",\"ok\":" + (m.venue().reconcilePercent <= 0 || Math.abs(c[2]) <= allowed) + "}";
    }

    /** @return JSON array of units with status, current task and whether the device is online */
    public String units() {
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
    public String incident(int id) {
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
    public String venue() {
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
    public static String fireZoneList(VenueProfile v) {
        StringBuilder sb = new StringBuilder("[");
        for (String[] z : v.fireZones) sb.append(sb.length() > 1 ? "," : "").append(str(z[0]));
        return sb.append(']').toString();
    }

    /** @return the venue's scheduled events as JSON */
    public static String eventsJson(VenueProfile v) {
        StringBuilder sb = new StringBuilder("[");
        for (String[] e : v.events) sb.append(sb.length() > 1 ? "," : "").append("{\"time\":").append(str(e[0])).append(",\"minutes\":").append(e[1])
            .append(",\"zone\":").append(str(e[2])).append(",\"name\":").append(str(e[3])).append('}');
        return sb.append(']').toString();
    }

    /** @return the sampled readings of one area: seconds ago and people counted */
    public String readings(String id) {
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
    public String evacuation(String zone) {
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
    public int clearAfter(String zone) {
        int worst = 0;
        for (String[] r : m.evacuationRows(zone)) if ("zone".equals(r[4]) && r[6] != null && r[6].matches("\\d+") && m.evacuationClearMinutesKnown()) worst = Math.max(worst, Integer.parseInt(r[6]));
        return worst;
    }

    /**
     * @return the venue layout as JSON: drawing size, each area's position and crowd level, exits with their
     *         flow, links with capacity and lifts, fire zones; "map" is false when the venue has no layout
     */
    public String layout() {
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

    /** @return the evacuation proposal as JSON, or null */
    public String proposalJson() {
        String[] p = m.proposal();
        return p == null ? "null" : "{\"zone\":" + str(p[0]) + ",\"reason\":" + str(p[1]) + ",\"time\":" + str(p[2]) + "}";
    }

    /** @return number of areas whose sensor data is stale or suspect */
    public int staleCount() {
        int n = 0;
        for (Area a : m.areaTree().inOrder()) if (a.staleSince != null || a.suspect != null) n++;
        return n;
    }

    /** @return open lost-person cases where the person is still missing */
    public int missingCount() {
        int n = 0;
        for (Incident i : m.lostCases()) if (i.foundAt == null) n++;
        return n;
    }

    /** @return a JSON array of strings */
    public static String list(crowdwatch.ds.LinkedList<String> l) {
        StringBuilder sb = new StringBuilder("[");
        for (String s : l) sb.append(sb.length() > 1 ? "," : "").append(str(s));
        return sb.append(']').toString();
    }

    /** @return open lost-person cases with their search areas and found state */
    public String lost() {
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
    public String announcements() {
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

    /** @return JSON for the control room: automation, officer, incidents awaiting review, evacuation and its plan */
    public String status() {
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

    /** @return JSON array of the 50 newest alerts */
    public String alerts() {
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
     * @param avoidCrowds false for the shortest walk, true for the crowd-aware route
     * @return JSON with found, walkMinutes and path
     */
    public String route(String from, String to, boolean avoidCrowds) {
        CrowdMonitor.Route r = m.route(from, to, avoidCrowds);
        StringBuilder sb = new StringBuilder("{\"found\":").append(r.found()).append(",\"walkMinutes\":").append(r.found() ? r.walk : -1).append(",\"path\":[");
        int i = 0;
        for (String s : r.path) sb.append(i++ > 0 ? "," : "").append(str(s));
        return sb.append("]}").toString();
    }

    /** @return the plain-text situation summary */
    public String summary() { return m.summary(); }

    /** @return the handover report for the officer coming on watch (plain text) */
    public String handover() { return m.handoverReport(); }
}
