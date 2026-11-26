package crowdwatch.fx;

import java.io.File;
import java.nio.file.Files;
import java.util.function.Function;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import crowdwatch.core.*;
import crowdwatch.db.Database;
import crowdwatch.ds.LinkedList;
import crowdwatch.ds.MergeSort;
import crowdwatch.ds.MinHeap;
import crowdwatch.feed.CsvFeed;
import crowdwatch.feed.SimulatedFeed;
import crowdwatch.gui.App;
import crowdwatch.model.*;
import crowdwatch.model.Enums.*;
import crowdwatch.model.Alert;
import crowdwatch.model.Enums.Priority;

/**
 * The JavaFX console: the same CrowdWatch features as the Swing window, in a Fluent-style layout
 * like a cloud portal: a dark top bar, a menu on the left, status chips, cards, a colour-coded
 * venue map, live charts and a details panel that slides in for an incident. Light and dark themes
 * come from {@code crowdwatch.css}.
 * <p>
 * It uses the same {@link CrowdMonitor}, database, autosave, watchdog, radio gateway and network
 * server as the Swing window; only the screens are different. Every read of the monitor's data
 * happens under the monitor's lock, and the monitor's change notices (which arrive on its event
 * thread) are passed to the JavaFX thread with {@link Platform#runLater}, merged into one refresh.
 */
public class FxApp extends Application {
    // ---------------------------------------------------------------- rows shown in tables (copies taken under the lock)
    /** One area row. */
    record AreaRow(String id, String name, String sector, int people, int cap, int pct, String level, String trend, boolean restricted) {}
    /** One incident row. */
    record IncRow(int num, String id, String priority, String category, String area, String status, String unit, String reported, String device, boolean automatic) {}
    /** One unit row. */
    record UnitRow(String id, String name, String area, String status, String task, String skills, String device, boolean online) {}

