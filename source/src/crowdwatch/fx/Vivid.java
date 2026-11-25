package crowdwatch.fx;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.util.Duration;

/**
 * Drawing helpers for the vivid control-room look of the JavaFX console: line icons, sparklines,
 * donut charts, crowd-level colours and the pulsing outline of areas that need action now.
 * Everything here is built from plain JavaFX shapes, so no images or icon fonts are needed.
 */
final class Vivid {
    /** Utility class: no instances. */
    private Vivid() { }

    /** Solid colour of a crowd level or accent: LOW, MODERATE, HIGH, CRITICAL, NORMAL, VIOLET or GRAY. */
    static Color color(String level) {
        return switch (level) {
            case "LOW" -> Color.web("#22C55E");
            case "MODERATE" -> Color.web("#FFAA00");
            case "HIGH" -> Color.web("#FF8A3D");
            case "CRITICAL" -> Color.web("#FF4D5E");
            case "NORMAL" -> Color.web("#38BDF8");
            case "VIOLET" -> Color.web("#A78BFA");
            default -> Color.web("#64748B");
        };
    }

    /** @return the colour as #RRGGBB for inline styles */
    static String hex(Color c) {
        return String.format("#%02X%02X%02X", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    // line icons (24 x 24 SVG paths, drawn with a stroke)
    static final String I_PEOPLE = "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z";
    static final String I_AREA = "M12 2l9 5v10l-9 5-9-5V7zM12 22V12M21 7l-9 5-9-5";
    static final String I_WARN = "M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z";
    static final String I_CHECK = "M20 6L9 17l-5-5";
    static final String I_REVIEW = "M9 11l3 3L22 4M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11";
    static final String I_CHEV = "M9 6l6 6-6 6";

    /** @return a stroked line icon of the given size and colour */
    static Node icon(String path, Color color, double size) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.setFill(Color.TRANSPARENT);
        p.setStroke(color);
        p.setStrokeWidth(2.2);
        p.setStrokeLineCap(StrokeLineCap.ROUND);
        p.setStrokeLineJoin(StrokeLineJoin.ROUND);
        p.setScaleX(size / 24);
        p.setScaleY(size / 24);
        Group g = new Group(p);
        StackPane box = new StackPane(g);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        return box;
    }

    /** @return an icon on a rounded tinted square, like the key-number cards of the web console */
    static Node iconChip(String path, String level) {
        StackPane chip = new StackPane(icon(path, color(level), 18));
        chip.setMinSize(38, 38);
        chip.setMaxSize(38, 38);
        Color c = color(level);
        chip.setStyle("-fx-background-radius: 12; -fx-background-color: " + rgba(c, 0.18) + ";");
        return chip;
    }

    /** @return the colour with an opacity, as rgba() for inline styles */
    static String rgba(Color c, double alpha) {
        return "rgba(" + Math.round(c.getRed() * 255) + "," + Math.round(c.getGreen() * 255) + "," + Math.round(c.getBlue() * 255) + "," + alpha + ")";
    }

    /** @return a small trend line of the values, {@code w} x {@code h} pixels */
    static Node sparkline(double[] values, int count, Color color, double w, double h) {
        Polyline line = new Polyline();
        if (count < 2) { double v = count == 1 ? values[0] : 0; values = new double[] { v, v }; count = 2; }
        double max = values[0], min = values[0];
        for (int k = 0; k < count; k++) { max = Math.max(max, values[k]); min = Math.min(min, values[k]); }
        for (int k = 0; k < count; k++) {
            line.getPoints().addAll(k * w / (count - 1), h - 3 - (values[k] - min) * (h - 6) / Math.max(1, max - min));
        }
        line.setStroke(color);
        line.setStrokeWidth(2.5);
        line.setStrokeLineCap(StrokeLineCap.ROUND);
        line.setStrokeLineJoin(StrokeLineJoin.ROUND);
        Pane p = new Pane(line);
        p.setMinSize(w, h);
        p.setPrefSize(w, h);
        p.setMaxSize(w, h);
        return p;
    }

    /**
     * @return a donut chart: a ring split by the values, with the total in the hole and a legend beside it
     */
    static Node donut(String[] labels, int[] values, String[] levels, String centre) {
        int total = 0;
        for (int v : values) total += v;
        Group ring = new Group();
        Circle base = new Circle(60, 60, 51, Color.TRANSPARENT);
        base.setStroke(Color.web("#64748B", 0.25));
        base.setStrokeWidth(18);
        ring.getChildren().add(base);
        double at = 90;
        for (int k = 0; k < values.length; k++) {
            if (values[k] <= 0 || total == 0) continue;
            double len = values[k] * 360.0 / total;
            Arc a = new Arc(60, 60, 51, 51, at, -len);
            a.setType(ArcType.OPEN);
            a.setFill(Color.TRANSPARENT);
            a.setStroke(color(levels[k]));
            a.setStrokeWidth(18);
            ring.getChildren().add(a);
            at -= len;
        }
        Label n = new Label(String.valueOf(total));
        n.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        Label sub = new Label(centre);
        sub.getStyleClass().add("sub");
        VBox hole = new VBox(-2, n, sub);
        hole.setAlignment(Pos.CENTER);
        StackPane d = new StackPane(ring, hole);
        d.setMinSize(120, 120);
        d.setMaxSize(120, 120);
        VBox legend = new VBox(7);
        for (int k = 0; k < labels.length; k++) {
            Circle dot = new Circle(5, color(levels[k]));
            Label t = new Label(labels[k] + "  ");
            Label v = new Label(String.valueOf(values[k]));
            v.setStyle("-fx-font-weight: bold;");
            HBox row = new HBox(8, dot, new HBox(t, v));
            row.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(row);
        }
        HBox h = new HBox(18, d, legend);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** @return a progress track with a coloured fill, {@code width} pixels wide */
    static Node bar(double fraction, Color color, double width) {
        Region track = new Region();
        track.setStyle("-fx-background-color: rgba(128,140,160,0.25); -fx-background-radius: 3;");
        track.setPrefSize(width, 5);
        track.setMaxSize(width, 5);
        Region fill = new Region();
        double w = Math.max(0, Math.min(1, fraction)) * width;
        fill.setStyle("-fx-background-radius: 3; -fx-background-color: linear-gradient(to right, " + rgba(color, 0.65) + ", " + hex(color) + ");");
        fill.setPrefSize(w, 5);
        fill.setMaxSize(w, 5);
        StackPane s = new StackPane(track, fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        s.setMaxWidth(width);
        return s;
    }

    // one shared pulse for every area that needs action: a glow that grows and fades
    static final DropShadow PULSE_RED = new DropShadow(BlurType.GAUSSIAN, Color.web("#FF4D5E", 0.65), 0, 0.3, 0, 0);
    static final DropShadow PULSE_AMBER = new DropShadow(BlurType.GAUSSIAN, Color.web("#FFAA00", 0.6), 0, 0.3, 0, 0);
    private static Timeline pulse;

    /** Starts the shared pulse animation once (call on the JavaFX thread). */
    static void startPulse() {
        if (pulse != null) return;
        pulse = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(PULSE_RED.radiusProperty(), 0), new KeyValue(PULSE_RED.colorProperty(), Color.web("#FF4D5E", 0.75)),
                new KeyValue(PULSE_AMBER.radiusProperty(), 0), new KeyValue(PULSE_AMBER.colorProperty(), Color.web("#FFAA00", 0.7))),
            new KeyFrame(Duration.seconds(1.4), new KeyValue(PULSE_RED.radiusProperty(), 26), new KeyValue(PULSE_RED.colorProperty(), Color.web("#FF4D5E", 0.0)),
                new KeyValue(PULSE_AMBER.radiusProperty(), 22), new KeyValue(PULSE_AMBER.colorProperty(), Color.web("#FFAA00", 0.0))));
        pulse.setCycleCount(Timeline.INDEFINITE);
        pulse.play();
    }
}
