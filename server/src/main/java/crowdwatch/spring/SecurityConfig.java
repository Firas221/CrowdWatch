package crowdwatch.spring;

import crowdwatch.net.ApiServer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security in front of the CrowdWatch API.
 * <p>
 * <b>Who is calling.</b> {@link CallerFilter} identifies every request with CrowdWatch's own
 * accounts: a personal session token (bearer), the plain dashboard's session cookie, the control
 * key, the scout key or a scout phone's device code. Passwords, lockout after wrong passwords,
 * two-step codes, the company directory (LDAP) and sessions that survive a restart stay in
 * {@code crowdwatch.net.Accounts}, which the existing tests cover. The caller becomes a Spring
 * {@link Authentication} with the authority {@code ROLE_OPERATOR}, {@code ROLE_OFFICER},
 * {@code ROLE_ADMIN}, {@code ROLE_DEVICE} or {@code ROLE_SCOUT}.
 * <p>
 * <b>What they may do.</b> Every endpoint is checked against {@link ApiServer#refusal}, the
 * same role table the API itself uses, before the request reaches any controller: a request
 * without a sign-in gets 401, a role that may not use the endpoint 403 (refused changes are
 * written to the audit log). The API checks again behind it, and also that a device code acts only
 * for its own unit.
 * <p>
 * <b>Other protections.</b> Requests without the control key are rate-limited per address
 * before anything else. No server sessions: every request carries its own sign-in, so the API
 * keeps no state for Spring. CSRF tokens are not used: the API and the console send the sign-in in
 * a header, which another site cannot make a browser send, and the plain dashboard's cookie is
 * SameSite=Strict. Headers: nosniff, framing only by this site, no referrer to other sites, HSTS
 * over HTTPS, and a content security policy for the React console.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    /** Request attribute holding the {@link ApiServer.Caller}, for the bridge and the audit log. */
    static final String CALLER = "crowdwatch.caller";
    /** Request attribute set when the rate limit has been applied. */
    static final String LIMITED = "crowdwatch.rateLimitChecked";
    // largest bodies the API accepts (64 KB; 3 MB for a message with a photo)
    private static final int MAX_BODY = 64 * 1024, MAX_MESSAGE_BODY = 3 * 1024 * 1024;

    private final ApiServer api;

    public SecurityConfig(ApiServer api) { this.api = api; }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        RequestMatcher console = new OrRequestMatcher(rq -> rq.getRequestURI().equals("/"), rq -> rq.getRequestURI().startsWith("/assets/"));
        http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .requestCache(c -> c.disable())
            .addFilterBefore(new CallerFilter(), AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(a -> a
                .requestMatchers(rq -> isStatic(rq.getRequestURI())).permitAll()
                .anyRequest().access(endpointRules()))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((rq, rs, ex) -> json(rs, 401, ApiServer.errorJson(api.signInMessage())))
                .accessDeniedHandler((rq, rs, ex) -> refused(rq, rs)))
            .headers(h -> h
                .cacheControl(c -> c.disable())   // the API and the static files set their own caching
                .contentTypeOptions(c -> { })
                .frameOptions(f -> f.sameOrigin())
                .referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN))
                .httpStrictTransportSecurity(s -> s.maxAgeInSeconds(31_536_000).includeSubDomains(false))
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(console, new StaticHeadersWriter("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
                    + "connect-src 'self' ws: wss:; frame-ancestors 'self'; base-uri 'none'; form-action 'self'"))));
        return http.build();
    }

    /** The React console's own files and Spring's error page: no data in them. */
    static boolean isStatic(String path) {
        return path.equals("/") || path.equals("/index.html") || path.startsWith("/assets/") || path.equals("/favicon.svg") || path.equals("/legacy") || path.equals("/error");
    }

    /** The endpoint as the API names it, e.g. "POST /api/evacuate". */
    static String endpoint(HttpServletRequest rq) {
        return rq.getMethod() + " " + URLDecoder.decode(rq.getRequestURI(), StandardCharsets.UTF_8);
    }

    /** Grants public endpoints; others need a caller whose role {@link ApiServer#refusal} allows; the live socket needs any sign-in. */
    private AuthorizationManager<RequestAuthorizationContext> endpointRules() {
        return (Supplier<Authentication> auth, RequestAuthorizationContext ctx) -> {
            String ep = endpoint(ctx.getRequest());
            if (ApiServer.isPublic(ep)) return new AuthorizationDecision(true);
            Authentication a = auth.get();
            if (a == null || a instanceof AnonymousAuthenticationToken || !(a.getPrincipal() instanceof ApiServer.Caller c)) return new AuthorizationDecision(false);
            if (ep.equals("GET /ws")) return new AuthorizationDecision(true);
            return new AuthorizationDecision(ApiServer.refusal(c.role(), ep) == null);
        };
    }

    // 403 with the API's own reason; a refused change is written to the audit log as the API would
    private void refused(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
        ApiServer.Caller c = rq.getAttribute(CALLER) instanceof ApiServer.Caller x ? x : null;
        String ep = endpoint(rq);
        String reason = c == null ? "not allowed" : ApiServer.refusal(c.role(), ep);
        if (reason == null) reason = "not allowed";
        if (rq.getMethod().equals("POST")) api.auditRefused(c, ep, params(rq), reason);
        json(rs, 403, ApiServer.errorJson(reason));
    }

    // the query and, for a posted form, its body
    private static String params(HttpServletRequest rq) {
        String q = rq.getQueryString() == null ? "" : rq.getQueryString();
        if (rq instanceof CachedBodyRequest b && b.body().length > 0) q = q.isEmpty() ? new String(b.body(), StandardCharsets.UTF_8) : q + "&" + new String(b.body(), StandardCharsets.UTF_8);
        return q;
    }

    private static void json(HttpServletResponse rs, int code, String body) throws IOException {
        rs.setStatus(code);
        rs.setContentType("application/json");
        rs.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Identifies the caller of every request with CrowdWatch's accounts and applies the rate limit
     * to requests without the control key. A posted form is read once here (so a {@code key}
     * field counts) and handed on whole.
     */
    final class CallerFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest rq, HttpServletResponse rs, FilterChain chain) throws ServletException, IOException {
            if (isStatic(rq.getRequestURI())) { chain.doFilter(rq, rs); return; }
            HttpServletRequest req = rq;
            String formKey = null;
            if (rq.getMethod().equals("POST")) {
                CachedBodyRequest b = new CachedBodyRequest(rq, rq.getRequestURI().equals("/api/message") ? MAX_MESSAGE_BODY : MAX_BODY);
                req = b;
                String ct = rq.getContentType();
                if (ct == null || ct.startsWith("application/x-www-form-urlencoded")) formKey = field(new String(b.body(), StandardCharsets.UTF_8), "key");
            }
            String given = rq.getHeader("X-Api-Key") != null ? rq.getHeader("X-Api-Key") : field(rq.getQueryString(), "key");
            if (given == null) given = formKey;
            // browsers cannot add headers to a WebSocket, so the live socket takes the session token in the address
            String token = rq.getRequestURI().equals("/ws") ? field(rq.getQueryString(), "token") : null;
            final HttpServletRequest seen = req;
            ApiServer.Exchange ex = new ApiServer.Exchange(rq.getMethod(), rq.getRequestURI(), rq.getQueryString(), name -> {
                if (token != null && name.equalsIgnoreCase("Authorization")) return List.of("Bearer " + token);
                return Collections.list(seen.getHeaders(name));
            }, null, rq.getRemoteAddr(), rq.isSecure());
            ApiServer.Caller c = api.caller(ex, given);
            if ((c == null || !c.system()) && api.tooMany(rq.getRemoteAddr())) {
                json(rs, 429, ApiServer.errorJson("too many requests, slow down"));
                return;
            }
            req.setAttribute(LIMITED, Boolean.TRUE);
            if (c != null) {
                req.setAttribute(CALLER, c);
                UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(c, null, List.of(new SimpleGrantedAuthority("ROLE_" + c.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            try { chain.doFilter(req, rs); }
            finally { SecurityContextHolder.clearContext(); }
        }
    }

    /** @return the value of one field of URL-encoded parameters, or null */
    static String field(String raw, String name) {
        if (raw == null) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }
}
