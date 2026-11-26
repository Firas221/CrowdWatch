package crowdwatch.spring;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Static files of the React console (security headers are set in {@link SecurityConfig}). */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Built files carry a hash in their name, so the browser may keep them; index.html is never cached. */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**").addResourceLocations("classpath:/static/assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable());
        registry.addResourceHandler("/favicon.svg").addResourceLocations("classpath:/static/");
    }
}
