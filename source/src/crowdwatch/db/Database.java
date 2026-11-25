package crowdwatch.db;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.core.VenueProfile;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.*;
import crowdwatch.model.Enums.*;

/**
 * Saves and loads the complete state of one venue in an embedded Apache Derby database.
 * <p>
 * Each venue has its own database folder (crowdwatch-data/&lt;code&gt;). The venue profile text
 * is stored as well, so a saved venue can be restored even if its .venue file is gone.
 * A save replaces all rows in one transaction, so a failed save leaves the previous copy intact.
 */
public class Database {
    // deletion order: child tables before the tables they reference
    private static final String[] TABLES = { "history", "alerts", "incidents", "unit_skills", "units", "area_state", "venue", "counters", "state" };
    // JDBC URL of the database folder
    private final String url;
    // last part of the folder path; must match the venue code being saved
    private final String folderName;

    /**
     * Prepares a database in a folder. Nothing is opened until the first save or load.
     * Derby's own log is written next to the folder.
     *
     * @param folder database folder; created on first use
     */
    public Database(String folder) {
        File f = new File(folder).getAbsoluteFile();
        f.getParentFile().mkdirs();
        System.setProperty("derby.stream.error.file", f.getPath() + ".log");
        this.url = "jdbc:derby:" + f.getPath() + ";create=true";
        this.folderName = f.getName();
        this.folder = f;
    }

    // the database folder
    private final File folder;

    /**
     * Turns a venue code into a safe folder name: lower case, letters, digits, _ and - only.
     *
     * @return the folder name used for that venue's database
     */
    public static String safeName(String code) { return code.toLowerCase().replaceAll("[^a-z0-9_-]", "_"); }

    /**
     * Opens the database once to check it can be used, creating the tables if needed.
     * Call it before loading. The usual failure is that another CrowdWatch program (or a
     * second window on another computer sharing the folder) already has this venue open:
     * Derby allows only one program per database.
     *
     * @throws SQLException with Derby's reason if the database cannot be opened
     */
    public void check() throws SQLException {
        try (Connection c = open()) { schema(c); }
    }

