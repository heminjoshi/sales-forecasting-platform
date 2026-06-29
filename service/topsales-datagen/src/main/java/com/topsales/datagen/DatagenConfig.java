package com.topsales.datagen;

import com.topsales.common.repository.AggregateRepository;
import com.topsales.ingestion.repo.JdbcAggregateRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * Wiring for the generator. The seed path reuses the real {@link JdbcAggregateRepository} (the local
 * data plane) so the bulk-insert conflict key/SQL can't drift from ingestion; the trickle path uses a
 * {@link RestClient} pointed at the running API. We construct the repository explicitly rather than
 * component-scanning {@code topsales-ingestion}, to avoid pulling its full bean graph into this
 * one-shot tool.
 */
@Configuration
public class DatagenConfig {

    @Bean
    public AggregateRepository aggregateRepository(JdbcTemplate jdbc) {
        return new JdbcAggregateRepository(jdbc);
    }

    @Bean
    public RestClient restClient(@Value("${topsales.datagen.api-base-url}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }
}
