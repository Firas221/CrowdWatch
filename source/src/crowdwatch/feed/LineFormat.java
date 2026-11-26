package crowdwatch.feed;

import crowdwatch.core.CrowdMonitor;
import crowdwatch.core.Result;
import crowdwatch.model.Enums.Source;

/**
 * The plain text that counting devices and their gateways send to CrowdWatch, one reading a line,
 * fields separated by commas, semicolons, tabs or spaces:
 * <pre>
 *   GATE-A,1250            the people in an area now (a camera or head count)
 *   GATE-A,1250,STAFF      the same with its source (SENSOR, STAFF, OFFICIAL, IMPORT)
 *   GATE-A,+3   GATE-A,-2  people who entered or left (a counter that only counts passes)
 *   move,GATE-A,PASS-1,4   4 people went through the door from GATE-A to PASS-1
 *   move,OUTSIDE,CURB-1,10 10 people came in through an outer gate (OUTSIDE = off the site)
 *   weather,38.5,45        temperature (C) and relative humidity (%) for the heat-stress check
 * </pre>
 * Blank lines, lines starting with # and a header line starting with "area" are skipped.
 */
public final class LineFormat {
    private LineFormat() { }

    /**
     * Applies one line.
     *
     * @param fallback the source used when the line names none
     * @return null if applied or skipped, otherwise why it was not applied
     */
    public static String apply(CrowdMonitor m, String raw, Source fallback) {
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty() || line.startsWith("#") || line.toLowerCase().startsWith("area")) return null;
        String[] f = line.split("\\s*[,;\\t]\\s*|\\s+");
        try {
            Result r;
            if (f[0].equalsIgnoreCase("weather")) {
                if (f.length < 3) return "expected weather,temperature,humidity";
                r = m.weather(Double.parseDouble(f[1]), Double.parseDouble(f[2]));
            } else if (f[0].equalsIgnoreCase("move")) {
                if (f.length < 4) return "expected move,from,to,count";
                r = m.movePeople(f[1].toUpperCase(), f[2].toUpperCase(), Integer.parseInt(f[3]), f.length > 4 ? Source.valueOf(f[4].toUpperCase()) : fallback);
            } else {
                if (f.length < 2) return "expected area,count";
                Source src = f.length > 2 ? Source.valueOf(f[2].toUpperCase()) : fallback;
                String n = f[1];
                r = n.startsWith("+") || n.startsWith("-")
                    ? m.adjustOccupancy(f[0].toUpperCase(), Integer.parseInt(n.startsWith("+") ? n.substring(1) : n), src)
                    : m.updateOccupancy(f[0].toUpperCase(), Integer.parseInt(n), src);
            }
            return r.ok ? null : r.message;
        } catch (IllegalArgumentException e) {   // includes NumberFormatException and an unknown source
            return "not understood (" + e.getMessage() + ")";
        }
    }

    /**
     * Applies every line of a text.
     *
     * @param log receives "line n: reason" for each line that was not applied
     * @return the number of lines applied
     */
    public static int applyAll(CrowdMonitor m, String text, Source fallback, crowdwatch.ds.LinkedList<String> log) {
        int ok = 0, n = 0;
        for (String line : text.split("\\r?\\n")) {
            n++;
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.toLowerCase().startsWith("area")) continue;
            String why = apply(m, line, fallback);
            if (why == null) ok++; else if (log != null) log.addLast("line " + n + ": " + why);
        }
        return ok;
    }
}
