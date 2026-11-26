package crowdwatch.gui;

import java.nio.file.Path;
import crowdwatch.core.*;
import crowdwatch.db.Database;

/**
 * Program entry point.
 * <p>
 * Without arguments (or with a venue code or .venue file) it starts the JavaFX desktop console
 * when JavaFX is on the class path; otherwise it says how to start it. With
 * {@code --server <venue> [port] [api-key]} it runs without a window as a network server.
 */
public class App {
    /**
     * @param code venue code, used as the folder name (unsafe characters replaced)
     * @return the venue's database, or null if the Derby driver is missing
     */
    public static Database openDatabase(String code) {
        return Database.driverAvailable() ? new Database("crowdwatch-data/" + Database.safeName(code)) : null;
    }

    /**
     * Loads the venue from its database if it was saved before; for a new, empty database
     * loads the profile with its sample data.
     * <p>
     * If the database exists but cannot be opened or read (most often because another CrowdWatch
     * program already has it open), this method refuses instead of quietly showing sample data:
     * an operator must never mistake made-up counts and units for the real ones.
     *
     * @param db the venue's database, or null to run in memory with sample data
     * @return a message for the user saying which happened
     * @throws IllegalStateException with a readable reason if the database cannot be used
     */
    public static String loadOrSeed(CrowdMonitor m, Database db, VenueProfile p) {
        if (db == null) { m.loadVenue(p, true); return "Running in memory with sample data for " + p.name + ": nothing will be saved."; }
        // handover reports are written next to the databases, one folder per venue
        m.setReportFolder(java.nio.file.Path.of("crowdwatch-data", "reports", Database.safeName(p.code)));
        try {
            db.check();
            if (db.load(m)) return "Loaded " + m.venueName() + " from the database.";
        } catch (Exception e) {
            throw new IllegalStateException("The database for " + p.name + " cannot be used: " + Database.explain(e));
        }
        m.loadVenue(p, true);
        return "New database for " + p.name + " created with its sample data.";
    }

