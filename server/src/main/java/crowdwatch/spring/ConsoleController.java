package crowdwatch.spring;

import crowdwatch.net.ApiServer;
import crowdwatch.net.ConsoleData;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the consoles read, as Spring endpoints: overview, areas, incidents, units, alerts, the
 * venue, its layout, lost persons, public messages, one incident, readings, the evacuation plan,
 * status, routes, the summary and the handover report.
 * <p>
 * Spring Security has checked the caller's role before these run ({@link SecurityConfig}). The
 * replies come from {@link ConsoleData}, the same code the JDK server uses, through
 * {@link ApiServer#read} (monitor lock, fair turns, half-second cache). Changes still go through
 * {@link ApiBridge}, which keeps the API's audit log and its per-unit checks for device codes.
 */
@RestController
public class ConsoleController {
    private static final String JSON = "application/json", TEXT = "text/plain; charset=utf-8";
    private final ApiServer api;

    public ConsoleController(ApiServer api) { this.api = api; }

    private ResponseEntity<String> reply(HttpServletRequest rq, String type, Function<ConsoleData, String> build) {
        String body = api.read("GET " + rq.getRequestURI(), rq.getQueryString(), type, build);
        // CORS stays open for reads, as on the JDK server, so other web pages and screens can show the data
        return ResponseEntity.ok().header("Content-Type", type).header("Access-Control-Allow-Origin", "*").body(body);
    }

    @GetMapping("/api/overview")
    public ResponseEntity<String> overview(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::overview); }

    @GetMapping("/api/areas")
    public ResponseEntity<String> areas(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::areas); }

    @GetMapping("/api/incidents")
    public ResponseEntity<String> incidents(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::incidents); }

    @GetMapping("/api/units")
    public ResponseEntity<String> units(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::units); }

    @GetMapping("/api/alerts")
    public ResponseEntity<String> alerts(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::alerts); }

    @GetMapping("/api/venue")
    public ResponseEntity<String> venue(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::venue); }

    @GetMapping("/api/layout")
    public ResponseEntity<String> layout(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::layout); }

    @GetMapping("/api/lost")
    public ResponseEntity<String> lost(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::lost); }

    @GetMapping("/api/announcements")
    public ResponseEntity<String> announcements(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::announcements); }

    @GetMapping("/api/status")
    public ResponseEntity<String> status(HttpServletRequest rq) { return reply(rq, JSON, ConsoleData::status); }

    /** One incident with its details, route and history; {"ok":false} if it is no longer in memory. Accepts "I1001" or "1001". */
    @GetMapping("/api/incident")
    public ResponseEntity<String> incident(HttpServletRequest rq, @RequestParam(required = false) String id) {
        int n = ConsoleData.number(id);
        return reply(rq, JSON, d -> d.incident(n));
    }

    /** The sampled readings of one area. */
    @GetMapping("/api/readings")
    public ResponseEntity<String> readings(HttpServletRequest rq, @RequestParam(required = false) String area) { return reply(rq, JSON, d -> d.readings(area)); }

    /** The evacuation in progress, or a preview of the plan for a zone. */
    @GetMapping("/api/evacuation")
    public ResponseEntity<String> evacuation(HttpServletRequest rq, @RequestParam(required = false) String zone) { return reply(rq, JSON, d -> d.evacuation(zone)); }

    /** A walking route; avoid=0 gives the shortest walk, anything else the crowd-aware one. */
    @GetMapping("/api/route")
    public ResponseEntity<String> route(HttpServletRequest rq, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String avoid) {
        return reply(rq, JSON, d -> d.route(from, to, !"0".equals(avoid)));
    }

    @GetMapping("/api/summary")
    public ResponseEntity<String> summary(HttpServletRequest rq) { return reply(rq, TEXT, ConsoleData::summary); }

    @GetMapping("/api/handover")
    public ResponseEntity<String> handover(HttpServletRequest rq) { return reply(rq, TEXT, ConsoleData::handover); }

    /** Bad input (such as an incident number that is not a number) gets 400 with the reason, as on the JDK server. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badInput(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).header("Content-Type", JSON).body(ApiServer.errorJson(String.valueOf(e.getMessage())));
    }
}
