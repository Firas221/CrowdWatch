package crowdwatch.model;

import java.time.Duration;
import java.time.LocalDateTime;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.Enums.*;

/**
 * A request for help: a lost person, a medical case, a security concern, a wheelchair request, and so on.
 * <p>
 * An incident is reported in an area, is assigned to the best available unit (or waits in the
 * queue for its priority), and moves through WAITING, ASSIGNED, ON_SCENE and RESOLVED.
 * Every step is written to its history.
 */
public class Incident {
    // replaces details and contact of personal-data incidents once resolved
    public static final String ERASED = "[erased on resolve]";
    // sequential number starting at 1001 (shown as I1001)
    public final int id;
    public final Category category;
    // starts as the category's priority; escalation can raise it
    public Priority priority;
    // where help is needed
    public final Area area;
    // description from the reporter
    public String details;
    // phone or other contact of the reporter
    public String contact;
    // language the reporter speaks; units that speak it are preferred
    public final String language;
    // time reported
    public final LocalDateTime reported;
    public IncidentStatus status = IncidentStatus.WAITING;
    // ID of the assigned unit, null while waiting
    public String unitId;
    // time the unit arrived
    public LocalDateTime onScene;
    // time resolved
    public LocalDateTime resolved;
    public final LinkedList<HistoryEntry> history = new LinkedList<>();
    // true if the system created it from a crowd reading (not reported by a person)
    public boolean automatic;
    // security officer who reviewed an automatic incident, null until reviewed
    public String reviewedBy;
    // when the task was last sent to the assigned unit
    public LocalDateTime notified;
    // when the assigned unit acknowledged it on its device, null until then
    public LocalDateTime acknowledged;
    // reminders sent because the unit did not acknowledge
    public int reminders;
    // lost-person case: when and where the person was found and who found them (null until found)
    public LocalDateTime foundAt;
    public String foundWhere, foundBy;
    // lost-person case: current search radius in walking minutes (0 for other incidents)
    public int searchMinutes;
    // lost-person case: escalation steps already taken (1 = 15 min, 2 = 30 min)
    public int lostSteps;
    // follow-ups because the unit acknowledged but did not arrive (1 = reminder, 2 = backup sent)
    public int followUps;
    // true once the long-wait rule has acted on this waiting incident
    public boolean waitHandled;

    /** Creates a new incident in the WAITING state. */
    public Incident(int id, Category category, Priority priority, Area area, String details, String contact, String language, LocalDateTime reported) {
        this.id = id;
        this.category = category;
        this.priority = priority;
        this.area = area;
        this.details = details;
        this.contact = contact;
        this.language = language;
        this.reported = reported;
    }

    /** @return true until the incident is resolved */
    public boolean open() { return status != IncidentStatus.RESOLVED; }

    /** @return true if this is a lost-person case (its category needs the lost-person skill) */
    public boolean lostCase() { return category.skill != null && category.skill.equalsIgnoreCase("lost-person"); }

    /**
     * @param now current time
     * @return minutes from report to resolve, or to now if still open
     */
    public long minutesOpen(LocalDateTime now) {
        return Duration.between(reported, resolved != null ? resolved : now).toMinutes();
    }

    /** @return the incident number, e.g. I1001 */
    @Override
    public String toString() { return "I" + id; }
}
