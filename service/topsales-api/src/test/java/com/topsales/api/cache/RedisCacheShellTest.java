package com.topsales.api.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.topsales.common.api.Interval;
import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Confidence;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link RedisCacheShell} (docs/lld.md §7): fail-open behaviour with a faulting Redis,
 * a JSON round-trip through the same Jackson 3 mapper Boot provides, and the TTL-jitter bounds.
 */
class RedisCacheShellTest {

    // Mirror application.yml (spring.jackson.default-property-inclusion=non_null), like the app's
    // Jackson 3 mapper — Boot's autoconfigured ObjectMapper serializes java.time + the enums for us.
    private static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                    .build();

    private static TopsalesProperties propsWith(Duration baseTtl, int jitterPct, Duration lockTtl) {
        return new TopsalesProperties(
                null, null, null, new TopsalesProperties.Cache(baseTtl, jitterPct, lockTtl), null);
    }

    private static TopKResponse sampleForecastResponse() {
        TopKItem item =
                new TopKItem(
                        1,
                        "Office Supplies",
                        new BigDecimal("5400.00"),
                        new BigDecimal("0.12"),
                        Confidence.HIGH,
                        new Interval(new BigDecimal("4900.00"), new BigDecimal("5900.00")));
        return new TopKResponse(
                "t_123",
                Mode.FORECAST,
                Window.MONTH,
                ChannelFilter.ONLINE,
                10,
                Status.FRESH,
                Instant.parse("2026-06-28T06:00:00Z"),
                LocalDate.parse("2026-05-30"),
                LocalDate.parse("2026-06-28"),
                "Office Supplies leads.",
                List.of(item));
    }

    @Test
    void failsOpenAndRunsSupplierExactlyOnceWhenRedisFaults() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        // The very first Redis touch (version read) blows up — the canonical connection failure.
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("redis down"));

        RedisCacheShell shell =
                new RedisCacheShell(
                        redis, MAPPER, propsWith(Duration.ofMinutes(15), 20, Duration.ofSeconds(2)));

        TopKResponse expected = sampleForecastResponse();
        AtomicInteger calls = new AtomicInteger();
        TopKQuery query = new TopKQuery("t_123", Window.MONTH, Mode.FORECAST, 10, ChannelFilter.ONLINE);

        TopKResponse result =
                shell.getOrCompute(
                        query,
                        () -> {
                            calls.incrementAndGet();
                            return expected;
                        });

        assertThat(result).isSameAs(expected);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void jsonRoundTripPreservesForecastItemFields() {
        TopKResponse original = sampleForecastResponse();

        String json = MAPPER.writeValueAsString(original);
        TopKResponse back = MAPPER.readValue(json, TopKResponse.class);

        // Compare by value; BigDecimal compared with compareTo so scale never makes this flaky.
        assertThat(back)
                .usingRecursiveComparison()
                .withEqualsForType((BigDecimal a, BigDecimal b) -> a.compareTo(b) == 0, BigDecimal.class)
                .isEqualTo(original);

        // Spot-check the forecast-only fields explicitly survived the trip.
        TopKItem item = back.items().get(0);
        assertThat(item.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(item.deltaVsPrior()).isEqualByComparingTo("0.12");
        assertThat(item.interval().low()).isEqualByComparingTo("4900.00");
        assertThat(item.interval().high()).isEqualByComparingTo("5900.00");
    }

    @Test
    void ttlJitterStaysWithinPlusMinusJitterPercentOfBase() {
        Duration base = Duration.ofMinutes(15);
        int jitterPct = 20;
        long baseMs = base.toMillis();
        long span = baseMs * jitterPct / 100;

        for (int i = 0; i < 10_000; i++) {
            long ttl = RedisCacheShell.ttlWithJitter(base, jitterPct).toMillis();
            assertThat(ttl).isBetween(baseMs - span, baseMs + span);
        }
    }

    @Test
    void ttlJitterDisabledWhenPercentIsZero() {
        Duration base = Duration.ofMinutes(15);
        assertThat(RedisCacheShell.ttlWithJitter(base, 0)).isEqualTo(base);
    }
}
