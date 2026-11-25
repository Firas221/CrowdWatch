package crowdwatch.core;

import crowdwatch.model.Alert;

/**
 * Output hook for other systems. Register an implementation with
 * {@link CrowdMonitor#addListener} to push alerts to SMS, a PA system, digital signage
 * or another service. Both methods have empty defaults, so implement only what you need.
 * <p>
 * Calls arrive on the monitor's event thread, in order, shortly after the change and never
 * while the monitor is locked. A slow listener therefore cannot hold up sensors, dispatch or
 * other terminals, and a listener may safely call monitor methods (for example
 * {@link CrowdMonitor#route}). Listeners run one at a time, so a very slow one delays the
 * notifications behind it; hand long work to your own thread if that matters.
 */
public interface MonitorListener {
    /** Called for every new alert (crowd level, trend, all-clear, no unit, escalation, system). */
    default void onAlert(Alert alert) {}

    /**
     * Called after changes to areas, units or incidents; the GUI uses it to refresh.
     * A burst of changes may be reported with a single call.
     */
    default void onChange() {}
}
