package crowdwatch.feed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.Enums.Source;

/**
 * Picks up count files that a counting system drops into a folder (many camera and door-counter
 * systems can export a file every minute): every {@code seconds} it reads each *.csv or *.txt file
 * in {@link LineFormat}, applies it, and moves it to done/ (or to failed/ if no line could be
 * applied), so no file is read twice. Venue line: {@code feed: folder <path> [seconds]}.
 */
public class FolderFeed implements CrowdFeed {
    private final Path folder;
    private final int seconds;
    private final LinkedList<String> log = new LinkedList<>();
    private java.util.concurrent.ScheduledExecutorService timer;
    private volatile int files, lines;

    /** @param seconds how often the folder is checked (at least 1) */
    public FolderFeed(Path folder, int seconds) { this.folder = folder; this.seconds = Math.max(1, seconds); }

    public String name() { return "Folder " + folder + " every " + seconds + " s"; }

    public synchronized void start(CrowdMonitor m) {
        if (timer != null) return;
        timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "feed-folder"); t.setDaemon(true); return t; });
        timer.scheduleWithFixedDelay(() -> scan(m), 0, seconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Reads every waiting file once (also called by the tests). */
    public void scan(CrowdMonitor m) {
        try {
            Files.createDirectories(folder);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder, "*.{csv,txt,CSV,TXT}")) {
                for (Path f : ds) {
                    LinkedList<String> problems = new LinkedList<>();
                    int ok = LineFormat.applyAll(m, Files.readString(f, StandardCharsets.UTF_8), Source.SENSOR, problems);
                    for (String p : problems) note(f.getFileName() + " " + p);
                    Path to = folder.resolve(ok > 0 || problems.isEmpty() ? "done" : "failed");
                    Files.createDirectories(to);
                    Files.move(f, to.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    files++;
                    lines += ok;
                }
            }
        } catch (IOException | RuntimeException e) {
            note("folder " + folder + ": " + e.getMessage());
        }
    }

    private synchronized void note(String s) { log.addLast(s); while (log.size() > 100) log.removeFirst(); }

    public synchronized void stop() { if (timer != null) timer.shutdownNow(); timer = null; }

    public synchronized boolean running() { return timer != null; }

    /** @return files and lines applied so far */
    public int[] counts() { return new int[] { files, lines }; }

    /** @return the newest problems (at most 100) */
    public synchronized LinkedList<String> log() { return log; }
}
