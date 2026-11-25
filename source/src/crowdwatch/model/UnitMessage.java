package crowdwatch.model;

import java.time.LocalDateTime;
import crowdwatch.model.Enums.MessageKind;

/**
 * A message sent to one response unit (scout team): a new task, a reminder, an SOS call,
 * an evacuation order or a broadcast. Messages are kept in the unit's inbox for its device
 * and handed to every delivery channel (radio gateway, console relay).
 */
public class UnitMessage {
    public final LocalDateTime time;
    public final MessageKind kind;
    // incident the message is about, or 0
    public final int incidentId;
    // text to show on the device or read out on the radio
    public final String text;

    /** Creates a message. */
    public UnitMessage(LocalDateTime time, MessageKind kind, int incidentId, String text) {
        this.time = time;
        this.kind = kind;
        this.incidentId = incidentId;
        this.text = text;
    }

    /** @return time, kind and text on one line */
    @Override
    public String toString() { return time.format(HistoryEntry.FORMAT) + "  " + kind + "  " + text; }
}
