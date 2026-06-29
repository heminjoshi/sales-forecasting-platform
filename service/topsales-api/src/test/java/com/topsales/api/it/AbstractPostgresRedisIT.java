package com.topsales.api.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.ObjectMapper;

/**
 * Shared base for the full-stack integration tests that exercise the read path against <em>both</em>
 * real backing stores — a Postgres for the serving/aggregate data and a Redis for the Phase-4
 * cache-aside layer. Boots the whole app on a random port, then leaves subclasses to drive HTTP and
 * assert behaviour (cache hit/miss, degradation chain, freshness) end-to-end.
 *
 * <p>Both containers are wired purely through {@link ServiceConnection}: Boot reads the running
 * container and synthesizes the connection details, so {@code spring.datasource.*} and
 * {@code spring.data.redis.*} point at the containers with no {@code @DynamicPropertySource}. The
 * Redis container needs the explicit {@code name = "redis"} hint because, unlike
 * {@link PostgreSQLContainer} (a typed container Boot recognizes outright), a bare
 * {@link GenericContainer} carries no image-type signal — the hint tells Boot to expose it as a
 * {@code RedisConnectionDetails}. Flyway auto-migrates from the classpath at startup (which also
 * seeds the {@code t_demo} tenant via V4), exactly as in the single-store ITs.
 *
 * <p>{@code GenericContainer} comes from testcontainers-core, already on the test classpath
 * transitively via {@code org.testcontainers:junit-jupiter} — no new dependency.
 *
 * <p>NOTE: Testcontainers may fail to start on some hosts (Docker API vs the bundled docker-java
 * client). When it does, these ITs are skipped locally but are correct for CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractPostgresRedisIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @LocalServerPort protected int port;

    @Autowired protected ObjectMapper objectMapper;

    /** A fresh {@link RestClient} pointed at the booted app's random port. */
    protected RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }
}
