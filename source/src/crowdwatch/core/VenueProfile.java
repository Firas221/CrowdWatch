package crowdwatch.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import crowdwatch.ds.LinkedList;

/**
 * A venue described in a plain text file (.venue): its areas, walking links, incident
 * categories, units and crowd thresholds.
 * <p>
 * This is what makes CrowdWatch venue-independent: the same code runs an airport, a stadium
 * or the Holy Mosques just by loading a different profile. The parser only checks the format;
 * {@link CrowdMonitor#loadVenue} builds the objects. The original text is kept so it can be
 * saved with the database.
 */
public class VenueProfile {
    // sample venues packaged inside the jar under crowdwatch/venues/
    public static final String[] BUILT_IN = { "airport", "stadium", "umrah" };

    // the original file text
    public final String text;
    public String name = "Unnamed venue";
    // short code, also used as the database folder name
    public String code = "venue";
    // percentages where MODERATE, HIGH and CRITICAL start
    public int[] thresholds = { 50, 75, 90 };
    // raw fields of each line, by keyword
    public final LinkedList<String[]> areas = new LinkedList<>();
    public final LinkedList<String[]> links = new LinkedList<>();
    public final LinkedList<String[]> categories = new LinkedList<>();
    public final LinkedList<String[]> units = new LinkedList<>();
    public final LinkedList<String[]> occupancy = new LinkedList<>();
    // automatic dispatch: category to report when an area gets crowded, or null for none
    public String autoCategory;
    // level that triggers it (HIGH or CRITICAL)
    public String autoLevel = "HIGH";
    // also trigger on a TREND warning (crowd expected to become CRITICAL soon)
    public boolean autoOnTrend;
    // exit or assembly areas used by the evacuation plan
    public final LinkedList<String> exits = new LinkedList<>();
    // minutes between required security-officer check-ins
    public int supervisionMinutes = 15;
    // minutes a unit has to acknowledge a task before a reminder (and, after a second wait, reassignment)
    public int ackMinutes = 2;
    // minutes after which a resolved incident is moved to the archive (default 24 hours)
    public int archiveMinutes = 24 * 60;
    // URL of a radio or messaging gateway that receives every unit message, or null
    public String gateway;

    // ---- further automation (all optional; the defaults below apply when a line is missing)
    // minutes without a sensor count before an area's data is called stale
    public int staleMinutes = 5;
    // a sensor count that changes by more than this percentage of capacity within a minute is suspect
    public int jumpPercent = 40;
    // minutes after acknowledging before a unit that has not arrived is reminded (twice: backup sent)
    public int onSceneMinutes = 10;
    // minutes an area must stay below HIGH before its automatic crowd incident is closed
    public int autoCloseMinutes = 10;
    // minutes an incident may wait for a qualified unit before a stand-in is sent
    public int maxWaitMinutes = 10;
    // forecast horizon: send a free unit to an area expected to reach CRITICAL within this many minutes (0 = off)
    public int prepositionMinutes = 15;
    // tell units next to a HIGH area to hold people back
    public boolean holdFlow = true;
    // keep at least one free unit in every sector by moving one from a sector that has two or more
    public boolean coverSectors = true;
    // count check: alert when the areas' total and the gate counts differ by more than this percentage (0 = off)
    public int reconcilePercent = 5;
    // reinforcement: units wanted at a crowded area at HIGH and at CRITICAL (0 0 = off), the skills
    // preferred for it, and minutes the area must stay below HIGH before they return to their posts
    public int reinforceHigh = 2, reinforceCritical = 4;
    public final LinkedList<String> reinforceSkills = new LinkedList<>();
    public int reinforceHold = 10;
    // at HIGH, only units within this many minutes' walk are sent as reinforcement (0 = any distance); CRITICAL has no limit
    public int reinforceMax = 10;
    // minutes an area must stay CRITICAL and rising before an evacuation is proposed
    public int proposeMinutes = 10;
    // draft public sign / PA messages for an officer to approve
    public boolean signs = true;
    // lost persons: where found persons are brought, first search radius and how fast it widens (minutes)
    public String meetingPoint;
    public int lostRadius = 5;
    public int lostWiden = 5;
    // scheduled peaks: {HH:MM, duration minutes, zone, name}
    public final LinkedList<String[]> events = new LinkedList<>();
    // minutes before an event that its preparation starts, and how many points its thresholds are lowered
    public int eventLead = 30;
    public int eventOffset = 10;
    // shift change times (HH:MM), each producing a handover report
    public final LinkedList<String> shiftChanges = new LinkedList<>();

