package crowdwatch.spring;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;

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
