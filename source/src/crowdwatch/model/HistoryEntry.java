package crowdwatch.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** One timestamped line in the history of an incident or unit. */
public class HistoryEntry {
    // format used for all times shown to users, e.g. 24/11 14:05
    public static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    public final LocalDateTime time;
    public final String text;

    /** Creates a history line. */
    public HistoryEntry(LocalDateTime time, String text) { this.time = time; this.text = text; }

    /** @return time and text on one line */
    @Override
    public String toString() { return time.format(FORMAT) + " " + text; }
}
