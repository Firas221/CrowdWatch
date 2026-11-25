package crowdwatch.model;

import crowdwatch.model.Enums.Priority;

/**
 * A type of incident defined in the venue file, e.g. LOST_PERSON or MEDICAL.
 * The category decides the incident's priority, which skill a unit needs to take it,
 * and whether the personal details must be erased when it is resolved.
 */
public class Category {
    // short code used in the venue file and API, e.g. LOST_PERSON
    public final String code;
    // text shown to users
    public final String label;
    // starting priority of new incidents in this category
    public final Priority priority;
    // skill a unit must have; empty means any unit can respond
    public final String skill;
    // if true, details and contact are erased on resolve
    public final boolean personalData;

    /** Creates a category. */
    public Category(String code, String label, Priority priority, String skill, boolean personalData) {
        this.code = code;
        this.label = label;
        this.priority = priority;
        this.skill = skill == null || skill.isBlank() ? null : skill.trim();
        this.personalData = personalData;
    }

    /** @return the label */
    @Override
    public String toString() { return label; }
}
