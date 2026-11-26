package crowdwatch.model;

/** All the enumerations used by the model, kept in one file. */
public final class Enums {
    // holder for the enums only, no instances
    private Enums() {}

    /** Crowd level of an area, from the occupancy percentage and the venue's three thresholds. */
    public enum CrowdLevel { LOW, MODERATE, HIGH, CRITICAL }

    /** Where a people count or level came from. */
    public enum Source { SENSOR, STAFF, OFFICIAL, SIMULATION, IMPORT }

    /**
     * Availability of a response unit. BUSY is set automatically while it has an open incident.
     * UNREACHABLE is set when the unit did not acknowledge a task twice; it is not dispatched
     * again until it answers on its device or an officer sets it AVAILABLE.
     */
    public enum UnitStatus { AVAILABLE, BUSY, OFF_DUTY, UNREACHABLE }

    /** Life cycle of an incident: WAITING (in a queue) -&gt; ASSIGNED -&gt; ON_SCENE -&gt; RESOLVED. */
    public enum IncidentStatus { WAITING, ASSIGNED, ON_SCENE, RESOLVED }

    /** Incident priority. The order matters: CRITICAL is served first, LOW last. */
    public enum Priority { CRITICAL, HIGH, NORMAL, LOW }

    /** Type of an {@link Alert}. */
    public enum AlertKind { CROWD, TREND, CLEARED, NO_UNIT, ESCALATED, SYSTEM, AUTO_DISPATCH, SUPERVISION, NO_ACK, SOS, EVACUATION, BROADCAST, DELIVERY,
        SENSOR, FOLLOW_UP, FLOW, REPOSITION, EVENT, PROPOSAL, ALARM, LOST, SHIFT, ANNOUNCE, HEAT, MESSAGE, BATTERY, NOTIFY }

    /** Type of a {@link UnitMessage} sent to a unit's device or radio. */
    public enum MessageKind { TASK, REMINDER, CANCELLED, RELEASED, SOS, SOS_NEARBY, EVACUATE, BROADCAST, LOOKOUT, HOLD, MOVE, CHECK, BACKUP, SHIFT, MESSAGE }
}
