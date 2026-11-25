package crowdwatch.model;

import java.time.LocalDateTime;

/**
 * One people count for an area at a point in time. Stored in the area's circular buffer
 * and used to measure the trend.
 */
public class Reading {
    public final LocalDateTime time;
    public final int occupancy;

    /** Creates a reading. */
    public Reading(LocalDateTime time, int occupancy) { this.time = time; this.occupancy = occupancy; }

    /** @return time and count, e.g. 14:05:00=320 */
    @Override
    public String toString() { return time.toLocalTime().withNano(0) + "=" + occupancy; }
}
