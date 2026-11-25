package crowdwatch.feed;

import crowdwatch.core.CrowdMonitor;

/**
 * Input adapter that feeds people counts or incidents into the monitor.
 * <p>
 * Write one class per kind of device (turnstiles, cameras, Wi-Fi counters, a CSV export, a
 * simulation). The adapter calls the monitor's public methods such as
 * {@link CrowdMonitor#updateOccupancy} or {@link CrowdMonitor#adjustOccupancy}. Those methods
 * are synchronized, so a feed can run on its own thread.
 */
public interface CrowdFeed {
    /** @return a name to show in the GUI */
    String name();

    /** Starts delivering data to the monitor. */
    void start(CrowdMonitor monitor);

    /** Stops delivering data and releases any resources. */
    void stop();

    /** @return true while the feed is delivering data */
    boolean running();
}
