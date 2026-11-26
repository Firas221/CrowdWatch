package crowdwatch.core;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import crowdwatch.ds.CircularBuffer;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.*;
import crowdwatch.model.Enums.*;

/**
 * What the monitor records besides its current state: one frame a minute of every area's count
 * (for replay and reports), the messages between units and the control room, and the weather
 * with its heat-stress level. The monitor owns one Records object and calls it under its lock.
 */
public final class Records {
    /** Minutes of frames kept in memory (one day); older frames are in the database. */
    public static final int FRAMES_KEPT = 24 * 60;
    /** Messages kept in memory. */
    public static final int MESSAGES_KEPT = 1000;

    /** The counts of every area at one minute. */
    public static final class Frame {
        public final LocalDateTime minute;
        public final String[] ids;
        public final int[] occupancy;
        public final String[] level;
        Frame(LocalDateTime minute, String[] ids, int[] occupancy, String[] level) { this.minute = minute; this.ids = ids; this.occupancy = occupancy; this.level = level; }
    }

    /** A message from a unit to the control room, or from the control room to a unit. */
    public static final class Message {
        public final int id;
        public final LocalDateTime time;
        /** The unit the conversation is with. */
        public final String unit;
        /** true if the unit sent it, false if the control room did */
        public final boolean fromUnit;
        /** Who wrote it: the unit, or the control-room user. */
        public final String by;
        /** The incident it is about, or 0. */
        public final int incident;
        public String text;
        /** File name of an attached photo, or null. */
        public String photo;
        public Message(int id, LocalDateTime time, String unit, boolean fromUnit, String by, int incident, String text, String photo) {
            this.id = id; this.time = time; this.unit = unit; this.fromUnit = fromUnit; this.by = by; this.incident = incident; this.text = text; this.photo = photo;
        }
    }

    private final CrowdMonitor m;
    private final CircularBuffer<Frame> frames = new CircularBuffer<>(FRAMES_KEPT);
    private final LinkedList<Frame> unsaved = new LinkedList<>();
    private final LinkedList<Message> messages = new LinkedList<>();
    private int nextMessage = 1;
    // weather: temperature (C), relative humidity (%), heat index (C), band; null band = no reading yet
    private double temperature, humidity, heatIndex;
    private String heatBand;
    private LocalDateTime weatherAt;

    Records(CrowdMonitor m) { this.m = m; }

    /** Clears everything (a new venue was loaded). */
    void reset() {
        frames.clear(); unsaved.clear(); messages.clear(); nextMessage = 1;
        heatBand = null; weatherAt = null;
    }

    // ------------------------------------------------------------------ frames

    /** Records a frame if the minute has moved on since the last one. */
    void sample(LocalDateTime now) {
        LocalDateTime minute = now.truncatedTo(ChronoUnit.MINUTES);
        Frame last = frames.last();
        if (last != null && !minute.isAfter(last.minute)) return;
        Area[] areas = m.areaTree().inOrder().toArray(new Area[0]);
        String[] ids = new String[areas.length], lv = new String[areas.length];
        int[] occ = new int[areas.length];
        for (int k = 0; k < areas.length; k++) { ids[k] = areas[k].id; occ[k] = areas[k].occupancy; lv[k] = areas[k].level.name(); }
        Frame f = new Frame(minute, ids, occ, lv);
        frames.add(f);
        unsaved.addLast(f);
        while (unsaved.size() > FRAMES_KEPT) unsaved.removeFirst();
    }

    /** Makes a frame (used when frames are read back from the database). */
    public static Frame frame(LocalDateTime minute, String[] ids, int[] occupancy, String[] level) { return new Frame(minute, ids, occupancy, level); }

    /** @return the frames in memory from {@code from} to {@code to}, oldest first */
    public LinkedList<Frame> frames(LocalDateTime from, LocalDateTime to) {
        LinkedList<Frame> out = new LinkedList<>();
        for (int k = 0; k < frames.size(); k++) {
            Frame f = frames.get(k);
            if (!f.minute.isBefore(from) && !f.minute.isAfter(to)) out.addLast(f);
        }
        return out;
    }

    /** @return the oldest minute held in memory, or null */
    public LocalDateTime firstFrame() { return frames.size() == 0 ? null : frames.get(0).minute; }

    /** Hands the frames not yet saved to the database (they are removed from the to-save list). */
    public LinkedList<Frame> takeUnsaved() {
        LinkedList<Frame> out = new LinkedList<>();
        for (Frame f : unsaved) out.addLast(f);
        unsaved.clear();
        return out;
    }

    /** Puts frames back after a failed save. */
    public void returnUnsaved(LinkedList<Frame> fs) {
        LinkedList<Frame> merged = new LinkedList<>();
        for (Frame f : fs) merged.addLast(f);
        for (Frame f : unsaved) merged.addLast(f);
        unsaved.clear();
        for (Frame f : merged) unsaved.addLast(f);
    }

    // ------------------------------------------------------------------ messages

