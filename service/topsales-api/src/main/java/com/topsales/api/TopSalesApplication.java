package com.topsales.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;

/**
 * Entry point for the TopSales serving API.
 *
 * <p>Controllers, top-k assembly, caching, degradation, the tenant filter, and the served dashboard
 * live under {@code com.topsales.*} and are picked up by the component scan below.
 *
 * <p>{@code @ConfigurationPropertiesScan} over {@code com.topsales} registers the central
 * {@link com.topsales.common.config.TopsalesProperties} (which lives in {@code topsales-common},
 * outside this app's package) so the whole {@code topsales.*} tree binds at startup.
 *
 * <p><b>Why the explicit {@code @ComponentScan} (Phase 4):</b> the read API depends on
 * {@code topsales-forecast} only to reuse the plain {@code SeasonalNaiveForecaster}/math classes for
 * the degradation fallback. That module also carries Spring beans for the batch app
 * ({@code ForecasterJob} is an {@code ApplicationRunner}; {@code BatchConfig}/{@code ForecastWiring}
 * define data-plane + {@code Forecaster} beans). Scanning them here would run the batch on API
 * startup and clash with the read-side bean graph. So we keep the Boot default filters and add a
 * regex exclude for {@code com.topsales.forecast.*}; the read path constructs the one forecaster it
 * needs explicitly in {@code ReadWiring}.
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.topsales",
        excludeFilters = {
            @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
            @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
            @Filter(type = FilterType.REGEX, pattern = "com\\.topsales\\.forecast\\..*")
        })
@ConfigurationPropertiesScan("com.topsales")
public class TopSalesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TopSalesApplication.class, args);
    }
}
