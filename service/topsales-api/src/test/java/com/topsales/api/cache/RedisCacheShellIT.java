package com.topsales.api.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.api.it.AbstractPostgresRedisIT;
import com.topsales.common.api.Interval;
import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.cache.CacheKeys;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Confidence;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Full-stack integration tests for {@link RedisCacheShell} against a <em>real</em> Redis (docs/lld.md
 * §7): the cache-aside hit/miss round-trip, per-tenant version-bump invalidation, and the single-flight
 * lease under concurrent cold misses. Unlike {@link RedisCacheShellTest} (which mocks the template),
 * these prove the live keying — {@code topk:{tenant}:{ver}:...} keyed off {@code tenantver:{tenant}} —
 * actually behaves end-to-end on the container.
 *
 * <p>Each test uses a distinct tenant so the versioned keys can never collide, and {@link #flushRedis()}
 * clears the keyspace between tests so cases stay independent.
 */
class RedisCacheShellIT extends AbstractPostgresRedisIT {

    @Autowired RedisCacheShell shell;
    @Autowired StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        // Independent cases: wipe every cache key (value, version, lease) the prior test left behind.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    /**
     * IT-CA-01 (+ IT-AI-05): a second {@code getOrCompute} for the same query is served from Redis — the
     * supplier runs exactly once, and the cache-served body still carries the insight (no regeneration).
     */
    @Test
    void missThenHit_secondCallServedFromRedis() {
        TopKQuery query = queryFor("t_hit");
        TopKResponse response = responseFor(query, "Office Supplies leads.");

        AtomicInteger calls = new AtomicInteger();
        Supplier<TopKResponse> compute =
                () -> {
                    calls.incrementAndGet();
                    return response;
                };

        TopKResponse first = shell.getOrCompute(query, compute); // miss → supplier runs + populates
        TopKResponse second = shell.getOrCompute(query, compute); // hit → served from Redis

        assertThat(calls.get()).isEqualTo(1);
        assertThat(first).isEqualTo(second);
        // IT-AI-05: the cached JSON carried the insight, so the hit needs no insight regeneration.
        assertThat(second.insight()).isEqualTo("Office Supplies leads.");
    }

    /**
     * IT-CA-03: bumping the per-tenant cache version makes the next read a miss — the version is part of
     * the value key, so the old entry is simply never read again and the supplier recomputes.
     */
    @Test
    void versionBump_invalidatesAndRecomputes() {
        String tenant = "t_bump";
        TopKQuery query = queryFor(tenant);

        AtomicInteger calls = new AtomicInteger();
        Supplier<TopKResponse> compute =
                () -> {
                    calls.incrementAndGet();
                    return responseFor(query, "v" + calls.get());
                };

        shell.getOrCompute(query, compute); // primes topk:...:0:...
        assertThat(calls.get()).isEqualTo(1);

        // The batch's invalidation: INCR tenantver:{tenant} (absent ⇒ becomes 1) — the value key moves.
        redis.opsForValue().increment(CacheKeys.tenantVersion(tenant));

        shell.getOrCompute(query, compute); // reads ver=1 ⇒ different key ⇒ miss ⇒ recompute
        assertThat(calls.get()).isEqualTo(2);
    }

    /**
     * IT-CA-05: under a burst of concurrent cold misses on the same key, the single-flight lease lets
     * exactly one caller compute while the rest poll and serve the published value.
     */
    @Test
    @Timeout(30)
    void concurrentMisses_singleRecompute() throws Exception {
        TopKQuery query = queryFor("t_concurrent");
        int threads = 8;

        AtomicInteger calls = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        Supplier<TopKResponse> compute =
                () -> {
                    calls.incrementAndGet();
                    sleep(150); // hold the lease long enough for the followers to converge on the value
                    return responseFor(query, "Office Supplies leads.");
                };

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<TopKResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    startGate.await(); // all threads race the cold key together
                                    return shell.getOrCompute(query, compute);
                                }));
            }
            startGate.countDown();

            for (Future<TopKResponse> f : futures) {
                // lockTtl (application.yml) is 2s — comfortably above the 150ms compute, so no follower
                // times out and recomputes; every caller resolves the leader's single published value.
                assertThat(f.get(20, TimeUnit.SECONDS).insight()).isEqualTo("Office Supplies leads.");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(calls.get()).isEqualTo(1);
    }

    // --- builders (mirror RedisCacheShellTest's sample shapes) ----------------------------------

    /** A distinct forecast query per tenant, so its versioned key can never collide with another test. */
    private static TopKQuery queryFor(String tenant) {
        return new TopKQuery(tenant, Window.MONTH, Mode.FORECAST, 10, ChannelFilter.ONLINE);
    }

    /** A sample forecast response matching {@code query}, carrying {@code insight} for the AI-05 check. */
    private static TopKResponse responseFor(TopKQuery query, String insight) {
        TopKItem item =
                new TopKItem(
                        1,
                        "Office Supplies",
                        new BigDecimal("5400.00"),
                        new BigDecimal("0.12"),
                        Confidence.HIGH,
                        new Interval(new BigDecimal("4900.00"), new BigDecimal("5900.00")));
        return new TopKResponse(
                query.tenantId(),
                query.mode(),
                query.window(),
                query.channel(),
                query.k(),
                Status.FRESH,
                Instant.parse("2026-06-28T06:00:00Z"),
                LocalDate.parse("2026-05-30"),
                LocalDate.parse("2026-06-28"),
                insight,
                List.of(item));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Duration.ofMillis(millis));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
