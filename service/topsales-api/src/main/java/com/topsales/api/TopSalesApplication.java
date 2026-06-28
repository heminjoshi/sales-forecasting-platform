package com.topsales.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the TopSales serving API.
 *
 * <p>Phase 0: boots an empty web server (no endpoints yet). Controllers, top-k assembly,
 * caching, degradation, the tenant filter, and the served dashboard arrive in Phase 2+.
 */
@SpringBootApplication(scanBasePackages = "com.topsales")
public class TopSalesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TopSalesApplication.class, args);
    }
}
