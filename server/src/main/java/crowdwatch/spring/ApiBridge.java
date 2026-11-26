package crowdwatch.spring;

import crowdwatch.net.ApiServer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hands the rest of the CrowdWatch API to {@link ApiServer#dispatch}: the changes (which write
 * the audit log and check that a device code acts only for its own unit), sign-in, scout phones,
 * reports, photos and the pages. Spring Security has already identified the caller and checked
 * the role ({@link SecurityConfig}); what the consoles read is served by {@link ConsoleController}.
 * <p>
 * The React console is served from {@code static/} ({@code /} and {@code /assets/...}); the
 * classic console stays available at {@code /legacy}.
 */
@RestController
public class ApiBridge {
    private final ApiServer api;

    public ApiBridge(ApiServer api) { this.api = api; }

    /** The React console, or the classic console if this jar was built without it. */
    @GetMapping("/")
    public void index(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        ClassPathResource page = new ClassPathResource("static/index.html");
        if (!page.exists()) { forward(rq, rs, "/console"); return; }
        rs.setContentType("text/html; charset=utf-8");
        rs.setHeader("Cache-Control", "no-cache");
        try (InputStream in = page.getInputStream()) { in.transferTo(rs.getOutputStream()); }
    }

    /** The classic console (plain JavaScript), for the views the React console does not have yet. */
    @GetMapping("/legacy")
    public void legacy(HttpServletRequest rq, HttpServletResponse rs) throws IOException { forward(rq, rs, "/console"); }

    /** Everything the API, the scout phones, the help page and monitoring tools use. */
    @RequestMapping(value = { "/api/**", "/classic", "/classic/**", "/console", "/console.css", "/console.js", "/i18n.js",
        "/scout", "/help", "/health", "/metrics" }, method = { RequestMethod.GET, RequestMethod.POST })
    public void api(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        forward(rq, rs, URLDecoder.decode(rq.getRequestURI(), StandardCharsets.UTF_8));
    }

    private void forward(HttpServletRequest rq, HttpServletResponse rs, String path) throws IOException {
        ApiServer.Exchange ex = new ApiServer.Exchange(rq.getMethod(), path, rq.getQueryString(),
            name -> Collections.list(rq.getHeaders(name)), rq.getInputStream(), rq.getRemoteAddr(), rq.isSecure());
        if (rq.getAttribute(SecurityConfig.LIMITED) != null) ex.rateLimitChecked();
        api.dispatch(ex);
        rs.setStatus(ex.code);
        for (String[] h : ex.replyHeaders) {
            if (h[0].equalsIgnoreCase("Content-Type")) rs.setContentType(h[1]);
            else rs.addHeader(h[0], h[1]);
        }
        if (ex.replyBody.length > 0) {
            rs.setContentLength(ex.replyBody.length);
            rs.getOutputStream().write(ex.replyBody);
        }
    }
}
