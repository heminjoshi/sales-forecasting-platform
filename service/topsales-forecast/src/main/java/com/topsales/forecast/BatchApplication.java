package com.topsales.forecast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The forecast batch entry point (ADR-0001, Phase-3 decision 4). A web-less, one-shot Spring Boot app
 * ({@code spring.main.web-application-type=none} in {@code application.yml}): it boots the datasource +
 * Flyway, runs {@link com.topsales.forecast.batch.ForecasterJob} once via {@code ApplicationRunner},
 * then exits. Fired by {@code make forecast}; on AWS this same image runs as a scheduled Fargate task.
 *
 * <p>Component scanning stays rooted at {@code com.topsales.forecast}, so it picks up
 * {@link com.topsales.forecast.config.ForecastWiring} (the single {@code forecaster} bean) and the
 * {@code com.topsales.forecast.batch} beans, but deliberately <b>not</b> the ingestion/api bean graph
 * — keeping the ML plane off the read JVM. The shared JDBC repo impls it does need are constructed
 * explicitly in {@link com.topsales.forecast.batch.BatchConfig}. {@code @ConfigurationPropertiesScan}
 * is widened to {@code com.topsales} only to bind the shared
 * {@link com.topsales.common.config.TopsalesProperties}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.topsales")
public class BatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatchApplication.class, args);
    }
}
