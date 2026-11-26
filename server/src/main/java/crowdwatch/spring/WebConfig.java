package crowdwatch.spring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Static files of the React console, and security headers on every reply. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Built files carry a hash in their name, so the browser may keep them; index.html is never cached. */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**").addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable());
        registry.addResourceHandler("/favicon.svg").addResourceLocations("classpath:/static/");
    }

    /**
     * nosniff, no framing by other sites, no referrer to other sites, HSTS over HTTPS, and a
     * content security policy for the React console (scripts only from this server).
     */
    @Bean
    OncePerRequestFilter securityHeaders() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest rq, HttpServletResponse rs, FilterChain chain) throws ServletException, IOException {
                rs.setHeader("X-Content-Type-Options", "nosniff");
                rs.setHeader("Referrer-Policy", "same-origin");
                rs.setHeader("X-Frame-Options", "SAMEORIGIN");
                if (rq.isSecure()) rs.setHeader("Strict-Transport-Security", "max-age=31536000");
                String p = rq.getRequestURI();
                if (p.equals("/") || p.startsWith("/assets/"))
                    rs.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
                        + "connect-src 'self' ws: wss:; frame-ancestors 'self'; base-uri 'none'; form-action 'self'");
                chain.doFilter(rq, rs);
            }
        };
    }
}