    // ---- layout (all optional)
    // drawing of the venue (PNG or JPG): a file next to the venue file, an absolute path, or a built-in resource name
    public String mapFile;
    // size of the drawing in map units (usually its pixels); positions use the same units
    public int mapWidth, mapHeight;
    // metres per map unit: lets link times be worked out from the drawing ("link: A B auto")
    public double scale;
    // where each area is on the drawing: {AREA, x, y, w, h}
    public final LinkedList<String[]> positions = new LinkedList<>();
    // how many people a minute each exit lets out: {AREA, people per minute}
    public final LinkedList<String[]> exitFlows = new LinkedList<>();
    // fire zones (compartments): {name, AREA, AREA, ...}
    public final LinkedList<String[]> fireZones = new LinkedList<>();
    // walking speed used for "auto" link times, metres per minute (a crowd moves at about 1 m/s)
    public static final double WALK_METRES_PER_MINUTE = 60;

    // created only through parse(), builtIn() or fromFile()
    private VenueProfile(String text) { this.text = text; }

    /**
     * Parses the text of a venue file.
     * <p>
     * Each non-empty line that does not start with # has the form {@code keyword: value}.
     * Keywords: venue, code, thresholds, area, link, category, unit, occupancy, and the optional
     * automation keywords auto (e.g. {@code auto: CROWD HIGH trend}), exit, supervision, ack and gateway,
     * and archive (e.g. {@code archive: 24h} or {@code archive: 30m}; a plain number means hours).
     * Area, category and unit fields are separated by |, link and occupancy fields by spaces.
     *
     * @param text file contents
     * @return the parsed profile
     * @throws IllegalArgumentException with the line number, if a line is malformed or there are no areas
     */
    public static VenueProfile parse(String text) {
        VenueProfile p = new VenueProfile(text);
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("Line " + (i + 1) + ": expected 'keyword: value'");
            String key = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "venue" -> p.name = value;
                case "code" -> p.code = value.toLowerCase();
                case "thresholds" -> {
                    String[] t = value.split("\\s+");
                    if (t.length != 3) throw new IllegalArgumentException("Line " + (i + 1) + ": thresholds need 3 numbers");
                    for (int k = 0; k < 3; k++) p.thresholds[k] = Integer.parseInt(t[k]);
                }
                case "area" -> p.areas.addLast(need(fields(value, "\\|"), 5, i));
                case "link" -> p.links.addLast(need(fields(value, "\\s+"), 3, i));
                case "category" -> p.categories.addLast(need(fields(value, "\\|"), 3, i));
                case "unit" -> p.units.addLast(need(fields(value, "\\|"), 3, i));
                case "occupancy" -> p.occupancy.addLast(need(fields(value, "\\s+"), 2, i));
                case "auto" -> {
                    String[] f = need(fields(value, "\\s+"), 2, i);
                    p.autoCategory = f[0].toUpperCase();
                    p.autoLevel = f[1].toUpperCase();
                    if (!p.autoLevel.equals("HIGH") && !p.autoLevel.equals("CRITICAL"))
                        throw new IllegalArgumentException("Line " + (i + 1) + ": auto level must be HIGH or CRITICAL");
                    p.autoOnTrend = f.length > 2 && f[2].equalsIgnoreCase("trend");
                }
                case "exit" -> { for (String e : fields(value, "\\s+")) if (!e.isEmpty()) p.exits.addLast(e.toUpperCase()); }
                case "supervision" -> p.supervisionMinutes = minutes(value, i);
                case "ack" -> p.ackMinutes = minutes(value, i);
                case "archive" -> {
                    String v = value.trim().toLowerCase();
                    int f = v.endsWith("m") ? 1 : 60;
                    p.archiveMinutes = minutes(v.replaceAll("[hm]$", ""), i) * f;
                }
                case "gateway" -> p.gateway = value;
                case "stale" -> p.staleMinutes = minutes(value, i);
                case "jump" -> p.jumpPercent = minutes(value, i);
                case "onscene" -> p.onSceneMinutes = minutes(value, i);
                case "autoclose" -> p.autoCloseMinutes = minutes(value, i);
                case "maxwait" -> p.maxWaitMinutes = minutes(value, i);
                case "preposition" -> p.prepositionMinutes = value.trim().equalsIgnoreCase("off") ? 0 : minutes(value, i);
                case "holdflow" -> p.holdFlow = onOff(value, i);
                case "cover" -> p.coverSectors = onOff(value, i);
                case "propose" -> p.proposeMinutes = minutes(value, i);
                case "reinforce" -> {
                    String[] f = need(fields(value, "\\s+"), 2, i);
                    try { p.reinforceHigh = Integer.parseInt(f[0]); p.reinforceCritical = Integer.parseInt(f[1]); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("Line " + (i + 1) + ": reinforce needs two numbers (units at HIGH and at CRITICAL)"); }
                    if (p.reinforceHigh < 0 || p.reinforceCritical < p.reinforceHigh || p.reinforceCritical > 50)
                        throw new IllegalArgumentException("Line " + (i + 1) + ": reinforce numbers must be 0-50, the CRITICAL one not smaller");
                }
                case "reinforceskills" -> { p.reinforceSkills.clear(); for (String s : fields(value, "[\\s,]+")) if (!s.isEmpty()) p.reinforceSkills.addLast(s); }
                case "reinforcehold" -> p.reinforceHold = minutes(value, i);
                case "reconcile" -> p.reconcilePercent = value.trim().equalsIgnoreCase("off") ? 0 : minutes(value, i);
                case "reinforcemax" -> p.reinforceMax = value.trim().equalsIgnoreCase("off") ? 0 : minutes(value, i);
                case "signs" -> p.signs = onOff(value, i);
                case "meeting" -> p.meetingPoint = value.trim().toUpperCase();
                case "lost" -> {
                    String[] f = need(fields(value, "\\s+"), 2, i);
                    p.lostRadius = minutes(f[0], i);
                    p.lostWiden = minutes(f[1], i);
                }
                case "event" -> {
                    String[] f = need(fields(value, "\\|"), 4, i);
                    if (!f[0].matches("\\d{1,2}:\\d{2}")) throw new IllegalArgumentException("Line " + (i + 1) + ": event time must be HH:MM");
                    minutes(f[1], i);
                    p.events.addLast(new String[] { time(f[0]), f[1], f[2], f[3] });
                }
                case "eventlead" -> p.eventLead = minutes(value, i);
                case "eventoffset" -> p.eventOffset = minutes(value, i);
                case "map" -> {
                    String[] f = need(fields(value, "\\s+"), 3, i);
                    p.mapFile = f[0];
                    p.mapWidth = minutes(f[1], i);
                    p.mapHeight = minutes(f[2], i);
                }
                case "scale" -> {
                    try { p.scale = Double.parseDouble(value.trim()); } catch (NumberFormatException e) { p.scale = 0; }
                    if (p.scale <= 0) throw new IllegalArgumentException("Line " + (i + 1) + ": scale must be a positive number of metres per map unit");
                }
                case "pos" -> {
                    String[] f = need(fields(value, "\\s+"), 5, i);
                    for (int k = 1; k <= 4; k++) number(f[k], i);
                    p.positions.addLast(new String[] { f[0].toUpperCase(), f[1], f[2], f[3], f[4] });
                }
                case "exitflow" -> {
                    String[] f = need(fields(value, "\\s+"), 2, i);
                    p.exitFlows.addLast(new String[] { f[0].toUpperCase(), String.valueOf(minutes(f[1], i)) });
                }
                case "zone" -> {
                    String[] f = need(fields(value, "\\|"), 2, i);
                    String[] areas = fields(f[1], "\\s+");
                    String[] z = new String[areas.length + 1];
                    z[0] = f[0];
                    for (int k = 0; k < areas.length; k++) z[k + 1] = areas[k].toUpperCase();
                    p.fireZones.addLast(z);
                }
                case "shift" -> {
                    for (String t : fields(value, "\\s+")) {
                        if (!t.matches("\\d{1,2}:\\d{2}")) throw new IllegalArgumentException("Line " + (i + 1) + ": shift times must be HH:MM");
                        p.shiftChanges.addLast(time(t));
                    }
                }
                default -> throw new IllegalArgumentException("Line " + (i + 1) + ": unknown keyword '" + key + "'");
            }
        }
        if (p.areas.isEmpty()) throw new IllegalArgumentException("A venue needs at least one area");
        return p;
    }

    /**
     * Loads one of the sample venues packaged in the jar.
     *
     * @param code "airport", "stadium" or "umrah"
     * @return the parsed profile
     * @throws IllegalArgumentException if there is no such built-in venue
     */
    public static VenueProfile builtIn(String code) {
        try (InputStream in = VenueProfile.class.getResourceAsStream("/crowdwatch/venues/" + code + ".venue")) {
            if (in == null) throw new IllegalArgumentException("No built-in venue '" + code + "'");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Loads a venue from a file on disk.
     *
     * @param file path to a .venue file (UTF-8)
     * @return the parsed profile
     * @throws IOException if the file cannot be read
     */
    public static VenueProfile fromFile(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        // a map drawing named relative to the venue file is stored with its full path, so it is still
        // found when the venue is later loaded from the database
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\r?\\n", -1)) {
            String t = line.trim();
            if (t.toLowerCase().startsWith("map:")) {
                String[] f = t.substring(4).trim().split("\\s+");
                Path img = file.toAbsolutePath().getParent().resolve(f[0]);
                if (f.length >= 3 && !Path.of(f[0]).isAbsolute() && Files.exists(img)) line = "map: " + img.toAbsolutePath() + " " + f[1] + " " + f[2];
            }
            sb.append(line).append('\n');
        }
        return parse(sb.toString());
    }

    /**
     * @return the bytes of the map drawing (a file, or a resource packaged with the built-in venues),
     *         or null if there is none or it cannot be read
     */
    public byte[] mapBytes() {
        if (mapFile == null) return null;
        try {
            Path f = Path.of(mapFile);
            if (Files.isRegularFile(f)) return Files.readAllBytes(f);
        } catch (Exception ignored) { }
        try (InputStream in = VenueProfile.class.getResourceAsStream("/crowdwatch/venues/" + mapFile)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) { return null; }
    }

    /** @return the content type of the map drawing, from its file name */
    public String mapType() {
        String f = mapFile == null ? "" : mapFile.toLowerCase();
        return f.endsWith(".png") ? "image/png" : f.endsWith(".svg") ? "image/svg+xml" : "image/jpeg";
    }

    /** Checks that a text is a number (used for positions), naming the line if not. */
    private static double number(String s, int line) {
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Line " + (line + 1) + ": expected a number, got '" + s + "'"); }
    }

    /** Splits a value by a separator (regular expression) and trims each field. */
    private static String[] fields(String value, String sep) {
        String[] f = value.split(sep);
        for (int i = 0; i < f.length; i++) f[i] = f[i].trim();
        return f;
    }

    /**
     * Parses a positive number of minutes.
     *
     * @throws IllegalArgumentException naming the line number if it is not a positive number
     */
    private static int minutes(String value, int line) {
        try {
            int v = Integer.parseInt(value.trim());
            if (v > 0) return v;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Line " + (line + 1) + ": expected a positive number of minutes");
    }

    /** @return true for on/yes/true, false for off/no/false; anything else is an error naming the line */
    private static boolean onOff(String value, int line) {
        String v = value.trim().toLowerCase();
        if (v.equals("on") || v.equals("yes") || v.equals("true")) return true;
        if (v.equals("off") || v.equals("no") || v.equals("false")) return false;
        throw new IllegalArgumentException("Line " + (line + 1) + ": expected on or off");
    }

    /** @return a time as HH:MM with a leading zero (7:30 -&gt; 07:30) */
    static String time(String t) {
        String[] p = t.trim().split(":");
        int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
        if (h > 23 || m > 59) throw new IllegalArgumentException("Not a time of day: " + t);
        return String.format("%02d:%02d", h, m);
    }

    /**
     * Checks that a line has at least {@code min} fields.
     *
     * @throws IllegalArgumentException naming the line number if it has fewer
     */
    private static String[] need(String[] f, int min, int line) {
        if (f.length < min) throw new IllegalArgumentException("Line " + (line + 1) + ": expected at least " + min + " fields");
        return f;
    }
}