    private CrowdMonitor m;
    private volatile Database db;
    // logins, device codes and the audit log (shared with the network server); null if the accounts file cannot be read
    private crowdwatch.net.Accounts accounts;
    private Stage stage;
    private Scene scene;
    private final SimulatedFeed sim = new SimulatedFeed(1500, 7);
    private crowdwatch.net.ApiServer api;
    private UnitChannel gateway;
    private Watchdog watchdog;
    private final java.util.concurrent.ScheduledExecutorService autosave = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "autosave"); t.setDaemon(true); return t; });
    // true while a refresh is queued on the JavaFX thread
    private final java.util.concurrent.atomic.AtomicBoolean refreshPending = new java.util.concurrent.atomic.AtomicBoolean();

    // layout
    private final StackPane root = new StackPane();
    private final BorderPane shell = new BorderPane();
    private final StackPane center = new StackPane();
    private final ToggleGroup navGroup = new ToggleGroup();
    private final VBox banners = new VBox();
    private final FlowPane chips = new FlowPane(8, 8);
    private final Label titleLabel = new Label();
    private final Label crumb = new Label();
    private final Label venueLabel = new Label();
    private final Label alertBadge = new Label("0");
    private final TextField officerField = new TextField();
    private final HBox toast = new HBox();
    private final Label toastText = new Label();
    private final PauseTransition toastTimer = new PauseTransition(Duration.seconds(3.5));
    private String view = "overview";
    private Node[] views;
    private final String[] viewIds = { "overview", "areas", "incidents", "units", "routes", "emergency", "alerts", "reports", "structures", "settings" };
    private final String[] viewTitles = { "Overview", "Areas and crowd", "Incidents", "Units and scouts", "Routes", "SOS and evacuation", "Alerts", "Reports", "Data structures", "Venue and server" };

    // overview
    private final Label[] kpiValue = new Label[5], kpiNote = new Label[5], kpiDelta = new Label[5];
    private final StackPane[] kpiSpark = new StackPane[5];
    private static final String[] KPI_LEVEL = { "NORMAL", "HIGH", "CRITICAL", "LOW", "VIOLET" };
    private static final int HIST = 20;
    private final double[][] kpiHist = new double[5][HIST];
    private int histCount;
    private long histAt;
    private final LineChart<Number, Number> topChart = new LineChart<>(new NumberAxis(), new NumberAxis(0, 110, 10));
    private final Label topLegend = new Label();
    private final VBox donutBox = new VBox(14);
    private final VBox mapBox = new VBox(10);
    private final VBox alertsShort = new VBox();
    private final TableView<IncRow> overviewTable = new TableView<>();

    // areas
    private final TableView<AreaRow> areaTable = new TableView<>();
    private final LineChart<Number, Number> chart = new LineChart<>(new NumberAxis(), new NumberAxis());
    private final ComboBox<String> areaPick = new ComboBox<>();
    private String chartArea;

    // incidents
    private final TableView<IncRow> incTable = new TableView<>();
    private final VBox drawer = new VBox();
    private Integer selected;

    // units
    private final FlowPane unitCards = new FlowPane(12, 12);
    private final ComboBox<String> moveTo = new ComboBox<>();
    private final TextArea unitHistory = new TextArea();
    private String selectedUnit;

    // routes, emergency, alerts, reports, structures, settings
    private final ComboBox<String> routeFrom = new ComboBox<>(), routeTo = new ComboBox<>();
    private final TextArea routeOut = new TextArea();
    private final ComboBox<String> sosArea = new ComboBox<>(), zonePick = new ComboBox<>();
    private final VBox planBox = new VBox();
    private final VBox proposalBox = new VBox(8), lostBox = new VBox(10), signsBox = new VBox(8);
    private final VBox venueMapBox = new VBox(), mapCard = new VBox(10), evacMapBox = new VBox(6);
    private javafx.scene.image.Image mapImage;
    private String mapImageFor;
    private String lostSig;
    private final Label evacState = new Label();
    private final VBox alertList = new VBox();
    private String alertFilter = "ALL";
    private final HBox alertFilters = new HBox(8);
    private final TextArea reportOut = new TextArea(), dsOut = new TextArea();
    private final CheckBox automationBox = new CheckBox("Automatic dispatch on crowd levels");
    private final Label serverState = new Label("Server not started");
    private final Label settingsText = new Label();

    // ================================================================ start-up

    /**
     * Chooses the venue (argument or dialog), loads it from its database (or asks whether to run
     * in memory if the database cannot be used), then opens the main window.
     */
    @Override
    public void start(Stage primary) {
        stage = primary;
        VenueProfile p;
        try {
            Parameters ps = getParameters();
            p = ps == null || ps.getRaw().isEmpty() ? chooseVenue() : App.pick(ps.getRaw().get(0));
        } catch (Exception e) {
            error("Venue not loaded", String.valueOf(e.getMessage()));
            Platform.exit();
            return;
        }
        if (p == null) { Platform.exit(); return; }
        m = new CrowdMonitor(Clock.SYSTEM);
        db = App.openDatabase(p.code);
        String note;
        try { note = App.loadOrSeed(m, db, p); }
        catch (IllegalStateException e) {
            if (!askMemoryOnly(e.getMessage())) { Platform.exit(); return; }
            db = null;
            note = App.loadOrSeed(m, null, p);
        }
        openAccounts();
        buildWindow();
        useGateway(m.venue());
        watchdog = new Watchdog(m);
        watchdog.start();
        autosave.scheduleAtFixedRate(this::autosaveTick, 60, 60, java.util.concurrent.TimeUnit.SECONDS);
        m.addListener(new MonitorListener() {
            public void onChange() { scheduleRefresh(); }
            public void onAlert(Alert a) {
                if (a.kind == AlertKind.SOS || a.kind == AlertKind.SUPERVISION || a.kind == AlertKind.NO_ACK || a.kind == AlertKind.DELIVERY || a.kind == AlertKind.EVACUATION)
                    Platform.runLater(() -> { java.awt.Toolkit.getDefaultToolkit().beep(); say(a.kind + ": " + a.text); });
            }
        });
        venueChanged();
        stage.show();
        say(note);
    }

    /** @return the chosen built-in venue or file, or null if the user cancelled */
    private VenueProfile chooseVenue() throws Exception {
        ChoiceDialog<String> d = new ChoiceDialog<>("Airport", "Airport", "Stadium", "Umrah (Holy Mosques)", "Open venue file...");
        d.setTitle("CrowdWatch");
        d.setHeaderText("Choose the venue to monitor. Each venue keeps its own database.");
        String c = d.showAndWait().orElse(null);
        if (c == null) return null;
        if (c.startsWith("Open")) {
            File f = new FileChooser().showOpenDialog(null);
            return f == null ? null : VenueProfile.fromFile(f.toPath());
        }
        return VenueProfile.builtIn(c.startsWith("Airport") ? "airport" : c.startsWith("Stadium") ? "stadium" : "umrah");
    }

    /** @return true if the user chooses to continue in memory when the database cannot be used */
    private boolean askMemoryOnly(String reason) {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING, reason
            + "\n\nContinue WITHOUT the database? The window will show sample data, not the real situation, and nothing will be saved.", ButtonType.YES, ButtonType.NO);
        a.setTitle("CrowdWatch - database not available");
        a.setHeaderText(null);
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    /** Shows an error dialog. */
    private static void error(String title, String text) {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR, text, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    // ================================================================ window layout

    /** Builds the header, navigation, status strip, all views and the toast, and sets the stylesheet. */
    private void buildWindow() {
        views = new Node[] { overview(), areas(), incidents(), units(), routes(), emergency(), alerts(), reports(), structures(), settings() };
        VBox top = new VBox(header(), banners);
        shell.setTop(top);
        shell.setLeft(nav());
        VBox main = new VBox(12, pageHeader(), chips, center);
        main.getStyleClass().add("content");
        VBox.setVgrow(center, javafx.scene.layout.Priority.ALWAYS);
        ScrollPane scroll = new ScrollPane(main);
        scroll.setFitToWidth(true);
        shell.setCenter(scroll);
        drawer.getStyleClass().add("drawer");
        drawer.setPrefWidth(420);
        drawer.setMaxWidth(420);
        drawer.setVisible(false);
        StackPane.setAlignment(drawer, Pos.CENTER_RIGHT);
        StackPane.setMargin(drawer, new Insets(52, 0, 0, 0));
        toast.getStyleClass().add("toast");
        toast.getChildren().add(toastText);
        toast.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        toast.setVisible(false);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 24, 0));
        toastTimer.setOnFinished(e -> toast.setVisible(false));
        root.getChildren().setAll(shell, drawer, toast);
        if (!root.getStyleClass().contains("dark")) root.getStyleClass().add("dark");   // the control-room look is dark by default
        Vivid.startPulse();
        scene = new Scene(root, 1360, 860);
        scene.getStylesheets().add(FxApp.class.getResource("crowdwatch.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("CrowdWatch console");
        stage.getProperties().put("crowdwatch.monitor", m);   // lets automated tests reach the monitor
        stage.setOnCloseRequest(e -> { e.consume(); exit(); });
        show("overview");
    }

    /** The dark top bar: brand, venue menu, search, theme switch, alerts, officer check-in. */
    private Node header() {
        Label brand = new Label("CrowdWatch");
        brand.getStyleClass().add("brand");
        MenuButton venueMenu = new MenuButton();
        venueMenu.textProperty().bind(venueLabel.textProperty());
        venueMenu.getStyleClass().add("header-button");
        for (String code : VenueProfile.BUILT_IN) {
            MenuItem it = new MenuItem("Switch to " + code);
            it.setOnAction(e -> switchVenue(VenueProfile.builtIn(code)));
            venueMenu.getItems().add(it);
        }
        MenuItem open = new MenuItem("Open venue file...");
        open.setOnAction(e -> {
            File f = new FileChooser().showOpenDialog(stage);
            if (f == null) return;
            try { switchVenue(VenueProfile.fromFile(f.toPath())); } catch (Exception ex) { say("Venue file not loaded: " + ex.getMessage()); }
        });
        MenuItem save = new MenuItem("Save now");
        save.setOnAction(e -> save());
        MenuItem reset = new MenuItem("Reset to sample data");
        reset.setOnAction(e -> { if (confirm("Replace all current data with the venue's sample data?")) { m.loadVenue(m.venue(), true); venueChanged(); } });
        venueMenu.getItems().addAll(new SeparatorMenuItem(), open, save, reset);

        TextField search = new TextField();
        search.setPromptText("Search an area, unit or incident (e.g. GATE-A, A03, I1001) and press Enter");
        HBox.setHgrow(search, javafx.scene.layout.Priority.ALWAYS);
        search.setMaxWidth(460);
        search.setOnAction(e -> search(search.getText()));

        Button theme = new Button("Light");
        theme.getStyleClass().add("header-button");
        theme.setOnAction(e -> {
            boolean dark = !root.getStyleClass().contains("dark");
            if (dark) root.getStyleClass().add("dark"); else root.getStyleClass().remove("dark");
            theme.setText(dark ? "Light" : "Dark");
        });
        Button alertsBtn = new Button("Alerts");
        alertsBtn.getStyleClass().add("header-button");
        alertsBtn.setOnAction(e -> show("alerts"));
        alertBadge.getStyleClass().add("badge");
        officerField.setPromptText("Officer name");
        officerField.setPrefWidth(150);
        Button checkIn = new Button("Check in");
        checkIn.getStyleClass().add("header-button");
        checkIn.setOnAction(e -> act(m.officerCheckIn(officerField.getText())));
        Region gap = new Region();
        HBox.setHgrow(gap, javafx.scene.layout.Priority.SOMETIMES);
        HBox h = new HBox(brand, new Label("|"), venueMenu, gap, search, theme, new HBox(2, alertsBtn, alertBadge), officerField, checkIn);
        h.getStyleClass().add("header");
        return h;
    }

    /** Left menu of views. */
    private Node nav() {
        VBox v = new VBox();
        v.getStyleClass().add("nav");
        for (int i = 0; i < viewIds.length; i++) {
            ToggleButton b = new ToggleButton(viewTitles[i]);
            b.getStyleClass().add("nav-button");
            b.setToggleGroup(navGroup);
            b.setUserData(viewIds[i]);
            String id = viewIds[i];
            b.setOnAction(e -> show(id));
            v.getChildren().add(b);
        }
        navGroup.selectedToggleProperty().addListener((o, was, now) -> { if (now == null) was.setSelected(true); });
        return v;
    }

    /** Breadcrumb, title and the main commands. */
    private Node pageHeader() {
        crumb.getStyleClass().add("sub");
        titleLabel.getStyleClass().add("title");
        VBox left = new VBox(2, crumb, titleLabel);
        Button report = button("Report incident", "btn", e -> reportDialog());
        Button evac = button("Evacuate", "btn", e -> show("emergency"));
        Button sos = button("SOS", "btn-danger", e -> sosDialog(null));
        Region gap = new Region();
        HBox.setHgrow(gap, javafx.scene.layout.Priority.ALWAYS);
        HBox h = new HBox(8, left, gap, report, evac, sos);
        h.setAlignment(Pos.BOTTOM_LEFT);
        return h;
    }

    /** Switches the centre to a view and selects its menu entry. */
    private void show(String id) {
        view = id;
        for (int i = 0; i < viewIds.length; i++) if (viewIds[i].equals(id)) {
            center.getChildren().setAll(views[i]);
            titleLabel.setText(viewTitles[i]);
            for (Toggle t : navGroup.getToggles()) if (id.equals(t.getUserData())) t.setSelected(true);
        }
        if (!"incidents".equals(id) && !"overview".equals(id)) drawer.setVisible(false);
        refresh();
    }

    // ================================================================ views

    /** Overview: five key numbers, the venue map, live alerts and the open incidents. */
    private Node overview() {
        String[] labels = { "People on site", "Areas HIGH or CRITICAL", "Open incidents", "Units available", "Awaiting officer review" };
        String[] targets = { "areas", "areas", "incidents", "units", "incidents" };
        String[] icons = { Vivid.I_PEOPLE, Vivid.I_AREA, Vivid.I_WARN, Vivid.I_CHECK, Vivid.I_REVIEW };
        TilePane kpis = new TilePane(14, 14);
        kpis.setPrefColumns(5);
        kpis.setPrefTileWidth(214);
        for (int i = 0; i < 5; i++) {
            Label l = new Label(labels[i]);
            l.getStyleClass().add("sub");
            kpiValue[i] = new Label();
            kpiValue[i].getStyleClass().add("big-number");
            kpiNote[i] = new Label();
            kpiNote[i].getStyleClass().add("sub");
            kpiDelta[i] = new Label("live");
            kpiDelta[i].getStyleClass().addAll("pill", "lvl-" + KPI_LEVEL[i]);
            kpiSpark[i] = new StackPane();
            kpiSpark[i].setAlignment(Pos.CENTER_LEFT);
            Region gap = new Region();
            HBox.setHgrow(gap, javafx.scene.layout.Priority.ALWAYS);
            HBox top = new HBox(Vivid.iconChip(icons[i], KPI_LEVEL[i]), gap, kpiDelta[i]);
            top.setAlignment(Pos.CENTER_LEFT);
            VBox body = new VBox(3, top, l, kpiValue[i], kpiSpark[i], kpiNote[i]);
            VBox.setMargin(l, new Insets(8, 0, 0, 0));
            Button card = new Button();
            card.setGraphic(body);
            card.getStyleClass().add("kpi");
            card.setStyle("-fx-border-color: " + Vivid.hex(Vivid.color(KPI_LEVEL[i])) + " -cw-border -cw-border -cw-border; -fx-border-width: 3 1 1 1;");
            card.setMaxWidth(Double.MAX_VALUE);
            body.setPrefWidth(180);
            String target = targets[i];
            card.setOnAction(e -> show(target));
            kpis.getChildren().add(card);
        }
        Region mgap = new Region();
        HBox.setHgrow(mgap, javafx.scene.layout.Priority.ALWAYS);
        HBox legend = new HBox(10);
        for (String lv : new String[] { "LOW", "MODERATE", "HIGH", "CRITICAL" }) {
            Region sw = new Region();
            sw.setStyle("-fx-background-color: " + Vivid.hex(Vivid.color(lv)) + "; -fx-background-radius: 4;");
            sw.setMinSize(12, 12);
            Label t = new Label(lv);
            t.getStyleClass().add("sub");
            HBox it = new HBox(5, sw, t);
            it.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(it);
        }
        Label mapTitle = sectionLabel("Live floor plan");
        HBox mapHead = new HBox(mapTitle, mgap, legend);
        mapHead.setAlignment(Pos.CENTER_LEFT);
        VBox map = new VBox(10, mapHead, sub("Each row is a sector in walking order - colour is the crowd level, a glowing outline means act now"), mapBox);
        map.getStyleClass().add("card");
        topChart.setLegendVisible(false);
        topChart.setCreateSymbols(false);
        topChart.setAnimated(false);
        topChart.setPrefHeight(250);
        topChart.getStyleClass().add("top-chart");
        ((NumberAxis) topChart.getXAxis()).setLabel("minutes ago");
        ((NumberAxis) topChart.getYAxis()).setLabel("% of capacity");
        topLegend.getStyleClass().add("sub");
        VBox chartCard = card("Occupancy - the three busiest areas", topLegend, topChart);
        HBox.setHgrow(chartCard, javafx.scene.layout.Priority.ALWAYS);
        VBox donuts = card("Units and open incidents", donutBox);
        donuts.setMinWidth(300);
        donuts.setPrefWidth(360);
        HBox row2 = new HBox(14, chartCard, donuts);
        HBox.setHgrow(map, javafx.scene.layout.Priority.ALWAYS);
        VBox al = card("Live alerts", alertsShort);
        al.setPrefWidth(360);
        al.setMinWidth(300);
        HBox row = new HBox(14, map, al);
        incidentColumns(overviewTable);
        overviewTable.setPrefHeight(230);
        overviewTable.setOnMouseClicked(e -> { IncRow r = overviewTable.getSelectionModel().getSelectedItem(); if (r != null) openIncident(r.num()); });
        mapCard.getChildren().setAll(sectionLabel("Venue map"), sub("The venue drawing: colour is the crowd level, blue badges count the units there, dashed green outlines are exits. During an evacuation the routes are drawn as arrows."), venueMapBox);
        mapCard.getStyleClass().add("card");
        return new VBox(14, kpis, mapCard, row, row2, card("Open incidents", overviewTable));
    }

    /** Areas: table with load bars and levels, a live chart of the chosen area, and the count forms. */
    private Node areas() {
        areaTable.getColumns().add(col("Area", 100, AreaRow::id));
        areaTable.getColumns().add(col("Name", 220, AreaRow::name));
        areaTable.getColumns().add(col("Sector", 120, AreaRow::sector));
        areaTable.getColumns().add(col("People", 110, r -> r.people() + " / " + r.cap()));
        TableColumn<AreaRow, AreaRow> load = new TableColumn<>("Load");
        load.setPrefWidth(150);
        load.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        load.setCellFactory(c -> new TableCell<>() {
            protected void updateItem(AreaRow r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) { setGraphic(null); setText(null); return; }
                ProgressBar pb = new ProgressBar(Math.min(1, r.pct() / 100.0));
                pb.setPrefWidth(90);
                Label t = new Label(r.pct() + "%");
                setGraphic(new HBox(6, pb, t));
            }
        });
        areaTable.getColumns().add(load);
        areaTable.getColumns().add(pillCol("Level", 110, AreaRow::level, AreaRow::level));
        areaTable.getColumns().add(col("Trend", 220, AreaRow::trend));
        areaTable.setPrefHeight(330);
        areaTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> { if (b != null) { chartArea = b.id(); areaPick.setValue(b.id()); refreshChart(); } });

        chart.setTitle("People in the chosen area (sampled readings)");
        chart.setLegendVisible(false);
        chart.setCreateSymbols(true);
        chart.setAnimated(false);
        chart.setPrefHeight(260);
        ((NumberAxis) chart.getXAxis()).setLabel("minutes ago");
        ((NumberAxis) chart.getYAxis()).setLabel("people");

        TextField count = new TextField();
        count.setPromptText("Count");
        TextField step = new TextField("25");
        Button set = button("Set count", "btn", e -> act(m.updateOccupancy(areaPick.getValue(), num(count.getText()), Source.STAFF)));
        Button in = button("+ entered", "btn", e -> act(m.adjustOccupancy(areaPick.getValue(), num(step.getText()), Source.SENSOR)));
        Button out = button("- left", "btn", e -> act(m.adjustOccupancy(areaPick.getValue(), -num(step.getText()), Source.SENSOR)));
        ComboBox<CrowdLevel> lvl = new ComboBox<>(FXCollections.observableArrayList(CrowdLevel.values()));
        lvl.setValue(CrowdLevel.MODERATE);
        Button rec = button("Record observed level", "btn", e -> act(m.recordLevel(areaPick.getValue(), lvl.getValue(), Source.STAFF)));
        Button simB = button("Start simulated sensors", "btn", null);
        simB.setOnAction(e -> {
            if (sim.running()) { sim.stop(); simB.setText("Start simulated sensors"); say("Simulated sensors stopped."); }
            else { sim.start(m); simB.setText("Stop simulated sensors"); say("Simulated sensors running (every 1.5 s)."); }
        });
        Button csv = button("Import counts from CSV...", "btn", e -> {
            File f = new FileChooser().showOpenDialog(stage);
            if (f == null) return;
            CsvFeed feed = new CsvFeed(f.toPath());
            feed.start(m);
            act(feed.log().isEmpty() ? Result.ok(feed.applied() + " readings imported.") : Result.fail(feed.applied() + " imported; problems: " + feed.log()));
        });
        areaPick.setOnAction(e -> { chartArea = areaPick.getValue(); refreshChart(); });
        GridPane form = grid();
        form.addRow(0, new Label("Area"), areaPick);
        form.addRow(1, new Label("People count"), new HBox(6, count, set));
        form.addRow(2, new Label("Gate counter step"), new HBox(6, step, in, out));
        form.addRow(3, new Label("Staff observation"), new HBox(6, lvl, rec));
        form.addRow(4, new Label("Sensor feeds"), new HBox(6, simB, csv));
        VBox chartCard = card("Live chart", chart);
        HBox.setHgrow(chartCard, javafx.scene.layout.Priority.ALWAYS);
        return new VBox(14, card("All areas (busiest first)", areaTable), new HBox(14, chartCard, card("Update counts", form)));
    }

    /** Incidents: table of open and recent incidents; selecting one opens the details panel. */
    private Node incidents() {
        incidentColumns(incTable);
        incTable.setPrefHeight(520);
        incTable.setOnMouseClicked(e -> { IncRow r = incTable.getSelectionModel().getSelectedItem(); if (r != null) openIncident(r.num()); });
        Label hint = new Label("Select an incident to open its details panel. Automatic dispatches show AUTO until an officer reviews them.");
        hint.getStyleClass().add("sub");
        return new VBox(10, hint, incTable);
    }

    /** Units: a card per unit with status, task and device state; actions for the chosen unit. */
    private Node units() {
        CheckBox approved = new CheckBox("Supervisor approved (restricted areas)");
        approved.setMinWidth(Region.USE_PREF_SIZE);
        Button move = button("Move", "btn", e -> act(m.moveUnit(selectedUnit, moveTo.getValue(), approved.isSelected())));
        Button avail = button("Set AVAILABLE", "btn", e -> act(m.setUnitStatus(selectedUnit, UnitStatus.AVAILABLE)));
        Button off = button("Set OFF DUTY", "btn", e -> act(m.setUnitStatus(selectedUnit, UnitStatus.OFF_DUTY)));
        Button add = button("Add unit...", "btn", e -> addUnitDialog());
        unitHistory.setEditable(false);
        unitHistory.setPrefRowCount(8);
        unitHistory.getStyleClass().add("mono");
        GridPane form = grid();
        form.addRow(0, new Label("Move to"), new HBox(6, moveTo, move));
        form.addRow(1, new Label(""), approved);
        form.addRow(2, new Label("Status"), new HBox(6, avail, off));
        form.addRow(3, new Label(""), add);
        VBox side = card("Selected unit", form, unitHistory);
        side.setPrefWidth(420);
        side.setMinWidth(360);
        unitCards.setPrefWrapLength(620);
        unitCards.setAlignment(Pos.TOP_LEFT);
        unitCards.setRowValignment(javafx.geometry.VPos.TOP);
        HBox.setHgrow(unitCards, javafx.scene.layout.Priority.ALWAYS);
        HBox box = new HBox(14, unitCards, side);
        box.setAlignment(Pos.TOP_LEFT);
        side.setMaxHeight(Region.USE_PREF_SIZE);
        return box;
    }

    /** Routes: shortest walk and crowd-aware route with the Dijkstra visit order. */
    private Node routes() {
        routeOut.setEditable(false);
        routeOut.getStyleClass().add("mono");
        routeOut.setPrefRowCount(20);
        Button go = button("Find route (Dijkstra)", "btn-primary", e -> {
            CrowdMonitor.Route plain = m.route(routeFrom.getValue(), routeTo.getValue(), false);
            CrowdMonitor.Route smart = m.route(routeFrom.getValue(), routeTo.getValue(), true);
            StringBuilder sb = new StringBuilder();
            sb.append("Shortest walk:      ").append(plain.found() ? plain.path + "  (" + plain.walk + " min)" : "no route").append("\n\n");
            sb.append("Crowd-aware route:  ").append(smart.found() ? smart.path + "  (" + smart.walk + " min, weighted cost " + smart.cost + ")" : "no route").append("\n\n");
            sb.append("Crowd-aware cost = walk minutes x factor of the area entered (LOW 1, MODERATE 2, HIGH 4, CRITICAL 10).\n");
            sb.append("Restricted areas and an evacuation zone are never passed through.\n\nDijkstra visit order (min-heap):\n");
            for (String s : smart.visitOrder) sb.append("  ").append(s).append('\n');
            routeOut.setText(sb.toString());
        });
        HBox top = new HBox(8, new Label("From"), routeFrom, new Label("To"), routeTo, go);
        top.setAlignment(Pos.CENTER_LEFT);
        return card("Guidance for staff and signage", top, routeOut);
    }

    /** SOS and evacuation: SOS form, evacuation zone with its live plan, message to all units. */
    private Node emergency() {
        TextField sosText = new TextField();
        sosText.setPromptText("What is happening (e.g. fight, one person injured)");
        Button sos = button("Send SOS", "btn-danger", e -> sosDialog(sosText.getText()));
        sos.setMaxWidth(Double.MAX_VALUE);
        Label sosHelp = new Label("Sends the nearest available unit, alerts every unit within 5 minutes as backup, and takes a unit from a low-priority task if none is free.");
        sosHelp.setWrapText(true);
        sosHelp.getStyleClass().add("sub");
        GridPane sf = grid();
        sf.addRow(0, new Label("Area"), sosArea);
        sf.addRow(1, new Label("Details"), sosText);
        VBox sosCard = card("SOS - person in danger", sosHelp, sf, sos);
        sosCard.setPrefWidth(380);
        sosCard.setMinWidth(320);

        Label evHelp = new Label("Each public area is sent to the nearest exit outside the zone, avoiding crowded areas. Units in the zone are told where to guide people; every other unit is told to keep the exits clear.");
        evHelp.setWrapText(true);
        evHelp.getStyleClass().add("sub");
        Button start = button("Evacuate zone", "btn-danger", e -> {
            if (confirm("Evacuate " + zonePick.getValue() + "? Every unit will be told.")) act(m.evacuate(zonePick.getValue(), officerField.getText()));
        });
        Button end = button("End evacuation", "btn", e -> act(m.endEvacuation(officerField.getText())));
        TextField bc = new TextField();
        bc.setPromptText("Message to every unit on duty");
        HBox.setHgrow(bc, javafx.scene.layout.Priority.ALWAYS);
        Button send = button("Send to all units", "btn-primary", e -> act(m.broadcast(bc.getText(), officerField.getText())));
        Button preview = button("Preview on map", "btn", e -> refresh());
        VBox evCard = card("Evacuation", evHelp, new HBox(8, new Label("Zone"), zonePick, start, end, preview), evacState, evacMapBox, planBox,
            sectionLabel("Message to all units"), new HBox(8, bc, send));
        HBox.setHgrow(evCard, javafx.scene.layout.Priority.ALWAYS);

        // lost persons
        TextArea lostDesc = new TextArea();
        lostDesc.setPromptText("Description: age, clothes, language, who they were with");
        lostDesc.setPrefRowCount(2);
        lostDesc.setWrapText(true);
        ComboBox<String> lostArea = new ComboBox<>();
        lostArea.setPromptText("Last seen at");
        lostArea.setOnShowing(e -> { synchronized (m) { lostArea.getItems().setAll(m.areaTree().keysInOrder().toArray(new String[0])); } });
        TextField lostContact = new TextField();
        lostContact.setPromptText("Reporter contact");
        Button lookout = button("Send lookout", "btn-primary", e -> {
            String code = null;
            synchronized (m) { for (Category c : m.categoryTree().inOrder()) if ("lost-person".equalsIgnoreCase(c.skill)) { code = c.code; break; } }
            if (code == null) { say("This venue has no lost-person category."); return; }
            act(m.reportIncident(code, lostArea.getValue(), lostDesc.getText(), lostContact.getText(), null));
            if (!lostDesc.getText().isBlank()) lostDesc.clear();
        });
        Button foundSomeone = button("Found someone alone...", "btn", e -> {
            String area = lostArea.getValue();
            if (area == null || lostDesc.getText().isBlank()) { say("Choose where and describe the person found (use the fields above)."); return; }
            act(m.foundPerson(area, lostDesc.getText(), lostContact.getText(), officerField.getText()));
        });
        VBox lostCard = card("Lost persons", sub("A lookout goes to every unit; units at exits check people leaving; the search widens with time; escalation at 15 and 30 min."),
            new HBox(8, lostArea, lostContact), lostDesc, new HBox(8, lookout, foundSomeone), lostBox);
        HBox.setHgrow(lostCard, javafx.scene.layout.Priority.ALWAYS);

        // public screens and PA, alarm input
        TextField own = new TextField();
        own.setPromptText("Your own message for screens and the PA");
        HBox.setHgrow(own, javafx.scene.layout.Priority.ALWAYS);
        Button ownSend = button("Send", "btn-primary", e -> { act(m.announce(own.getText(), officerField.getText())); own.clear(); });
        ComboBox<String> alarmArea = new ComboBox<>();
        alarmArea.setPromptText("Alarm at");
        alarmArea.setOnShowing(e -> { synchronized (m) { alarmArea.getItems().setAll(m.areaTree().keysInOrder().toArray(new String[0])); } });
        Button alarm = button("Fire alarm", "btn-danger", e -> { if (alarmArea.getValue() != null && confirm("Raise a fire alarm at " + alarmArea.getValue() + "?")) act(m.alarm(alarmArea.getValue(), "Fire alarm")); });
        VBox signsCard = card("Public screens and PA", sub("Drafted by the system; nothing reaches the public until an officer approves it."), signsBox, new HBox(8, own, ownSend),
            sectionLabel("Alarm input"), new HBox(8, alarmArea, alarm));
        signsCard.setPrefWidth(460);
        signsCard.setMinWidth(380);
        return new VBox(14, proposalBox, new HBox(14, sosCard, evCard), new HBox(14, lostCard, signsCard));
    }

    /** Alerts: filter chips and the full alert list. */
    private Node alerts() {
        for (String f : new String[] { "ALL", "CROWD", "TREND", "AUTO_DISPATCH", "SUPERVISION", "NO_ACK", "SOS", "EVACUATION", "SYSTEM" }) {
            Button b = new Button(f.equals("ALL") ? "All" : f);
            b.getStyleClass().addAll("chip", "chip-neutral");
            b.setUserData(f);
            b.setOnAction(e -> { alertFilter = f; refresh(); });
            alertFilters.getChildren().add(b);
        }
        ScrollPane sp = new ScrollPane(alertList);
        sp.setFitToWidth(true);
        sp.setPrefHeight(560);
        return card("All alerts (newest first)", alertFilters, sp);
    }

    /** Reports made with merge sort, and export to a text file. */
    private Node reports() {
        reportOut.setEditable(false);
        reportOut.getStyleClass().add("mono");
        reportOut.setPrefRowCount(24);
        Button a = button("Open incidents by priority", "btn", e -> report("OPEN INCIDENTS - priority, then longest waiting first", () -> {
            StringBuilder sb = new StringBuilder();
            for (Incident i : m.openIncidentsByPriority())
                sb.append(String.format("%-6s %-9s %-34s %-12s %-9s %-6s %4d min%n", i, i.priority, i.category.label, i.area.id, i.status, i.unitId == null ? "-" : i.unitId, i.minutesOpen(m.now())));
            return sb.toString();
        }));
        Button b = button("Areas by crowd load", "btn", e -> report("AREAS - highest level and occupancy first", () -> {
            StringBuilder sb = new StringBuilder();
            for (Area x : m.areasByLoad()) sb.append(String.format("%-12s %-34s %-9s %6d / %-6d %4d%%%n", x.id, x.name, x.level, x.occupancy, x.capacity, x.percent()));
            return sb.toString();
        }));
        Button c = button("Units", "btn", e -> report("UNITS - status, sector, ID", () -> {
            StringBuilder sb = new StringBuilder();
            for (Unit u : m.unitsSorted()) sb.append(String.format("%-6s %-24s %-12s %-12s %s%n", u.id, u.name, u.status, u.area.id, u.skillList()));
            return sb.toString();
        }));
        Button d = button("Incident log", "btn", e -> report("INCIDENT LOG - by time reported (last 24 hours; older ones are archived)", () -> {
            StringBuilder sb = new StringBuilder();
            for (Incident i : m.incidentsByTime()) sb.append(String.format("%-6s %s  %-34s %-12s %-9s %s%n", i, i.reported.format(HistoryEntry.FORMAT), i.category.label, i.area.id, i.status, i.details));
            return sb.toString();
        }));
        Button x = button("Export to text file...", "btn-primary", e -> {
            FileChooser fc = new FileChooser();
            fc.setInitialFileName(m.venue().code + "-report.txt");
            File f = fc.showSaveDialog(stage);
            if (f == null) return;
            try { Files.writeString(f.toPath(), reportOut.getText()); say("Report saved to " + f); } catch (Exception ex) { say("Not saved: " + ex.getMessage()); }
        });
        Button h = button("Handover report", "btn", e -> { synchronized (m) { reportOut.setText(m.handoverReport()); } });
        Button rc = button("Reset count check", "btn", e -> { if (confirm("Reset the count check? Only after a full recount: the total now becomes the new starting point.")) act(m.resetCountCheck(officerField.getText())); });
        return card("Reports (sorted with merge sort)", new FlowPane(8, 8, a, b, c, d, h, rc, x), reportOut);
    }

    /** Data structures: the live AVL trees, cache, buffers, queues, graph, a heap demo and a merge-sort trace. */
    private Node structures() {
        dsOut.setEditable(false);
        dsOut.getStyleClass().add("mono");
        dsOut.setPrefRowCount(26);
        Button avl = button("AVL trees", "btn", e -> ds(() -> "Unit AVL tree (size " + m.unitTree().size() + ", height " + m.unitTree().height() + ", rotations " + m.unitTree().rotations() + ")\n" + m.unitTree().shape()
            + "\nArea AVL tree (size " + m.areaTree().size() + ", height " + m.areaTree().height() + ")\n" + m.areaTree().shape()
            + "\nIncident AVL tree (size " + m.incidentTree().size() + ", height " + m.incidentTree().height() + ", rotations " + m.incidentTree().rotations() + ")\n" + m.incidentTree().shape()));
        Button cache = button("Distance cache and trends", "btn", e -> ds(() -> {
            StringBuilder sb = new StringBuilder("Distance cache (AVL tree of Dijkstra results): hits " + m.cacheHits() + ", misses " + m.cacheMisses() + "\n\n");
            sb.append("Sampled readings per area (circular buffer, 60 slots, about one per 10 s)\n");
            for (Area a : m.areaTree().inOrder()) sb.append(String.format("  %-12s rate %5d/min  critical in %-7s %s%n", a.id, Math.round(m.ratePerMinute(a)), eta(m.minutesToCritical(a)), a.readings));
            return sb.toString();
        }));
        Button queues = button("Queues", "btn", e -> ds(() -> {
            StringBuilder sb = new StringBuilder();
            for (Priority p : Priority.values()) sb.append(String.format("%-9s %s%n", p, m.queue(p)));
            return sb + "\nDispatch serves CRITICAL first; inside each level, first in first out.";
        }));
        Button graph = button("Area graph", "btn", e -> ds(() -> "Adjacency lists (walk minutes)\n" + m.map().adjacencyText()));
        Button heap = button("Min-heap demo", "btn", e -> ds(() -> {
            MinHeap<Area> h = new MinHeap<>((a, b) -> Integer.compare(b.percent(), a.percent()));
            StringBuilder sb = new StringBuilder("Insert every area into a heap ordered by occupancy % (largest on top):\n");
            for (Area a : m.areaTree().inOrder()) { h.insert(a); sb.append("  insert ").append(a.id).append(" -> ").append(h).append('\n'); }
            sb.append("\nPoll order:\n");
            while (!h.isEmpty()) { Area a = h.poll(); sb.append("  ").append(a.id).append(' ').append(a.percent()).append("%\n"); }
            return sb.toString();
        }));
        Button merge = button("Merge-sort trace", "btn", e -> ds(() -> {
            Area[] a = m.areaTree().inOrder().toArray(new Area[0]);
            LinkedList<String> trace = new LinkedList<>();
            MergeSort.sort(a, (x, y) -> Integer.compare(y.percent(), x.percent()), trace);
            StringBuilder sb = new StringBuilder("Sorting areas by occupancy % (descending):\n");
            for (String s : trace) sb.append(s).append('\n');
            return sb.toString();
        }));
        return card("Data structures at work (live data)", new FlowPane(8, 8, avl, cache, queues, graph, heap, merge), dsOut);
    }

    /** Venue and server: settings of the venue, automation switch, network server control, save. */
    private Node settings() {
        automationBox.setOnAction(e -> {
            Result in = m.officerCheckIn(officerField.getText());
            if (!in.ok) { automationBox.setSelected(m.automation()); act(in); return; }
            m.setAutomation(automationBox.isSelected());
            act(Result.ok("Automatic dispatch " + (automationBox.isSelected() ? "ON" : "OFF") + "."));
        });
        settingsText.setWrapText(true);
        TextField port = new TextField("8080");
        port.setPrefWidth(80);
        PasswordField ctl = new PasswordField();
        ctl.setPromptText("Control key");
        PasswordField scout = new PasswordField();
        scout.setPromptText("Scout key (optional)");
        CheckBox https = new CheckBox("HTTPS (encrypted)");
        Button start = button("Start server", "btn-primary", e -> {
            try {
                if (api != null) api.stop();
                boolean logins = accounts != null && accounts.hasUsers();
                if (ctl.getText().isBlank() && !logins && !confirm("No users and no control key: anyone on the network can read and change everything. Start anyway (demonstration only)?")) return;
                api = new crowdwatch.net.ApiServer(m, ctl.getText(), scout.getText(), crowdwatch.net.ApiServer.RATE_LIMIT);
                api.setAccounts(accounts);
                int p = api.start(Integer.parseInt(port.getText().trim()), https.isSelected() ? App.tls() : null);
                String sc = https.isSelected() ? "https" : "http";
                serverState.setText("Server running on port " + p + ": dashboard " + sc + "://<this-computer>:" + p + "/  scouts " + sc + "://<this-computer>:" + p + "/scout"
                    + (logins ? "  - personal logins on" : ""));
                say("Server started on port " + p + ".");
            } catch (Exception ex) { say("Server not started: " + ex.getMessage()); }
        });
        Button stop = button("Stop server", "btn", e -> { if (api != null) api.stop(); api = null; serverState.setText("Server not started"); say("Server stopped."); });
        Button save = button("Save now", "btn", e -> save());
        serverState.getStyleClass().add("sub");
        GridPane g = grid();
        g.addRow(0, new Label("Port"), port);
        g.addRow(1, new Label("Keys"), new HBox(6, ctl, scout));
        g.addRow(2, new Label(""), https);
        g.addRow(3, new Label(""), new HBox(6, start, stop));
        Button users = button("Users and roles...", "btn", e -> usersDialog());
        Button devices = button("Scout device codes...", "btn", e -> deviceDialog());
        Button audit = button("Audit log...", "btn", e -> auditDialog());
        Label accNote = new Label("Personal logins: each person signs in with a name and password and gets a role (OPERATOR, OFFICER or ADMIN). "
            + "Each scout phone can get its own code that only works for its unit. Every change, here or on the network, is written to the audit log.");
        accNote.setWrapText(true);
        accNote.getStyleClass().add("sub");
        return new VBox(14, card("Venue settings", settingsText, automationBox, save), card("Network server for other terminals and scout devices", g, serverState),
            card("Users, scout devices and audit log", accNote, new HBox(6, users, devices, audit)));
    }

    /** Users and roles: lists the users and adds or changes one. */
    private void usersDialog() {
        if (accounts == null) { say("The accounts file cannot be read: " + App.ACCOUNTS_FILE.toAbsolutePath()); return; }
        StringBuilder list = new StringBuilder();
        for (crowdwatch.net.Accounts.User u : accounts.users()) list.append(u.name).append("  ").append(u.role).append(u.active ? "" : "  (switched off)").append('\n');
        TextArea current = new TextArea(list.length() == 0 ? "No users yet: the first one should be an ADMIN." : list.toString());
        current.setEditable(false);
        current.setPrefRowCount(6);
        TextField name = new TextField();
        ComboBox<crowdwatch.net.Accounts.Role> role = new ComboBox<>(FXCollections.observableArrayList(crowdwatch.net.Accounts.Role.values()));
        role.setValue(crowdwatch.net.Accounts.Role.OPERATOR);
        PasswordField pw = new PasswordField(), pw2 = new PasswordField();
        pw.setPromptText("empty keeps the current password");
        CheckBox active = new CheckBox("Can sign in");
        active.setSelected(true);
        GridPane g = grid();
        g.addRow(0, new Label("User name"), name);
        g.addRow(1, new Label("Role"), role);
        g.addRow(2, new Label("Password"), pw);
        g.addRow(3, new Label("Again"), pw2);
        g.addRow(4, new Label(""), active);
        if (!dialog("Users and roles", new VBox(10, current, g))) return;
        if (!pw.getText().equals(pw2.getText())) { act(Result.fail("The two passwords differ - nothing changed.")); return; }
        String done = accounts.setUser(name.getText(), role.getValue(), pw.getText().isEmpty() ? null : pw.getText());
        if (done == null) done = accounts.setActive(name.getText(), active.isSelected());
        act(done == null ? Result.ok("User " + name.getText().trim() + " saved as " + role.getValue() + (active.isSelected() ? "." : " (switched off).")) : Result.fail(done));
    }

    /** Scout device codes: issues or cancels the code of one unit's phone. */
    private void deviceDialog() {
        if (accounts == null) { say("The accounts file cannot be read."); return; }
        ComboBox<String> unit = new ComboBox<>();
        synchronized (m) { unit.getItems().setAll(m.unitTree().keysInOrder().toArray(new String[0])); }
        if (!unit.getItems().isEmpty()) unit.setValue(unit.getItems().get(0));
        CheckBox cancel = new CheckBox("Cancel the unit's code instead of issuing a new one");
        if (!dialog("Scout device codes", new VBox(10, new Label("Unit"), unit, cancel)) || unit.getValue() == null) return;
        String u = unit.getValue();
        if (cancel.isSelected()) { act(accounts.revokeDevice(u) ? Result.ok("Device code of " + u + " cancelled.") : Result.fail(u + " has no device code.")); return; }
        String code = accounts.issueDevice(u);
        act(Result.ok("New device code issued for " + u + "."));
        TextField shown = new TextField(code);
        shown.setEditable(false);
        shown.setStyle("-fx-font-size: 22px; -fx-font-family: monospace;");
        dialog("Device code for " + u, new VBox(10, new Label("Type this code on the scout phone of " + u + ". It replaces the unit's earlier code."), shown));
    }

    /** Audit log: the newest entries from the venue's database (or from memory). */
    private void auditDialog() {
        String[] lines = null;
        Database d = db;
        if (d != null) try { lines = d.readAudit(300); } catch (Exception ignored) { }
        if (lines == null && accounts != null) {
            crowdwatch.net.Accounts.Entry[] e = accounts.recentAudit(300);
            lines = new String[e.length];
            for (int k = 0; k < e.length; k++) lines[k] = e[k].toString();
        }
        TextArea t = new TextArea(lines == null || lines.length == 0 ? "No entries yet." : String.join("\n", lines));
        t.setEditable(false);
        t.setPrefSize(900, 500);
        t.setStyle("-fx-font-family: monospace;");
        dialog("Audit log (newest first)", t);
    }

    /** Shows content in an OK / Cancel dialog. @return true for OK */
    private boolean dialog(String title, Node content) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        if (stage != null && stage.getScene() != null) d.getDialogPane().getStylesheets().setAll(stage.getScene().getStylesheets());
        return d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    // ================================================================ incident details panel and dialogs

    /** Opens the details panel for an incident. */
    private void openIncident(int num) {
        selected = num;
        drawer.setVisible(true);
        refreshDrawer();
    }

    /** Fills the details panel from the selected incident (under the monitor's lock). */
    private void refreshDrawer() {
        if (selected == null || !drawer.isVisible()) return;
        drawer.getChildren().clear();
        synchronized (m) {
            Incident i = m.findIncident(selected);
            Button close = button("Close", "btn", e -> { drawer.setVisible(false); selected = null; });
            close.setMinWidth(Region.USE_PREF_SIZE);
            if (i == null) { drawer.getChildren().addAll(new Label("I" + selected + " is no longer in memory (archived)."), close); return; }
            Label kicker = new Label("I" + i.id + " - " + i.priority + (i.automatic ? " - AUTOMATIC" : ""));
            kicker.setStyle("-fx-text-fill: white; -fx-font-size: 12px; -fx-font-weight: bold; -fx-opacity: 0.9;");
            Label head = new Label(i.category.label + " at " + i.area.id);
            head.setStyle("-fx-text-fill: white; -fx-font-size: 19px; -fx-font-weight: bold;");
            head.setWrapText(true);
            Label state = new Label(i.status + (i.unitId == null ? "" : " - " + i.unitId) + " - " + deviceText(i));
            state.setStyle("-fx-text-fill: white; -fx-font-size: 13px;");
            state.setWrapText(true);
            close.getStyleClass().setAll("drawer-close");
            close.setText("x");
            Region gap = new Region();
            HBox.setHgrow(gap, javafx.scene.layout.Priority.ALWAYS);
            Color pc = Vivid.color(priorityClass(i.priority));
            VBox headBox = new VBox(4, new HBox(8, kicker, gap, close), head, state);
            headBox.setStyle("-fx-background-color: linear-gradient(to bottom right, " + Vivid.hex(pc) + ", " + Vivid.rgba(pc, 0.6) + "); -fx-padding: 16 18 16 18; -fx-background-radius: 0;");
            FlowPane tags = new FlowPane(6, 6, pill(i.priority.name(), priorityClass(i.priority)), pill(i.status.name(), "GRAY"));
            if (i.automatic) tags.getChildren().add(pill(i.reviewedBy == null ? "AUTOMATIC - needs review" : "AUTOMATIC - reviewed by " + i.reviewedBy, "MODERATE"));
            GridPane facts = grid();
            ColumnConstraints keys = new ColumnConstraints(76);
            ColumnConstraints vals = new ColumnConstraints();
            vals.setHgrow(javafx.scene.layout.Priority.ALWAYS);
            facts.getColumnConstraints().addAll(keys, vals);
            facts.addRow(0, sub("Area"), new Label(i.area.id + " - " + i.area.name));
            facts.addRow(1, sub("Unit"), new Label(i.unitId == null ? "none yet (waiting in queue)" : i.unitId));
            facts.addRow(2, sub("Reported"), new Label(i.reported.format(HistoryEntry.FORMAT)));
            facts.addRow(3, sub("Device"), new Label(deviceText(i)));
            facts.addRow(4, sub("Details"), wrap(i.details.isEmpty() ? "-" : i.details));
            if (i.unitId != null) {
                Unit u = m.findUnit(i.unitId);
                CrowdMonitor.Route r = u == null ? null : m.responderRoute(u.area.id, i.area.id);
                facts.addRow(5, sub("Route"), wrap(u == null ? "-" : u.area == i.area ? "the unit is there" : r.found() ? r.path + " (" + r.walk + " min)" : "ask the control room"));
            }
            VBox hist = new VBox(0);
            hist.getStyleClass().add("timeline");
            int left = i.history.size();
            for (HistoryEntry h : i.history) {
                javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(6, --left == 0 ? Vivid.color("LOW") : Color.web("#2F7CF6"));
                dot.setTranslateX(-7);
                Label t = new Label(h.time.format(HistoryEntry.FORMAT));
                t.getStyleClass().add("sub");
                HBox item = new HBox(6, dot, new VBox(1, t, wrap(h.text)));
                item.setPadding(new Insets(0, 0, 10, 0));
                hist.getChildren().add(item);
            }
            FlowPane actions = new FlowPane(6, 6);
            int id = i.id;
            if (i.automatic && i.reviewedBy == null && i.open()) actions.getChildren().addAll(
                button("Confirm dispatch", "btn-primary", e -> act(m.review(id, officerField.getText(), true, ""))),
                button("False alarm", "btn", e -> act(m.review(id, officerField.getText(), false, ""))));
            if (i.open()) actions.getChildren().addAll(
                button("Acknowledged by radio", "btn", e -> { Incident x = m.findIncident(id); act(x == null || x.unitId == null ? Result.fail("No unit assigned.") : m.acknowledge(x.unitId)); }),
                button("On scene", "btn", e -> act(m.confirmOnScene(id))),
                button("Reassign...", "btn", e -> reassignDialog(id)),
                button("Escalate", "btn", e -> act(m.escalate(id))),
                button("Resolve...", "btn-primary", e -> resolveDialog(id)));
            VBox inner = new VBox(12, tags, facts, actions, sectionLabel("Timeline"), hist);
            inner.setPadding(new Insets(16, 18, 18, 18));
            ScrollPane sp = new ScrollPane(inner);
            sp.setFitToWidth(true);
            VBox.setVgrow(sp, javafx.scene.layout.Priority.ALWAYS);
            drawer.getChildren().addAll(headBox, sp);
        }
    }

    /** Dialog to report an incident. */
    private void reportDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Report incident");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ComboBox<String> cat = new ComboBox<>(), area = new ComboBox<>();
        synchronized (m) {
            for (Category c : m.categoryTree().inOrder()) cat.getItems().add(c.code);
            for (Area a : m.areaTree().inOrder()) area.getItems().add(a.id);
        }
        cat.getSelectionModel().selectFirst();
        area.getSelectionModel().selectFirst();
        TextField details = new TextField(), contact = new TextField(), lang = new TextField();
        GridPane g = grid();
        g.addRow(0, new Label("Category"), cat);
        g.addRow(1, new Label("Area"), area);
        g.addRow(2, new Label("Details"), details);
        g.addRow(3, new Label("Contact"), contact);
        g.addRow(4, new Label("Language"), lang);
        d.getDialogPane().setContent(g);
        d.getDialogPane().getStylesheets().add(FxApp.class.getResource("crowdwatch.css").toExternalForm());
        if (d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK)
            act(m.reportIncident(cat.getValue(), area.getValue(), details.getText(), contact.getText(), lang.getText()));
    }

    /** Asks for confirmation, then sends an SOS for the chosen area. */
    private void sosDialog(String text) {
        String area = sosArea.getValue();
        if (area == null) { show("emergency"); return; }
        if (!confirm("Send SOS for " + area + "?\nThe nearest available unit is sent at once and every unit within 5 minutes' walk is alerted. If a life is at risk, also call the emergency services.")) return;
        act(m.sos(area, null, text == null ? "" : text));
    }

    /** Dialog to hand an incident to another unit. */
    private void reassignDialog(int id) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Reassign I" + id);
        d.setHeaderText("Unit ID to take over (must be AVAILABLE and have the skill)");
        d.showAndWait().ifPresent(u -> act(m.reassign(id, u, "reassigned from the console")));
    }

    /** Dialog for the closing note of an incident. */
    private void resolveDialog(int id) {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Resolve I" + id);
        d.setHeaderText("Closing note (optional)");
        d.showAndWait().ifPresent(n -> act(m.resolve(id, n)));
    }

    /** Dialog to add a unit. */
    private void addUnitDialog() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Add unit");
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField id = new TextField(), name = new TextField(), skills = new TextField();
        ComboBox<String> area = new ComboBox<>(FXCollections.observableArrayList(moveTo.getItems()));
        area.getSelectionModel().selectFirst();
        GridPane g = grid();
        g.addRow(0, new Label("Unit ID"), id);
        g.addRow(1, new Label("Name"), name);
        g.addRow(2, new Label("Area"), area);
        g.addRow(3, new Label("Skills and languages"), skills);
        d.getDialogPane().setContent(g);
        if (d.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) act(m.addUnit(id.getText(), name.getText(), area.getValue(), skills.getText().split(",")));
    }

    /** Jumps to an area, unit or incident by ID. */
    private void search(String text) {
        String q = text == null ? "" : text.trim().toUpperCase();
        if (q.isEmpty()) return;
        synchronized (m) {
            if (m.findArea(q) != null) { show("areas"); chartArea = q; areaPick.setValue(q); refreshChart(); return; }
            if (m.findUnit(q) != null) { selectedUnit = q; show("units"); return; }
            String digits = q.replaceAll("[^0-9]", "");
            if (!digits.isEmpty() && m.findIncident(Integer.parseInt(digits)) != null) { show("incidents"); openIncident(Integer.parseInt(digits)); return; }
        }
        say("Nothing called " + q + ".");
    }

    // ================================================================ refresh

    /** Called on the monitor's event thread after a change: queues one refresh on the JavaFX thread. */
    private void scheduleRefresh() {
        if (refreshPending.compareAndSet(false, true)) Platform.runLater(() -> { refreshPending.set(false); refresh(); });
    }

    /** Reloads what depends on the venue: title, drop-downs and settings. */
    private void venueChanged() {
        synchronized (m) {
            venueLabel.setText(m.venueName() + (db == null ? "  [NOT SAVED - memory only]" : ""));
            stage.setTitle("CrowdWatch console - " + m.venueName());
            ObservableList<String> ids = FXCollections.observableArrayList();
            ObservableList<String> zones = FXCollections.observableArrayList();
            Area firstPublic = null, lastPublic = null;
            for (Area a : m.areaTree().inOrder()) {
                ids.add(a.id);
                if (!zones.contains(a.sector)) zones.add(a.sector);
                if (!a.restricted) { if (firstPublic == null) firstPublic = a; lastPublic = a; }
            }
            zones.addAll(ids);
            for (ComboBox<String> c : java.util.List.of(areaPick, moveTo, routeFrom, routeTo, sosArea)) c.setItems(FXCollections.observableArrayList(ids));
            zonePick.setItems(zones);
            zonePick.getSelectionModel().selectFirst();
            String first = firstPublic == null ? null : firstPublic.id;
            areaPick.setValue(first);
            sosArea.setValue(first);
            routeFrom.setValue(first);
            routeTo.setValue(lastPublic == null ? null : lastPublic.id);
            moveTo.setValue(first);
            chartArea = first;
            VenueProfile v = m.venue();
            int[] t = m.thresholds();
            settingsText.setText("Venue: " + v.name + " (code " + v.code + ")\n"
                + "Thresholds: MODERATE " + t[0] + "%, HIGH " + t[1] + "%, CRITICAL " + t[2] + "%\n"
                + "Automatic dispatch: " + (v.autoCategory == null ? "not configured (add an auto: line)" : v.autoCategory + " at " + v.autoLevel + (v.autoOnTrend ? " or on a TREND warning" : "")) + "\n"
                + "Officer check-in: every " + v.supervisionMinutes + " min; unit acknowledgement: within " + v.ackMinutes + " min\n"
                + "Exits for evacuation: " + (v.exits.isEmpty() ? "none (add an exit: line)" : String.join(", ", v.exits.toArray(new String[0]))) + "\n"
                + "Archive resolved incidents after: " + (v.archiveMinutes % 60 == 0 ? v.archiveMinutes / 60 + " h" : v.archiveMinutes + " min") + "\n"
                + "Radio gateway: " + (v.gateway == null ? "none" : v.gateway) + "\n"
                + "Database: " + (db == null ? "NONE - running in memory, nothing is saved" : "crowdwatch-data/" + Database.safeName(v.code) + ", autosave every 60 s"));
            if (officerField.getText().isBlank() && m.officer() != null) officerField.setText(m.officer());
        }
        refresh();
    }

    /** Redraws everything visible from the monitor's current data, under its lock. */
    private void refresh() {
        if (m == null || views == null) return;
        synchronized (m) {
            crumb.setText("CrowdWatch  >  " + m.venueName() + "  >  " + titleLabel.getText());
            if (officerField.getText().isBlank() && m.officer() != null && !officerField.isFocused()) officerField.setText(m.officer());
            refreshBanners();
            refreshChips();
            alertBadge.setText(String.valueOf(Math.min(99, m.alerts().size())));
            IncRow[] inc = incidentRows();
            switch (view) {
                case "overview" -> refreshOverview(inc);
                case "areas" -> { keepSelection(areaTable, areaRows(), AreaRow::id); refreshChart(); }
                case "incidents" -> keepSelection(incTable, inc, IncRow::id);
                case "units" -> refreshUnits();
                case "emergency" -> refreshEmergency();
                case "alerts" -> refreshAlerts();
                case "settings" -> automationBox.setSelected(m.automation());
                default -> { }
            }
            refreshDrawer();
        }
    }

    /** Evacuation and supervision banners under the top bar. */
    private void refreshBanners() {
        banners.getChildren().clear();
        if (m.evacuationZone() != null) banners.getChildren().add(banner("EVACUATION IN PROGRESS: " + m.evacuationZone() + "  -  see SOS and evacuation", "banner-evac"));
        if (m.proposal() != null) banners.getChildren().add(banner("EVACUATION PROPOSED for " + m.proposal()[0] + ": " + m.proposal()[1] + "  -  decide in SOS and evacuation", "banner-bad"));
        if (m.officer() == null || m.officerStatus().contains("OVERDUE")) banners.getChildren().add(banner("SECURITY OFFICER CHECK-IN NEEDED (" + m.officerStatus() + ") - enter your name at the top right and press Check in", "banner-bad"));
    }

    /** Status chips: officer, automation, reviews, unacknowledged tasks, unreachable units. */
    private void refreshChips() {
        chips.getChildren().clear();
        chips.getChildren().add(chip("Officer: " + m.officerStatus(), m.officer() == null || m.officerStatus().contains("OVERDUE") ? "chip-bad" : "chip-ok", null));
        chips.getChildren().add(chip("Automatic dispatch " + (m.automation() ? "ON" : "OFF"), m.automation() ? "chip-ok" : "chip-warn", "settings"));
        int review = m.awaitingReview().length;
        if (review > 0) chips.getChildren().add(chip(review + " automatic dispatch(es) awaiting review", "chip-warn", "incidents"));
        int unack = 0, unreachable = 0;
        for (Incident i : m.openIncidentsByPriority()) if (i.status == IncidentStatus.ASSIGNED && i.acknowledged == null) unack++;
        for (Unit u : m.unitsSorted()) if (u.status == UnitStatus.UNREACHABLE) unreachable++;
        if (unack > 0) chips.getChildren().add(chip(unack + " task(s) not yet acknowledged", "chip-warn", "incidents"));
        if (unreachable > 0) chips.getChildren().add(chip(unreachable + " unit(s) UNREACHABLE", "chip-bad", "units"));
        if (db == null) chips.getChildren().add(chip("NOT SAVED - memory only", "chip-bad", "settings"));
        int missing = 0, doubtful = 0;
        for (Incident i : m.lostCases()) if (i.foundAt == null) missing++;
        for (Area a : m.areaTree().inOrder()) if (a.staleSince != null || a.suspect != null) doubtful++;
        if (m.proposal() != null) chips.getChildren().add(chip("Evacuation proposed for " + m.proposal()[0], "chip-bad", "emergency"));
        if (missing > 0) chips.getChildren().add(chip(missing + " lost person(s) missing", "chip-bad", "emergency"));
        if (m.pendingAnnouncements().length > 0) chips.getChildren().add(chip(m.pendingAnnouncements().length + " public message(s) to approve", "chip-warn", "emergency"));
        if (doubtful > 0) chips.getChildren().add(chip(doubtful + " area(s) with doubtful sensor data", "chip-neutral", "areas"));
        long[] cc = m.countCheck();
        if (cc != null && m.venue().reconcilePercent > 0 && Math.abs(cc[2]) > Math.max(50, Math.abs(cc[0]) * m.venue().reconcilePercent / 100))
            chips.getChildren().add(chip("Counts do not add up: areas " + String.format("%,d", cc[1]) + ", gates " + String.format("%,d", cc[0]) + " - recount", "chip-bad", "reports"));
        for (String ev : m.activeEvents()) chips.getChildren().add(chip("Scheduled peak: " + ev, "chip-neutral", null));
    }

    /** Overview numbers, map tiles, short alert list and the open incidents. */
    private void refreshOverview(IncRow[] inc) {
        int people = 0, cap = 0, hot = 0, open = 0, critical = 0, waiting = m.waitingCount(), avail = 0, onDuty = 0;
        for (Area a : m.areaTree().inOrder()) { people += a.occupancy; cap += a.capacity; if (a.level.compareTo(CrowdLevel.HIGH) >= 0) hot++; }
        for (Incident i : m.openIncidentsByPriority()) { open++; if (i.priority == Priority.CRITICAL) critical++; }
        for (Unit u : m.unitTree().inOrder()) { if (u.status == UnitStatus.AVAILABLE) avail++; if (u.status != UnitStatus.OFF_DUTY) onDuty++; }
        setKpi(0, String.format("%,d", people), (cap == 0 ? 0 : Math.round(people * 100.0 / cap)) + "% of " + String.format("%,d", cap) + " capacity");
        setKpi(1, String.valueOf(hot), "of " + m.areaTree().size() + " areas");
        setKpi(2, String.valueOf(open), critical + " critical, " + waiting + " waiting");
        setKpi(3, String.valueOf(avail), "of " + onDuty + " on duty");
        setKpi(4, String.valueOf(m.awaitingReview().length), "automatic dispatches");
        double[] now = { people, hot, open, avail, m.awaitingReview().length };
        long ms = System.currentTimeMillis();
        if (histCount == 0 || ms - histAt >= 3000) {          // one sample every 3 seconds at most
            histAt = ms;
            for (int k = 0; k < 5; k++) {
                if (histCount == HIST) System.arraycopy(kpiHist[k], 1, kpiHist[k], 0, HIST - 1);
                kpiHist[k][Math.min(histCount, HIST - 1)] = now[k];
            }
            if (histCount < HIST) histCount++;
        }
        for (int k = 0; k < 5; k++) {
            double d = kpiHist[k][histCount - 1] - kpiHist[k][0];
            String txt = histCount < 2 ? "live" : d == 0 ? "steady" : k == 0 && kpiHist[k][0] > 0
                ? (d > 0 ? "+" : "") + Math.round(d * 100 / kpiHist[k][0]) + "%" : (d > 0 ? "+" : "") + Math.round(d);
            kpiDelta[k].setText(txt);
            kpiSpark[k].getChildren().setAll(Vivid.sparkline(kpiHist[k], histCount, Vivid.color(KPI_LEVEL[k]), 178, 30));
        }

        mapBox.getChildren().clear();
        LinkedList<String> sectors = new LinkedList<>();
        for (Area a : m.areaTree().inOrder()) if (!sectors.contains(a.sector)) sectors.addLast(a.sector);
        for (String s : sectors) {
            FlowPane tiles = new FlowPane(6, 6);
            tiles.setAlignment(Pos.CENTER_LEFT);
            LinkedList<Area> lane = walkOrder(s);
            int k = 0;
            for (Area a : lane) {
                tiles.getChildren().add(tile(a));
                if (++k < lane.size()) tiles.getChildren().add(Vivid.icon(Vivid.I_CHEV, Color.web("#93A4C2"), 16));
            }
            Label sl = new Label(s.toUpperCase());
            sl.getStyleClass().add("lane-name");
            mapBox.getChildren().addAll(sl, tiles);
        }
        refreshTopChart();
        Node vm = venueMap(m.evacuationZone() != null ? m.evacuationRows(null) : null, 1100);
        mapCard.setVisible(vm != null);
        mapCard.setManaged(vm != null);
        if (vm != null) venueMapBox.getChildren().setAll(vm);
        donutBox.getChildren().setAll(
            Vivid.donut(new String[] { "Available", "Busy", "Unreachable", "Off duty" }, unitCounts(), new String[] { "LOW", "NORMAL", "CRITICAL", "GRAY" }, "units"),
            Vivid.donut(new String[] { "Critical", "High", "Normal", "Low" }, priorityCounts(), new String[] { "CRITICAL", "HIGH", "NORMAL", "MODERATE" }, "open"));
        alertsShort.getChildren().clear();
        int n = 0;
        for (Alert a : m.alerts()) { if (n++ == 7) break; alertsShort.getChildren().add(alertRow(a)); }
        keepSelection(overviewTable, openOnly(inc), IncRow::id);
    }

    /** @return the areas of a sector in walking order: start at an end of the chain and follow the links */
    private LinkedList<Area> walkOrder(String sector) {
        LinkedList<Area> in = new LinkedList<>(), out = new LinkedList<>();
        for (Area a : m.areaTree().inOrder()) if (a.sector.equals(sector)) in.addLast(a);
        LinkedList<String> done = new LinkedList<>();
        while (out.size() < in.size()) {
            Area cur = null;
            for (Area a : in) if (!done.contains(a.id) && (cur == null || links(a, sector) < links(cur, sector))) cur = a;
            while (cur != null) {
                done.addLast(cur.id);
                out.addLast(cur);
                Area next = null;
                for (crowdwatch.ds.Graph.Edge e : m.map().neighbours(m.map().indexOf(cur.id))) {
                    Area b = m.findArea(m.map().name(e.to));
                    if (b != null && b.sector.equals(sector) && !done.contains(b.id) && (next == null || links(b, sector) < links(next, sector))) next = b;
                }
                cur = next;
            }
        }
        return out;
    }

    /** @return how many walking links an area has to areas of the same sector */
    private int links(Area a, String sector) {
        int n = 0;
        for (crowdwatch.ds.Graph.Edge e : m.map().neighbours(m.map().indexOf(a.id))) {
            Area b = m.findArea(m.map().name(e.to));
            if (b != null && b.sector.equals(sector)) n++;
        }
        return n;
    }

    /** @return one floor-plan tile: ID and level, percentage, a fill bar and the head count */
    private Node tile(Area a) {
        int eta = m.minutesToCritical(a);
        boolean rising = eta > 0 && eta <= 30 && a.level != CrowdLevel.CRITICAL && m.ratePerMinute(a) > 0;
        Label id = new Label(a.id);
        id.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        id.setMinWidth(0);
        String doubt = a.staleSince != null ? "STALE" : a.suspect != null ? "CHECK" : null;
        Label lv = new Label(a.evacuating ? "EVAC" : doubt != null ? doubt : a.level.name());
        lv.setStyle("-fx-font-weight: bold; -fx-font-size: 10px;");
        lv.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, javafx.scene.layout.Priority.ALWAYS);
        HBox top = new HBox(4, id, gap, lv);
        top.setAlignment(Pos.BASELINE_LEFT);
        Label pct = new Label(a.percent() + "%");
        pct.getStyleClass().add("tile-pct");
        Label n = new Label(String.format("%,d / %,d", a.occupancy, a.capacity) + (rising ? "  +" + Math.round(m.ratePerMinute(a)) + "/min" : ""));
        n.setStyle("-fx-font-size: 11px; -fx-opacity: 0.85;");
        VBox box = new VBox(3, top, pct, Vivid.bar(a.percent() / 100.0, Vivid.color(a.level.name()), 116), n);
        int wanted = crowdwatch.core.AutoRulesProbe.target(m, a.id);
        if (wanted > 0) {
            int here = crowdwatch.core.AutoRulesProbe.unitsAt(m, a.id);
            Label units = new Label(here + " of " + wanted + " units");
            units.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;" + (here < wanted ? " -fx-text-fill: #FF9AA4;" : ""));
            box.getChildren().add(units);
        }
        box.setPrefWidth(116);
        Button tile = new Button();
        tile.setGraphic(box);
        tile.getStyleClass().addAll("tile", "lvl-" + (doubt != null ? "GRAY" : a.level.name()));
        if (rising) tile.getStyleClass().add("rising");
        if (a.evacuating) tile.getStyleClass().add("evac");
        if (a.level.compareTo(CrowdLevel.HIGH) >= 0) tile.setEffect(Vivid.PULSE_RED);
        else if (rising) tile.setEffect(Vivid.PULSE_AMBER);
        Tooltip.install(tile, new Tooltip(a.name + (rising ? " - CRITICAL in about " + eta + " min" : "") + (a.staleSince != null ? " - no sensor data: count may be out of date" : "") + (a.suspect != null ? " - sensor count doubtful: " + a.suspect : "") + (a.flowHeld ? " - flow held" : "")));
        String aid = a.id;
        tile.setOnAction(e -> { show("areas"); chartArea = aid; areaPick.setValue(aid); refreshChart(); });
        return tile;
    }

    /** Fills the overview chart with the three busiest areas (percent of capacity) and the HIGH and CRITICAL lines. */
    private void refreshTopChart() {
        Area[] top = new Area[3];
        for (Area a : m.areaTree().inOrder()) {
            for (int k = 0; k < 3; k++) {
                if (top[k] == null || a.percent() > top[k].percent()) {
                    for (int j = 2; j > k; j--) top[j] = top[j - 1];
                    top[k] = a;
                    break;
                }
            }
        }
        java.time.LocalDateTime now = m.now();
        java.util.List<XYChart.Series<Number, Number>> list = new java.util.ArrayList<>();
        double oldest = -5;                     // at least five minutes wide, like the web console
        StringBuilder legend = new StringBuilder();
        String[] names = { "orange", "violet", "blue" };
        for (int k = 0; k < 3; k++) {
            XYChart.Series<Number, Number> s = new XYChart.Series<>();
            Area a = top[k];
            if (a != null) {
                for (int r = 0; r < a.readings.size(); r++) {
                    Reading rd = a.readings.get(r);
                    double min = -java.time.Duration.between(rd.time, now).getSeconds() / 60.0;
                    if (min < -60) continue;
                    oldest = Math.min(oldest, min);
                    s.getData().add(new XYChart.Data<>(min, a.capacity == 0 ? 0 : rd.occupancy * 100.0 / a.capacity));
                }
                s.getData().add(new XYChart.Data<>(0, a.percent()));
                legend.append(legend.length() > 0 ? "     " : "").append(names[k]).append(": ").append(a.id).append(' ').append(a.percent()).append('%');
            }
            list.add(s);
        }
        int[] t = m.thresholds();
        for (int k = 1; k <= 2; k++) {
            XYChart.Series<Number, Number> s = new XYChart.Series<>();
            s.getData().add(new XYChart.Data<>(Math.floor(oldest), t[k]));
            s.getData().add(new XYChart.Data<>(0, t[k]));
            list.add(s);
        }
        NumberAxis x = (NumberAxis) topChart.getXAxis();
        x.setAutoRanging(false);
        x.setLowerBound(Math.floor(oldest));
        x.setUpperBound(0);
        x.setTickUnit(Math.max(1, Math.ceil(-oldest / 6)));
        topLegend.setText(legend + "     dashed: HIGH " + t[1] + "% and CRITICAL " + t[2] + "%");
        topChart.getData().setAll(list);
    }

    /** @return units per status: available, busy, unreachable, off duty */
    private int[] unitCounts() {
        int[] c = new int[4];
        for (Unit u : m.unitTree().inOrder()) c[switch (u.status) { case AVAILABLE -> 0; case BUSY -> 1; case UNREACHABLE -> 2; default -> 3; }]++;
        return c;
    }

    /** @return open incidents per priority: critical, high, normal, low */
    private int[] priorityCounts() {
        int[] c = new int[4];
        for (Incident i : m.openIncidentsByPriority()) c[i.priority.ordinal()]++;
        return c;
    }

    /**
     * Draws the venue drawing with every area on it in its crowd colour, the number of units there, the
     * exits, and (if rows are given) the evacuation routes as arrows, thicker for more people.
     * Called under the monitor's lock.
     *
     * @param rows  evacuation rows {area, exit, path, walk, zone, people, clear}, or null for none
     * @param width width to show it at, in pixels
     * @return the map, or null if the venue has no layout
     */
    private Node venueMap(LinkedList<String[]> rows, double width) {
        VenueProfile v = m.venue();
        if (v == null || v.positions.isEmpty() || v.mapWidth <= 0) return null;
        javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane();
        pane.setPrefSize(v.mapWidth, v.mapHeight);
        if (!v.code.equals(mapImageFor)) {
            byte[] b = v.mapBytes();
            mapImage = b == null ? null : new javafx.scene.image.Image(new java.io.ByteArrayInputStream(b));
            mapImageFor = v.code;
        }
        if (mapImage != null) {
            javafx.scene.image.ImageView iv = new javafx.scene.image.ImageView(mapImage);
            iv.setFitWidth(v.mapWidth);
            iv.setFitHeight(v.mapHeight);
            pane.getChildren().add(iv);
        } else pane.setStyle("-fx-background-color: -cw-tint;");
        double fs = Math.max(11, v.mapWidth / 95.0);
        java.util.Map<String, double[]> centre = new java.util.HashMap<>();
        java.util.Map<String, Integer> unitsAt = new java.util.HashMap<>();
        for (Unit u : m.unitTree().inOrder()) if (u.status != UnitStatus.OFF_DUTY) unitsAt.merge(u.area.id, 1, Integer::sum);
        for (String[] p : v.positions) {
            Area a = m.findArea(p[0]);
            if (a == null) continue;
            double x = Double.parseDouble(p[1]), y = Double.parseDouble(p[2]), w = Double.parseDouble(p[3]), h = Double.parseDouble(p[4]);
            centre.put(a.id, new double[] { x + w / 2, y + h / 2 });
            String lv = a.restricted || a.staleSince != null || a.suspect != null ? "GRAY" : a.level.name();
            Color c = Vivid.color(lv);
            boolean hot = !a.restricted && a.level.compareTo(CrowdLevel.HIGH) >= 0, exit = v.exits.contains(a.id);
            javafx.scene.shape.Rectangle r = new javafx.scene.shape.Rectangle(x, y, w, h);
            r.setArcWidth(16);
            r.setArcHeight(16);
            r.setFill(Color.color(c.getRed(), c.getGreen(), c.getBlue(), a.restricted ? 0.12 : hot ? 0.45 : 0.26));
            r.setStroke(c);
            r.setStrokeWidth(hot ? 4 : 2);
            if (exit || a.evacuating) r.getStrokeDashArray().addAll(exit ? 10.0 : 4.0, exit ? 6.0 : 4.0);
            if (hot) r.setEffect(Vivid.PULSE_RED);
            javafx.scene.text.Text id = new javafx.scene.text.Text(x + 7, y + fs + 4, a.id);
            id.setFont(javafx.scene.text.Font.font(null, javafx.scene.text.FontWeight.BOLD, fs));
            id.setFill(Color.WHITE);
            pane.getChildren().addAll(r, id);
            if (!a.restricted) {
                javafx.scene.text.Text pc = new javafx.scene.text.Text(x + 7, y + 2 * fs + 10, a.percent() + "%");
                pc.setFont(javafx.scene.text.Font.font(null, javafx.scene.text.FontWeight.BOLD, fs * 1.25));
                pc.setFill(c.brighter());
                pane.getChildren().add(pc);
            }
            if (exit) {
                javafx.scene.text.Text ex = new javafx.scene.text.Text(x + 7, y + h - 8, "EXIT" + (m.exitFlow(a.id) > 0 ? " " + String.format("%,d", m.exitFlow(a.id)) + "/min" : ""));
                ex.setFont(javafx.scene.text.Font.font(null, javafx.scene.text.FontWeight.BOLD, fs));
                ex.setFill(Color.web("#86EFAC"));
                pane.getChildren().add(ex);
            }
            Integer n = unitsAt.get(a.id);
            if (n != null) {
                javafx.scene.shape.Circle badge = new javafx.scene.shape.Circle(x + w - fs, y + fs, fs * 0.85, Color.web("#2F7CF6"));
                javafx.scene.text.Text t = new javafx.scene.text.Text(String.valueOf(n));
                t.setFont(javafx.scene.text.Font.font(null, javafx.scene.text.FontWeight.BOLD, fs));
                t.setFill(Color.WHITE);
                t.setX(x + w - fs - t.getLayoutBounds().getWidth() / 2);
                t.setY(y + fs * 1.35);
                pane.getChildren().addAll(badge, t);
            }
            Tooltip.install(r, new Tooltip(a.name + (a.restricted ? " - staff only" : " - " + a.percent() + "% " + a.level) + (n != null ? " - " + n + " unit(s)" : "")));
            String aid = a.id;
            r.setOnMouseClicked(e -> { show("areas"); chartArea = aid; areaPick.setValue(aid); refreshChart(); });
            r.setCursor(javafx.scene.Cursor.HAND);
        }
        if (rows != null) {
            java.util.Set<String> drawn = new java.util.HashSet<>();
            for (String[] row : rows) {
                if (!drawn.add(row[2])) continue;
                String[] ids = row[2].replaceAll("[\\[\\]]", "").split(" -> ");
                if (ids.length < 2) continue;
                javafx.scene.shape.Polyline line = new javafx.scene.shape.Polyline();
                for (String s : ids) { double[] c = centre.get(s.trim()); if (c != null) line.getPoints().addAll(c[0], c[1]); }
                if (line.getPoints().size() < 4) continue;
                int people = row[5] == null || !row[5].matches("\\d+") ? 0 : Integer.parseInt(row[5]);
                Color col = "zone".equals(row[4]) ? Vivid.color("HIGH") : Vivid.color("LOW");
                line.setStroke(col);
                line.setStrokeWidth(people > 0 ? Math.max(3, Math.min(14, Math.log(people + 1) * 1.4)) : 2);
                line.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
                line.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
                line.setOpacity(0.9);
                // arrow head at the exit
                int k = line.getPoints().size();
                double x2 = line.getPoints().get(k - 2), y2 = line.getPoints().get(k - 1), x1 = line.getPoints().get(k - 4), y1 = line.getPoints().get(k - 3);
                double ang = Math.atan2(y2 - y1, x2 - x1), s = 18;
                javafx.scene.shape.Polygon head = new javafx.scene.shape.Polygon(x2, y2, x2 - s * Math.cos(ang - 0.45), y2 - s * Math.sin(ang - 0.45), x2 - s * Math.cos(ang + 0.45), y2 - s * Math.sin(ang + 0.45));
                head.setFill(col);
                Tooltip.install(line, new Tooltip(row[0] + " -> " + row[1] + (people > 0 ? ": " + String.format("%,d", people) + " people" + (row[6] != null && row[6].matches("\\d+") && m.evacuationClearMinutesKnown() ? ", out in about " + row[6] + " min" : "") : "")));
                pane.getChildren().addAll(line, head);
            }
        }
        double k = width / v.mapWidth;
        Group g = new Group(pane);
        g.getTransforms().add(new javafx.scene.transform.Scale(k, k));
        return new Group(g);
    }

    /** Unit cards and the chosen unit's history. */
    private void refreshUnits() {
        unitCards.getChildren().clear();
        for (UnitRow u : unitRows()) {
            Label name = new Label(u.id() + " - " + u.name());
            name.setStyle("-fx-font-weight: bold");
            Region gap = new Region();
            HBox.setHgrow(gap, javafx.scene.layout.Priority.ALWAYS);
            Label dev = new Label(u.device());
            dev.getStyleClass().add("pill");
            dev.getStyleClass().add(u.online() ? "lvl-LOW" : "lvl-CRITICAL");
            VBox body = new VBox(6, new HBox(8, name, gap, pill(u.status(), u.status().equals("AVAILABLE") ? "LOW" : u.status().equals("UNREACHABLE") ? "CRITICAL" : u.status().equals("OFF_DUTY") ? "GRAY" : "NORMAL")),
                sub("At " + u.area()), wrap(u.task()), dev, sub(u.skills()));
            body.getStyleClass().add("kpi");
            body.setPrefWidth(290);
            body.setMaxHeight(Region.USE_PREF_SIZE);
            if (u.id().equals(selectedUnit)) body.setStyle("-fx-border-color: -cw-accent; -fx-border-width: 2");
            body.setOnMouseClicked(e -> { selectedUnit = u.id(); refresh(); });
            unitCards.getChildren().add(body);
        }
        Unit su = selectedUnit == null ? null : m.findUnit(selectedUnit);
        if (su == null) { unitHistory.setText("Select a unit card."); return; }
        StringBuilder sb = new StringBuilder(su.id + " " + su.name + "\nMessages to the device (newest first):\n");
        for (int k = su.inbox.size() - 1; k >= 0; k--) sb.append("  ").append(su.inbox.get(k)).append('\n');
        sb.append("\nHistory:\n");
        for (HistoryEntry h : su.history) sb.append("  ").append(h).append('\n');
        unitHistory.setText(sb.toString());
    }

    /** Evacuation state and plan. */
    private void refreshEmergency() {
        planBox.getChildren().clear();
        String zone = m.evacuationZone();
        evacState.setText(zone == null ? "No evacuation in progress. The plan appears here when one starts." : "EVACUATION of " + zone + " in progress. Plan sent to every unit:");
        evacState.setStyle(zone == null ? "" : "-fx-font-weight: bold; -fx-text-fill: #C24E00");
        for (String line : m.evacuationPlan()) planBox.getChildren().add(wrap(line));
        // the plan on the drawing: the live one, or a preview for the chosen zone
        String pz = zone != null ? null : zonePick.getValue();
        LinkedList<String[]> rows = zone != null || pz != null ? m.evacuationRows(pz) : null;
        Node em = rows == null || rows.isEmpty() ? null : venueMap(rows, 740);
        evacMapBox.getChildren().clear();
        if (em != null) {
            int clear = 0;
            for (String[] r : rows) if ("zone".equals(r[4]) && r[6] != null && r[6].matches("\\d+") && m.evacuationClearMinutesKnown()) clear = Math.max(clear, Integer.parseInt(r[6]));
            Label est = new Label(zone != null ? "Live plan" + (clear > 0 ? ": the zone is clear in about " + clear + " min" : "") : "Preview for " + pz + (clear > 0 ? ": the zone is clear in about " + clear + " min" : "") + " (nothing has been started)");
            est.setStyle("-fx-font-weight: bold;");
            evacMapBox.getChildren().addAll(est, em);
        }
        proposalBox.getChildren().clear();
        String[] p = m.proposal();
        if (p != null) {
            Label t = new Label("EVACUATION PROPOSED for " + p[0] + ": " + p[1] + " (since " + p[2] + "). The system never starts an evacuation by itself.");
            t.setWrapText(true);
            t.setStyle("-fx-font-weight: bold;");
            HBox.setHgrow(t, javafx.scene.layout.Priority.ALWAYS);
            HBox row = new HBox(10, t, button("Start evacuation", "btn-danger", e -> act(m.decideProposal(true, officerField.getText()))),
                button("Dismiss", "btn", e -> act(m.decideProposal(false, officerField.getText()))));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("card");
            row.setStyle("-fx-border-color: #FF4D5E;");
            proposalBox.getChildren().add(row);
        }
        // rebuild the lost-person and sign lists only when they changed, so a half-made choice is not lost
        StringBuilder sig = new StringBuilder();
        for (Incident i : m.lostCases()) sig.append(i.id).append(i.foundAt != null).append(i.minutesOpen(m.now())).append(i.unitId).append(i.searchMinutes).append('|');
        for (crowdwatch.core.AutoRules.Announcement a : m.pendingAnnouncements()) sig.append('#').append(a.id);
        if (sig.toString().equals(lostSig)) return;
        lostSig = sig.toString();
        lostBox.getChildren().clear();
        for (Incident i : m.lostCases()) {
            Label head = new Label(i + "  " + (i.foundAt != null ? "FOUND at " + i.foundWhere + " by " + i.foundBy : "MISSING " + i.minutesOpen(m.now()) + " min") + "  -  last seen " + i.area.id);
            head.setStyle("-fx-font-weight: bold;");
            Label d = wrap(i.details);
            Label s = sub(i.foundAt != null ? "Bring to " + m.meetingFor(i.area.id) + ", check identity, then resolve."
                : "Search: " + String.join(", ", m.searchAreas(i).toArray(new String[0])) + "   Meeting point: " + m.meetingFor(i.area.id) + "   On the case: " + (i.unitId == null ? "waiting" : i.unitId));
            int id = i.id;
            HBox acts = new HBox(8, button("Open", "btn", e -> openIncident(id)));
            if (i.foundAt == null) {
                ComboBox<String> where = new ComboBox<>(FXCollections.observableArrayList(m.areaTree().keysInOrder().toArray(new String[0])));
                where.setValue(i.area.id);
                acts.getChildren().addAll(where, button("Mark found", "btn-primary", e -> act(m.found(id, where.getValue(), officerField.getText()))));
            }
            VBox box = new VBox(4, head, d, s, acts);
            box.getStyleClass().addAll("tile", i.foundAt != null ? "lvl-LOW" : "lvl-VIOLET");
            box.setStyle("-fx-cursor: default;");
            lostBox.getChildren().add(box);
        }
        if (lostBox.getChildren().isEmpty()) lostBox.getChildren().add(sub("No open lost-person cases."));
        signsBox.getChildren().clear();
        for (crowdwatch.core.AutoRules.Announcement a : m.pendingAnnouncements()) {
            int id = a.id;
            Label t = wrap("#" + a.id + "  " + a.text);
            HBox row = new HBox(8, button("Approve and send", "btn-primary", e -> act(m.decideAnnouncement(id, true, officerField.getText()))),
                button("Reject", "btn", e -> act(m.decideAnnouncement(id, false, officerField.getText()))));
            signsBox.getChildren().add(new VBox(4, t, row));
        }
        if (signsBox.getChildren().isEmpty()) signsBox.getChildren().add(sub("No drafts waiting."));
    }

    /** Alert list with the current filter. */
    private void refreshAlerts() {
        for (Node b : alertFilters.getChildren()) {
            b.getStyleClass().removeAll("chip-neutral", "chip-selected");
            b.getStyleClass().add(alertFilter.equals(b.getUserData()) ? "chip-selected" : "chip-neutral");
        }
        alertList.getChildren().clear();
        for (Alert a : m.alerts()) if (alertFilter.equals("ALL") || a.kind.name().equals(alertFilter)) alertList.getChildren().add(alertRow(a));
    }

    /** Live chart of the chosen area's sampled readings. */
    private void refreshChart() {
        if (m == null || chartArea == null) return;
        synchronized (m) {
            Area a = m.findArea(chartArea);
            if (a == null) return;
            XYChart.Series<Number, Number> s = new XYChart.Series<>();
            java.time.LocalDateTime now = m.now();
            long span = a.readings.size() == 0 ? 0 : java.time.Duration.between(a.readings.get(0).time, now).getSeconds();
            boolean minutes = span >= 180;
            ((NumberAxis) chart.getXAxis()).setLabel(minutes ? "minutes ago" : "seconds ago");
            for (int k = 0; k < a.readings.size(); k++) {
                Reading r = a.readings.get(k);
                long secs = java.time.Duration.between(r.time, now).getSeconds();
                s.getData().add(new XYChart.Data<>(minutes ? -secs / 60.0 : -secs, r.occupancy));
            }
            chart.setTitle(a.id + " - " + a.name + ": " + a.occupancy + " / " + a.capacity + " (" + a.percent() + "%, " + a.level + ")");
            chart.getData().setAll(java.util.List.of(s));
        }
    }

    // ================================================================ rows (copied under the monitor's lock)

    /** @return incident rows: open ones by priority, then resolved ones by time */
    private IncRow[] incidentRows() {
        LinkedList<IncRow> out = new LinkedList<>();
        for (Incident i : m.openIncidentsByPriority()) out.addLast(incRow(i));
        for (Incident i : m.incidentsByTime()) if (!i.open()) out.addLast(incRow(i));
        return out.toArray(new IncRow[0]);
    }

    /** @return one incident as a table row */
    private IncRow incRow(Incident i) {
        return new IncRow(i.id, i.toString(), i.priority.name(), i.category.label, i.area.id, i.status.name(),
            i.unitId == null ? "-" : i.unitId, i.reported.format(HistoryEntry.FORMAT), deviceText(i), i.automatic && i.reviewedBy == null && i.open());
    }

    /** @return what the unit's device has done with the task */
    private static String deviceText(Incident i) {
        if (!i.open()) return "closed";
        if (i.unitId == null) return "waiting in the " + i.priority + " queue";
        if (i.status == IncidentStatus.ON_SCENE) return "on scene";
        if (i.acknowledged != null) return "acknowledged";
        return i.reminders > 0 ? "NOT acknowledged - reminder sent" : "sent, not yet acknowledged";
    }

    /** @return the open incidents only */
    private static IncRow[] openOnly(IncRow[] all) {
        LinkedList<IncRow> out = new LinkedList<>();
        for (IncRow r : all) if (!r.status().equals("RESOLVED")) out.addLast(r);
        return out.toArray(new IncRow[0]);
    }

    /** @return area rows, busiest first */
    private AreaRow[] areaRows() {
        Area[] sorted = m.areasByLoad();
        AreaRow[] out = new AreaRow[sorted.length];
        for (int k = 0; k < sorted.length; k++) {
            Area a = sorted[k];
            long rate = Math.round(m.ratePerMinute(a));
            int eta = m.minutesToCritical(a);
            String trend = rate > 0 ? "+" + rate + "/min" + (eta > 0 ? ", CRITICAL in " + eta + " min" : "") : rate < 0 ? rate + "/min" : "steady";
            out[k] = new AreaRow(a.id, a.name, a.sector, a.occupancy, a.capacity, a.percent(), a.level.name(), trend, a.restricted);
        }
        return out;
    }

    /** @return unit rows sorted by status, sector and ID */
    private UnitRow[] unitRows() {
        Unit[] us = m.unitsSorted();
        UnitRow[] out = new UnitRow[us.length];
        java.time.LocalDateTime now = m.now();
        for (int k = 0; k < us.length; k++) {
            Unit u = us[k];
            Incident t = m.activeIncident(u.id);
            boolean online = u.lastContact != null && java.time.Duration.between(u.lastContact, now).getSeconds() < ConsoleRelay.DEVICE_ONLINE_SECONDS;
            String task = t == null ? "No task" : t + " " + t.category.label + " at " + t.area.id + " - " + deviceText(t);
            if (u.reinforcing != null) task = "REINFORCING " + u.reinforcing + (u.home != null ? " (returns to " + u.home + ")" : "") + (t == null ? "" : " - " + task);
            out[k] = new UnitRow(u.id, u.name, u.area.id, u.status.name(), task,
                u.skillList(), online ? "Device online" : u.lastContact == null ? "No device seen - use radio" : "Device offline - use radio", online);
        }
        return out;
    }

    // ================================================================ actions and helpers

    /** Opens the accounts and the audit log for the current venue (again after a venue switch). */
    private void openAccounts() {
        try { accounts = App.openAccounts(db, m.venue() == null ? "venue" : m.venue().code); }
        catch (java.io.IOException e) { accounts = null; }
        if (api != null) api.setAccounts(accounts);
    }

    /** Shows the result of an action, writes it to the audit log (role DESKTOP) and refreshes. */
    private void act(Result r) {
        if (accounts != null) {
            String who = officerField.getText().isBlank() ? (m.officer() == null ? "desktop" : m.officer()) : officerField.getText().trim();
            accounts.record(m.now(), who, "DESKTOP", "desktop", r.message, r.ok);
        }
        say((r.ok ? "" : "Refused: ") + r.message);
        refresh();
    }

    /** Shows a short message at the bottom of the window for a few seconds. */
    private void say(String text) {
        toastText.setText(text);
        toast.setVisible(true);
        toastTimer.playFromStart();
    }

    /** Fills a report under the monitor's lock. */
    private void report(String title, java.util.function.Supplier<String> body) {
        synchronized (m) { reportOut.setText(m.venueName().toUpperCase() + " - " + title + "\nGenerated " + m.now().format(HistoryEntry.FORMAT) + "\n\n" + body.get()); }
    }

    /** Fills the data-structure view under the monitor's lock. */
    private void ds(java.util.function.Supplier<String> body) {
        synchronized (m) { dsOut.setText(body.get()); }
    }

    /** Saves now on a background thread and reports the outcome. */
    private void save() {
        Thread t = new Thread(() -> { Result r = saveNow(); Platform.runLater(() -> act(r)); }, "save");
        t.setDaemon(true);
        t.start();
    }

    /** @return the outcome of saving now on the calling thread */
    private Result saveNow() {
        Database d = db;
        if (d == null) return Result.fail("Running in memory only: nothing can be saved.");
        try { d.save(m); return Result.ok("Saved " + m.venueName() + "."); }
        catch (Exception e) { return Result.fail("Save failed: " + Database.explain(e)); }
    }

    /** Autosave: quiet unless it fails. */
    private void autosaveTick() {
        if (db == null) return;
        Result r = saveNow();
        if (!r.ok) Platform.runLater(() -> say("Autosave: " + r.message));
    }

    /** Connects the venue's radio gateway, replacing the previous one. */
    private void useGateway(VenueProfile p) {
        if (gateway != null) m.removeChannel(gateway);
        gateway = p != null && p.gateway != null ? new crowdwatch.net.HttpGatewayChannel(p.gateway) : null;
        if (gateway != null) m.addChannel(gateway);
    }

    /** Switches to another venue (checks its database first, offers to save the current one). */
    private void switchVenue(VenueProfile p) {
        Database next = App.openDatabase(p.code);
        if (next != null) {
            try { next.check(); }
            catch (Exception e) {
                if (!askMemoryOnly("The database for " + p.name + " cannot be used: " + Database.explain(e))) return;
                next = null;
            }
        }
        if (db != null && confirm("Save " + m.venueName() + " before switching?")) {
            Result r = saveNow();
            if (!r.ok && !confirm(r.message + "\nSwitch anyway and lose the unsaved changes?")) return;
        }
        sim.stop();
        String note;
        synchronized (m) {
            db = next;
            useGateway(p);
            try { note = App.loadOrSeed(m, next, p); }
            catch (IllegalStateException e) { db = null; note = e.getMessage() + " " + App.loadOrSeed(m, null, p); }
        }
        openAccounts();
        venueChanged();
        say(note);
    }

    /** Offers to save, stops the background work and closes the program. */
    private void exit() {
        if (db != null) {
            javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION, "Save before exit?", ButtonType.YES, ButtonType.NO, ButtonType.CANCEL);
            a.setHeaderText(null);
            ButtonType c = a.showAndWait().orElse(ButtonType.CANCEL);
            if (c == ButtonType.CANCEL) return;
            if (c == ButtonType.YES) {
                Result r = saveNow();
                if (!r.ok && !confirm(r.message + "\nExit anyway and lose the unsaved changes?")) return;
            }
        }
        autosave.shutdownNow();
        if (watchdog != null) watchdog.stop();
        sim.stop();
        if (api != null) api.stop();
        if (m != null) m.awaitListeners(1000);
        if (db != null) Database.shutdown();
        Platform.exit();
        System.exit(0);
    }

    /** @return true if the user answers OK to a question */
    private boolean confirm(String q) {
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION, q, ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        a.setTitle("CrowdWatch");
        return a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** Replaces a table's rows and keeps the selected row (matched by key). */
    private static <T> void keepSelection(TableView<T> table, T[] rows, Function<T, String> key) {
        T sel = table.getSelectionModel().getSelectedItem();
        table.getItems().setAll(rows);
        if (sel == null) return;
        for (T r : rows) if (key.apply(r).equals(key.apply(sel))) { table.getSelectionModel().select(r); return; }
    }

    /** Adds the incident columns to a table. */
    private void incidentColumns(TableView<IncRow> t) {
        TableColumn<IncRow, IncRow> idc = new TableColumn<>("ID");
        idc.setPrefWidth(110);
        idc.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        idc.setCellFactory(c -> new TableCell<>() {
            protected void updateItem(IncRow r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) { setGraphic(null); setText(null); return; }
                Label id = new Label(r.id());
                id.setStyle("-fx-font-weight: bold");
                HBox h = new HBox(6, id);
                if (r.automatic()) h.getChildren().add(pill("AUTO", "MODERATE"));
                setGraphic(h);
            }
        });
        t.getColumns().add(idc);
        t.getColumns().add(pillCol("Priority", 100, IncRow::priority, r -> r.priority().equals("LOW") || r.priority().equals("NORMAL") ? "NORMAL" : r.priority()));
        t.getColumns().add(col("Category", 240, IncRow::category));
        t.getColumns().add(col("Area", 110, IncRow::area));
        t.getColumns().add(col("Status", 100, IncRow::status));
        t.getColumns().add(col("Unit", 70, IncRow::unit));
        t.getColumns().add(col("Reported", 100, IncRow::reported));
        t.getColumns().add(col("Device", 230, IncRow::device));
        t.setPlaceholder(new Label("No incidents."));
    }

    /** @return a text column */
    private static <T> TableColumn<T, String> col(String title, double width, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(d -> new SimpleStringProperty(value.apply(d.getValue())));
        return c;
    }

    /** @return a column showing a coloured pill */
    private static <T> TableColumn<T, T> pillCol(String title, double width, Function<T, String> text, Function<T, String> level) {
        TableColumn<T, T> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue()));
        c.setCellFactory(x -> new TableCell<>() {
            protected void updateItem(T r, boolean empty) {
                super.updateItem(r, empty);
                setText(null);
                setGraphic(empty || r == null ? null : pill(text.apply(r), level.apply(r)));
            }
        });
        return c;
    }

    /** @return a small coloured label for a level, priority or status */
    private static Label pill(String text, String level) {
        Label l = new Label(text);
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.getStyleClass().addAll("pill", "lvl-" + level);
        return l;
    }

    /** @return the pill colour for a priority */
    private static String priorityClass(Priority p) { return p == Priority.CRITICAL ? "CRITICAL" : p == Priority.HIGH ? "HIGH" : "NORMAL"; }

    /** @return a status chip; clicking it opens a view (if given) */
    private Node chip(String text, String style, String target) {
        Button b = new Button(text);
        b.getStyleClass().addAll("chip", style);
        if (target != null) b.setOnAction(e -> show(target)); else b.setFocusTraversable(false);
        return b;
    }

    /** @return a full-width banner */
    private static Node banner(String text, String style) {
        HBox h = new HBox(new Label(text));
        h.getStyleClass().add(style);
        return h;
    }

    /** @return one alert as a row with a coloured dot */
    private static Node alertRow(Alert a) {
        Region dot = new Region();
        String lvl = switch (a.kind) {
            case SOS, NO_ACK, SUPERVISION, DELIVERY, EVACUATION -> "CRITICAL";
            case CROWD, TREND, AUTO_DISPATCH, ESCALATED, NO_UNIT -> "HIGH";
            case CLEARED -> "LOW";
            default -> "NORMAL";
        };
        dot.getStyleClass().addAll("dot", "lvl-" + lvl);
        Label when = new Label(a.time.format(HistoryEntry.FORMAT) + "  -  " + a.kind);
        when.getStyleClass().add("sub");
        Label text = new Label(a.text);
        text.setWrapText(true);
        HBox row = new HBox(10, dot, new VBox(2, when, text));
        row.setPadding(new Insets(8, 0, 8, 0));
        row.setStyle("-fx-border-color: -cw-border transparent transparent transparent");
        return row;
    }

    /** @return a card with a title and content */
    private static VBox card(String title, Node... content) {
        VBox v = new VBox(10);
        v.getStyleClass().add("card");
        v.getChildren().add(sectionLabel(title));
        v.getChildren().addAll(content);
        return v;
    }

    /** @return a bold section heading */
    private static Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    /** @return a small grey label */
    private static Label sub(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("sub");
        l.setWrapText(true);
        return l;
    }

    /** @return a label that wraps long text */
    private static Label wrap(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        return l;
    }

    /** @return a two-column form grid */
    private static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        return g;
    }

    /** @return a styled button with an action (the action may be set later) */
    private static Button button(String text, String style, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button b = new Button(text);
        b.getStyleClass().add(style);
        if (action != null) b.setOnAction(action);
        return b;
    }

    /** Sets one of the overview numbers. */
    private void setKpi(int k, String value, String note) {
        kpiValue[k].setText(value);
        kpiNote[k].setText(note);
    }

    /** @return the number in a text field, or -1 if it is not a number (the monitor then refuses it) */
    private static int num(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    /** @return "-" if not rising, "now" if already CRITICAL, otherwise "N min" */
    private static String eta(int min) { return min < 0 ? "-" : min == 0 ? "now" : min + " min"; }
}
