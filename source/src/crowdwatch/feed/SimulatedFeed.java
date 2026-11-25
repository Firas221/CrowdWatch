package crowdwatch.feed;

import java.util.Random;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.model.Area;
import crowdwatch.model.Enums.Source;

/**
 * Pretend sensor network for demonstrations and testing.
 * <p>
 * A background thread changes the people count of about a third of the areas at random
 * every period, through the same {@link CrowdMonitor#updateOccupancy} call a real sensor
 * would use. A fixed random seed gives the same sequence on every run.
 */
public class SimulatedFeed implements CrowdFeed {
    private final long periodMillis;
    private final Random random;
    // the running thread, or null when stopped; volatile so stop() is seen at once
    private volatile Thread worker;

    /**
     * @param periodMillis time between updates in milliseconds
     * @param seed         random seed, so a demonstration can be repeated exactly
     */
    public SimulatedFeed(long periodMillis, long seed) {
        this.periodMillis = periodMillis;
        this.random = new Random(seed);
    }

    /** @return "Simulated sensors" */
    public String name() { return "Simulated sensors"; }

    /** @return true while the background thread is running */
    public boolean running() { return worker != null; }

    /** Starts a daemon thread that calls {@link #tick} every period. Does nothing if already running. */
    public void start(CrowdMonitor monitor) {
        if (worker != null) return;
        Thread t = new Thread(() -> {
            while (worker == Thread.currentThread()) {
                tick(monitor);
                try { Thread.sleep(periodMillis); } catch (InterruptedException e) { return; }
            }
        }, "simulated-feed");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** Stops the background thread. The loop checks {@code worker} and ends when it is cleared. */
    public void stop() {
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    /**
     * One simulation step: picks random areas and moves each count up or down by up to 1/12 of
     * its capacity. Areas below 40% get a small push upwards so the demonstration shows alerts.
     * Counts stay between 0 and 110% of capacity. Holds the monitor's lock for the whole step.
     */
    public void tick(CrowdMonitor monitor) {
        synchronized (monitor) {
            Area[] all = monitor.areaTree().inOrder().toArray(new Area[0]);
            if (all.length == 0) return;
            for (int k = 0; k < Math.max(1, all.length / 3); k++) {
                Area a = all[random.nextInt(all.length)];
                int step = Math.max(1, a.capacity / 12);
                int delta = random.nextInt(2 * step + 1) - step + (a.percent() < 40 ? step / 3 : 0);
                monitor.updateOccupancy(a.id, Math.min(a.capacity * 11 / 10, Math.max(0, a.occupancy + delta)), Source.SIMULATION);
            }
        }
    }
}
