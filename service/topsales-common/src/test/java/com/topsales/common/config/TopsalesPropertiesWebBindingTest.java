package com.topsales.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Binds the {@code topsales.web.cors.*} subtree into {@link TopsalesProperties.Web} via the Spring
 * Boot {@link Binder} — no context boot needed. Confirms the new Phase-7 CORS allow-list binds and,
 * critically, that the comma-separated env default (the {@code TOPSALES_CORS_ALLOWED_ORIGINS} shape)
 * relaxes into a {@code List<String>} and is reachable via {@code props.web().cors().allowedOrigins()}.
 */
class TopsalesPropertiesWebBindingTest {

    @Test
    void bindsCommaSeparatedOriginsIntoNonEmptyList() {
        // Mirrors the application.yml env default: a single comma-joined string.
        ConfigurationPropertySource source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "topsales.web.cors.allowed-origins",
                                "http://localhost:8080,http://localhost:5173"));

        TopsalesProperties.Web web =
                new Binder(source).bind("topsales.web", TopsalesProperties.Web.class).get();

        assertThat(web.cors()).isNotNull();
        assertThat(web.cors().allowedOrigins())
                .isNotEmpty()
                .containsExactly("http://localhost:8080", "http://localhost:5173");
    }
}
