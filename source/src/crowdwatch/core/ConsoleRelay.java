package crowdwatch.core;

import java.io.PrintStream;
import java.time.Duration;
import crowdwatch.model.Enums.MessageKind;
import crowdwatch.model.Unit;
import crowdwatch.model.UnitMessage;

/**
 * Radio-operator relay for server mode: prints unit messages as "RADIO &gt;&gt; unit: text" so an
 * operator can read them out on the radio.
 * <p>
 * To keep the list short enough for a person to work through, it prints only what the unit
 * cannot already see: messages for units whose device has not been in contact for
 * {@link #DEVICE_ONLINE_SECONDS} seconds (no device, or the device is offline). "Task closed"
 * confirmations are never printed, because the unit closed the task itself. Everything is still
 * kept in the unit's inbox and sent to a radio gateway, if one is configured.
 */
public class ConsoleRelay implements UnitChannel {
    /** A device seen within this many seconds counts as online (it polls every 3 s). */
    public static final int DEVICE_ONLINE_SECONDS = 30;
    private final PrintStream out;
    private final Clock clock;
    // messages not printed because the unit's device showed them
    private int skipped;

    /**
     * @param out   where to print (System.out in server mode)
     * @param clock time source, to decide whether a device is online
     */
    public ConsoleRelay(PrintStream out, Clock clock) { this.out = out; this.clock = clock; }

    /** @return "Console radio relay" */
    public String name() { return "Console radio relay"; }

    /** Prints the message unless the unit's device is online or it is only a "task closed" note. */
    public synchronized void deliver(Unit u, UnitMessage msg) {
        boolean online = u.lastContact != null && Duration.between(u.lastContact, clock.now()).getSeconds() < DEVICE_ONLINE_SECONDS;
        if (online || msg.kind == MessageKind.RELEASED) { skipped++; return; }
        if (msg.kind == MessageKind.LOOKOUT) {
            // a lookout goes to every unit: the radio operator reads it out once to all units
            String key = msg.incidentId + ":" + msg.text.substring(0, Math.min(40, msg.text.length()));
            if (key.equals(lastLookout)) { skipped++; return; }
            lastLookout = key;
            out.println("RADIO >> ALL UNITS: " + msg.text.replaceAll("\\. YOU ARE [^.]*", ""));
            return;
        }
        out.println("RADIO >> " + u.id + " (" + u.name + "): " + msg.text);
    }

    // the last lookout read out, so the same lookout is not repeated for every unit
    private String lastLookout;

    /** Prints an approved public message for the PA operator. */
    @Override
    public synchronized void announce(String text) { out.println("SIGNS/PA >> " + text); }

    /** @return how many messages were not printed because the unit's device showed them */
    public synchronized int skipped() { return skipped; }
}
