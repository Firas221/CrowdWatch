package crowdwatch.core;

/**
 * Outcome of an operation on the monitor: success or refusal, with a message for the user.
 * Operations return a Result instead of throwing exceptions for normal rule violations
 * (unknown area, unit busy, restricted area without approval, ...).
 */
public class Result {
    public final boolean ok;
    public final String message;

    // use ok() or fail()
    private Result(boolean ok, String message) { this.ok = ok; this.message = message; }

    /** @return a successful result */
    public static Result ok(String message) { return new Result(true, message); }

    /** @return a refused result */
    public static Result fail(String message) { return new Result(false, message); }

    /** @return "OK: message" or "REFUSED: message" */
    @Override
    public String toString() { return (ok ? "OK: " : "REFUSED: ") + message; }
}
