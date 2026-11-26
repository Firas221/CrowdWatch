package crowdwatch.feed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.core.Result;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.Enums.Source;

/**
 * A training exercise: a list of timed events played into a drill server (started with
 * {@code --drill}, which never touches the real database), so staff practise on the real
 * screens. One event a line, the time after the start as MM:SS:
 * <pre>
 *   00:30 count GATE-A 1400               a head count (any count line of {@link LineFormat} works)
 *   01:00 move OUTSIDE CURB-1 300         people arriving
 *   02:00 incident MEDICAL GATE-B Man collapsed near B4
 *   03:00 lost GATE-A Girl, 6, yellow dress
 *   04:00 alarm BAGGAGE-1 smoke detector
 *   05:00 sos SEC-1 fight in the queue
 *   06:00 weather 41 35                   temperature and humidity (heat stress)
 *   07:00 say Exercise ends in 3 minutes   a note to everyone
 * </pre>
 */
public final class DrillScript implements CrowdFeed {
    private final LinkedList<long[]> times = new LinkedList<>();
    private final LinkedList<String> steps = new LinkedList<>();
    private final LinkedList<String> log = new LinkedList<>();
    private final String name;
    private java.util.concurrent.ScheduledExecutorService timer;
    private volatile int done;

    /**
     * Reads a drill file.
     *
     * @throws IllegalArgumentException naming the line if one is not understood
     */
    public static DrillScript read(Path file) throws IOException {
        return parse(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Parses drill text. */
    public static DrillScript parse(String name, String text) {
        DrillScript d = new DrillScript(name);
        int n = 0;
        for (String raw : text.split("\\r?\\n")) {
            n++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("^(\\d{1,3}):(\\d{2})\\s+(\\w+)(?:\\s+(.*))?$").matcher(line);
            if (!mt.find()) throw new IllegalArgumentException("Drill line " + n + ": expected MM:SS command ...");
            String cmd = mt.group(3).toLowerCase();
            if (!cmd.matches("count|move|incident|lost|alarm|sos|weather|say")) throw new IllegalArgumentException("Drill line " + n + ": unknown command " + cmd);
            d.times.addLast(new long[] { Long.parseLong(mt.group(1)) * 60 + Long.parseLong(mt.group(2)) });
            d.steps.addLast(cmd + " " + (mt.group(4) == null ? "" : mt.group(4).trim()));
        }
        return d;
    }

    private DrillScript(String name) { this.name = name; }

    public String name() { return "Drill " + name + " (" + steps.size() + " events)"; }

    /** @return the number of events */
    public int size() { return steps.size(); }

    public synchronized void start(CrowdMonitor m) {
        if (timer != null) return;
        timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "drill"); t.setDaemon(true); return t; });
        for (int k = 0; k < steps.size(); k++) {
            String step = steps.get(k);
            timer.schedule(() -> run(m, step), times.get(k)[0], java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** Plays one event (also used by the tests). @return what happened */
    public String run(CrowdMonitor m, String step) {
        String[] p = step.split("\\s+", 2);
        String args = p.length > 1 ? p[1] : "";
        String[] a = args.split("\\s+", 3);
        String result;
        switch (p[0]) {
            case "count", "move" -> {
                String why = LineFormat.apply(m, (p[0].equals("move") ? "move " : "") + args, Source.SIMULATION);
                result = why == null ? "applied" : why;
            }
            case "incident" -> {
                Result r = m.reportIncident(a[0].toUpperCase(), a.length > 1 ? a[1].toUpperCase() : "", "[DRILL] " + (a.length > 2 ? a[2] : "exercise"), null, null);
                result = r.message;
            }
            case "lost" -> {
                String cat = null;
                synchronized (m) { for (crowdwatch.model.Category c : m.categoryTree().inOrder()) if ("lost-person".equalsIgnoreCase(c.skill)) { cat = c.code; break; } }
                String[] b = args.split("\\s+", 2);
                result = cat == null ? "no lost-person category" : m.reportIncident(cat, b[0].toUpperCase(), "[DRILL] " + (b.length > 1 ? b[1] : "lost person"), null, null).message;
            }
            case "alarm" -> { String[] b = args.split("\\s+", 2); result = m.alarm(b[0].toUpperCase(), "[DRILL] " + (b.length > 1 ? b[1] : "alarm")).message; }
            case "sos" -> { String[] b = args.split("\\s+", 2); result = m.sos(b[0].toUpperCase(), null, "[DRILL] " + (b.length > 1 ? b[1] : "SOS")).message; }
            case "weather" -> result = m.weather(Double.parseDouble(a[0]), Double.parseDouble(a[1])).message;
            case "say" -> result = m.broadcast("[DRILL] " + args, "drill").message;
            default -> result = "unknown";
        }
        done++;
        synchronized (this) { log.addLast(step + " -> " + result); }
        return result;
    }

    public synchronized void stop() { if (timer != null) timer.shutdownNow(); timer = null; }

    public synchronized boolean running() { return timer != null && done < steps.size(); }

    /** @return what each event did */
    public synchronized LinkedList<String> log() { return log; }
}