    /**
     * Starts CrowdWatch.
     *
     * @param args none for the venue chooser of the JavaFX console; a venue code or file to open it
     *             directly; or --server &lt;venue&gt; [port] [api-key] for headless mode
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--user")) { user(args); return; }
        if (args.length > 0 && args[0].equals("--site")) { site(args); return; }
        if (args.length > 0 && (args[0].equals("--server") || args[0].equals("--drill"))) {
            boolean https = false, drill = args[0].equals("--drill");
            int n = 0;
            for (String a : args) if (a.equals("--https")) https = true; else n++;
            String[] rest = new String[n];
            n = 0;
            for (String a : args) if (!a.equals("--https")) rest[n++] = a;
            if (drill) {
                // --drill <venue> [port] [file.drill]: a training server with the venue's sample data, never saved
                String script = rest.length > 3 ? rest[3] : null;
                String[] srv = { "--server", rest.length > 1 ? rest[1] : "airport", rest.length > 2 ? rest[2] : "8081" };
                headless(srv, https, true, script);
            } else headless(rest, https, false, null);
            return;
        }
        // the desktop console is the JavaFX one (run.bat / run.sh put JavaFX on the path)
        try {
            Class.forName("javafx.application.Application");
        } catch (ClassNotFoundException e) {
            System.out.println("The desktop console needs JavaFX: start it with program/run.bat (Windows) or ./run.sh (macOS, Linux).");
            System.out.println("Server without a window: java -jar CrowdWatch.jar --server <airport|stadium|umrah|file.venue> [port] [control-key] [scout-key] [--https]");
            System.out.println("Users: java -jar CrowdWatch.jar --user <name> <OPERATOR|OFFICER|ADMIN>   Sites: --site list   Drill: --drill <venue> [port] [file.drill]");
            return;
        }
        Class.forName("crowdwatch.fx.FxLauncher").getMethod("main", String[].class).invoke(null, (Object) args);
    }

    /**
     * Headless server mode: no window. Prints alerts to the console, saves to the database every
     * 60 seconds, and saves again when the program is stopped with Ctrl+C (shutdown hook).
     * Refuses to start (exit code 1) if the venue's database cannot be used, and prints a clear
     * message if an autosave or the final save fails. Messages for units whose device is offline
     * are printed as "RADIO &gt;&gt; unit: text" for a radio operator ({@link ConsoleRelay}); every
     * message also goes to the venue's radio gateway if one is configured. The {@link Watchdog} runs the officer and acknowledgement checks.
     */
    private static void headless(String[] args, boolean https, boolean drill, String drillScript) throws Exception {
        if (args.length < 2) { System.out.println("Usage: java -jar CrowdWatch.jar --server <airport|stadium|umrah|file.venue> [port] [control-key] [scout-key] [--https]"); return; }
        VenueProfile p = pick(args[1]);
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 8080;
        CrowdMonitor m = new CrowdMonitor(Clock.SYSTEM);
        // console output also goes to daily log files (30 days kept)
        LogFiles.tee(Path.of("crowdwatch-data", "logs"), Database.safeName(p.code) + (drill ? "-drill" : ""));
        Database db = drill ? null : openDatabase(p.code);
        if (drill) System.out.println("DRILL: a training server with the sample data of " + p.name + " - nothing is saved and the real venue is not touched.");
        m.addListener(new MonitorListener() { public void onAlert(crowdwatch.model.Alert a) { System.out.println(a); } });
        // radio relay: messages for units without an online device are printed for a radio operator
        m.addChannel(new ConsoleRelay(System.out, Clock.SYSTEM));
        if (p.gateway != null) {
            m.addChannel(new crowdwatch.net.HttpGatewayChannel(p.gateway));
            System.out.println("Unit messages are also sent to the radio gateway " + p.gateway);
        }
        // listeners and channels are connected before loading, so alerts and tasks from the sample data are printed too
        try {
            System.out.println(loadOrSeed(m, db, p));
        } catch (IllegalStateException e) {
            System.out.println("ERROR: " + e.getMessage());
            System.out.println("Server not started.");
            System.exit(1);
        }
        Watchdog watchdog = new Watchdog(m);
        watchdog.start();
        crowdwatch.ds.LinkedList<crowdwatch.feed.CrowdFeed> feeds = crowdwatch.feed.Feeds.start(m, p.feeds, System.out::println);
        crowdwatch.net.ApiServer api = new crowdwatch.net.ApiServer(m, args.length > 3 ? args[3] : null, args.length > 4 ? args[4] : null, crowdwatch.net.ApiServer.RATE_LIMIT);
        crowdwatch.net.Accounts accounts = openAccounts(db, p.code + (drill ? "-drill" : ""));
        api.setAccounts(accounts);
        api.setDatabase(db);
        api.setDataFolder(Path.of("crowdwatch-data"));
        api.setDrill(drill);
        if (p.notifyUrl != null) {
            new crowdwatch.core.Notifier(m, p.notifyUrl, p.notifyMinutes).start();
            System.out.println("Urgent alerts nobody deals with in " + p.notifyMinutes + " min are sent to " + java.net.URI.create(p.notifyUrl).getHost());
        }
        if (drillScript != null) {
            crowdwatch.feed.DrillScript ds = crowdwatch.feed.DrillScript.read(Path.of(drillScript));
            ds.start(m);
            System.out.println("Drill script started: " + ds.name());
        }
        if (accounts.hasUsers()) System.out.println("Personal logins are on: " + accounts.users().length + " user(s). Every change is written to the audit log.");
        else if (args.length < 4 || args[3].isBlank()) System.out.println("WARNING: no users and no control key - anyone on the network can read and change everything. Add users with: java -jar CrowdWatch.jar --user <name> <ROLE>");
        int bound = api.start(port, https ? tls() : null);
        System.out.println("CrowdWatch server for " + m.venueName() + " on " + (https ? "https" : "http") + "://localhost:" + bound + "/  (Ctrl+C to stop)");
        if (db != null && Database.backupCopyFolder() != null) System.out.println("Daily backups are also copied to " + Database.backupCopyFolder().getAbsolutePath());
        if (db != null) System.out.println("Saved every " + AUTOSAVE_SECONDS + " s; personal fields are encrypted with " + (System.getenv(crowdwatch.db.FieldCipher.KEY_VARIABLE) != null
            ? crowdwatch.db.FieldCipher.KEY_VARIABLE : db.keyFile().getPath() + " - keep a copy of it apart from the backups"));
        java.util.concurrent.ScheduledExecutorService saver = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        if (db != null) saver.scheduleAtFixedRate(() -> {
            try { db.save(m); } catch (Exception e) { System.out.println(m.now() + "  AUTOSAVE FAILED: " + Database.explain(e)); }
        }, AUTOSAVE_SECONDS, AUTOSAVE_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            crowdwatch.feed.Feeds.stopAll(feeds);
            watchdog.stop();
            saver.shutdown();
            try { saver.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            m.awaitListeners(2000);
            if (db == null) { System.out.println("Stopped (memory only, nothing saved)."); return; }
            try { db.save(m); System.out.println("Saved and stopped."); }
            catch (Exception e) { System.out.println("FINAL SAVE FAILED: " + Database.explain(e)); }
            Database.shutdown();
        }));
        Thread.currentThread().join();
    }

    /** Seconds between automatic saves (property crowdwatch.autosave, at least 10; default 60). */
    public static final int AUTOSAVE_SECONDS = Math.max(10, Integer.getInteger("crowdwatch.autosave", 60));

    /**
     * Head office sites: {@code --site add <name> <address> [control-key]}, {@code --site remove <name>},
     * {@code --site list}. Kept in crowdwatch-data/sites.txt (readable by its owner only); the web
     * console's Sites view shows every site's situation.
     */
    private static void site(String[] args) throws java.io.IOException {
        Path f = Path.of("crowdwatch-data", "sites.txt");
        java.util.List<String> lines = java.nio.file.Files.exists(f) ? new java.util.ArrayList<>(java.nio.file.Files.readAllLines(f)) : new java.util.ArrayList<>();
        if (args.length >= 2 && args[1].equals("list")) { for (String l : lines) if (!l.startsWith("#")) System.out.println(l.replaceAll("\\|\\s*[^|]*$", "| (key hidden)")); return; }
        if (args.length >= 4 && args[1].equals("add")) {
            if (!args[3].matches("https?://\\S+")) { System.out.println("The address must start with http:// or https://"); return; }
            lines.removeIf(l -> l.split("\\|")[0].trim().equalsIgnoreCase(args[2]));
            lines.add(args[2] + " | " + args[3] + " | " + (args.length > 4 ? args[4] : ""));
        } else if (args.length >= 3 && args[1].equals("remove")) {
            lines.removeIf(l -> l.split("\\|")[0].trim().equalsIgnoreCase(args[2]));
        } else { System.out.println("Usage: --site add <name> <http(s)://address:port> [control-key] | --site remove <name> | --site list"); return; }
        java.nio.file.Files.createDirectories(f.getParent());
        java.nio.file.Files.write(f, lines);
        crowdwatch.net.Accounts.ownerOnlyFile(f);
        System.out.println("Saved " + f.toAbsolutePath() + " (" + lines.size() + " site(s))");
    }

    /** Installation-wide accounts file (password hashes only). */
    public static final Path ACCOUNTS_FILE = Path.of("crowdwatch-data", "accounts.txt");

    /**
     * Opens the accounts file and connects the audit log to the venue's database (table AUDIT)
     * and to a daily text file in crowdwatch-data/audit/&lt;venue&gt;/.
     *
     * @param db the venue's database, or null (then only the text file is written)
     */
    public static crowdwatch.net.Accounts openAccounts(Database db, String code) throws java.io.IOException {
        crowdwatch.net.Accounts a = new crowdwatch.net.Accounts(ACCOUNTS_FILE);
        if (db != null) a.addSink(db.auditSink());
        a.addSink(crowdwatch.net.Accounts.dailyFile(Path.of("crowdwatch-data", "audit", Database.safeName(code))));
        return a;
    }

    /**
     * The TLS context for --https: the key store named by -Dcrowdwatch.keystore (default
     * crowdwatch-data/tls/server.p12). Its password comes from CROWDWATCH_KEYSTORE_PASSWORD; if
     * neither the key store nor the variable exists, a self-signed certificate for this computer
     * is made and its random password kept in crowdwatch-data/tls/keystore-password.txt (readable
     * only by the owner where the file system allows).
     */
    public static javax.net.ssl.SSLContext tls() throws java.io.IOException {
        java.io.File ks = new java.io.File(System.getProperty("crowdwatch.keystore", "crowdwatch-data/tls/server.p12"));
        Path pwFile = Path.of("crowdwatch-data", "tls", "keystore-password.txt");
        char[] pw = crowdwatch.net.Tls.passwordFromEnvironment();
        if (pw == null && java.nio.file.Files.exists(pwFile)) pw = java.nio.file.Files.readString(pwFile).trim().toCharArray();
        if (pw == null && ks.exists()) throw new java.io.IOException("set " + crowdwatch.net.Tls.PASSWORD_VARIABLE + " to the password of " + ks);
        if (!ks.exists()) {
            if (pw == null) {
                pw = java.util.UUID.randomUUID().toString().toCharArray();
                java.nio.file.Files.createDirectories(pwFile.getParent());
                java.nio.file.Files.writeString(pwFile, new String(pw));
                java.io.File f = pwFile.toFile();
                f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true);
            }
            String host = "localhost", ip = "127.0.0.1";
            try { java.net.InetAddress me = java.net.InetAddress.getLocalHost(); host = me.getHostName(); ip = me.getHostAddress(); } catch (java.io.IOException ignored) { }
            crowdwatch.net.Tls.selfSigned(ks, pw, "localhost", "127.0.0.1", host, ip);
            System.out.println("Made a self-signed certificate in " + ks + " - browsers will warn until it is trusted; use the venue's own certificate for real use.");
        }
        return crowdwatch.net.Tls.context(ks, pw);
    }

    /**
     * Command line user management:
     * {@code --user <name> <OPERATOR|OFFICER|ADMIN>} adds the user or changes the role and
     * password (asked twice on the console, or taken from CROWDWATCH_PASSWORD for scripts);
     * {@code --user <name> off} / {@code on} switches a user off or on; {@code --user list} lists them.
     */
    private static void user(String[] args) throws Exception {
        crowdwatch.net.Accounts a = new crowdwatch.net.Accounts(ACCOUNTS_FILE);
        if (args.length == 2 && args[1].equals("list")) {
            for (crowdwatch.net.Accounts.User u : a.users()) System.out.println(u.name + "  " + u.role + (u.active ? "" : "  (off)"));
            return;
        }
        if (args.length < 3) { System.out.println("Usage: java -jar CrowdWatch.jar --user <name> <OPERATOR|OFFICER|ADMIN|off|on>   or   --user list"); return; }
        String done;
        if (args[2].equalsIgnoreCase("off") || args[2].equalsIgnoreCase("on")) done = a.setActive(args[1], args[2].equalsIgnoreCase("on"));
        else {
            crowdwatch.net.Accounts.Role role = crowdwatch.net.Accounts.Role.valueOf(args[2].toUpperCase());
            if (args.length > 3 && args[3].equalsIgnoreCase("directory")) {
                done = a.setDirectoryUser(args[1], role);
                System.out.println(done == null ? "Saved: " + args[1] + " signs in with the company directory (" + (crowdwatch.net.Directory.url() == null ? "set CROWDWATCH_LDAP_URL" : crowdwatch.net.Directory.url()) + ")" : "Not saved: " + done);
                return;
            }
            String pw = System.getenv("CROWDWATCH_PASSWORD");
            if (pw == null) {
                java.io.Console c = System.console();
                if (c == null) { System.out.println("No console: set CROWDWATCH_PASSWORD to give the password."); System.exit(1); return; }
                char[] p1 = c.readPassword("Password for %s: ", args[1]), p2 = c.readPassword("Again: ");
                if (p1 == null || !java.util.Arrays.equals(p1, p2)) { System.out.println("The passwords differ - nothing changed."); System.exit(1); return; }
                pw = new String(p1);
            }
            done = a.setUser(args[1], role, pw);
        }
        System.out.println(done == null ? "Saved in " + ACCOUNTS_FILE.toAbsolutePath() : "Not saved: " + done);
        if (done != null) System.exit(1);
    }

    /** @return a built-in venue if {@code arg} is its code, otherwise the venue file at that path */
    public static VenueProfile pick(String arg) throws Exception {
        for (String code : VenueProfile.BUILT_IN) if (code.equalsIgnoreCase(arg)) return VenueProfile.builtIn(code);
        return VenueProfile.fromFile(Path.of(arg));
    }
}
