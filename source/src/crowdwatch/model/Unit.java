package crowdwatch.model;

import java.time.LocalDateTime;
import crowdwatch.ds.CircularBuffer;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.Enums.UnitStatus;

/**
 * A response team or staff member who can be sent to incidents: a security team, medical
 * responder, steward, wheelchair assistant, and so on.
 * <p>
 * A unit has a current area and a list of skills. Languages are stored in the same list
 * (for example "Arabic, English, first-aid, lost-person"), and dispatch checks it for both.
 */
public class Unit {
    public final String id;
    // display name, e.g. "Security Team Alpha"
    public String name;
    // where the unit is now; changed by CrowdMonitor.moveUnit and when it arrives at an incident
    public Area area;
    // AVAILABLE, BUSY or OFF_DUTY
    public UnitStatus status = UnitStatus.AVAILABLE;
    // skills and languages
    public final LinkedList<String> skills = new LinkedList<>();
    // what the unit did, newest last
    public final LinkedList<HistoryEntry> history = new LinkedList<>();
    // last 20 messages sent to the unit's device, oldest overwritten first
    public final CircularBuffer<UnitMessage> inbox = new CircularBuffer<>(20);
    // last time the unit's device contacted the system, null if never
    public LocalDateTime lastContact;
    // battery of the unit's phone in percent as it last reported it, -1 if unknown; warned once when low
    public int battery = -1;
    public boolean batteryWarned;
    // working hours from the venue file, e.g. "06:00-14:00"; null for no shift
    public String shift;
    // true if the shift rule set this unit OFF_DUTY (it comes back at its next shift)
    public boolean shiftOff;
    // true if the shift ended during a task: the unit goes off duty when the task is closed
    public boolean offAfterTask;
    // area the unit was sent to in advance (pre-positioning or cover), or null
    public String stagedFor;
    public LocalDateTime stagedAt;
    // crowded area this unit was sent to as reinforcement, and the area it came from (it returns there), or null
    public String reinforcing, home;

    /** Creates an available unit at a starting area. */
    public Unit(String id, String name, Area area) {
        this.id = id;
        this.name = name;
        this.area = area;
    }

    /**
     * Checks whether the unit has a skill or speaks a language (not case-sensitive).
     *
     * @param skill skill or language; null or blank counts as "no skill needed"
     * @return true if the unit qualifies
     */
    public boolean has(String skill) {
        if (skill == null || skill.isBlank()) return true;
        for (String s : skills) if (s.equalsIgnoreCase(skill.trim())) return true;
        return false;
    }

    /** @return skills and languages joined with commas */
    public String skillList() {
        StringBuilder sb = new StringBuilder();
        for (String s : skills) sb.append(sb.length() == 0 ? "" : ", ").append(s);
        return sb.toString();
    }

    /** @return the unit ID */
    @Override
    public String toString() { return id; }
}
