package crowdwatch.model;

import java.time.LocalDateTime;
import crowdwatch.model.Enums.AlertKind;

/**
 * A message raised by the monitor: a crowd level change, a trend warning, an all-clear,
 * an incident with no free unit, an escalation, or a system note.
 * Alerts are kept in a linked list and passed to every {@link crowdwatch.core.MonitorListener}.
 */
public class Alert {
    public final LocalDateTime time;
    public final AlertKind kind;
    // ID of the area or incident the alert is about
    public final String ref;
    public final String text;

    /** Creates an alert. */
    public Alert(LocalDateTime time, AlertKind kind, String ref, String text) {
        this.time = time;
        this.kind = kind;
        this.ref = ref;
        this.text = text;
    }

    /** @return time, kind and text on one line */
    @Override
    public String toString() { return time.format(HistoryEntry.FORMAT) + "  " + kind + "  " + text; }
}
