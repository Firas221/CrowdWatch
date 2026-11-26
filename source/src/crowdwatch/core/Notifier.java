package crowdwatch.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.Alert;
import crowdwatch.model.Enums.AlertKind;

/**
 * Sends urgent alerts that nobody at a console dealt with to a web address (the venue's
 * {@code notify:} line), from where an SMS, WhatsApp or e-mail service passes them to the duty
 * officer's phone. "Dealt with" means an officer checked in, or made any decision (which counts
 * as a check-in), after the alert. Each alert is sent at most once.
 * <p>
 * The message is posted as JSON: {@code {"venue","kind","ref","text","time","minutes"}}. If the
 * environment variable {@value #TOKEN_VARIABLE} is set it is sent as a bearer token, so the
 * gateway can check the sender.
 */
public final class Notifier implements MonitorListener {
    /** Environment variable with a token for the gateway (optional). */
    public static final String TOKEN_VARIABLE = "CROWDWATCH_NOTIFY_TOKEN";
    // alert kinds that must reach a person
    private static final AlertKind[] URGENT = { AlertKind.SOS, AlertKind.ALARM, AlertKind.PROPOSAL, AlertKind.SUPERVISION, AlertKind.NO_ACK, AlertKind.LOST, AlertKind.HEAT, AlertKind.EVACUATION };

    private final CrowdMonitor m;
    private final URI url;
    private final int minutes;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final LinkedList<Alert> pending = new LinkedList<>();
    private java.util.concurrent.ScheduledExecutorService timer;
    private volatile int sent, failed;

    /**
     * @param url     the gateway's web address
     * @param minutes how long an urgent alert may wait for an officer before it is sent
     */
    public Notifier(CrowdMonitor m, String url, int minutes) {
        this.m = m;
        this.url = URI.create(url);
        this.minutes = Math.max(1, minutes);
        String t = System.getenv(TOKEN_VARIABLE);
        this.token = t == null || t.isBlank() ? null : t.trim();
    }

    /** Starts listening and checking every 15 seconds. */
    public synchronized void start() {
        if (timer != null) return;
        m.addListener(this);
        timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "notifier"); t.setDaemon(true); return t; });
        timer.scheduleWithFixedDelay(() -> check(m.now()), 15, 15, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Stops checking (alerts still waiting are not sent). */
    public synchronized void stop() {
        m.removeListener(this);
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    @Override
    public void onAlert(Alert a) {
        for (AlertKind k : URGENT) if (k == a.kind) { synchronized (this) { pending.addLast(a); } return; }
    }

    /**
     * Sends every waiting urgent alert that is older than the limit and that no officer dealt
     * with; drops the ones an officer dealt with.
     *
     * @return the number sent now
     */
    public int check(LocalDateTime now) {
        LinkedList<Alert> due = new LinkedList<>();
        LocalDateTime seen = m.officerCheckInTime();
        synchronized (this) {
            LinkedList<Alert> keep = new LinkedList<>();
            for (Alert a : pending) {
                boolean dealt = seen != null && seen.isAfter(a.time);   // a check-in after the alert
                if (dealt) continue;
                if (Duration.between(a.time, now).toMinutes() >= minutes) due.addLast(a); else keep.addLast(a);
            }
            pending.clear();
            for (Alert a : keep) pending.addLast(a);
        }
        int n = 0;
        for (Alert a : due) {
            if (post(a, now)) { n++; sent++; } else failed++;
        }
        if (n > 0) m.alertNotified(n);
        return n;
    }

    private boolean post(Alert a, LocalDateTime now) {
        String json = "{\"venue\":" + q(m.venueName()) + ",\"kind\":" + q(a.kind.name()) + ",\"ref\":" + q(a.ref) + ",\"text\":" + q(a.text)
            + ",\"time\":" + q(a.time.withNano(0).toString()) + ",\"minutes\":" + Duration.between(a.time, now).toMinutes() + "}";
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
            if (token != null) b.header("Authorization", "Bearer " + token);
            int code = http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            return code >= 200 && code < 300;
        } catch (java.io.IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** @return {sent, failed} so far */
    public int[] counts() { return new int[] { sent, failed }; }

    /** @return the alerts still waiting */
    public synchronized int waiting() { return pending.size(); }
}