    /**
     * Adds a message.
     *
     * @return the message
     */
    Message addMessage(LocalDateTime t, String unit, boolean fromUnit, String by, int incident, String text, String photo) {
        Message msg = new Message(nextMessage++, t, unit, fromUnit, by, incident, text, photo);
        messages.addLast(msg);
        while (messages.size() > MESSAGES_KEPT) messages.removeFirst();
        return msg;
    }

    /** @return the messages with a unit (or with any unit if null) or about an incident (if > 0), oldest first */
    public LinkedList<Message> messages(String unit, int incident) {
        LinkedList<Message> out = new LinkedList<>();
        for (Message x : messages)
            if ((unit == null || x.unit.equalsIgnoreCase(unit)) && (incident <= 0 || x.incident == incident)) out.addLast(x);
        return out;
    }

    /** @return the message with that id, or null */
    public Message message(int id) {
        for (Message x : messages) if (x.id == id) return x;
        return null;
    }

    /** @return every message in memory (for saving) */
    public LinkedList<Message> allMessages() { return messages; }

    /** Restores messages loaded from the database. */
    public void restoreMessage(Message x) {
        messages.addLast(x);
        nextMessage = Math.max(nextMessage, x.id + 1);
    }

    // ------------------------------------------------------------------ weather and heat stress

    /**
     * Heat index (apparent temperature) in Celsius from temperature and relative humidity, with
     * the US National Weather Service formula (Rothfusz regression with its adjustments).
     */
    public static double heatIndex(double tempC, double rh) {
        double t = tempC * 9 / 5 + 32;
        double hi = 0.5 * (t + 61.0 + (t - 68.0) * 1.2 + rh * 0.094);
        if ((hi + t) / 2 >= 80) {
            hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh - 0.00683783 * t * t - 0.05481717 * rh * rh
                + 0.00122874 * t * t * rh + 0.00085282 * t * rh * rh - 0.00000199 * t * t * rh * rh;
            if (rh < 13 && t >= 80 && t <= 112) hi -= ((13 - rh) / 4) * Math.sqrt((17 - Math.abs(t - 95)) / 17);
            else if (rh > 85 && t >= 80 && t <= 87) hi += ((rh - 85) / 10) * ((87 - t) / 5);
        }
        return (hi - 32) * 5 / 9;
    }

    /** @return the heat-stress band of a heat index: NONE, CAUTION (27 C), EXTREME CAUTION (32), DANGER (41), EXTREME DANGER (54) */
    public static String heatBand(double hiC) {
        if (hiC >= 54) return "EXTREME DANGER";
        if (hiC >= 41) return "DANGER";
        if (hiC >= 32) return "EXTREME CAUTION";
        if (hiC >= 27) return "CAUTION";
        return "NONE";
    }

    /** What staff should do in a band. */
    static String heatAdvice(String band) {
        switch (band) {
            case "EXTREME DANGER": return "heat stroke likely: stop people waiting in the sun, open every shaded area, medical units to the outdoor areas, consider limiting entry";
            case "DANGER": return "heat exhaustion likely: water and shade at the outdoor areas, shorten queues in the sun, units watch for people in distress, medical units on standby";
            case "EXTREME CAUTION": return "heat cramps and exhaustion possible: remind people to drink, offer shade and water at the outdoor areas";
            case "CAUTION": return "fatigue possible with long waits: keep water available";
            default: return "no heat stress";
        }
    }

    /**
     * Records a weather reading. When the heat-stress band changes an alert names the outdoor
     * areas and what to do.
     *
     * @return what was recorded
     */
    Result weather(double tempC, double rh, LocalDateTime t) {
        if (tempC < -40 || tempC > 60 || rh < 0 || rh > 100) return Result.fail("Weather out of range: temperature -40..60 C, humidity 0..100 %.");
        temperature = tempC; humidity = rh; weatherAt = t;
        heatIndex = heatIndex(tempC, rh);
        String band = heatBand(heatIndex);
        String text = String.format("Heat index %.0f C (%.0f C, %.0f %% humidity): %s", heatIndex, tempC, rh, band);
        if (!band.equals(heatBand)) {
            boolean rising = heatBand == null || bandRank(band) > bandRank(heatBand);
            if (heatBand != null || !band.equals("NONE")) {
                String outdoor = String.join(", ", m.outdoorAreas());
                m.alert(AlertKind.HEAT, "weather", (rising ? "HEAT " : "Heat stress easing: ") + text
                    + (outdoor.isEmpty() ? "" : " - outdoor areas " + outdoor) + " - " + heatAdvice(band));
            }
            heatBand = band;
        }
        return Result.ok("Weather recorded. " + text + ".");
    }

    private static int bandRank(String b) {
        switch (b) { case "CAUTION": return 1; case "EXTREME CAUTION": return 2; case "DANGER": return 3; case "EXTREME DANGER": return 4; default: return 0; }
    }

    /** @return {temperature, humidity, heat index} or null if no reading in the last 3 hours */
    public double[] weatherNow(LocalDateTime now) {
        if (weatherAt == null || Duration.between(weatherAt, now).toHours() >= 3) return null;
        return new double[] { temperature, humidity, heatIndex };
    }

    /** @return the current heat band, or null without a recent reading */
    public String heatBandNow(LocalDateTime now) { return weatherNow(now) == null ? null : heatBand; }
}
