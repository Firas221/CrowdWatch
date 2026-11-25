package crowdwatch.gui;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Files;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import crowdwatch.core.*;
import crowdwatch.db.Database;
import crowdwatch.ds.*;
import crowdwatch.feed.CsvFeed;
import crowdwatch.feed.SimulatedFeed;
import crowdwatch.model.*;
import crowdwatch.model.Enums.*;

/**
 * The desktop window: a menu bar and seven tabs (Dashboard, Areas &amp; crowd, Units, Incidents,
 * Routes, Reports, Data structures).
 * <p>
 * Every button calls one {@link CrowdMonitor} method and shows the {@link Result} in the status
 * bar. The window registers a {@link MonitorListener}, so changes made by sensor feeds or other
 * terminals appear here too. Any code that reads the monitor's trees holds the monitor's lock.
 * <p>
 * The venue is saved automatically every 60 seconds in the background, as in headless mode.
 * Without a database the title bar says "NOT SAVED - memory only".
 */
public class MainWindow extends JFrame {
    static final Color NAVY = new Color(0x1B, 0x3A, 0x5C);
    private static final Color OK = new Color(0x1E, 0x7B, 0x34);
    private static final Color BAD = new Color(0xB0, 0x00, 0x20);
    // row colours for LOW, MODERATE, HIGH and CRITICAL
    private static final Color[] LEVEL_BG = { new Color(0xE3, 0xF4, 0xE6), new Color(0xFF, 0xF4, 0xCC), new Color(0xFF, 0xDD, 0xBB), new Color(0xF8, 0xC6, 0xC6) };

