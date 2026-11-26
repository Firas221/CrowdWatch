package crowdwatch.feed;

import java.nio.file.Path;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.ds.LinkedList;

/** Makes and starts the sensor connections named in a venue file's {@code feed:} lines. */
public final class Feeds {
    private Feeds() { }

    /**
     * @param line the text after "feed:", e.g. {@code folder counts 10}, {@code poll https://host/counts 15 COUNTER_TOKEN}
     *             or {@code tcp 5020 10.0.0.7 10.0.0.8}
     * @return the feed (not started)
     * @throws IllegalArgumentException if the line is not understood
     */
    public static CrowdFeed fromLine(String line) {
        String[] f = line.trim().split("\\s+");
        switch (f[0].toLowerCase()) {
            case "folder": return new FolderFeed(Path.of(f[1]), f.length > 2 ? Integer.parseInt(f[2]) : 10);
            case "poll": {
                String token = f.length > 3 ? System.getenv(f[3]) : null;
                return new PollFeed(java.net.URI.create(f[1]), f.length > 2 ? Integer.parseInt(f[2]) : 15, token);
            }
            case "tcp": {
                String[] allowed = new String[Math.max(0, f.length - 2)];
                System.arraycopy(f, 2, allowed, 0, allowed.length);
                return new TcpFeed(Integer.parseInt(f[1]), allowed);
            }
            default: throw new IllegalArgumentException("unknown feed " + f[0]);
        }
    }

    /**
     * Starts every feed of a venue.
     *
     * @param report receives one line per feed started or refused (may be null)
     * @return the running feeds, to stop them later
     */
    public static LinkedList<CrowdFeed> start(CrowdMonitor m, LinkedList<String> lines, java.util.function.Consumer<String> report) {
        LinkedList<CrowdFeed> out = new LinkedList<>();
        for (String l : lines) {
            try {
                CrowdFeed f = fromLine(l);
                f.start(m);
                out.addLast(f);
                if (report != null) report.accept("Sensor feed started: " + f.name());
            } catch (RuntimeException e) {
                if (report != null) report.accept("Sensor feed not started (" + l + "): " + e.getMessage());
            }
        }
        return out;
    }

    /** Stops every feed in the list. */
    public static void stopAll(LinkedList<CrowdFeed> feeds) { if (feeds != null) for (CrowdFeed f : feeds) f.stop(); }
}
