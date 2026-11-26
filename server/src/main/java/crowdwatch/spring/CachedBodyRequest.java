package crowdwatch.spring;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A request whose body has been read once (up to a limit) and can be read again, so the sign-in
 * check can see a {@code key} field in a posted form and the API still gets the whole body.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    /**
     * @param limit largest body the API accepts; one byte more is kept so the API can still
     *              answer 413 for a body that is too large
     */
    CachedBodyRequest(HttpServletRequest rq, int limit) throws IOException {
        super(rq);
        body = rq.getInputStream().readNBytes(limit + 1);
    }

    byte[] body() { return body; }

    // query fields, then form fields, in order; parsed on first use
    private Map<String, String[]> params;

    private Map<String, String[]> params() {
        if (params != null) return params;
        Map<String, List<String>> all = new LinkedHashMap<>();
        add(all, getQueryString());
        String ct = getContentType();
        if (ct == null || ct.startsWith("application/x-www-form-urlencoded")) add(all, new String(body, StandardCharsets.UTF_8));
        Map<String, String[]> out = new LinkedHashMap<>();
        all.forEach((k, v) -> out.put(k, v.toArray(new String[0])));
        params = Collections.unmodifiableMap(out);
        return params;
    }

    private static void add(Map<String, List<String>> all, String raw) {
        if (raw == null || raw.isEmpty()) return;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                all.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            } catch (IllegalArgumentException e) { /* a malformed field is skipped, as the API does */ }
        }
    }

    @Override
    public String getParameter(String name) { String[] v = params().get(name); return v == null || v.length == 0 ? null : v[0]; }

    @Override
    public Map<String, String[]> getParameterMap() { return params(); }

    @Override
    public Enumeration<String> getParameterNames() { return Collections.enumeration(params().keySet()); }

    @Override
    public String[] getParameterValues(String name) { return params().get(name); }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return in.read(); }
            @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
        };
    }

    @Override
    public int getContentLength() { return body.length; }

    @Override
    public long getContentLengthLong() { return body.length; }
}
