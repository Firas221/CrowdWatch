package crowdwatch.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import crowdwatch.ds.AVLTree;
import crowdwatch.ds.CircularBuffer;
import crowdwatch.ds.LinkedList;

/**
 * Personal logins, roles, scout device codes and the audit log of the network server.
 * <p>
 * <b>Users.</b> Each person has a user name, a password and one role. Passwords are never
 * stored: only a random salt and a PBKDF2-SHA256 hash ({@link #ITERATIONS} rounds). After
 * {@link #MAX_FAILURES} wrong passwords in a row a name is locked for {@link #LOCK_MINUTES} minutes.
 * A successful login gives a random session token, valid for {@link #SESSION_HOURS} hours, that
 * the console sends as {@code Authorization: Bearer <token>}.
 * <p>
 * <b>Roles</b> (each includes the ones before it):
 * <ul>
 * <li>VIEWER - reads everything, changes nothing;</li>
 * <li>OPERATOR - enters counts and incidents, resolves, reassigns, raises alarms;</li>
 * <li>OFFICER - decisions: reviews, evacuation, public messages, automation, device codes, audit log;</li>
 * <li>ADMIN - also manages the user accounts.</li>
 * </ul>
 * <b>Device codes.</b> An officer issues a code for one unit; the scout phone that uses it can
 * only see and act on that unit's own tasks. Issuing a new code for the unit cancels the old one,
 * so a lost phone is shut out at once.
 * <p>
 * <b>Audit log.</b> Every change made through the server (and every login, failed login and
 * account change) is written with time, user, role, action, summary and result. The newest
 * {@link #AUDIT_KEPT} entries are kept in memory for the console; every entry also goes to the
 * {@link Sink} (the venue's database and a daily text file) so the trail survives a restart.
 * <p>
 * Users and device codes are saved in a small text file (hashes only) that is replaced
 * atomically, so a crash during a save never leaves a half-written file.
 */
public class Accounts {
    /** Roles, lowest first. */
    public enum Role { VIEWER, OPERATOR, OFFICER, ADMIN }

    /** PBKDF2 rounds per password check. */
    public static final int ITERATIONS = 100_000;
    /** Wrong passwords in a row before a name is locked. */
    public static final int MAX_FAILURES = 5;
    /** Minutes a name stays locked after too many wrong passwords. */
    public static final int LOCK_MINUTES = 5;
    /** Hours a session token is valid. */
    public static final int SESSION_HOURS = 12;
    /** Audit entries kept in memory for the console. */
    public static final int AUDIT_KEPT = 500;
    /** Shortest password accepted. */
    public static final int MIN_PASSWORD = 8;

    /** One person's account. */
    public static final class User {
        public final String name;
        public Role role;
        public boolean active = true;
        String salt, hash;
        int failures;
        LocalDateTime lockedUntil;
        User(String name, Role role) { this.name = name; this.role = role; }
    }

    /** Who is making a request: a user, the control key (system), a scout key or a device code. */
    public static final class Who {
        /** Name written in the audit log and used as the officer's name. */
        public final String name;
        /** Role, or null for a scout device. */
        public final Role role;
        /** For a device code: the only unit it may act for; null otherwise. */
        public final String unit;
        Who(String name, Role role, String unit) { this.name = name; this.role = role; this.unit = unit; }
        /** @return true if this caller has at least the role */
        public boolean atLeast(Role r) { return role != null && role.ordinal() >= r.ordinal(); }
    }

    /** One line of the audit log. */
    public static final class Entry {
        public final LocalDateTime at;
        public final String user, role, action, summary;
        public final boolean ok;
        Entry(LocalDateTime at, String user, String role, String action, String summary, boolean ok) {
            this.at = at; this.user = user; this.role = role; this.action = action; this.summary = summary; this.ok = ok;
        }
        @Override public String toString() { return at.withNano(0) + "  " + user + " (" + role + ")  " + action + "  " + (ok ? "OK" : "REFUSED") + "  " + summary; }
    }

    /** Where audit entries are kept for good (database, file). */
    public interface Sink { void write(Entry e); }

    private static final class Session { final String user; final LocalDateTime expires; Session(String u, LocalDateTime e) { user = u; expires = e; } }

    private final AVLTree<String, User> users = new AVLTree<>();
    private final AVLTree<String, Session> sessions = new AVLTree<>();
    // device code -> unit id
    private final AVLTree<String, String> devices = new AVLTree<>();
    private final CircularBuffer<Entry> audit = new CircularBuffer<>(AUDIT_KEPT);
    private final LinkedList<Sink> sinks = new LinkedList<>();
    private final SecureRandom random = new SecureRandom();
    // null = memory only
    private final Path file;