    private final CrowdMonitor m;
    // database of the current venue, or null if Derby is missing
    private volatile Database db;
    // saves every 60 seconds in the background; the same interval as headless mode
    private final java.util.concurrent.ScheduledExecutorService autosave = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "autosave"); t.setDaemon(true); return t; });
    // demonstration sensor feed: updates every 1.5 s, fixed seed
    private final SimulatedFeed sim = new SimulatedFeed(1500, 7);
    // true while a refresh is queued, so bursts of changes cause one refresh
    private volatile boolean refreshPending;
    // network server, or null when not started
    private crowdwatch.net.ApiServer api;

    private final JLabel title = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JTextArea summary = text(7), queues = text(6), load = text(12);
    private final DefaultListModel<String> alertModel = new DefaultListModel<>();

    private final DefaultTableModel areaModel = model("ID", "Name", "Sector", "Type", "People", "Capacity", "%", "Level", "Source", "Updated", "Restricted", "Trend /min", "Critical in");
    private final JTable areaTable = table(areaModel);
    private final DefaultTableModel unitModel = model("ID", "Name", "Area", "Sector", "Status", "Skills / languages");
    private final JTable unitTable = table(unitModel);
    private final JTextArea unitHistory = text(6);
    private final DefaultTableModel incModel = model("ID", "Priority", "Category", "Area", "Status", "Unit", "Reported", "Min", "Details");
    private final JTable incTable = table(incModel);
    private final JTextArea incHistory = text(6);
    private final JTextArea routeOut = text(18), reportOut = text(22), dsOut = text(22);

    private final JComboBox<String> areaPick = new JComboBox<>(), levelArea = new JComboBox<>(), unitArea = new JComboBox<>(),
        moveArea = new JComboBox<>(), incArea = new JComboBox<>(), routeFrom = new JComboBox<>(), routeTo = new JComboBox<>();
    private final JComboBox<Category> incCategory = new JComboBox<>();
    private final JButton simButton = new JButton("Start simulated sensors");
    // Operations tab: officer, automation, reviews, SOS, evacuation, broadcast
    private final JTextField officerName = new JTextField();
    private final JLabel officerState = new JLabel(" ");
    private final JCheckBox automationBox = new JCheckBox("Automatic dispatch");
    private final DefaultListModel<String> reviewModel = new DefaultListModel<>();
    private final JList<String> reviewList = new JList<>(reviewModel);
    private final JComboBox<String> sosArea = new JComboBox<>(), zonePick = new JComboBox<>();
    private final JTextArea opsOut = text(20);
    // checks officer check-ins and unit acknowledgements every 15 s
    private final Watchdog watchdog;
    // radio gateway of the current venue, or null
    private UnitChannel gateway;

    /**
     * Builds the window for a monitor that already has a venue loaded.
     *
     * @param monitor the monitor to show and control
     * @param db      the venue's database, or null to run in memory
     */
    public MainWindow(CrowdMonitor monitor, Database db) {
        super("CrowdWatch - Crowd Monitoring and Response");
        this.m = monitor;
        this.db = db;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { exit(); } });
        setJMenuBar(menu());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Dashboard", dashboard());
        tabs.addTab("Areas & crowd", areas());
        tabs.addTab("Units", units());
        tabs.addTab("Incidents", incidents());
        tabs.addTab("Routes", routes());
        tabs.addTab("Reports", reports());
        tabs.addTab("Data structures", structures());
        tabs.insertTab("Operations", null, operations(), "Security officer, automatic dispatch, SOS and evacuation", 1);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setForeground(Color.WHITE);
        title.setOpaque(true);
        title.setBackground(NAVY);
        title.setBorder(new EmptyBorder(8, 12, 8, 12));
        status.setBorder(new EmptyBorder(6, 10, 6, 10));
        add(title, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        m.addListener(new MonitorListener() {
            public void onChange() { scheduleRefresh(); }
            public void onAlert(Alert a) {
                if (a.kind == AlertKind.SOS || a.kind == AlertKind.SUPERVISION || a.kind == AlertKind.NO_ACK || a.kind == AlertKind.DELIVERY || a.kind == AlertKind.EVACUATION)
                    SwingUtilities.invokeLater(() -> { Toolkit.getDefaultToolkit().beep(); show(Result.fail(a.kind + ": " + a.text)); });
            }
        });
        setSize(1280, 800);
        setLocationRelativeTo(null);
        autosave.scheduleAtFixedRate(this::autosaveTick, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        watchdog = new Watchdog(m);
        watchdog.start();
        useGateway(m.venue());
        venueChanged();
    }

    /**
     * Called by the monitor (possibly from another thread) after every change. Queues one refresh
     * on the Swing event thread; further changes before it runs are merged into it.
     */
    private void scheduleRefresh() {
        if (refreshPending) return;
        refreshPending = true;
        SwingUtilities.invokeLater(() -> { refreshPending = false; refresh(); });
    }

    /** Builds the File, Venue and Network menus. */
    private JMenuBar menu() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        file.add(item("Save to database", this::save));
        file.add(item("Reset venue to its sample data", () -> {
            if (confirm("Replace all current data with the venue's sample data?")) { m.loadVenue(m.venue(), true); venueChanged(); show(Result.ok("Sample data reloaded.")); }
        }));
        file.addSeparator();
        file.add(item("Exit", this::exit));
        JMenu venue = new JMenu("Venue");
        for (String code : VenueProfile.BUILT_IN) venue.add(item("Switch to " + code, () -> switchVenue(VenueProfile.builtIn(code))));
        venue.addSeparator();
        venue.add(item("Open venue file...", () -> {
            JFileChooser fc = new JFileChooser(new File("."));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try { switchVenue(VenueProfile.fromFile(fc.getSelectedFile().toPath())); }
            catch (Exception ex) { show(Result.fail("Venue file not loaded: " + ex.getMessage())); }
        }));
        JMenu net = new JMenu("Network");
        net.add(item("Start server for other terminals...", () -> {
            String port = JOptionPane.showInputDialog(this, "Port for the web dashboard and API:", "8080");
            if (port == null) return;
            String key = JOptionPane.showInputDialog(this, "Control key for the dashboard and API (leave empty for an open demo server):", "");
            if (key == null) return;
            String scoutKey = key.isBlank() ? null : JOptionPane.showInputDialog(this, "Scout key for the scouts' devices (optional):", "");
            try {
                if (api != null) api.stop();
                api = new crowdwatch.net.ApiServer(m, key, scoutKey, crowdwatch.net.ApiServer.RATE_LIMIT);
                int p = api.start(Integer.parseInt(port.trim()));
                show(Result.ok("Server running: other terminals open http://<this-computer>:" + p + "/  (API under /api/...)"));
            } catch (Exception ex) { show(Result.fail("Server not started: " + ex.getMessage())); }
        }));
        net.add(item("Stop server", () -> { if (api != null) api.stop(); api = null; show(Result.ok("Server stopped.")); }));
        bar.add(file);
        bar.add(venue);
        bar.add(net);
        return bar;
    }

    /**
     * Switches to another venue. First checks that the new venue's database can be opened (if
     * not, the user chooses between memory-only mode and staying on the current venue), then
     * offers to save the current venue, stops the simulation and loads the new venue from its
     * own database or with its sample data. The switch happens under the monitor's lock, so an
     * autosave never copies a half-loaded venue.
     */
    private void switchVenue(VenueProfile p) {
        Database next = App.openDatabase(p.code);
        if (next != null) {
            try { next.check(); }
            catch (Exception e) {
                if (!App.askMemoryOnly(this, "The database for " + p.name + " cannot be used: " + Database.explain(e))) return;
                next = null;
            }
        }
        if (db != null && confirm("Save " + m.venueName() + " before switching?")) {
            Result r = saveNow();
            show(r);
            if (!r.ok && !confirm(r.message + "\nSwitch anyway and lose the unsaved changes?")) return;
        }
        sim.stop();
        simButton.setText("Start simulated sensors");
        String note;
        synchronized (m) {   // an autosave cannot copy the monitor halfway through the switch
            db = next;
            useGateway(p);
            try { note = App.loadOrSeed(m, next, p); }
            catch (IllegalStateException e) { db = null; note = e.getMessage() + " " + App.loadOrSeed(m, null, p); }
        }
        venueChanged();
        show(db == null ? Result.fail(note) : Result.ok(note));
    }

    /**
     * Reloads everything that depends on the venue (title, area lists, categories) under the monitor's lock.
     */
    private void venueChanged() {
        synchronized (m) { venueChangedNow(); }
    }

    /**
     * Updates the title bar and every area and category drop-down, then refreshes the tables.
     * The route boxes default to the first and last public (not restricted) areas.
     */
    @SuppressWarnings("unchecked")
    private void venueChangedNow() {
        title.setText((db == null ? "[NOT SAVED - memory only]   " : "") + m.venueName() + "   |   " + m.areaTree().size() + " areas, " + m.map().edgeCount() + " links, thresholds "
            + m.thresholds()[0] + "/" + m.thresholds()[1] + "/" + m.thresholds()[2] + "%");
        String[] ids = m.areaTree().keysInOrder().toArray(new String[0]);
        for (JComboBox<String> c : new JComboBox[] { areaPick, levelArea, unitArea, moveArea, incArea, routeFrom, routeTo }) c.setModel(new DefaultComboBoxModel<>(ids));
        Area[] allAreas = m.areaTree().inOrder().toArray(new Area[0]);
        Area[] all = m.areaTree().inOrder().toArray(new Area[0]);
        for (Area a : all) if (!a.restricted) { routeFrom.setSelectedItem(a.id); break; }
        for (int i = all.length - 1; i >= 0; i--) if (!all[i].restricted) { routeTo.setSelectedItem(all[i].id); break; }
        incCategory.setModel(new DefaultComboBoxModel<>(m.categoryTree().inOrder().toArray(new Category[0])));
        sosArea.setModel(new DefaultComboBoxModel<>(ids));
        for (Area a : allAreas) if (!a.restricted) { sosArea.setSelectedItem(a.id); break; }
        DefaultComboBoxModel<String> zones = new DefaultComboBoxModel<>();
        for (Area a : allAreas) if (zones.getIndexOf(a.sector) < 0) zones.addElement(a.sector);
        for (String id : ids) zones.addElement(id);
        zonePick.setModel(zones);
        refresh();
    }

    /**
     * Operations tab for the security officer: check in, switch automatic dispatch, review
     * automatic dispatches, send an SOS, start or end an evacuation, message all units, and see
     * the evacuation plan, unacknowledged tasks and the messages sent to units.
     */
    private JPanel operations() {
        JPanel p = pad(new JPanel(new BorderLayout(10, 10)));
        JPanel side = column();
        JButton checkIn = new JButton("Check in");
        checkIn.addActionListener(e -> act(m.officerCheckIn(officerName.getText())));
        automationBox.addActionListener(e -> {
            Result in = m.officerCheckIn(officerName.getText());
            if (!in.ok) { automationBox.setSelected(m.automation()); act(in); return; }
            m.setAutomation(automationBox.isSelected());
            act(Result.ok("Automatic dispatch " + (automationBox.isSelected() ? "ON" : "OFF") + "."));
        });
        side.add(form("Security officer", new String[] { "Name / badge", "", "Status", "" }, new JComponent[] { officerName, checkIn, officerState, automationBox }, null));

        reviewList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        reviewList.setVisibleRowCount(4);
        JButton confirm = new JButton("Confirm"), falseAlarm = new JButton("False alarm");
        confirm.addActionListener(e -> reviewSelected(true));
        falseAlarm.addActionListener(e -> reviewSelected(false));
        JPanel rb = new JPanel(new GridLayout(1, 2, 6, 0));
        rb.add(confirm); rb.add(falseAlarm);
        JScrollPane rs = scroll(reviewList);
        rs.setPreferredSize(new Dimension(300, 90));
        side.add(form("Automatic dispatches to review", new String[] { "" }, new JComponent[] { rs }, rb));

        JTextField sosText = new JTextField();
        JButton sos = new JButton("SEND SOS");
        sos.setBackground(new Color(0xD5, 0x00, 0x00));
        sos.setForeground(Color.WHITE);
        sos.addActionListener(e -> act(m.sos(sel(sosArea), null, sosText.getText())));
        side.add(form("SOS - person in danger", new String[] { "Area", "What is happening" }, new JComponent[] { sosArea, sosText }, sos));

        JButton evac = new JButton("Evacuate"), endEvac = new JButton("End evacuation");
        evac.addActionListener(e -> { if (confirm("Evacuate " + sel(zonePick) + "? Every unit will be told.")) act(m.evacuate(sel(zonePick), officerName.getText())); });
        endEvac.addActionListener(e -> act(m.endEvacuation(officerName.getText())));
        JPanel eb = new JPanel(new GridLayout(1, 2, 6, 0));
        eb.add(evac); eb.add(endEvac);
        side.add(form("Evacuation", new String[] { "Zone (sector or area)" }, new JComponent[] { zonePick }, eb));

        JTextField bc = new JTextField();
        JButton send = new JButton("Send to all units");
        send.addActionListener(e -> act(m.broadcast(bc.getText(), officerName.getText())));
        side.add(form("Message to all units", new String[] { "Text" }, new JComponent[] { bc }, send));

        p.add(new JScrollPane(side), BorderLayout.WEST);
        p.add(titled("Evacuation plan, unacknowledged tasks and messages to units (devices and radio)", scroll(opsOut)), BorderLayout.CENTER);
        return p;
    }

    /** Confirms or rejects the automatic dispatch selected in the review list. */
    private void reviewSelected(boolean confirmed) {
        String s = reviewList.getSelectedValue();
        if (s == null) { show(Result.fail("Select an automatic dispatch first.")); return; }
        act(m.review(incId(s.substring(0, s.indexOf(' '))), officerName.getText(), confirmed, ""));
    }

    /** Fills the Operations tab (called from the refresh, under the monitor's lock). */
    private void refreshOperations() {
        officerState.setText(m.officerStatus());
        if (officerName.getText().isBlank() && m.officer() != null) officerName.setText(m.officer());
        officerState.setForeground(m.officer() == null || m.officerStatus().contains("OVERDUE") ? BAD : OK);
        automationBox.setSelected(m.automation());
        String selected = reviewList.getSelectedValue();
        reviewModel.clear();
        for (Incident i : m.awaitingReview()) reviewModel.addElement(i + " " + i.area.id + " -> " + nz(i.unitId) + "  " + i.details);
        if (selected != null) reviewList.setSelectedValue(selected, false);
        StringBuilder sb = new StringBuilder();
        if (m.evacuationZone() != null) {
            sb.append("EVACUATION IN PROGRESS: ").append(m.evacuationZone()).append("\n");
            for (String line : m.evacuationPlan()) sb.append("  ").append(line).append('\n');
            sb.append('\n');
        }
        sb.append("Tasks not yet acknowledged by the unit:\n");
        int n = 0;
        for (Incident i : m.openIncidentsByPriority())
            if (i.status == IncidentStatus.ASSIGNED && i.acknowledged == null) {
                sb.append(String.format("  %-6s %-9s %-12s -> %-5s sent %s%s%n", i, i.priority, i.area.id, i.unitId, i.notified == null ? "-" : i.notified.format(HistoryEntry.FORMAT), i.reminders > 0 ? "  (reminder sent)" : ""));
                n++;
            }
        if (n == 0) sb.append("  none\n");
        sb.append("\nMessages sent to units (newest first per unit):\n");
        for (Unit u : m.unitsSorted()) {
            if (u.inbox.size() == 0) continue;
            sb.append(u.id).append("  ").append(u.name).append("  [").append(u.status).append(u.lastContact == null ? ", device never connected" : ", device seen " + u.lastContact.format(HistoryEntry.FORMAT)).append("]\n");
            for (int k = u.inbox.size() - 1; k >= Math.max(0, u.inbox.size() - 3); k--) sb.append("    ").append(u.inbox.get(k)).append('\n');
        }
        int caret = Math.min(opsOut.getCaretPosition(), sb.length());
        opsOut.setText(sb.toString());
        opsOut.setCaretPosition(caret);
    }

    /** Connects the venue's radio gateway (its {@code gateway:} line), replacing the previous one. */
    private void useGateway(VenueProfile p) {
        if (gateway != null) m.removeChannel(gateway);
        gateway = p != null && p.gateway != null ? new crowdwatch.net.HttpGatewayChannel(p.gateway) : null;
        if (gateway != null) m.addChannel(gateway);
    }

    /** Dashboard tab: summary, busiest areas, waiting queues and the alert list. */
    private JPanel dashboard() {
        JPanel p = pad(new JPanel(new GridLayout(1, 2, 10, 10)));
        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.add(titled("Summary", scroll(summary)), BorderLayout.NORTH);
        left.add(titled("Busiest areas (merge sort by level, then %)", scroll(load)), BorderLayout.CENTER);
        left.add(titled("Waiting queues by priority (FIFO)", scroll(queues)), BorderLayout.SOUTH);
        JList<String> alerts = new JList<>(alertModel);
        alerts.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        p.add(left);
        p.add(titled("Alerts (newest first - linked list)", scroll(alerts)));
        return p;
    }

    /**
     * Areas &amp; crowd tab: table of areas coloured by level, people count and gate counter,
     * staff observation, and the simulated sensor and CSV import buttons.
     */
    private JPanel areas() {
        JPanel p = pad(new JPanel(new BorderLayout(10, 10)));
        areaTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, f, r, c);
                if (!sel) {
                    String lv = String.valueOf(t.getValueAt(r, 7));
                    comp.setBackground(Color.WHITE);
                    for (CrowdLevel l : CrowdLevel.values()) if (l.name().equals(lv)) comp.setBackground(LEVEL_BG[l.ordinal()]);
                }
                return comp;
            }
        });
        areaTable.getSelectionModel().addListSelectionListener(e -> {
            int r = areaTable.getSelectedRow();
            if (r >= 0) { areaPick.setSelectedItem(areaModel.getValueAt(r, 0)); levelArea.setSelectedItem(areaModel.getValueAt(r, 0)); }
        });
        p.add(scroll(areaTable), BorderLayout.CENTER);

        JPanel side = column();
        JTextField count = new JTextField("0"), delta = new JTextField("25");
        JButton set = new JButton("Set occupancy");
        set.addActionListener(e -> act(m.updateOccupancy(sel(areaPick), num(count.getText()), Source.STAFF)));
        JButton in = new JButton("+ entered"), out = new JButton("- left");
        in.addActionListener(e -> act(m.adjustOccupancy(sel(areaPick), num(delta.getText()), Source.SENSOR)));
        out.addActionListener(e -> act(m.adjustOccupancy(sel(areaPick), -num(delta.getText()), Source.SENSOR)));
        JPanel counter = new JPanel(new GridLayout(1, 2, 6, 0));
        counter.add(in); counter.add(out);
        side.add(form("People count", new String[] { "Area", "Count", "", "Gate counter step", "" }, new JComponent[] { areaPick, count, set, delta, counter }, null));

        JComboBox<CrowdLevel> lvl = new JComboBox<>(CrowdLevel.values());
        JComboBox<Source> src = new JComboBox<>(new Source[] { Source.STAFF, Source.OFFICIAL });
        JButton rec = new JButton("Record observed level");
        rec.addActionListener(e -> act(m.recordLevel(sel(levelArea), (CrowdLevel) lvl.getSelectedItem(), (Source) src.getSelectedItem())));
        side.add(form("Staff observation", new String[] { "Area", "Level", "Source" }, new JComponent[] { levelArea, lvl, src }, rec));

        simButton.addActionListener(e -> {
            if (sim.running()) { sim.stop(); simButton.setText("Start simulated sensors"); show(Result.ok("Simulated sensor feed stopped.")); }
            else { sim.start(m); simButton.setText("Stop simulated sensors"); show(Result.ok("Simulated sensor feed running (every 1.5 s).")); }
        });
        JButton csv = new JButton("Import counts from CSV...");
        csv.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(new File("."));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            CsvFeed f = new CsvFeed(fc.getSelectedFile().toPath());
            f.start(m);
            show(f.log().isEmpty() ? Result.ok(f.applied() + " readings imported.") : Result.fail(f.applied() + " imported; problems: " + f.log()));
        });
        side.add(form("Sensor feeds (CrowdFeed adapters)", new String[] {}, new JComponent[] {}, stack(simButton, csv)));
        p.add(side, BorderLayout.EAST);
        return p;
    }

    /**
     * Units tab: unit table with history, adding a unit, moving it, changing its status,
     * and searching for it in the AVL tree (the search path is shown).
     */
    private JPanel units() {
        JPanel p = pad(new JPanel(new BorderLayout(10, 10)));
        unitTable.getSelectionModel().addListSelectionListener(e -> {
            int r = unitTable.getSelectedRow();
            if (r >= 0) synchronized (m) { history(unitHistory, m.findUnit(String.valueOf(unitModel.getValueAt(r, 0))).history); }
        });
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(scroll(unitTable), BorderLayout.CENTER);
        center.add(titled("History of the selected unit (linked list)", scroll(unitHistory)), BorderLayout.SOUTH);
        p.add(center, BorderLayout.CENTER);

        JPanel side = column();
        JTextField id = new JTextField(), name = new JTextField(), skills = new JTextField();
        JButton add = new JButton("Add unit");
        add.addActionListener(e -> act(m.addUnit(id.getText(), name.getText(), sel(unitArea), skills.getText().split(","))));
        side.add(form("Add a unit", new String[] { "Unit ID", "Name", "Area", "Skills (comma)" }, new JComponent[] { id, name, unitArea, skills }, add));

        JTextField uid = new JTextField();
        JCheckBox approved = new JCheckBox("Supervisor approved (restricted areas)");
        JButton move = new JButton("Move");
        move.addActionListener(e -> act(m.moveUnit(target(uid), sel(moveArea), approved.isSelected())));
        JComboBox<UnitStatus> st = new JComboBox<>(new UnitStatus[] { UnitStatus.AVAILABLE, UnitStatus.OFF_DUTY });
        JButton setSt = new JButton("Set status");
        setSt.addActionListener(e -> act(m.setUnitStatus(target(uid), (UnitStatus) st.getSelectedItem())));
        JButton find = new JButton("Search (AVL)");
        find.addActionListener(e -> {
            Unit u;
            String path;
            synchronized (m) {
                u = m.findUnit(uid.getText());
                path = m.unitTree().lastPath().toString();
            }
            if (u == null) show(Result.fail("No unit " + uid.getText() + ". AVL path " + path));
            else { select(unitTable, unitModel, u.id); show(Result.ok("Found " + u.id + " at " + u.area.id + ". AVL path " + path)); }
        });
        side.add(form("Selected unit (or type an ID)", new String[] { "Unit ID", "Move to", "", "Status" }, new JComponent[] { uid, moveArea, approved, st }, stack(move, setSt, find)));
        p.add(side, BorderLayout.EAST);
        return p;
    }

    /**
     * Incidents tab: incident table with history, reporting form, and On scene, Resolve,
     * Reassign and Escalate for the selected incident.
     */
    private JPanel incidents() {
        JPanel p = pad(new JPanel(new BorderLayout(10, 10)));
        incTable.getSelectionModel().addListSelectionListener(e -> {
            int r = incTable.getSelectedRow();
            if (r >= 0) synchronized (m) { history(incHistory, m.findIncident(incId(String.valueOf(incModel.getValueAt(r, 0)))).history); }
        });
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(scroll(incTable), BorderLayout.CENTER);
        center.add(titled("History of the selected incident (linked list)", scroll(incHistory)), BorderLayout.SOUTH);
        p.add(center, BorderLayout.CENTER);

        JPanel side = column();
        JTextField details = new JTextField(), contact = new JTextField(), lang = new JTextField();
        JButton report = new JButton("Report incident");
        report.addActionListener(e -> {
            Category c = (Category) incCategory.getSelectedItem();
            act(m.reportIncident(c == null ? null : c.code, sel(incArea), details.getText(), contact.getText(), lang.getText()));
        });
        side.add(form("Report", new String[] { "Category", "Area", "Details", "Contact", "Language" }, new JComponent[] { incCategory, incArea, details, contact, lang }, report));

        JTextField iid = new JTextField(), unit = new JTextField(), note = new JTextField();
        JButton scene = new JButton("On scene"), resolve = new JButton("Resolve"), reassign = new JButton("Reassign to unit"), esc = new JButton("Escalate priority");
        scene.addActionListener(e -> act(m.confirmOnScene(incTarget(iid))));
        resolve.addActionListener(e -> act(m.resolve(incTarget(iid), note.getText())));
        reassign.addActionListener(e -> act(m.reassign(incTarget(iid), unit.getText(), note.getText())));
        esc.addActionListener(e -> act(m.escalate(incTarget(iid))));
        // for units without a device: the radio operator records the spoken acknowledgement
        JButton ackRadio = new JButton("Acknowledged by radio");
        ackRadio.addActionListener(e -> {
            Incident x = m.findIncident(incTarget(iid));
            act(x == null || x.unitId == null ? Result.fail("Select an assigned incident first.") : m.acknowledge(x.unitId));
        });
        side.add(form("Selected incident (or type an ID)", new String[] { "Incident", "Unit", "Note / reason" }, new JComponent[] { iid, unit, note }, stack(scene, resolve, reassign, esc, ackRadio)));
        p.add(side, BorderLayout.EAST);
        return p;
    }

    /**
     * Routes tab: shortest walk and crowd-aware route between two areas, with the Dijkstra visit order.
     */
    private JPanel routes() {
        JPanel p = pad(new JPanel(new BorderLayout(10, 10)));
        JButton go = new JButton("Find route (Dijkstra)");
        go.addActionListener(e -> {
            CrowdMonitor.Route plain = m.route(sel(routeFrom), sel(routeTo), false);
            CrowdMonitor.Route smart = m.route(sel(routeFrom), sel(routeTo), true);
            StringBuilder sb = new StringBuilder();
            sb.append("Shortest walk:        ").append(routeText(plain, false)).append("\n\n");
            sb.append("Crowd-aware route:    ").append(routeText(smart, true)).append("\n\n");
            sb.append("Edge cost in crowd-aware mode = walk minutes x factor of the area entered\n(LOW 1, MODERATE 2, HIGH 4, CRITICAL 10). Restricted areas are never passed through.\n\n");
            sb.append("Dijkstra visit order (crowd-aware, min-heap):\n");
            for (String s : smart.visitOrder) sb.append("  ").append(s).append('\n');
            routeOut.setText(sb.toString());
            routeOut.setCaretPosition(0);
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("From")); top.add(routeFrom); top.add(new JLabel("To")); top.add(routeTo); top.add(go);
        p.add(top, BorderLayout.NORTH);
        p.add(titled("Guidance for staff and signage", scroll(routeOut)), BorderLayout.CENTER);
        return p;
    }

    /** @return the path and walking time as text, or "no route" */
    private String routeText(CrowdMonitor.Route r, boolean showCost) {
        if (!r.found()) return "no route";
        return r.path + "\n                      walk " + r.walk + " min" + (showCost ? ", weighted cost " + r.cost : "");
    }

    /** Reports tab: sorted reports (each made with merge sort) and export to a text file. */
    private JPanel reports() {
        JPanel p = pad(new JPanel(new BorderLayout(10, 10)));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(lockedButton("Open incidents by priority", () -> {
            StringBuilder sb = header("OPEN INCIDENTS - priority, then longest waiting first");
            for (Incident i : m.openIncidentsByPriority())
                sb.append(String.format("%-6s %-9s %-34s %-12s %-9s %-5s %4d min%n", i, i.priority, i.category.label, i.area.id, i.status, nz(i.unitId), i.minutesOpen(m.now())));
            report(sb);
        }));
        buttons.add(lockedButton("Areas by crowd load", () -> {
            StringBuilder sb = header("AREAS - highest level and occupancy first");
            for (Area a : m.areasByLoad()) sb.append(String.format("%-12s %-34s %-9s %6d / %-6d %4d%%  %s%n", a.id, a.name, a.level, a.occupancy, a.capacity, a.percent(), bar(a.percent())));
            report(sb);
        }));
        buttons.add(lockedButton("Units", () -> {
            StringBuilder sb = header("UNITS - status, sector, ID");
            for (Unit u : m.unitsSorted()) sb.append(String.format("%-6s %-24s %-10s %-12s %s%n", u.id, u.name, u.status, u.area.id, u.skillList()));
            report(sb);
        }));
        buttons.add(lockedButton("Incident log", () -> {
            StringBuilder sb = header("INCIDENT LOG - by time reported");
            for (Incident i : m.incidentsByTime())
                sb.append(String.format("%-6s %s  %-34s %-12s %-9s %s%n", i, i.reported.format(HistoryEntry.FORMAT), i.category.label, i.area.id, i.status, i.details));
            report(sb);
        }));
        buttons.add(button("Export to text file...", () -> {
            JFileChooser fc = new JFileChooser(new File("."));
            fc.setSelectedFile(new File(m.venue().code + "-report.txt"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            try { Files.writeString(fc.getSelectedFile().toPath(), reportOut.getText()); show(Result.ok("Report saved to " + fc.getSelectedFile())); }
            catch (Exception ex) { show(Result.fail(ex.getMessage())); }
        }));
        p.add(buttons, BorderLayout.NORTH);
        p.add(scroll(reportOut), BorderLayout.CENTER);
        return p;
    }

    /**
     * Data structures tab: shows the AVL trees, distance cache, circular buffers, queues, the
     * area graph, a min-heap demonstration and a merge-sort trace using the live data.
     */
    private JPanel structures() {
        JPanel p = pad(new JPanel(new BorderLayout(10, 10)));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(lockedButton("AVL trees", () -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Unit AVL tree (size ").append(m.unitTree().size()).append(", height ").append(m.unitTree().height()).append(", rotations ").append(m.unitTree().rotations()).append(")\n").append(m.unitTree().shape()).append('\n');
            sb.append("Area AVL tree (size ").append(m.areaTree().size()).append(", height ").append(m.areaTree().height()).append(")\n").append(m.areaTree().shape()).append('\n');
            sb.append("Incident AVL tree (size ").append(m.incidentTree().size()).append(", height ").append(m.incidentTree().height()).append(", rotations ").append(m.incidentTree().rotations()).append(")\n").append(m.incidentTree().shape());
            ds(sb.toString());
        }));
        buttons.add(lockedButton("Distance cache & trends", () -> {
            StringBuilder sb = new StringBuilder("Distance cache (AVL tree keyed by area, values = Dijkstra results)\n");
            sb.append("  hits ").append(m.cacheHits()).append(", misses ").append(m.cacheMisses()).append(" - cleared only when areas or links change\n\n");
            sb.append("Recent readings per area (circular buffer, capacity 60, oldest overwritten)\n");
            for (Area a : m.areaTree().inOrder())
                sb.append(String.format("  %-12s rate %5s/min  critical in %-7s %s%n", a.id, trend(m.ratePerMinute(a)), eta(m.minutesToCritical(a)), a.readings));
            ds(sb.toString());
        }));
        buttons.add(lockedButton("Queues", () -> {
            StringBuilder sb = new StringBuilder();
            for (Priority pr : Priority.values()) sb.append(String.format("%-9s %s%n", pr, m.queue(pr)));
            ds(sb + "\nDispatch serves CRITICAL first; inside each level, first in first out.");
        }));
        buttons.add(lockedButton("Area graph", () -> ds("Adjacency lists (walk minutes)\n" + m.map().adjacencyText())));
        buttons.add(lockedButton("Min-heap demo", () -> {
            MinHeap<Area> h = new MinHeap<>((a, b) -> Integer.compare(b.percent(), a.percent()));
            StringBuilder sb = new StringBuilder("Insert every area into a heap ordered by occupancy % (largest on top):\n");
            for (Area a : m.areaTree().inOrder()) { h.insert(a); sb.append("  insert ").append(a.id).append(" -> ").append(h).append('\n'); }
            sb.append("\nPoll order:\n");
            while (!h.isEmpty()) { Area a = h.poll(); sb.append("  ").append(a.id).append(" ").append(a.percent()).append("%\n"); }
            ds(sb.toString());
        }));
        buttons.add(lockedButton("Merge-sort trace", () -> {
            Area[] a = m.areaTree().inOrder().toArray(new Area[0]);
            LinkedList<String> trace = new LinkedList<>();
            MergeSort.sort(a, (x, y) -> Integer.compare(y.percent(), x.percent()), trace);
            StringBuilder sb = new StringBuilder("Sorting areas by occupancy % (descending):\n");
            for (String s : trace) sb.append(s).append('\n');
            ds(sb.toString());
        }));
        p.add(buttons, BorderLayout.NORTH);
        p.add(scroll(dsOut), BorderLayout.CENTER);
        return p;
    }

    /** Redraws every table and text area from the monitor's current data, holding its lock. */
    private void refresh() {
        synchronized (m) { refreshNow(); }
    }

    /**
     * Fills the dashboard texts, alert list and the area, unit and incident tables.
     * Open incidents are listed first by priority, then resolved ones by time.
     */
    private void refreshNow() {
        summary.setText(m.summary());
        summary.setCaretPosition(0);
        StringBuilder q = new StringBuilder();
        for (Priority p : Priority.values()) q.append(String.format("%-9s %s%n", p, m.queue(p)));
        queues.setText(q.toString());
        StringBuilder l = new StringBuilder();
        Area[] sorted = m.areasByLoad();
        for (int i = 0; i < sorted.length && i < 12; i++) {
            Area a = sorted[i];
            l.append(String.format("%-12s %-9s %4d%%  %s%n", a.id, a.level, a.percent(), bar(a.percent())));
        }
        load.setText(l.toString());
        load.setCaretPosition(0);
        alertModel.clear();
        for (Alert a : m.alerts()) alertModel.addElement(a.toString());

        fill(areaTable, areaModel, m.areaTree().inOrder(), a -> new Object[] { a.id, a.name, a.sector, a.type, a.occupancy, a.capacity, a.percent(), a.level,
            a.source == null ? "" : a.source, a.updated == null ? "" : a.updated.format(HistoryEntry.FORMAT), a.restricted ? "yes" : "", trend(m.ratePerMinute(a)), eta(m.minutesToCritical(a)) });
        fill(unitTable, unitModel, m.unitTree().inOrder(), u -> new Object[] { u.id, u.name, u.area.id, u.area.sector, u.status, u.skillList() });
        LinkedList<Incident> inc = new LinkedList<>();
        for (Incident i : m.openIncidentsByPriority()) inc.addLast(i);
        for (Incident i : m.incidentsByTime()) if (!i.open()) inc.addLast(i);
        fill(incTable, incModel, inc, i -> new Object[] { i.toString(), i.priority, i.category.label, i.area.id, i.status, nz(i.unitId),
            i.reported.format(HistoryEntry.FORMAT), i.minutesOpen(m.now()), i.details });
        refreshOperations();
    }

    // turns one item into a table row
    private interface Row<T> { Object[] of(T t); }

    /**
     * Replaces the rows of a table and keeps the selected row selected (matched by the ID in column 0).
     */
    private <T> void fill(JTable t, DefaultTableModel model, LinkedList<T> items, Row<T> row) {
        int r = t.getSelectedRow();
        Object key = r >= 0 && r < model.getRowCount() ? model.getValueAt(r, 0) : null;
        model.setRowCount(0);
        for (T item : items) model.addRow(row.of(item));
        if (key != null) select(t, model, String.valueOf(key));
    }

    /** Shows the result of a button action and refreshes the window. */
    private void act(Result r) {
        show(r);
        refresh();
    }

    /** Shows a result in the status bar: green for OK, red for REFUSED. */
    private void show(Result r) {
        status.setText((r.ok ? "OK  " : "REFUSED  ") + r.message);
        status.setForeground(r.ok ? OK : BAD);
    }

    /** File &gt; Save: saves on a background thread so the window stays usable, then shows the outcome. */
    private void save() {
        show(Result.ok("Saving..."));
        Thread t = new Thread(() -> { Result r = saveNow(); SwingUtilities.invokeLater(() -> show(r)); }, "save");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Saves now on the calling thread.
     *
     * @return the outcome, with a readable reason if it failed
     */
    private Result saveNow() {
        Database d = db;
        if (d == null) return Result.fail("Running in memory only: nothing can be saved.");
        try { d.save(m); return Result.ok("Saved " + m.venueName() + " at " + m.now().format(HistoryEntry.FORMAT) + "."); }
        catch (Exception e) { return Result.fail("Save failed: " + Database.explain(e)); }
    }

    /** Autosave: saves quietly; only a failure is shown in the status bar. */
    private void autosaveTick() {
        if (db == null) return;
        Result r = saveNow();
        if (!r.ok) SwingUtilities.invokeLater(() -> show(Result.fail("Autosave: " + r.message)));
    }

    /**
     * Offers to save, then stops autosave, the simulation and the server, shuts down Derby and
     * closes the program. Cancel keeps the window open with everything still running. If the
     * save fails, the user is asked before anything is lost.
     */
    private void exit() {
        if (db != null) {
            int c = JOptionPane.showConfirmDialog(this, "Save before exit?", "CrowdWatch", JOptionPane.YES_NO_CANCEL_OPTION);
            if (c == JOptionPane.CANCEL_OPTION || c == JOptionPane.CLOSED_OPTION) return;
            if (c == JOptionPane.YES_OPTION) {
                Result r = saveNow();
                if (!r.ok && !confirm(r.message + "\nExit anyway and lose the unsaved changes?")) { show(r); return; }
            }
        }
        autosave.shutdownNow();
        watchdog.stop();
        sim.stop();
        if (api != null) api.stop();
        m.awaitListeners(1000);
        if (db != null) Database.shutdown();
        dispose();
        System.exit(0);
    }

    /** @return the unit ID typed in the field, or else the ID of the selected table row */
    private String target(JTextField f) {
        if (!f.getText().isBlank()) return f.getText().trim();
        int r = unitTable.getSelectedRow();
        return r >= 0 ? String.valueOf(unitModel.getValueAt(r, 0)) : "";
    }

    /** @return the incident number typed in the field, or else that of the selected row; -1 if none */
    private int incTarget(JTextField f) {
        if (!f.getText().isBlank()) return incId(f.getText());
        int r = incTable.getSelectedRow();
        return r >= 0 ? incId(String.valueOf(incModel.getValueAt(r, 0))) : -1;
    }

    /** @return the digits of an incident ID ("I1001" -&gt; 1001), or -1 if there are none */
    private static int incId(String s) {
        String d = s.trim().replaceAll("[^0-9]", "");
        return d.isEmpty() ? -1 : Integer.parseInt(d);
    }

    /** @return the number in a text field, or -1 if it is not a number (the monitor then refuses it) */
    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** @return the selected item of a drop-down */
    private static String sel(JComboBox<String> c) { return (String) c.getSelectedItem(); }

    /** @return "-" for null, otherwise the text */
    private static String nz(String s) { return s == null ? "-" : s; }

    /** @return a rate with its sign, e.g. +43 */
    private static String trend(double r) { long v = Math.round(r); return v > 0 ? "+" + v : String.valueOf(v); }

    /** @return "-" if not rising, "now" if already CRITICAL, otherwise "N min" */
    private static String eta(int min) { return min < 0 ? "-" : min == 0 ? "now" : min + " min"; }

    /** @return a 20-character text bar, one # per 5% */
    static String bar(int percent) {
        int n = Math.max(0, Math.min(20, percent / 5));
        return "#".repeat(n) + ".".repeat(20 - n);
    }

    /** @return the heading of a report: venue, title and time */
    private StringBuilder header(String t) {
        return new StringBuilder(m.venueName().toUpperCase()).append(" - ").append(t).append("\nGenerated ").append(m.now().format(HistoryEntry.FORMAT)).append("\n\n");
    }

    /** Shows a report in the Reports tab. */
    private void report(StringBuilder sb) { reportOut.setText(sb.toString()); reportOut.setCaretPosition(0); }

    /** Shows text in the Data structures tab. */
    private void ds(String s) { dsOut.setText(s); dsOut.setCaretPosition(0); }

    /** Shows a history list, one entry per line. */
    private static void history(JTextArea a, LinkedList<HistoryEntry> h) {
        StringBuilder sb = new StringBuilder();
        for (HistoryEntry e : h) sb.append(e).append('\n');
        a.setText(sb.toString());
        a.setCaretPosition(0);
    }

    /** Selects the table row whose first column equals {@code key}. */
    private static void select(JTable t, DefaultTableModel model, String key) {
        for (int i = 0; i < model.getRowCount(); i++) if (String.valueOf(model.getValueAt(i, 0)).equals(key)) { t.setRowSelectionInterval(i, i); return; }
    }

    /** @return true if the user answers Yes */
    private boolean confirm(String q) { return JOptionPane.showConfirmDialog(this, q, "CrowdWatch", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION; }

    /** @return a menu item that runs an action */
    private static JMenuItem item(String label, Runnable r) { JMenuItem i = new JMenuItem(label); i.addActionListener(e -> r.run()); return i; }

    /** @return a button that runs an action */
    private static JButton button(String label, Runnable r) { JButton b = new JButton(label); b.addActionListener(e -> r.run()); return b; }

    /**
     * @return a button whose action runs while holding the monitor's lock (for actions that read the trees)
     */
    private JButton lockedButton(String label, Runnable r) { return button(label, () -> { synchronized (m) { r.run(); } }); }

    /** @return a read-only table model with the given column names */
    private static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) { public boolean isCellEditable(int r, int c) { return false; } };
    }

    /** @return a single-selection table */
    private static JTable table(DefaultTableModel model) {
        JTable t = new JTable(model);
        t.setRowHeight(22);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setAutoCreateRowSorter(false);
        return t;
    }

    /** @return a read-only monospaced text area */
    private static JTextArea text(int rows) {
        JTextArea a = new JTextArea(rows, 40);
        a.setEditable(false);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return a;
    }

    /** @return the component inside a scroll pane */
    private static JScrollPane scroll(Component c) { return new JScrollPane(c); }

    /** @return the component with a 10-pixel empty border */
    private static <T extends JComponent> T pad(T c) { c.setBorder(new EmptyBorder(10, 10, 10, 10)); return c; }

    /** @return the component inside a panel with a titled border */
    private static JPanel titled(String t, Component c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new TitledBorder(t));
        p.add(c);
        return p;
    }

    /** @return a vertical panel 330 pixels wide, used for the forms on the right of each tab */
    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setPreferredSize(new Dimension(330, 10));
        return p;
    }

    /** @return the components stacked vertically with equal size */
    private static JPanel stack(JComponent... cs) {
        JPanel p = new JPanel(new GridLayout(cs.length, 1, 0, 4));
        for (JComponent c : cs) p.add(c);
        return p;
    }

    /** Builds a titled form: one label and field per row, with an optional action button below. */
    private static JPanel form(String title, String[] labels, JComponent[] fields, JComponent action) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder(title));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < fields.length; i++) {
            g.gridy = i; g.gridx = 0; g.weightx = 0;
            p.add(new JLabel(labels[i]), g);
            g.gridx = 1; g.weightx = 1;
            p.add(fields[i], g);
        }
        if (action != null) { g.gridy = fields.length; g.gridx = 0; g.gridwidth = 2; p.add(action, g); }
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }
}
