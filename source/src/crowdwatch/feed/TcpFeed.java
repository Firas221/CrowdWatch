package crowdwatch.feed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import crowdwatch.core.CrowdMonitor;
import crowdwatch.ds.LinkedList;
import crowdwatch.model.Enums.Source;

/**
 * Listens on a TCP port for lines in {@link LineFormat}, as sent by counting devices, PLCs and
 * serial-to-network gateways that can only write plain text to a socket. Only the listed
 * addresses may connect (an empty list allows every address, which is only acceptable on a
 * closed sensor network). Each line is answered with "OK" or "ERR reason". Venue line:
 * {@code feed: tcp <port> [allowed-address ...]}.
 */
public class TcpFeed implements CrowdFeed {
    /** Longest line accepted; longer lines are refused. */
    public static final int MAX_LINE = 200;
    private final int port;
    private final String[] allowed;
    private final LinkedList<String> log = new LinkedList<>();
    private volatile ServerSocket server;
    private volatile int lines;

    public TcpFeed(int port, String... allowed) { this.port = port; this.allowed = allowed == null ? new String[0] : allowed; }

    public String name() { return "TCP port " + port; }

    /** @return the port actually listened on (0 picks a free one; used by the tests) */
    public int port() { ServerSocket s = server; return s == null ? port : s.getLocalPort(); }

    public synchronized void start(CrowdMonitor m) {
        if (server != null) return;
        try { server = new ServerSocket(port); }
        catch (IOException e) { note("cannot listen on port " + port + ": " + e.getMessage()); return; }
        Thread t = new Thread(() -> accept(m), "feed-tcp");
        t.setDaemon(true);
        t.start();
    }

    private void accept(CrowdMonitor m) {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try {
                Socket c = s.accept();
                if (!allowed(c.getInetAddress())) { note("refused " + c.getInetAddress().getHostAddress()); c.close(); continue; }
                Thread t = new Thread(() -> serve(m, c), "feed-tcp-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!s.isClosed()) note("accept: " + e.getMessage());
            }
        }
    }

    private boolean allowed(InetAddress a) {
        if (allowed.length == 0) return true;
        for (String ok : allowed) if (ok.equals(a.getHostAddress()) || (ok.equals("localhost") && a.isLoopbackAddress())) return true;
        return false;
    }

    private void serve(CrowdMonitor m, Socket c) {
        try (c; BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
             java.io.Writer out = new java.io.OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8)) {
            c.setSoTimeout(5 * 60 * 1000);   // a silent device is dropped after 5 minutes; it reconnects
            String line;
            while ((line = in.readLine()) != null) {
                String why = line.length() > MAX_LINE ? "line too long" : LineFormat.apply(m, line, Source.SENSOR);
                if (why == null) lines++; else note(c.getInetAddress().getHostAddress() + ": " + why);
                out.write(why == null ? "OK\n" : "ERR " + why + "\n");
                out.flush();
            }
        } catch (IOException e) {
            // the device disconnected
        }
    }

    private synchronized void note(String s) { log.addLast(s); while (log.size() > 100) log.removeFirst(); }

    public synchronized void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
    }

    public boolean running() { return server != null; }

    /** @return lines applied so far */
    public int lines() { return lines; }

    public synchronized LinkedList<String> log() { return log; }
}
