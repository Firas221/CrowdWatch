package crowdwatch.feed;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.Enums.Source;

/**
 * Asks a counting system's web address for the current counts every {@code seconds}. The reply
 * may be text in {@link LineFormat} or JSON: an array of objects with "area" and "count" (or
 * "occupancy"), optionally "source", e.g. {@code [{"area":"GATE-A","count":1250}]}. An optional
 * token is sent as {@code Authorization: Bearer}. Venue line:
 * {@code feed: poll <url> [seconds] [token-variable]} where the token is read from that
 * environment variable, so it never appears in the venue file.
 */
public class PollFeed implements CrowdFeed {
    private final URI url;
    private final int seconds;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final LinkedList<String> log = new LinkedList<>();
    private java.util.concurrent.ScheduledExecutorService timer;
    private volatile int polls, lines;

    /** @param token sent as a bearer token, or null */
    public PollFeed(URI url, int seconds, String token) { this.url = url; this.seconds = Math.max(2, seconds); this.token = token; }

    public String name() { return "Poll " + url.getHost() + " every " + seconds + " s"; }

    public synchronized void start(CrowdMonitor m) {
        if (timer != null) return;
        timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "feed-poll"); t.setDaemon(true); return t; });
        timer.scheduleWithFixedDelay(() -> pollOnce(m), 0, seconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Asks once and applies the reply (also called by the tests). */
    public void pollOnce(CrowdMonitor m) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10));
            if (token != null) b.header("Authorization", "Bearer " + token);
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) { note("HTTP " + r.statusCode() + " from " + url.getHost()); return; }
            polls++;
            String body = r.body().trim();
            LinkedList<String> problems = new LinkedList<>();
            lines += LineFormat.applyAll(m, body.startsWith("[") ? jsonToLines(body) : body, Source.SENSOR, problems);
            for (String p : problems) note(p);
        } catch (java.io.IOException | RuntimeException e) {
            note(url.getHost() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Turns a flat JSON array of objects into lines "area,count,source". Only the fields area,
     * count/occupancy and source are read; anything else is ignored.
     */
    static String jsonToLines(String json) {
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher obj = java.util.regex.Pattern.compile("\\{([^{}]*)\\}").matcher(json);
        while (obj.find()) {
            String o = obj.group(1);
            String area = field(o, "area"), count = field(o, "count"), src = field(o, "source");
            if (count == null) count = field(o, "occupancy");
            if (area != null && count != null) out.append(area).append(',').append(count).append(src == null ? "" : "," + src).append('\n');
        }
        return out.toString();
    }

    private static String field(String obj, String name) {
        java.util.regex.Matcher f = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*(\"([^\"]*)\"|(-?\\d+))").matcher(obj);
        return f.find() ? (f.group(2) != null ? f.group(2) : f.group(3)) : null;
    }

    private synchronized void note(String s) { log.addLast(s); while (log.size() > 100) log.removeFirst(); }

    public synchronized void stop() { if (timer != null) timer.shutdownNow(); timer = null; }

    public synchronized boolean running() { return timer != null; }

    /** @return successful polls and lines applied so far */
    public int[] counts() { return new int[] { polls, lines }; }

    public synchronized LinkedList<String> log() { return log; }
}
