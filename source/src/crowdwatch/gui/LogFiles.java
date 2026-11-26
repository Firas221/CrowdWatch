package crowdwatch.gui;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

/**
 * The server's console output also goes to a daily log file, crowdwatch-data/logs/&lt;venue&gt;-&lt;date&gt;.log;
 * a new file starts at midnight and files older than {@link #KEEP_DAYS} days are deleted, so the
 * logs never fill the disk.
 */
public final class LogFiles extends OutputStream {
    /** Days of log files kept. */
    public static final int KEEP_DAYS = 30;
    private final Path folder;
    private final String prefix;
    private final OutputStream console;
    private LocalDate day;
    private OutputStream file;

    private LogFiles(Path folder, String prefix, OutputStream console) { this.folder = folder; this.prefix = prefix; this.console = console; }

    /** Sends System.out (and System.err) to the console and to the daily files. */
    public static void tee(Path folder, String prefix) {
        LogFiles out = new LogFiles(folder, prefix, System.out);
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LogFiles(folder, prefix, System.err), true, StandardCharsets.UTF_8));
    }

    private synchronized OutputStream current() throws IOException {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            if (file != null) file.close();
            Files.createDirectories(folder);
            file = Files.newOutputStream(folder.resolve(prefix + "-" + today + ".log"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            day = today;
            try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(folder, prefix + "-*.log")) {
                for (Path p : ds) {
                    String d = p.getFileName().toString().substring(prefix.length() + 1).replace(".log", "");
                    try { if (LocalDate.parse(d).isBefore(today.minusDays(KEEP_DAYS))) Files.deleteIfExists(p); } catch (java.time.format.DateTimeParseException ignored) { }
                }
            }
        }
        return file;
    }

    @Override public synchronized void write(int b) throws IOException {
        console.write(b);
        try { current().write(b); } catch (IOException ignored) { }
    }

    @Override public synchronized void write(byte[] b, int off, int len) throws IOException {
        console.write(b, off, len);
        try { current().write(b, off, len); } catch (IOException ignored) { }
    }

    @Override public synchronized void flush() throws IOException {
        console.flush();
        if (file != null) file.flush();
    }
}