    /**
     * @param file the accounts file, or null to keep accounts in memory only (tests)
     * @throws IOException if the file exists but cannot be read
     */
    public Accounts(Path file) throws IOException {
        this.file = file;
        if (file != null && Files.exists(file)) load();
    }

    /** Adds a place where audit entries are kept for good. */
    public synchronized void addSink(Sink s) { sinks.addLast(s); }

    /** @return true if at least one active user exists (then the console asks for a login) */
    public synchronized boolean hasUsers() {
        for (User u : users.inOrder()) if (u.active) return true;
        return false;
    }

    /** @return the users in name order */
    public synchronized User[] users() { return users.inOrder().toArray(new User[0]); }

    /** @return the user, or null */
    public synchronized User user(String name) { return name == null ? null : users.search(key(name)); }

    // user names are not case sensitive
    private static String key(String name) { return name.trim().toLowerCase(java.util.Locale.ROOT); }

    /**
     * Adds a user or changes an existing user's role and password.
     *
     * @param password new password, or null to keep the current one (existing users only)
     * @return null if done, otherwise why it was refused
     */
    public synchronized String setUser(String name, Role role, String password) {
        if (name == null || !name.trim().matches("[A-Za-z0-9._-]{2,40}")) return "user name must be 2 to 40 letters, digits, dots, dashes or underscores";
        if (role == null) return "role must be VIEWER, OPERATOR, OFFICER or ADMIN";
        User u = users.search(key(name));
        if (u == null && password == null) return "a new user needs a password";
        if (password != null && password.length() < MIN_PASSWORD) return "password must have at least " + MIN_PASSWORD + " characters";
        if (u != null && u.role == Role.ADMIN && role != Role.ADMIN && activeAdmins() == 1 && u.active) return "the last administrator cannot lose the ADMIN role";
        if (u == null) { u = new User(name.trim(), role); users.insert(key(name), u); }
        u.role = role;
        if (password != null) {
            u.salt = Base64.getEncoder().encodeToString(bytes(16));
            u.hash = hash(password, u.salt);
            u.failures = 0;
            u.lockedUntil = null;
            endSessions(u.name);   // a new password signs the user out everywhere
        }
        return saveQuietly();
    }

    /**
     * Switches a user on or off. A user who is switched off cannot log in and is signed out.
     *
     * @return null if done, otherwise why it was refused
     */
    public synchronized String setActive(String name, boolean active) {
        User u = user(name);
        if (u == null) return "no user " + name;
        if (!active && u.role == Role.ADMIN && u.active && activeAdmins() == 1) return "the last administrator cannot be switched off";
        u.active = active;
        if (!active) endSessions(u.name);
        return saveQuietly();
    }

    private int activeAdmins() {
        int n = 0;
        for (User u : users.inOrder()) if (u.active && u.role == Role.ADMIN) n++;
        return n;
    }

    /**
     * Checks a password and opens a session.
     *
     * @return the session token, or null if the name or password is wrong, the user is switched
     *         off or the name is locked (the reason is in the audit log)
     */
    public synchronized String login(String name, String password, LocalDateTime now) {
        User u = user(name);
        if (u == null || !u.active || u.hash == null) { record(now, name == null ? "?" : name, "-", "login", "unknown or switched-off user", false); return null; }
        if (u.lockedUntil != null && now.isBefore(u.lockedUntil)) { record(now, u.name, u.role.name(), "login", "locked after " + MAX_FAILURES + " wrong passwords", false); return null; }
        if (password == null || !slowEquals(hash(password, u.salt), u.hash)) {
            u.failures++;
            if (u.failures >= MAX_FAILURES) { u.lockedUntil = now.plusMinutes(LOCK_MINUTES); u.failures = 0; }
            record(now, u.name, u.role.name(), "login", "wrong password", false);
            return null;
        }
        u.failures = 0;
        u.lockedUntil = null;
        String token = token();
        sessions.insert(token, new Session(u.name, now.plusHours(SESSION_HOURS)));
        record(now, u.name, u.role.name(), "login", "signed in", true);
        return token;
    }

    /** Ends one session (sign out). */
    public synchronized void logout(String token) { if (token != null) sessions.delete(token); }

    private void endSessions(String name) {
        for (String t : sessions.keysInOrder()) if (sessions.search(t).user.equalsIgnoreCase(name)) sessions.delete(t);
    }

