package crowdwatch.net;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import crowdwatch.core.UnitChannel;
import crowdwatch.model.Unit;
import crowdwatch.model.UnitMessage;

/**
 * Sends every unit message to a radio or messaging gateway over HTTP.
 * <p>
 * Most digital radio dispatch systems (DMR/TETRA text messaging or text-to-speech), SMS
 * providers and pager systems can accept messages through an HTTP endpoint or a small bridge
 * program. This channel POSTs one JSON object per message:
 * <pre>
 * {"unit":"A03","name":"Medical Responder 1","kind":"TASK","incident":1004,
 *  "time":"2026-12-01T09:00","text":"TASK I1004 CRITICAL: ..."}
 * </pre>
 * The gateway maps the unit ID to a radio ID or phone number. A reply other than 2xx, or no
 * reply within 5 seconds, counts as a failed delivery and the control room gets a DELIVERY alert.
 * Configure it with a {@code gateway:} line in the venue file.
 */
public class HttpGatewayChannel implements UnitChannel {
    private final URI url;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** @param url the gateway's address, e.g. https://radio-gateway.example/send */
    public HttpGatewayChannel(String url) { this.url = URI.create(url.trim()); }

    /** @return "Radio gateway" */
    public String name() { return "Radio gateway"; }

    /**
     * Posts the message as JSON.
     *
     * @throws IOException if the gateway cannot be reached or does not answer 2xx
     */
    public void deliver(Unit unit, UnitMessage message) throws Exception {
        String json = "{\"unit\":" + ApiServer.str(unit.id) + ",\"name\":" + ApiServer.str(unit.name) + ",\"kind\":" + ApiServer.str(message.kind.name())
            + ",\"incident\":" + message.incidentId + ",\"time\":" + ApiServer.str(message.time.toString()) + ",\"text\":" + ApiServer.str(message.text) + "}";
        HttpRequest rq = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<Void> rs = client.send(rq, HttpResponse.BodyHandlers.discarding());
        if (rs.statusCode() / 100 != 2) throw new IOException("gateway answered " + rs.statusCode());
    }

    /** Posts an approved public message with unit "PUBLIC" and kind "ANNOUNCE", for screens and the PA. */
    @Override
    public void announce(String text) throws Exception {
        String json = "{\"unit\":\"PUBLIC\",\"name\":\"Public signs and PA\",\"kind\":\"ANNOUNCE\",\"incident\":0,\"text\":" + ApiServer.str(text) + "}";
        HttpRequest rq = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<Void> rs = client.send(rq, HttpResponse.BodyHandlers.discarding());
        if (rs.statusCode() / 100 != 2) throw new IOException("gateway answered " + rs.statusCode());
    }
}
