package crowdwatch.feed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.core.Result;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.Enums.Source;

/**
 * Imports people counts from a CSV file, e.g. an export from a counting system.
 * <p>
 * Each line is {@code area,count[,source]}. Blank lines, lines starting with # and a header line
 * starting with "area" are skipped. Bad lines are not applied; they are listed in {@link #log()}
 * so the user can see what went wrong.
 */
public class CsvFeed implements CrowdFeed {
    private final Path file;
    private final LinkedList<String> log = new LinkedList<>();
    // number of lines applied in the last import
    private int applied;

    /** @param file the CSV file to import */
    public CsvFeed(Path file) { this.file = file; }

    /** @return "CSV import: " and the file name */
    public String name() { return "CSV import: " + file.getFileName(); }

    /** @return always false: an import runs once and finishes */
    public boolean running() { return false; }

    // nothing to stop: the import runs once inside start()
    public void stop() {}

    /** Reads the file and applies every valid line. A read error is added to the log. */
    public void start(CrowdMonitor monitor) {
        try {
            applied = apply(monitor, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.addLast("Cannot read " + file + ": " + e.getMessage());
        }
    }

    /**
     * Applies CSV text to the monitor, line by line.
     *
     * @param csv the CSV contents
     * @return number of lines applied
     */
    public int apply(CrowdMonitor monitor, String csv) {
        int ok = 0;
        String[] lines = csv.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#") || line.toLowerCase().startsWith("area")) continue;
            String[] f = line.split(",");
            if (f.length < 2) { log.addLast("Line " + (i + 1) + ": expected area,count"); continue; }
            try {
                Source src = f.length > 2 ? Source.valueOf(f[2].trim().toUpperCase()) : Source.IMPORT;
                Result r = monitor.updateOccupancy(f[0].trim(), Integer.parseInt(f[1].trim()), src);
                if (r.ok) ok++; else log.addLast("Line " + (i + 1) + ": " + r.message);
            } catch (IllegalArgumentException e) {
                log.addLast("Line " + (i + 1) + ": " + e.getMessage());
            }
        }
        applied = ok;
        return ok;
    }

    /** @return number of lines applied in the last import */
    public int applied() { return applied; }

    /** @return one message per line that could not be applied */
    public LinkedList<String> log() { return log; }
}