    /**
     * @return the signed-in user of a token, or null if the token is unknown or expired, or the
     *         user has been switched off
     */
    public synchronized Who session(String token, LocalDateTime now) {
        if (token == null) return null;
        Session s = sessions.search(token);
        if (s == null) return null;
        if (now.isAfter(s.expires)) { sessions.delete(token); return null; }
        User u = user(s.user);
        if (u == null || !u.active) { sessions.delete(token); return null; }
        return new Who(u.name, u.role, null);
    }

    /**
     * Issues a new device code for a unit; the unit's previous code stops working.
     *
     * @return the code: 10 letters and digits, easy to type on a phone
     */
    public synchronized String issueDevice(String unit) {
        revokeDevice(unit);
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O or 1/I
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 10; k++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        devices.insert(sb.toString(), unit);
        saveQuietly();
        return sb.toString();
    }

    /** Cancels the device code of a unit. @return true if it had one */
    public synchronized boolean revokeDevice(String unit) {
        boolean had = false;
        for (String c : devices.keysInOrder()) if (devices.search(c).equalsIgnoreCase(unit)) { devices.delete(c); had = true; }
        if (had) saveQuietly();
        return had;
    }

    /** @return the caller for a device code, or null if the code is unknown */
    public synchronized Who device(String code) {
        String unit = code == null ? null : devices.search(code.trim().toUpperCase(java.util.Locale.ROOT));
        return unit == null ? null : new Who("device " + unit, null, unit);
    }

    /** @return true if the unit has a device code */
    public synchronized boolean hasDevice(String unit) {
        for (String u : devices.inOrder()) if (u.equalsIgnoreCase(unit)) return true;
        return false;
    }

    /**
     * Writes one entry to the audit log (memory, then every sink). A failing sink never stops
     * the request it records.
     */
    public void record(LocalDateTime at, String user, String role, String action, String summary, boolean ok) {
        Entry e = new Entry(at, clip(user, 40), clip(role, 12), clip(action, 40), clip(summary, 400), ok);
        Sink[] to;
        synchronized (this) { audit.add(e); to = sinks.toArray(new Sink[0]); }
        for (Sink s : to) {
            try { s.write(e); } catch (RuntimeException ignored) { }
        }
    }

    private static String clip(String s, int max) { s = s == null ? "" : s.replace('\n', ' '); return s.length() > max ? s.substring(0, max) : s; }

    /** @return the newest audit entries, newest first, at most {@code max} */
    public synchronized Entry[] recentAudit(int max) {
        int n = Math.min(max, audit.size());
        Entry[] out = new Entry[n];
        for (int k = 0; k < n; k++) out[k] = audit.get(audit.size() - 1 - k);
        return out;
    }

    // ---------------------------------------------------------------- passwords

    private byte[] bytes(int n) { byte[] b = new byte[n]; random.nextBytes(b); return b; }

    private String token() { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32)); }

    /** @return the PBKDF2-SHA256 hash of the password with the salt, Base64 */
    static String hash(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, 256);
            return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    // compares in constant time, so the time taken does not tell how much of a hash matched
    private static boolean slowEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    // ---------------------------------------------------------------- file

    /*
     * File format, one record a line, fields separated by '|':
     *   user|name|ROLE|active(1/0)|salt|hash
     *   device|CODE|unit
     */
    private void load() throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] f = line.split("\\|", -1);
            if (f.length == 6 && f[0].equals("user")) {
                User u = new User(f[1], Role.valueOf(f[2]));
                u.active = f[3].equals("1");
                u.salt = f[4];
                u.hash = f[5];
                users.insert(key(f[1]), u);
            } else if (f.length == 3 && f[0].equals("device")) {
                devices.insert(f[1], f[2]);
            }
        }
    }

    /** Writes the file (to a temporary file first, then moved over the old one). */
    public synchronized void save() throws IOException {
        if (file == null) return;
        StringBuilder sb = new StringBuilder("# CrowdWatch accounts: password hashes only (PBKDF2-SHA256). Do not edit by hand.\n");
        for (User u : users.inOrder()) sb.append("user|").append(u.name).append('|').append(u.role).append('|').append(u.active ? 1 : 0).append('|').append(u.salt).append('|').append(u.hash).append('\n');
        for (String c : devices.keysInOrder()) sb.append("device|").append(c).append('|').append(devices.search(c)).append('\n');
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    private String saveQuietly() {
        try { save(); return null; } catch (IOException e) { return "saved in memory, but the accounts file could not be written: " + e.getMessage(); }
    }

    /**
     * A sink that appends every entry to a daily text file in a folder (audit-YYYY-MM-DD.log).
     */
    public static Sink dailyFile(Path folder) {
        return e -> {
            try {
                Files.createDirectories(folder);
                Files.writeString(folder.resolve("audit-" + e.at.toLocalDate() + ".log"), e + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
        };
    }
}
