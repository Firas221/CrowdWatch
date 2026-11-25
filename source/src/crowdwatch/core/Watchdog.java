package crowdwatch.core;

/**
 * Background timer that calls {@link CrowdMonitor#tick()} every 15 seconds, so time-based
 * rules run even when nobody is using the system: the security-officer check-in, reminders to
 * units that have not acknowledged a task, and reassignment of tasks from units that stay silent.
 */
public final class Watchdog {
    /** Seconds between checks. */
    public static final int PERIOD_SECONDS = 15;
    private final CrowdMonitor monitor;
    // the timer thread, null while stopped
    private volatile Thread thread;

    /** @param monitor the monitor to check */
    public Watchdog(CrowdMonitor monitor) { this.monitor = monitor; }

    /** Starts the timer (a daemon thread). Does nothing if it is already running. */
    public synchronized void start() {
        if (thread != null) return;
        Thread t = new Thread(() -> {
            while (thread == Thread.currentThread()) {
                try { Thread.sleep(PERIOD_SECONDS * 1000L); } catch (InterruptedException e) { return; }
                try { monitor.tick(); } catch (RuntimeException e) { System.err.println("CrowdWatch watchdog: " + e); }
            }
        }, "watchdog");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Stops the timer. */
    public synchronized void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }
}
