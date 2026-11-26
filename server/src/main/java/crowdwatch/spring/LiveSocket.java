package crowdwatch.spring;

import crowdwatch.core.CrowdMonitor;
import crowdwatch.core.MonitorListener;
import crowdwatch.model.Alert;
import crowdwatch.net.ApiServer;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Live updates for the consoles at {@code /ws}: the server tells every connected console the
 * moment something changes, instead of each browser asking every 3 seconds.
 * <p>
 * The messages carry no venue data, only what happened: {@code {"type":"change"}} after changes
 * to areas, units or incidents (at most one every {@link #COALESCE_MS} ms) and
 * {@code {"type":"alert","kind":"CROWD"}} for a new alert. The console then reloads what it shows
 * through the normal API, which checks the user's rights as always.
 * <p>
 * Connecting needs the same sign-in as the API, given in the address because browsers cannot add
 * headers to a WebSocket: {@code /ws?token=<session token>} or {@code /ws?key=<control key>}
 * (the session cookie of the plain dashboard also works). Every {@link #RECHECK_SECONDS} seconds
 * each connection's sign-in is checked again, and connections whose session has ended are closed.
 */
@Configuration
@EnableWebSocket
public class LiveSocket extends TextWebSocketHandler implements WebSocketConfigurer, MonitorListener {
    /** Changes within this time are sent as one message. */
    public static final int COALESCE_MS = 300;
    /** Seconds between checks that each connection's sign-in is still valid. */
    public static final int RECHECK_SECONDS = 60;

    private final ApiServer api;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean changePending = new AtomicBoolean();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "crowdwatch-live");
        t.setDaemon(true);
        return t;
    });

    public LiveSocket(ApiServer api, CrowdMonitor monitor) {
        this.api = api;
        monitor.addListener(this);
        timer.scheduleAtFixedRate(this::recheck, RECHECK_SECONDS, RECHECK_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/ws").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest rq, ServerHttpResponse rs, WebSocketHandler h, Map<String, Object> attrs) {
                Credentials c = Credentials.of(rq.getURI(), rq.getHeaders().get("Cookie"), rq.getRemoteAddress() == null ? "" : rq.getRemoteAddress().getAddress().getHostAddress());
                if (!signedIn(c)) { rs.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED); return false; }
                attrs.put("credentials", c);
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest rq, ServerHttpResponse rs, WebSocketHandler h, Exception e) { }
        });
    }

    /** Who connected: the token or key from the address, any cookies, and the address of the browser. */
    record Credentials(String token, String key, List<String> cookies, String remote) {
        static Credentials of(URI uri, List<String> cookies, String remote) {
            String token = null, key = null;
            if (uri.getRawQuery() != null) for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (pair.startsWith("token=")) token = v;
                else if (pair.startsWith("key=")) key = v;
            }
            return new Credentials(token, key, cookies == null ? List.of() : cookies, remote);
        }
    }

    /** @return true if the API accepts these credentials (asks GET /api/me, so the rules are the API's own) */
    boolean signedIn(Credentials c) {
        ApiServer.Exchange ex = new ApiServer.Exchange("GET", "/api/me", null, name -> switch (name) {
            case "Authorization" -> c.token() == null ? null : List.of("Bearer " + c.token());
            case "X-Api-Key" -> c.key() == null ? null : List.of(c.key());
            case "Cookie" -> c.cookies();
            default -> null;
        }, null, c.remote(), false);
        api.dispatch(ex);
        return ex.code == 200 && new String(ex.replyBody, StandardCharsets.UTF_8).contains("\"signedIn\":true");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession s) throws IOException {
        // sends from several threads are serialised; a console that stops reading is dropped
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(s, 5000, 64 * 1024);
        sessions.put(s.getId(), safe);
        safe.sendMessage(new TextMessage("{\"type\":\"hello\"}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) { sessions.remove(s.getId()); }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage msg) {
        // consoles may send "ping" to keep the connection open through proxies; nothing else is expected
    }

    @Override
    public void onChange() {
        if (changePending.compareAndSet(false, true))
            timer.schedule(() -> { changePending.set(false); broadcast("{\"type\":\"change\"}"); }, COALESCE_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onAlert(Alert a) { broadcast("{\"type\":\"alert\",\"kind\":\"" + a.kind.name() + "\"}"); }

    /** @return the number of consoles connected now */
    public int connected() { return sessions.size(); }

    private void broadcast(String json) {
        TextMessage m = new TextMessage(json);
        for (WebSocketSession s : new ArrayList<>(sessions.values())) {
            try { if (s.isOpen()) s.sendMessage(m); else sessions.remove(s.getId()); }
            catch (IOException | IllegalStateException e) { sessions.remove(s.getId()); closeQuietly(s, CloseStatus.SESSION_NOT_RELIABLE); }
        }
    }

    // closes connections whose sign-in has ended (signed out, session expired, user switched off)
    private void recheck() {
        for (WebSocketSession s : new ArrayList<>(sessions.values())) {
            Object c = s.getAttributes().get("credentials");
            if (!(c instanceof Credentials cr) || !signedIn(cr)) { sessions.remove(s.getId()); closeQuietly(s, new CloseStatus(4401, "signed out")); }
        }
    }

    private static void closeQuietly(WebSocketSession s, CloseStatus why) {
        try { s.close(why); } catch (IOException | IllegalStateException ignored) { }
    }
}
