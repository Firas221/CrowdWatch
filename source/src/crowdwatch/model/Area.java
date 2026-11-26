package crowdwatch.model;

import java.time.Duration;
import java.time.LocalDateTime;
import crowdwatch.ds.CircularBuffer;
import crowdwatch.model.Enums.*;

/**
 * A place in the venue that people occupy: a gate, hall, checkpoint, stand or courtyard.
 * <p>
 * An area has a fixed capacity and a live people count. Its crowd level is worked out by
 * {@link crowdwatch.core.CrowdMonitor} from the occupancy percentage and the venue's thresholds.
 * The last 60 counts are kept in a circular buffer to measure how fast the area is filling.
 */
public class Area {
    // unique ID used in the venue file, e.g. SEC-1
    public final String id;
    public final String name;
    // group of areas, e.g. "Landside T1" or "Makkah"
    public final String sector;
    // free label from the venue file, e.g. GATE, HALL, CHECKPOINT
    public final String type;
    // maximum safe number of people
    public final int capacity;
    // staff-only: units need supervisor approval, public routes never pass through
    public final boolean restricted;
    // current people count
    public int occupancy;
    // floor area in square metres (size: line), 0 if not known; with it the level also follows the density
    public double sizeM2;
    public CrowdLevel level = CrowdLevel.LOW;
    // who supplied the latest count or level (sensor, staff, ...)
    public Source source;
    // time of the latest update
    public LocalDateTime updated;
    // true once a TREND alert was raised; reset when the rise stops
    public boolean trendWarned;
    // time of the last TREND alert, for the cool-down; null if none yet
    public LocalDateTime lastTrendAlert;
    // true while this area is part of an evacuation zone; routes then avoid it
    public boolean evacuating;
    // number of the open automatic crowd incident for this area, 0 if none
    public int autoIncident;
    // time of the latest count from an automatic source (sensor, simulation, import); null if none yet
    public LocalDateTime lastSensor;
    // since when the sensor has been silent too long; null while its data is fresh
    public LocalDateTime staleSince;
    // why the latest sensor count looks wrong (an impossible jump), or null; cleared by a staff count
    public String suspect;
    // when the area last dropped below HIGH (for closing its automatic incident), or null while HIGH or above
    public LocalDateTime belowHighSince;
    // when the area became CRITICAL (for proposing an evacuation), or null
    public LocalDateTime criticalSince;
    // percentage points added during a scheduled event, so its levels start earlier (0 normally)
    public int thresholdOffset;
    // true while units next to this area are told to hold people back
    public boolean flowHeld;
    // "AREA|LEVEL" once the control room was told the area is short of reinforcements at that level
    public String reinforceShortNoted;
    /**
     * Minimum time between stored readings. With 60 slots this keeps about 10 minutes of
     * history, the trend window, even when a turnstile sends a count for every person.
     */
    public static final int SAMPLE_SECONDS = 10;

    // last 60 sampled counts (about one per 10 s), oldest overwritten first
    public final CircularBuffer<Reading> readings = new CircularBuffer<>(60);

    /** Creates an empty area (occupancy 0, level LOW). */
    public Area(String id, String name, String sector, String type, int capacity, boolean restricted) {
        this.id = id;
        this.name = name;
        this.sector = sector;
        this.type = type;
        this.capacity = capacity;
        this.restricted = restricted;
    }

    /**
     * Stores a new count in the readings buffer, at most one reading per {@link #SAMPLE_SECONDS}.
     * <p>
     * If the reading before the newest one is less than SAMPLE_SECONDS older than {@code now},
     * the new count replaces the newest reading; otherwise it is appended. The newest reading is therefore
     * always the live count, while older readings stay about 10 seconds apart. Without this,
     * a sensor that reports every person would fill all 60 slots within seconds and the trend
     * rate could never be measured.
     *
     * @param now   time of the count
     * @param count people counted
     */
    public void record(LocalDateTime now, int count) {
        Reading r = new Reading(now, count);
        int n = readings.size();
        if (n >= 2 && Duration.between(readings.get(n - 2).time, now).getSeconds() < SAMPLE_SECONDS) readings.replaceLast(r);
        else readings.add(r);
    }

    /** @return occupancy as a whole percentage of capacity (0 if capacity is not positive) */
    public int percent() { return capacity <= 0 ? 0 : (int) Math.round(occupancy * 100.0 / capacity); }

    /** @return people per square metre, or 0 if the area's size is not known */
    public double density() { return sizeM2 <= 0 ? 0 : occupancy / sizeM2; }

    /**
     * Measures how fast the area is filling, in people per minute.
     * <p>
     * Takes the oldest reading that is still inside the time window and compares it with the
     * newest reading. Readings less than 30 seconds apart are ignored, because a rate over a very
     * short time is mostly noise.
     *
     * @param now           current time
     * @param windowMinutes how far back to look (CrowdMonitor uses 10 minutes)
     * @return people per minute; negative when the area is emptying, 0 when there is not enough data
     */
    public double ratePerMinute(LocalDateTime now, int windowMinutes) {
        Reading last = readings.last();
        if (last == null) return 0;
        for (int i = 0; i < readings.size(); i++) {
            Reading r = readings.get(i);
            if (Duration.between(r.time, now).toMinutes() > windowMinutes) continue;
            long sec = Duration.between(r.time, last.time).getSeconds();
            return sec < 30 ? 0 : (last.occupancy - r.occupancy) * 60.0 / sec;
        }
        return 0;
    }

    /**
     * Predicts how many minutes until occupancy reaches a percentage, assuming the current rate continues.
     *
     * @param percent target percentage, e.g. the CRITICAL threshold
     * @param rate    people per minute from {@link #ratePerMinute}
     * @return 0 if already at or above the target, -1 if the area is not filling, otherwise minutes (rounded up)
     */
    public int minutesTo(int percent, double rate) {
        int target = (int) Math.ceil(capacity * percent / 100.0);
        if (occupancy >= target) return 0;
        if (rate <= 0) return -1;
        return (int) Math.ceil((target - occupancy) / rate);
    }

    /** @return the area ID */
    @Override
    public String toString() { return id; }
}
