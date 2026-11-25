package crowdwatch.core;

/** Read-only access to reinforcement counts for the tests and the API (outside the core package). */
public final class AutoRulesProbe {
    /** Utility class: no instances. */
    private AutoRulesProbe() { }

    /** @return units on duty at an area, sent there, or busy with a task there */
    public static int unitsAt(CrowdMonitor m, String areaId) {
        synchronized (m) { crowdwatch.model.Area a = m.findArea(areaId); return a == null ? 0 : m.rules.unitsAt(a); }
    }

    /** @return how many units the area should have now (0 below HIGH) */
    public static int target(CrowdMonitor m, String areaId) {
        synchronized (m) { crowdwatch.model.Area a = m.findArea(areaId); return a == null ? 0 : m.rules.target(a); }
    }
}
