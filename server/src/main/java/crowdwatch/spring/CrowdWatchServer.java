package crowdwatch.spring;

import crowdwatch.core.CrowdMonitor;
import crowdwatch.gui.App;
import crowdwatch.net.ApiServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * CrowdWatch server on Spring Boot: the same monitor, rules, database and API as
 * {@code java -jar CrowdWatch.jar --server}, served by Spring's embedded web server, with the
 * React console, live updates over a WebSocket ({@link LiveSocket}) and HTTPS through Spring.
 * <p>
 * Usage (the same arguments as {@code --server} and {@code --drill}):
 * <pre>
 *   java -jar CrowdWatch-server.jar &lt;airport|stadium|umrah|file.venue&gt; [port] [control-key] [scout-key] [--https]
 *   java -jar CrowdWatch-server.jar --drill &lt;venue&gt; [port] [file.drill] [--https]
 * </pre>
 */
@SpringBootApplication
public class CrowdWatchServer {
    // set by main before Spring starts; the beans below hand it to the controllers
    private static App.Headless parts;

    /** Starts the server; see the class comment for the arguments. */
    public static void main(String[] args) throws Exception {
        boolean https = false, drill = false;
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--https")) https = true;
            else if (a.equals("--drill")) drill = true;
            else if (!a.equals("--server")) rest.add(a);
        }
        if (System.getenv("CROWDWATCH_HTTPS") != null && !System.getenv("CROWDWATCH_HTTPS").isBlank()) https = true;
        String script = null;
        List<String> srv = new ArrayList<>(List.of("--server", rest.isEmpty() ? "airport" : rest.get(0), rest.size() > 1 ? rest.get(1) : drill ? "8081" : "8080"));
        if (drill) script = rest.size() > 2 ? rest.get(2) : null;
        else for (int k = 2; k < rest.size(); k++) srv.add(rest.get(k));
        App.Headless h = App.prepare(srv.toArray(new String[0]), drill, script);
        if (h == null) return;
        start(h, https);   // CrowdWatch's own arguments are not Spring settings
    }

    /**
     * Starts Spring Boot around prepared server parts.
     *
     * @param h     the monitor and API, from {@link App#prepare}
     * @param https true for HTTPS with the key store of {@link App#keyStore()}
     * @return the running Spring application
     */
    public static org.springframework.context.ConfigurableApplicationContext start(App.Headless h, boolean https, String... args) throws Exception {
        parts = h;
        SpringApplication app = new SpringApplication(CrowdWatchServer.class);
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", h.port);
        if (https) {
            App.KeyStore ks = App.keyStore();
            props.put("server.ssl.enabled", true);
            props.put("server.ssl.key-store", ks.file().getAbsolutePath());
            props.put("server.ssl.key-store-password", new String(ks.password()));
            props.put("server.ssl.key-store-type", "PKCS12");
            props.put("server.ssl.enabled-protocols", "TLSv1.3,TLSv1.2");
        }
        app.setDefaultProperties(props);
        org.springframework.context.ConfigurableApplicationContext ctx = app.run(args);
        String port = ctx.getEnvironment().getProperty("local.server.port", String.valueOf(h.port));
        System.out.println("CrowdWatch server (Spring Boot) for " + h.monitor.venueName() + " on " + (https ? "https" : "http") + "://localhost:" + port + "/  (Ctrl+C to stop)");
        return ctx;
    }

    @Bean(destroyMethod = "stop")
    App.Headless headless() { return parts; }

    @Bean
    ApiServer apiServer(App.Headless h) { return h.api; }

    @Bean
    CrowdMonitor monitor(App.Headless h) { return h.monitor; }
}
