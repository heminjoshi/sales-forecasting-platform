package com.topsales.datagen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Dev-only synthetic-data generator (ADR-0010). A web-less, one-shot Spring Boot app: it runs the
 * requested load mode ({@code seed} | {@code trickle}) via {@link DatagenRunner} and exits. Scans only
 * {@code com.topsales.datagen}, so it does not boot the ingestion/api bean graph.
 */
@SpringBootApplication
public class DatagenApplication {

    public static void main(String[] args) {
        SpringApplication.run(DatagenApplication.class, args);
    }
}
