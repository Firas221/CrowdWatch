package crowdwatch.core;

import java.time.LocalDateTime;

/**
 * Source of the current time. The monitor asks the clock instead of calling
 * LocalDateTime.now() directly, so the tests can use a fixed clock and get the same
 * results on every run (for example for the trend warning).
 */
public interface Clock {
    /** @return the current date and time */
    LocalDateTime now();

    // the real system clock, used by the application
    Clock SYSTEM = LocalDateTime::now;
}
