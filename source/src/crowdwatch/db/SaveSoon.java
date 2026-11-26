package crowdwatch.db;

import crowdwatch.core.MonitorListener;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Saves soon after a change instead of only at the next regular autosave: the first change
 * schedules one save {@link #SECONDS} seconds later, and every change until then is part of that
 * save. A crash or power cut therefore loses at most about that many seconds of work, while a
 * venue whose sensors change something every second still saves only once per period.
 * <p>
 * Register it with {@code CrowdMonitor.addListener}. The save runs on the given executor (the
 * one that also runs the regular autosave, so two saves never run at the same time).
 */
public final class SaveSoon implements MonitorListener {
    /** Seconds from a change to its save (property crowdwatch.saveDelay, 2 to 60; default 10). */
    public static final int SECONDS = Math.max(2, Math.min(60, Integer.getInteger("crowdwatch.saveDelay", 10)));

    private final ScheduledExecutorService executor;
    private final Runnable save;
    private final long delayMillis;
    private final AtomicBoolean due = new AtomicBoolean();

    /**
     * @param executor runs the save (single-threaded, shared with the regular autosave)
     * @param save     saves the monitor; reports its own failures
     */
    public SaveSoon(ScheduledExecutorService executor, Runnable save) { this(executor, save, SECONDS * 1000L); }

    /** For tests: a delay in milliseconds. */
    public SaveSoon(ScheduledExecutorService executor, Runnable save, long delayMillis) {
        this.executor = executor;
        this.save = save;
        this.delayMillis = delayMillis;
    }

    @Override
    public void onChange() {
        if (!due.compareAndSet(false, true)) return;   // a save is already coming
        try {
            executor.schedule(() -> { due.set(false); save.run(); }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            due.set(false);   // shutting down: the final save covers it
        }
    }
}
