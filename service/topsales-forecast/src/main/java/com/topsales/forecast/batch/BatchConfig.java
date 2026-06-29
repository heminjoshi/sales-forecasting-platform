package com.topsales.forecast.batch;

import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.forecast.ServingTableRepository;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.common.repository.TenantConfigRepository;
import com.topsales.ingestion.repo.JdbcAggregateRepository;
import com.topsales.ingestion.repo.JdbcServingTableRepository;
import com.topsales.ingestion.repo.JdbcTenantConfigRepository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the data-plane ports the batch reaches (aggregates read + tenant config + serving write) to
 * their local JDBC impls, constructed explicitly from the autoconfigured {@link JdbcTemplate}.
 *
 * <p>Mirrors {@code topsales-datagen}'s {@code DatagenConfig}: we build the {@code topsales-ingestion}
 * repositories here rather than component-scanning that module, so the batch borrows the shared
 * data-plane SQL without booting ingestion's full bean graph (and without an {@code api → forecast}
 * cycle — both sides reach the serving table through the same port, DR-1/DR-7).
 */
@Configuration
public class BatchConfig {

    @Bean
    public AggregateRepository aggregateRepository(JdbcTemplate jdbc) {
        return new JdbcAggregateRepository(jdbc);
    }

    @Bean
    public TenantConfigRepository tenantConfigRepository(JdbcTemplate jdbc) {
        return new JdbcTenantConfigRepository(jdbc);
    }

    @Bean
    public ServingTableRepository servingTableRepository(JdbcTemplate jdbc, TopsalesProperties props) {
        return new JdbcServingTableRepository(jdbc, props.forecast().versionKeep());
    }
}
