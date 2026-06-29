package com.topsales.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the TopSales serving API.
 *
 * <p>Phase 0: boots an empty web server (no endpoints yet). Controllers, top-k assembly,
 * caching, degradation, the tenant filter, and the served dashboard arrive in Phase 2+.
 *
 * <p>{@code @ConfigurationPropertiesScan} over {@code com.topsales} registers the central
 * {@link com.topsales.common.config.TopsalesProperties} (which lives in {@code topsales-common},
 * outside this app's package) so the whole {@code topsales.*} tree binds at startup.
 */
@SpringBootApplication(scanBasePackages = "com.topsales")
@ConfigurationPropertiesScan("com.topsales")
public class TopSalesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TopSalesApplication.class, args);
    }
}
