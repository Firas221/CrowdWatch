package crowdwatch.core;

import crowdwatch.model.Unit;
import crowdwatch.model.UnitMessage;

/**
 * A way of getting a message to a response unit: a radio gateway, an SMS service, a pager
 * system or a console that a radio operator reads out.
 * <p>
 * Register channels with {@link CrowdMonitor#addChannel}. Every message sent to a unit (a new
 * task, a reminder, an SOS call, an evacuation order, a broadcast) is passed to every channel
 * on the monitor's event thread, outside its lock. Every message is also kept in the unit's
 * inbox, which the scout device page shows. If {@link #deliver} throws, the monitor raises a
 * DELIVERY alert so the control room can reach the unit another way.
 */
public interface UnitChannel {
    /** @return a short name for alerts, e.g. "Radio gateway" */
    String name();

    /**
     * Delivers one message to one unit.
     *
     * @throws Exception if the message could not be delivered
     */
    void deliver(Unit unit, UnitMessage message) throws Exception;

    /**
     * Delivers an approved public message for screens and the PA. Channels that cannot reach
     * public systems ignore it (the default).
     *
     * @param text the message
     * @throws Exception if delivery failed (a DELIVERY alert is raised)
     */
    default void announce(String text) throws Exception { }
}