    /**
     * Gives a short, readable reason for a database error, looking through the chain of
     * causes for Derby's "already booted" message.
     *
     * @return a message for the user
     */
    public static String explain(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException q) for (SQLException n = q; n != null; n = n.getNextException())
                if (n.getMessage() != null && n.getMessage().contains("Another instance of Derby"))
                    return "the database is already in use by another CrowdWatch program on this computer. Close it first, or use a different folder.";
        }
        return String.valueOf(e.getMessage());
    }

    /** @return true if the Derby jar is on the classpath; without it the program runs in memory only */
    public static boolean driverAvailable() {
        try { DriverManager.getDriver("jdbc:derby:"); return true; }
        catch (SQLException e) { return false; }
    }

    // opens the database, creating it if needed
    private Connection open() throws SQLException { return DriverManager.getConnection(url); }

    /** Creates the tables the first time the database is opened (checked by looking for COUNTERS). */
    private void schema(Connection c) throws SQLException {
        boolean exists;
        try (ResultSet rs = c.getMetaData().getTables(null, null, "COUNTERS", null)) { exists = rs.next(); }
        if (!exists) try (Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE venue (id INT PRIMARY KEY, code VARCHAR(40), name VARCHAR(120), profile CLOB)");
            s.executeUpdate("CREATE TABLE area_state (id VARCHAR(40) PRIMARY KEY, occupancy INT, level VARCHAR(10), source VARCHAR(12), updated TIMESTAMP)");
            s.executeUpdate("CREATE TABLE units (id VARCHAR(20) PRIMARY KEY, name VARCHAR(80), area VARCHAR(40), status VARCHAR(12))");
            s.executeUpdate("CREATE TABLE unit_skills (unit_id VARCHAR(20) REFERENCES units(id), seq INT, skill VARCHAR(40))");
            s.executeUpdate("CREATE TABLE incidents (id INT PRIMARY KEY, category VARCHAR(40), priority VARCHAR(10), area VARCHAR(40), details VARCHAR(400), contact VARCHAR(120), language VARCHAR(40), reported TIMESTAMP, status VARCHAR(10), unit_id VARCHAR(20), on_scene TIMESTAMP, resolved TIMESTAMP, queue_pos INT)");
            s.executeUpdate("CREATE TABLE history (owner_type CHAR(1), owner_id VARCHAR(20), seq INT, happened_at TIMESTAMP, text VARCHAR(400))");
            s.executeUpdate("CREATE TABLE alerts (seq INT, happened_at TIMESTAMP, kind VARCHAR(12), ref VARCHAR(40), text VARCHAR(400))");
            s.executeUpdate("CREATE TABLE counters (name VARCHAR(20) PRIMARY KEY, value INT)");
        }
        upgrade(c);
    }

    /**
     * Adds what later versions need to a database created by an earlier version: the automation
     * columns and the state table. Each step is skipped when it is already there, so it is safe
     * to run every time. New columns are added at the end of their tables, so fresh and upgraded
     * databases have the same column order.
     */
    private static void upgrade(Connection c) throws SQLException {
        String[] steps = {
            "ALTER TABLE incidents ADD COLUMN automatic INT",
            "ALTER TABLE incidents ADD COLUMN reviewed_by VARCHAR(40)",
            "ALTER TABLE incidents ADD COLUMN notified TIMESTAMP",
            "ALTER TABLE incidents ADD COLUMN acknowledged TIMESTAMP",
            "ALTER TABLE incidents ADD COLUMN reminders INT",
            "ALTER TABLE area_state ADD COLUMN evacuating INT",
            "ALTER TABLE area_state ADD COLUMN auto_incident INT",
            "CREATE TABLE state (name VARCHAR(40) PRIMARY KEY, text VARCHAR(400), stamp TIMESTAMP)",
            // alert kinds such as AUTO_DISPATCH and SUPERVISION are longer than the original 12 characters
            "ALTER TABLE alerts ALTER COLUMN kind SET DATA TYPE VARCHAR(20)",
            // archive of resolved incidents: same columns as incidents and history, written once and never deleted by a save
            "CREATE TABLE incident_archive (id INT PRIMARY KEY, category VARCHAR(40), priority VARCHAR(10), area VARCHAR(40), details VARCHAR(400), contact VARCHAR(120), language VARCHAR(40), reported TIMESTAMP, status VARCHAR(10), unit_id VARCHAR(20), on_scene TIMESTAMP, resolved TIMESTAMP, queue_pos INT, automatic INT, reviewed_by VARCHAR(40), notified TIMESTAMP, acknowledged TIMESTAMP, reminders INT)",
            "CREATE TABLE history_archive (owner_type CHAR(1), owner_id VARCHAR(20), seq INT, happened_at TIMESTAMP, text VARCHAR(400))",
            // lost-person cases and the follow-up rules
            "ALTER TABLE incidents ADD COLUMN found_at TIMESTAMP", "ALTER TABLE incidents ADD COLUMN found_where VARCHAR(40)", "ALTER TABLE incidents ADD COLUMN found_by VARCHAR(40)",
            "ALTER TABLE incidents ADD COLUMN search_min INT", "ALTER TABLE incidents ADD COLUMN lost_steps INT", "ALTER TABLE incidents ADD COLUMN follow_ups INT", "ALTER TABLE incidents ADD COLUMN wait_handled INT",
            "ALTER TABLE incident_archive ADD COLUMN found_at TIMESTAMP", "ALTER TABLE incident_archive ADD COLUMN found_where VARCHAR(40)", "ALTER TABLE incident_archive ADD COLUMN found_by VARCHAR(40)",
            "ALTER TABLE incident_archive ADD COLUMN search_min INT", "ALTER TABLE incident_archive ADD COLUMN lost_steps INT", "ALTER TABLE incident_archive ADD COLUMN follow_ups INT", "ALTER TABLE incident_archive ADD COLUMN wait_handled INT" };
        try (Statement s = c.createStatement()) {
            for (String sql : steps) {
                try { s.executeUpdate(sql); }
                catch (SQLException e) { if (!"X0Y32".equals(e.getSQLState())) throw e; }   // X0Y32: column or table already exists
            }
        }
    }

    /**
     * Saves the venue, area states, units with skills, incidents with their queue positions,
     * histories, alerts and the next incident number, as one transaction.
     * <p>
     * The monitor is locked only while a {@link Snapshot} is copied out of it (a few
     * milliseconds). The slow part, writing to disk, happens after the lock is released, so
     * sensors, dispatch and other terminals keep working during a save. Rows are sent in JDBC
     * batches. Text longer than its column is shortened, so one long note can never make every
     * later save fail.
     *
     * @throws SQLException          if the database cannot be written
     * @throws IllegalStateException if the monitor holds a different venue than this database
     *                               (protects against saving one venue into another's folder)
     */
    public synchronized void save(CrowdMonitor m) throws SQLException {
        Snapshot s;
        synchronized (m) { s = new Snapshot(m); }
        try { write(s); }
        catch (SQLException | RuntimeException e) {
            m.returnArchive(s.archived);   // not written: keep them for the next save
            throw e;
        }
        m.noteSaved(m.now(), backupIfDue());
    }

    /** Daily backups are kept for this many days. */
    public static final int BACKUP_DAYS = 7;
    // date of the newest backup made by this program
    private java.time.LocalDate lastBackup;

    /**
     * Makes the day's backup after the first save of each day: Derby copies the whole database,
     * while it stays in use, to backups/&lt;date&gt;/ next to the database folder. Only the newest
     * {@link #BACKUP_DAYS} backups are kept. A failed backup never stops the save.
     *
     * @return the new backup folder, or null if none was made
     */
    private String backupIfDue() {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (today.equals(lastBackup)) return null;
        File dir = new File(folder.getParentFile(), "backups" + File.separator + folderName + File.separator + today);
        try (Connection c = open(); java.sql.CallableStatement cs = c.prepareCall("CALL SYSCS_UTIL.SYSCS_BACKUP_DATABASE(?)")) {
            if (!new File(dir, folderName).exists()) {
                dir.mkdirs();
                cs.setString(1, dir.getPath());
                cs.execute();
            }
            lastBackup = today;
            File[] old = dir.getParentFile().listFiles(File::isDirectory);
            if (old != null && old.length > BACKUP_DAYS) {
                java.util.Arrays.sort(old);
                for (int k = 0; k < old.length - BACKUP_DAYS; k++) deleteTree(old[k]);
            }
            return dir.getPath();
        } catch (SQLException | RuntimeException e) {
            lastBackup = today;   // try again tomorrow, not on every save
            return null;
        }
    }

    /** Deletes a folder and everything in it. */
    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** Writes a snapshot as one transaction: replaces the current state and appends to the archive. */
    private void write(Snapshot s) throws SQLException {
        if (!safeName(s.code).equals(folderName))
            throw new IllegalStateException("Venue '" + s.code + "' does not belong to database '" + folderName + "'; not saved.");
        try (Connection c = open()) {
            schema(c);
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) { for (String t : TABLES) st.executeUpdate("DELETE FROM " + t); }
                insert(c, "INSERT INTO venue VALUES (?,?,?,?)", s.venue);
                insert(c, "INSERT INTO area_state VALUES (?,?,?,?,?,?,?)", s.areas);
                insert(c, "INSERT INTO units VALUES (?,?,?,?)", s.units);
                insert(c, "INSERT INTO unit_skills VALUES (?,?,?)", s.skills);
                insert(c, "INSERT INTO incidents VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", s.incidents);
                insert(c, "INSERT INTO history VALUES (?,?,?,?,?)", s.history);
                insert(c, "INSERT INTO alerts VALUES (?,?,?,?,?)", s.alerts);
                insert(c, "INSERT INTO counters VALUES (?,?)", s.counters);
                insert(c, "INSERT INTO state VALUES (?,?,?)", s.state);
                insert(c, "INSERT INTO incident_archive VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", s.archiveRows);
                insert(c, "INSERT INTO history_archive VALUES (?,?,?,?,?)", s.archiveHistory);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * A copy of everything the database stores, taken while the monitor is locked.
     * Each row is an array of column values in table order: Strings, Integers and Timestamps
     * (null where the column is empty). Strings are cut to their column length.
     */
    private static final class Snapshot {
        final String code;
        final LinkedList<Object[]> venue = new LinkedList<>(), areas = new LinkedList<>(), units = new LinkedList<>(),
            skills = new LinkedList<>(), incidents = new LinkedList<>(), history = new LinkedList<>(),
            alerts = new LinkedList<>(), counters = new LinkedList<>(), state = new LinkedList<>(),
            archiveRows = new LinkedList<>(), archiveHistory = new LinkedList<>();
        // incidents taken from the monitor's archive queue by this snapshot
        final LinkedList<Incident> archived;

        // the caller holds the monitor's lock
        Snapshot(CrowdMonitor m) {
            code = m.venue().code;
            venue.addLast(new Object[] { 1, fit(m.venue().code, 40), fit(m.venue().name, 120), m.venue().text });
            for (Area a : m.areaTree().inOrder())
                areas.addLast(new Object[] { fit(a.id, 40), a.occupancy, a.level.name(), a.source == null ? null : a.source.name(), ts(a.updated), a.evacuating ? 1 : 0, a.autoIncident });
            for (Unit u : m.unitTree().inOrder()) {
                units.addLast(new Object[] { fit(u.id, 20), fit(u.name, 80), fit(u.area.id, 40), u.status.name() });
                int k = 0;
                for (String sk : u.skills) skills.addLast(new Object[] { fit(u.id, 20), k++, fit(sk, 40) });
                addHistory("U", u.id, u.history);
            }
            archived = m.takeArchive();
            for (Incident i : archived) {
                archiveRows.addLast(incidentRow(m, i));
                int k = 0;
                for (HistoryEntry e : i.history) archiveHistory.addLast(new Object[] { "I", String.valueOf(i.id), k++, ts(e.time), fit(e.text, 400) });
            }
            for (Incident i : m.incidentTree().inOrder()) {
                incidents.addLast(incidentRow(m, i));
                addHistory("I", String.valueOf(i.id), i.history);
            }
            int seq = 0;
            for (Alert a : m.alerts()) alerts.addLast(new Object[] { seq++, ts(a.time), fit(a.kind.name(), 20), fit(a.ref, 40), fit(a.text, 400) });
            counters.addLast(new Object[] { "incident", m.nextIncidentId() });
            counters.addLast(new Object[] { "archived", m.archivedCount() });
            state.addLast(new Object[] { "automation", m.automation() ? "ON" : "OFF", null });
            long[] g = m.gateCounts();
            if (g[3] == 1) state.addLast(new Object[] { "gates", g[0] + "," + g[1] + "," + g[2], null });
            if (m.officer() != null) state.addLast(new Object[] { "officer", fit(m.officer(), 400), ts(m.officerCheckInTime()) });
            if (m.evacuationZone() != null) {
                state.addLast(new Object[] { "evacuation", fit(m.evacuationZone(), 400), ts(m.evacuationStarted()) });
                state.addLast(new Object[] { "evacuation_by", fit(m.evacuationBy(), 400), null });
            }
        }

        // one incidents (or incident_archive) row, columns in table order
        private static Object[] incidentRow(CrowdMonitor m, Incident i) {
            return new Object[] { i.id, fit(i.category.code, 40), i.priority.name(), fit(i.area.id, 40), fit(i.details, 400),
                fit(i.contact, 120), fit(i.language, 40), ts(i.reported), i.status.name(), fit(i.unitId, 20), ts(i.onScene), ts(i.resolved), queuePos(m, i),
                i.automatic ? 1 : 0, fit(i.reviewedBy, 40), ts(i.notified), ts(i.acknowledged), i.reminders,
                ts(i.foundAt), fit(i.foundWhere, 40), fit(i.foundBy, 40), i.searchMinutes, i.lostSteps, i.followUps, i.waitHandled ? 1 : 0 };
        }

        // one row per history line; type U = unit, I = incident
        private void addHistory(String type, String id, LinkedList<HistoryEntry> h) {
            int k = 0;
            for (HistoryEntry e : h) history.addLast(new Object[] { type, fit(id, 20), k++, ts(e.time), fit(e.text, 400) });
        }
    }

    /**
     * Inserts rows with one prepared statement, sent to the database in batches of 500.
     * The column types are taken from the statement's parameter metadata, so null values get the right SQL type.
     */
    private static void insert(Connection c, String sql, LinkedList<Object[]> rows) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            ParameterMetaData md = p.getParameterMetaData();
            int n = 0;
            for (Object[] row : rows) {
                for (int k = 0; k < row.length; k++) {
                    Object v = row[k];
                    if (v == null) p.setNull(k + 1, md.getParameterType(k + 1));
                    else if (v instanceof Integer x) p.setInt(k + 1, x);
                    else if (v instanceof Timestamp t) p.setTimestamp(k + 1, t);
                    else p.setString(k + 1, (String) v);
                }
                p.addBatch();
                if (++n % 500 == 0) p.executeBatch();
            }
            p.executeBatch();
        }
    }

    /** @return the text cut to at most {@code max} characters (null stays null) */
    private static String fit(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }

    /** @return the incident's position in its waiting queue, or -1 if it is not waiting */
    private static int queuePos(CrowdMonitor m, Incident i) {
        int k = 0;
        for (Incident q : m.queue(i.priority)) { if (q == i) return k; k++; }
        return -1;
    }

    /**
     * Restores a saved venue into the monitor.
     * <p>
     * The stored profile is loaded first without sample data, then the saved area states,
     * units, incidents, histories, alerts and counter are applied. Waiting incidents are read in
     * order of their saved queue position, so the FIFO order of every queue is kept.
     *
     * @return false if the database is empty (nothing saved yet)
     * @throws SQLException if the database cannot be read
     */
    public synchronized boolean load(CrowdMonitor m) throws SQLException {
        try (Connection c = open()) {
            schema(c);
            String profile;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT profile FROM venue")) {
                if (!rs.next()) return false;
                profile = rs.getString(1);
            }
            synchronized (m) {
                m.loadVenue(VenueProfile.parse(profile), false);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM area_state")) {
                    while (rs.next()) {
                        Area a = m.findArea(rs.getString("id"));
                        if (a == null) continue;
                        a.occupancy = rs.getInt("occupancy");
                        a.level = CrowdLevel.valueOf(rs.getString("level"));
                        String src = rs.getString("source");
                        a.source = src == null ? null : Source.valueOf(src);
                        a.updated = dt(rs.getTimestamp("updated"));
                        a.autoIncident = rs.getInt("auto_incident");
                    }
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM units")) {
                    while (rs.next()) {
                        Unit u = new Unit(rs.getString("id"), rs.getString("name"), m.findArea(rs.getString("area")));
                        u.status = UnitStatus.valueOf(rs.getString("status"));
                        m.unitTree().insert(u.id, u);
                    }
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM unit_skills ORDER BY unit_id, seq")) {
                    while (rs.next()) {
                        Unit u = m.findUnit(rs.getString("unit_id"));
                        if (u != null) u.skills.addLast(rs.getString("skill"));
                    }
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM incidents ORDER BY queue_pos, reported")) {
                    while (rs.next()) {
                        Incident i = new Incident(rs.getInt("id"), m.findCategory(rs.getString("category")), Priority.valueOf(rs.getString("priority")),
                            m.findArea(rs.getString("area")), rs.getString("details"), rs.getString("contact"), rs.getString("language"), dt(rs.getTimestamp("reported")));
                        i.status = IncidentStatus.valueOf(rs.getString("status"));
                        i.unitId = rs.getString("unit_id");
                        i.onScene = dt(rs.getTimestamp("on_scene"));
                        i.resolved = dt(rs.getTimestamp("resolved"));
                        i.automatic = rs.getInt("automatic") == 1;
                        i.reviewedBy = rs.getString("reviewed_by");
                        i.notified = dt(rs.getTimestamp("notified"));
                        i.acknowledged = dt(rs.getTimestamp("acknowledged"));
                        i.reminders = rs.getInt("reminders");
                        i.foundAt = dt(rs.getTimestamp("found_at"));
                        i.foundWhere = rs.getString("found_where");
                        i.foundBy = rs.getString("found_by");
                        i.searchMinutes = rs.getInt("search_min");
                        i.lostSteps = rs.getInt("lost_steps");
                        i.followUps = rs.getInt("follow_ups");
                        i.waitHandled = rs.getInt("wait_handled") == 1;
                        m.incidentTree().insert(i.id, i);
                        if (i.status == IncidentStatus.WAITING) m.queue(i.priority).enqueue(i);
                    }
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM history ORDER BY owner_type, owner_id, seq")) {
                    while (rs.next()) {
                        HistoryEntry e = new HistoryEntry(dt(rs.getTimestamp("happened_at")), rs.getString("text"));
                        String id = rs.getString("owner_id");
                        if (rs.getString("owner_type").equals("U")) { Unit u = m.findUnit(id); if (u != null) u.history.addLast(e); }
                        else { Incident i = m.findIncident(Integer.parseInt(id)); if (i != null) i.history.addLast(e); }
                    }
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM alerts ORDER BY seq")) {
                    while (rs.next()) m.alerts().addLast(new Alert(dt(rs.getTimestamp("happened_at")), AlertKind.valueOf(rs.getString("kind")), rs.getString("ref"), rs.getString("text")));
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT value FROM counters WHERE name = 'incident'")) {
                    if (rs.next()) m.setNextIncidentId(rs.getInt(1));
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT value FROM counters WHERE name = 'archived'")) {
                    if (rs.next()) m.setArchivedCount(rs.getInt(1));
                }
                String zone = null, by = null, officer = null;
                LocalDateTime started = null, checkIn = null;
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM state")) {
                    while (rs.next()) {
                        switch (rs.getString("name")) {
                            case "automation" -> m.setAutomation(!"OFF".equals(rs.getString("text")));
                            case "officer" -> { officer = rs.getString("text"); checkIn = dt(rs.getTimestamp("stamp")); }
                            case "evacuation" -> { zone = rs.getString("text"); started = dt(rs.getTimestamp("stamp")); }
                            case "evacuation_by" -> by = rs.getString("text");
                            case "gates" -> {
                                String[] g = rs.getString("text").split(",");
                                m.restoreGateCounts(Long.parseLong(g[0]), Long.parseLong(g[1]), Long.parseLong(g[2]), true);
                            }
                            default -> { }
                        }
                    }
                }
                m.restoreState(officer, checkIn, zone, by, started);
            }
            return true;
        }
    }

    /** Shuts down the Derby engine cleanly (call once before the program exits). */
    public static void shutdown() {
        try { DriverManager.getConnection("jdbc:derby:;shutdown=true"); } catch (SQLException ignored) { }
    }

    // LocalDateTime -> SQL timestamp, keeping null
    private static Timestamp ts(LocalDateTime d) { return d == null ? null : Timestamp.valueOf(d); }

    // SQL timestamp -> LocalDateTime, keeping null
    private static LocalDateTime dt(Timestamp t) { return t == null ? null : t.toLocalDateTime(); }
}
