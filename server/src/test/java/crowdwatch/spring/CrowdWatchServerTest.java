package crowdwatch.spring;

import static org.junit.jupiter.api.Assertions.*;

import crowdwatch.gui.App;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs the Spring Boot server (a drill of the airport, with a control key) and talks to it over HTTP and WebSocket. */
class CrowdWatchServerTest {
    static final String KEY = "test-key";
    static ConfigurableApplicationContext ctx;
    static String base;
    static final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() throws Exception {
        App.Headless h = App.prepare(new String[] { "--server", "airport", "0", KEY }, true, null);
        ctx = CrowdWatchServer.start(h, false);
        base = "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stop() { if (ctx != null) ctx.close(); }

    static HttpResponse<String> get(String path, boolean withKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (withKey) b.header("X-Api-Key", KEY);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(String path, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).header("X-Api-Key", KEY)
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void apiAnswersThroughSpring() throws Exception {
        HttpResponse<String> r = get("/api/overview", true);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"drill\":true"), r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
    }

    @Test
    void keyIsStillRequired() throws Exception {
        assertEquals(401, get("/api/areas", false).statusCode());
        assertEquals(200, get("/api/areas", true).statusCode());
        assertEquals(404, get("/api/no-such-thing", true).statusCode());
    }

    @Test
    void changesAndRulesWork() throws Exception {
        String area = get("/api/areas", true).body().replaceAll("(?s)^\\[\\{\"id\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> ok = post("/api/occupancy", "area=" + area + "&count=10&source=STAFF");
        assertEquals(200, ok.statusCode(), ok.body());
        HttpResponse<String> refused = post("/api/occupancy", "area=NOWHERE&count=10&source=STAFF");
        assertEquals(422, refused.statusCode(), refused.body());
    }

    @Test
    void publicPagesAndLegacyConsole() throws Exception {
        assertTrue(get("/legacy", false).body().contains("CrowdWatch console"));
        assertTrue(get("/console.js", false).body().contains("CrowdWatch web console"));
        int health = get("/health", false).statusCode();
        assertTrue(health == 200 || health == 503);
        assertEquals(200, get("/", false).statusCode());
    }

    @Test
    void webSocketNeedsSignIn() {
        CompletableFuture<WebSocket> ws = http.newWebSocketBuilder().buildAsync(URI.create(base.replace("http", "ws") + "/ws"), new WebSocket.Listener() { });
        Exception e = assertThrows(Exception.class, () -> ws.get(5, TimeUnit.SECONDS));
        assertInstanceOf(java.net.http.WebSocketHandshakeException.class, e.getCause());
        assertEquals(401, ((java.net.http.WebSocketHandshakeException) e.getCause()).getResponse().statusCode());
    }

    @Test
    void webSocketPushesChanges() throws Exception {
        LinkedBlockingQueue<String> got = new LinkedBlockingQueue<>();
        WebSocket ws = http.newWebSocketBuilder().buildAsync(URI.create(base.replace("http", "ws") + "/ws?key=" + KEY), new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                got.add(data.toString());
                w.request(1);
                return null;
            }
        }).get(5, TimeUnit.SECONDS);
        try {
            assertEquals("{\"type\":\"hello\"}", got.poll(5, TimeUnit.SECONDS));
            String area = get("/api/areas", true).body().replaceAll("(?s)^\\[\\{\"id\":\"([^\"]+)\".*", "$1");
            long t0 = System.nanoTime();
            post("/api/occupancy", "area=" + area + "&delta=1&source=STAFF");
            String m;
            do { m = got.poll(3, TimeUnit.SECONDS); } while (m != null && !m.contains("change"));
            assertNotNull(m, "no change message within 3 s");
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000, "the change took too long to arrive");
        } finally {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }
}
