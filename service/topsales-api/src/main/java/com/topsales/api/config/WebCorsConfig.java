package com.topsales.api.config;

import com.topsales.common.config.TopsalesProperties;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Declarative MVC CORS for the read API (docs/lld.md §11). The cross-origin SPA (Vercel) sits outside
 * the AWS account, so {@code /api/**} must answer CORS preflight for the allow-listed origins; the
 * same-origin Spring-served demo dashboard needs none.
 *
 * <p>Why {@link WebMvcConfigurer#addCorsMappings} (rather than a {@code CorsFilter}): the preflight is
 * answered by the {@code DispatcherServlet}'s {@code HandlerMapping}, i.e. <em>downstream</em> of the
 * servlet filter chain. So {@link com.topsales.api.web.TenantScopeFilter} — a pass-through that never
 * rejects and only publishes the tenant/MDC — runs first but never blocks an {@code OPTIONS} preflight
 * (which carries no {@code X-Tenant-Id}). If a Spring Security chain is later added, CORS must move
 * ahead of the security filters: register a {@code CorsConfigurationSource} bean and let Security's
 * {@code .cors()} pick it up (or a high-precedence {@code CorsFilter}) — that bean is the swap point.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final TopsalesProperties props;

    public WebCorsConfig(TopsalesProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.web().cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowedHeaders("X-Tenant-Id", "X-Request-Id", "Accept", "Content-Type")
                .exposedHeaders("X-Request-Id")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
