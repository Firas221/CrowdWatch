package crowdwatch.gui;

import java.nio.file.Path;
import javax.swing.*;
import crowdwatch.core.*;
import crowdwatch.db.Database;

/**
 * Program entry point.
 * <p>
 * Without arguments it asks which venue to open and starts the desktop window. With
 * {@code --server <venue> [port] [api-key]} it runs without a window as a network server.
 * A first argument that is a venue code or .venue file opens that venue directly.
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
     * Asks the user what to do when a venue's database cannot be used.
     *
     * @return true to continue in memory with sample data (nothing saved), false to give up
     */
    static boolean askMemoryOnly(java.awt.Component parent, String reason) {
        return JOptionPane.showConfirmDialog(parent, reason + "\n\nContinue WITHOUT the database? The window will show sample data,\nnot the real situation, and nothing will be saved.",
            "CrowdWatch - database not available", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /**
     * Starts CrowdWatch.
     *
     * @param args none for the venue chooser; a venue code or file to open it directly;
     *             or --server &lt;venue&gt; [port] [api-key] for headless mode
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--server")) { headless(args); return; }
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            VenueProfile p;
            try {
                p = args.length > 0 ? pick(args[0]) : choose();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Venue not loaded: " + e.getMessage(), "CrowdWatch", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (p == null) return;
            CrowdMonitor m = new CrowdMonitor(Clock.SYSTEM);
            Database db = openDatabase(p.code);
            String note;
            try {
                note = loadOrSeed(m, db, p);
            } catch (IllegalStateException e) {
                if (!askMemoryOnly(null, e.getMessage())) { System.exit(1); return; }
                db = null;
                note = loadOrSeed(m, null, p);
            }
            MainWindow w = new MainWindow(m, db);
            w.setVisible(true);
            JOptionPane.showMessageDialog(w, note, "CrowdWatch", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    /**
     * Headless server mode: no window. Prints alerts to the console, saves to the database every
     * 60 seconds, and saves again when the program is stopped with Ctrl+C (shutdown hook).
     * Refuses to start (exit code 1) if the venue's database cannot be used, and prints a clear
     * message if an autosave or the final save fails. Messages for units whose device is offline
     * are printed as "RADIO &gt;&gt; unit: text" for a radio operator ({@link ConsoleRelay}); every
     * message also goes to the venue's radio gateway if one is configured. The {@link Watchdog} runs the officer and acknowledgement checks.
     */
    private static void headless(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("Usage: java -jar CrowdWatch.jar --server <airport|stadium|umrah|file.venue> [port] [control-key] [scout-key]"); return; }
        VenueProfile p = pick(args[1]);
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 8080;
        CrowdMonitor m = new CrowdMonitor(Clock.SYSTEM);
        Database db = openDatabase(p.code);
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
        crowdwatch.net.ApiServer api = new crowdwatch.net.ApiServer(m, args.length > 3 ? args[3] : null, args.length > 4 ? args[4] : null, crowdwatch.net.ApiServer.RATE_LIMIT);
        if (args.length < 4 || args[3].isBlank()) System.out.println("WARNING: no control key given - anyone on the network can read and change everything. Use: --server <venue> <port> <control-key> [scout-key]");
        int bound = api.start(port);
        System.out.println("CrowdWatch server for " + m.venueName() + " on http://localhost:" + bound + "/  (Ctrl+C to stop)");
        java.util.concurrent.ScheduledExecutorService saver = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        if (db != null) saver.scheduleAtFixedRate(() -> {
            try { db.save(m); } catch (Exception e) { System.out.println(m.now() + "  AUTOSAVE FAILED: " + Database.explain(e)); }
        }, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
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

    /** @return a built-in venue if {@code arg} is its code, otherwise the venue file at that path */
    public static VenueProfile pick(String arg) throws Exception {
        for (String code : VenueProfile.BUILT_IN) if (code.equalsIgnoreCase(arg)) return VenueProfile.builtIn(code);
        return VenueProfile.fromFile(Path.of(arg));
    }

    /**
     * Shows the venue chooser dialog.
     *
     * @return the chosen venue, or null if the user cancelled
     */
    private static VenueProfile choose() throws Exception {
        String[] options = { "Airport", "Stadium", "Umrah (Holy Mosques)", "Open venue file..." };
        int c = JOptionPane.showOptionDialog(null, "Choose the venue to monitor.\nEach venue keeps its own database.", "CrowdWatch",
            JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (c < 0) return null;
        if (c < 3) return VenueProfile.builtIn(VenueProfile.BUILT_IN[c]);
        JFileChooser fc = new JFileChooser(new java.io.File("."));
        return fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? VenueProfile.fromFile(fc.getSelectedFile().toPath()) : null;
    }
}
