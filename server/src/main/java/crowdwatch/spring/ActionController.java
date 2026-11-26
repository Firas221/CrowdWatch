package crowdwatch.spring;

import crowdwatch.net.ApiServer;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The control-room changes as Spring endpoints: counts, incidents, dispatch, SOS, lost persons,
 * evacuation, public messages, alarms, broadcasts, officer check-in and automatic dispatch.
 * <p>
 * Spring Security has checked the caller's role first ({@link SecurityConfig}). Each change is
 * then carried out by {@link ApiServer#act}, the same code as on the JDK server, which also
 * checks that a device code acts only for its own unit's tasks, uses the signed-in user's own
 * name as the officer, and writes the audit log. Replies: 200 done, 422 refused by a rule (with
 * the reason), 400 bad input. A form from the plain dashboard (with a {@code back} field) is
 * sent back to /classic with the result, as before.
 * <p>
 * The parameters each endpoint takes are listed in its signature (all optional, so the rules can
 * answer with their own messages); every field of the request is handed on.
 */
@RestController
public class ActionController {
    private final ApiServer api;

    public ActionController(ApiServer api) { this.api = api; }

    private ResponseEntity<String> act(HttpServletRequest rq) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String k : Collections.list(rq.getParameterNames())) fields.put(k, rq.getParameter(k));
        String given = rq.getHeader("X-Api-Key") != null ? rq.getHeader("X-Api-Key") : fields.get("key");
        ApiServer.Exchange ex = new ApiServer.Exchange(rq.getMethod(), rq.getRequestURI(), rq.getQueryString(),
            name -> Collections.list(rq.getHeaders(name)), null, rq.getRemoteAddr(), rq.isSecure());
        ApiServer.Outcome o = api.act(ex, given, "POST " + URLDecoder.decode(rq.getRequestURI(), StandardCharsets.UTF_8), fields);
        if (fields.get("back") != null && (o.code() == 200 || o.code() == 422)) {
            String msg = (o.ok() ? "OK: " : "REFUSED: ") + o.message();
            return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", "/classic?msg=" + URLEncoder.encode(msg, StandardCharsets.UTF_8)).build();
        }
        return ResponseEntity.status(o.code()).header("Content-Type", "application/json").header("Access-Control-Allow-Origin", "*").body(o.json());
    }

    // ---------- counts ----------

    /** Sets an area's count ({@code count}) or changes it ({@code delta}); source SENSOR, STAFF, OFFICIAL, SIMULATION or IMPORT. */
    @PostMapping("/api/occupancy")
    public ResponseEntity<String> occupancy(HttpServletRequest rq, @RequestParam(required = false) String area, @RequestParam(required = false) String count,
                                            @RequestParam(required = false) String delta, @RequestParam(required = false) String source) { return act(rq); }

    /** People counted going from one area to another (OUTSIDE for the venue's gates). */
    @PostMapping("/api/move")
    public ResponseEntity<String> move(HttpServletRequest rq, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                       @RequestParam(required = false) String count, @RequestParam(required = false) String source) { return act(rq); }

    /** Takes the current total as the new starting point of the count check (after a full recount). */
    @PostMapping("/api/countcheck/reset")
    public ResponseEntity<String> countCheckReset(HttpServletRequest rq, @RequestParam(required = false) String officer) { return act(rq); }

    // ---------- incidents and units ----------

    /** Reports an incident; the nearest suitable unit is sent (or it waits in its priority queue). */
    @PostMapping("/api/incidents")
    public ResponseEntity<String> report(HttpServletRequest rq, @RequestParam(required = false) String category, @RequestParam(required = false) String area,
                                         @RequestParam(required = false) String details, @RequestParam(required = false) String contact,
                                         @RequestParam(required = false) String language) { return act(rq); }

    /** The unit acknowledges its task. */
    @PostMapping("/api/ack")
    public ResponseEntity<String> acknowledge(HttpServletRequest rq, @RequestParam(required = false) String unit) { return act(rq); }

    /** The unit is at the incident. */
    @PostMapping("/api/onscene")
    public ResponseEntity<String> onScene(HttpServletRequest rq, @RequestParam(required = false) String id) { return act(rq); }

    /** Closes an incident with a note; the unit becomes available. */
    @PostMapping("/api/resolve")
    public ResponseEntity<String> resolve(HttpServletRequest rq, @RequestParam(required = false) String id, @RequestParam(required = false) String note) { return act(rq); }

    /** Sends another unit to an incident. */
    @PostMapping("/api/reassign")
    public ResponseEntity<String> reassign(HttpServletRequest rq, @RequestParam(required = false) String id, @RequestParam(required = false) String unit,
                                           @RequestParam(required = false) String reason) { return act(rq); }

    /** Raises an incident's priority one step. */
    @PostMapping("/api/escalate")
    public ResponseEntity<String> escalate(HttpServletRequest rq, @RequestParam(required = false) String id) { return act(rq); }

    /** An officer approves ({@code decision=true}) or rejects an automatic dispatch. */
    @PostMapping("/api/review")
    public ResponseEntity<String> review(HttpServletRequest rq, @RequestParam(required = false) String id, @RequestParam(required = false) String decision,
                                         @RequestParam(required = false) String note, @RequestParam(required = false) String officer) { return act(rq); }

    // ---------- emergencies ----------

    /** SOS: a CRITICAL incident, the nearest free units sent at once. */
    @PostMapping("/api/sos")
    public ResponseEntity<String> sos(HttpServletRequest rq, @RequestParam(required = false) String area, @RequestParam(required = false) String text,
                                      @RequestParam(required = false) String unit) { return act(rq); }

    /** An alarm panel or the control room raises an alarm: a unit checks it and an evacuation of the sector is proposed. */
    @PostMapping("/api/alarm")
    public ResponseEntity<String> alarm(HttpServletRequest rq, @RequestParam(required = false) String area, @RequestParam(required = false) String text) { return act(rq); }

    /** Starts the evacuation of a sector, fire zone or area. */
    @PostMapping("/api/evacuate")
    public ResponseEntity<String> evacuate(HttpServletRequest rq, @RequestParam(required = false) String zone, @RequestParam(required = false) String officer) { return act(rq); }

    /** Ends the evacuation. */
    @PostMapping("/api/evacuate/end")
    public ResponseEntity<String> endEvacuation(HttpServletRequest rq, @RequestParam(required = false) String officer) { return act(rq); }

    /** Starts ({@code decision=start}) or dismisses the evacuation the system proposed. */
    @PostMapping("/api/proposal")
    public ResponseEntity<String> proposal(HttpServletRequest rq, @RequestParam(required = false) String decision, @RequestParam(required = false) String officer) { return act(rq); }

    /** A message to every unit's device and the radio gateway. */
    @PostMapping("/api/broadcast")
    public ResponseEntity<String> broadcast(HttpServletRequest rq, @RequestParam(required = false) String text, @RequestParam(required = false) String officer) { return act(rq); }

    // ---------- public messages ----------

    /** Approves ({@code decision=approve}) or rejects a drafted public message. */
    @PostMapping("/api/announcement")
    public ResponseEntity<String> announcement(HttpServletRequest rq, @RequestParam(required = false) String id, @RequestParam(required = false) String decision,
                                               @RequestParam(required = false) String officer) { return act(rq); }

    /** An officer's own message to the public screens and PA. */
    @PostMapping("/api/announce")
    public ResponseEntity<String> announce(HttpServletRequest rq, @RequestParam(required = false) String text, @RequestParam(required = false) String officer) { return act(rq); }

    // ---------- lost persons ----------

    /** A missing person has been found (stops the lookout). */
    @PostMapping("/api/found")
    public ResponseEntity<String> found(HttpServletRequest rq, @RequestParam(required = false) String id, @RequestParam(required = false) String area,
                                        @RequestParam(required = false) String unit) { return act(rq); }

    /** A person found alone is recorded and looked after. */
    @PostMapping("/api/foundperson")
    public ResponseEntity<String> foundPerson(HttpServletRequest rq, @RequestParam(required = false) String area, @RequestParam(required = false) String details,
                                              @RequestParam(required = false) String contact, @RequestParam(required = false) String unit) { return act(rq); }

    // ---------- supervision ----------

    /** The security officer checks in. */
    @PostMapping("/api/checkin")
    public ResponseEntity<String> checkIn(HttpServletRequest rq, @RequestParam(required = false) String officer) { return act(rq); }

    /** Switches automatic dispatch on ({@code on=1}) or off; counts as a check-in. */
    @PostMapping("/api/automation")
    public ResponseEntity<String> automation(HttpServletRequest rq, @RequestParam(required = false) String on, @RequestParam(required = false) String officer) { return act(rq); }
}
