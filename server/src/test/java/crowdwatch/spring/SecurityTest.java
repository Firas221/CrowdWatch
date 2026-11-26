package crowdwatch.spring;

import static org.junit.jupiter.api.Assertions.*;

import crowdwatch.gui.App;
import crowdwatch.net.Accounts;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** Spring Security in front of the API: personal users, roles, device codes, the audit log, the rate limit. */
class SecurityTest {
    static final String KEY = "sec-key";
    static ConfigurableApplicationContext ctx;
    static String base, operator, officer;
    static final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeAll
    static void start() throws Exception {
        App.Headless h = App.prepare(new String[] { "--server", "airport", "0", KEY }, true, null);
        Accounts acc = h.api.accounts();
        assertNull(acc.setUser("sec-operator", Accounts.Role.OPERATOR, "operator-pass-1"));
        assertNull(acc.setUser("sec-officer", Accounts.Role.OFFICER, "officer-pass-1"));
        ctx = CrowdWatchServer.start(h, false);
        base = "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
        operator = login("sec-operator", "operator-pass-1");
        officer = login("sec-officer", "officer-pass-1");
    }

    @AfterAll
    static void stop() { if (ctx != null) ctx.close(); }

    static String login(String user, String pw) throws Exception {
        HttpResponse<String> r = send("POST", "/api/login", null, null, "user=" + user + "&password=" + pw);
        assertEquals(200, r.statusCode(), r.body());
        return r.body().replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    /** @param token bearer token, or null; key: X-Api-Key, or null */
    static HttpResponse<String> send(String method, String path, String token, String key, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (key != null) b.header("X-Api-Key", key);
        if (method.equals("POST")) b.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form == null ? "" : form));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String firstArea() throws Exception {
        return send("GET", "/api/areas", null, KEY, null).body().replaceAll("(?s)^\\[\\{\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void signInIsNeeded() throws Exception {
        HttpResponse<String> r = send("GET", "/api/areas", null, null, null);
        assertEquals(401, r.statusCode());
        assertTrue(r.body().contains("please sign in"), r.body());
        assertEquals(401, send("GET", "/api/areas", "not-a-token", null, null).statusCode());
        assertEquals(200, send("GET", "/api/me", null, null, null).statusCode(), "public endpoints stay open");
        assertEquals(200, send("GET", "/", null, null, null).statusCode());
    }

    @Test
    void rolesAreEnforced() throws Exception {
        assertEquals(200, send("GET", "/api/areas", operator, null, null).statusCode());
        HttpResponse<String> report = send("GET", "/api/report", operator, null, null);
        assertEquals(403, report.statusCode());
        assertTrue(report.body().contains("needs OFFICER"), report.body());
        assertEquals(200, send("GET", "/api/report", officer, null, null).statusCode());
        assertEquals(403, send("GET", "/api/users", officer, null, null).statusCode(), "users are for administrators");
        assertEquals(200, send("POST", "/api/occupancy", operator, null, "area=" + firstArea() + "&delta=1&source=STAFF").statusCode());
    }

    @Test
    void refusedChangesAreAudited() throws Exception {
        HttpResponse<String> r = send("POST", "/api/broadcast", operator, null, "text=refused+broadcast+test");
        assertEquals(403, r.statusCode());
        String audit = send("GET", "/api/audit", officer, null, null).body();
        assertTrue(audit.contains("\"user\":\"sec-operator\"") && audit.contains("refused broadcast test") && audit.contains("\"ok\":false"), audit);
        assertFalse(audit.contains("operator-pass-1"), "passwords are never written to the audit log");
    }

    @Test
    void keyInAFormBodyStillWorks() throws Exception {
        // the plain dashboard posts its forms with the key as a field
        HttpResponse<String> r = send("POST", "/api/occupancy", null, null, "key=" + KEY + "&area=" + firstArea() + "&delta=1&source=STAFF");
        assertEquals(200, r.statusCode(), r.body());
    }

    @Test
    void deviceCodesOnlyDoWhatAPhoneNeeds() throws Exception {
        String unit = send("GET", "/api/units", null, KEY, null).body().replaceAll("(?s)^\\[\\{\"id\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> issued = send("POST", "/api/devices/code", officer, null, "unit=" + unit);
        assertEquals(200, issued.statusCode(), issued.body());
        String code = issued.body().replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> mine = send("GET", "/api/unit", null, code, null);
        assertEquals(200, mine.statusCode(), mine.body());
        assertTrue(mine.body().contains("\"id\":\"" + unit + "\""));
        HttpResponse<String> other = send("GET", "/api/areas", null, code, null);
        assertEquals(403, other.statusCode());
        assertTrue(other.body().contains("scout device cannot use"), other.body());
    }

    @Test
    void liveSocketTakesASessionToken() throws Exception {
        WebSocket ws = http.newWebSocketBuilder().buildAsync(URI.create(base.replace("http", "ws") + "/ws?token=" + URLEncoder.encode(operator, StandardCharsets.UTF_8)),
            new WebSocket.Listener() { }).get(5, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }

    @Test
    void securityHeaders() throws Exception {
        HttpResponse<String> page = send("GET", "/", null, null, null);
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"));
        assertEquals("SAMEORIGIN", page.headers().firstValue("X-Frame-Options").orElse(""));
        HttpResponse<String> api = send("GET", "/api/areas", operator, null, null);
        assertEquals(1, api.headers().allValues("X-Content-Type-Options").size(), "one nosniff header, not two");
        assertTrue(api.headers().firstValue("Content-Security-Policy").isEmpty(), "the policy is for the React console only");
    }

    @Test
    void manyRequestsWithoutTheKeyAreSlowedDown() throws Exception {
        // 50 a second on average with bursts of 200: keep asking until the server says 429
        int sent = 0;
        boolean limited = false;
        while (!limited && sent++ < 2000) limited = send("GET", "/api/me", null, null, null).statusCode() == 429;
        assertTrue(limited, "no request was limited after " + sent);
        assertEquals(200, send("GET", "/api/areas", null, KEY, null).statusCode(), "the control key is never limited");
        Thread.sleep(4500);   // the bucket fills again, so the other tests from this address are not limited
    }
}
