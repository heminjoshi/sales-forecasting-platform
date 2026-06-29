package com.topsales.api.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

/**
 * Shared base for the full-stack integration tests that exercise the read path against <em>both</em>
 * real backing stores — a Postgres for the serving/aggregate data and a Redis for the Phase-4
 * cache-aside layer. Boots the whole app on a random port, then leaves subclasses to drive HTTP and
 * assert behaviour (cache hit/miss, degradation chain, freshness, tenant isolation) end-to-end.
 *
 * <p><b>Backing stores.</b> Unlike the single-store repository ITs (which spin their own
 * {@code PostgreSQLContainer} via Testcontainers {@code @ServiceConnection}), these full-stack ITs
 * need <em>both</em> Postgres and Redis, and the Redis side proved unreliable to wire through
 * Testcontainers on the CI runner (neither {@code @ServiceConnection(name = "redis")} on a
 * {@code GenericContainer} nor a {@code @DynamicPropertySource} override bound the connection details,
 * leaving the client on the {@code localhost:6379} default until every command tripped the aggressive
 * 500&nbsp;ms read timeout). So these run against stores provided at the {@code application.yml}
 * defaults — {@code localhost:5432} / {@code localhost:6379}:
 * <ul>
 *   <li><b>CI</b> — the {@code ci.yml} {@code build & test} job declares {@code postgres:16} and
 *       {@code redis:7} as service containers on those ports.</li>
 *   <li><b>Locally</b> — the {@code local/docker-compose.yml} stack (started with {@code make up});
 *       run these via {@code make verify}.</li>
 * </ul>
 * Flyway auto-migrates the Postgres on startup (which also seeds the {@code t_demo}/{@code t_acme}
 * tenants via V4/V7). A clean schema each run is provided by the ephemeral CI service container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractPostgresRedisIT {

    @LocalServerPort protected int port;

    @Autowired protected ObjectMapper objectMapper;

    /** A fresh {@link RestClient} pointed at the booted app's random port. */
    protected RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }
}
