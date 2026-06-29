package com.topsales.api.cache;

import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.cache.CacheKeys;
import com.topsales.common.config.TopsalesProperties;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * Redis-backed cache-aside shell over the forecast read path (docs/lld.md §7). On a hit it returns the
 * cached {@link TopKResponse} JSON; on a miss it runs the supplier (the degradation chain), populates
 * the key, and returns. Marked {@code @Primary} so it wins injection over {@link NoOpCacheShell} (the
 * fallback / test double) wherever a {@link StringRedisTemplate} is present.
 *
 * <p><b>Keying.</b> The value key {@code topk:{tenant}:{ver}:{window}:{mode}:{channel}:{k}} embeds the
 * per-tenant cache version held at {@code tenantver:{tenant}}. The batch {@code INCR}s that counter
 * after writing serving rows, so every old key (carrying the stale version) is simply never read again
 * — O(1) event-driven invalidation, no key scan. See {@link CacheKeys}.
 *
 * <p><b>TTL jitter.</b> Each key expires at {@code baseTtl ± jitterPct%} ({@link #ttlWithJitter}) so a
 * burst of keys written together does not expire in lock-step and stampede the read path.
 *
 * <p><b>Single-flight.</b> On a miss the caller races for a short lease
 * ({@code SET {key}:lock NX PX lockTtl}); the leader computes and populates while followers briefly poll
 * the value key, then fall through and compute anyway if the lease lapses — never deadlock.
 *
 * <p><b>Fail-open.</b> The cache is an accelerator, never a correctness dependency. Any Redis fault
 * (connection failure, timeout, serialization error) is logged once and the supplier is run directly;
 * {@link #getOrCompute} never throws on a cache-layer fault. Supplier exceptions (e.g.
 * {@code UnknownTenantException} → 404) are <b>not</b> intercepted or cached — they propagate.
 */
@Component
@Primary
public class RedisCacheShell implements CacheShell {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheShell.class);

    /** How often a follower re-checks the value key while the leader computes. */
    private static final long POLL_INTERVAL_MS = 25L;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final TopsalesProperties props;

    /** Log the first cache fault loudly (with stack), the rest at debug — so logs stay quiet. */
    private final AtomicBoolean faultLogged = new AtomicBoolean(false);

    public RedisCacheShell(
            StringRedisTemplate redis, ObjectMapper mapper, TopsalesProperties props) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public TopKResponse getOrCompute(TopKQuery query, Supplier<TopKResponse> compute) {
        // Build the versioned key (reads tenantver from Redis). null ⇒ Redis unavailable: just compute.
        String key = cacheKey(query);
        if (key != null) {
            TopKResponse hit = tryRead(key);
            if (hit != null) {
                return hit;
            }
        }
        return computeWithSingleFlight(query, compute, key);
    }

    // --- miss path ------------------------------------------------------------------------------

    /**
     * Single-flight a miss: the lease leader computes + caches; followers poll briefly then compute too.
     * The {@code compute.get()} call site is reached exactly once per invocation and sits outside every
     * fail-open catch, so supplier exceptions propagate (never cached).
     */
    private TopKResponse computeWithSingleFlight(
            TopKQuery query, Supplier<TopKResponse> compute, String key) {
        if (key == null) {
            // Redis is unavailable — single-flight needs it, so just compute directly (fail-open).
            return compute.get();
        }

        String lockKey = CacheKeys.lock(key);
        boolean leader = acquireLease(lockKey);
        if (!leader) {
            TopKResponse published = pollForValue(key);
            if (published != null) {
                return published;
            }
            // Lease holder never published in time (slow/crashed): fall through and compute anyway.
        }

        TopKResponse result = compute.get();
        cache(key, result);
        return result;
    }

    // --- Redis ops (each fails soft: null/false + log-once on any fault) -------------------------

    /** {@code topk:...} value key for the query, or {@code null} if the version read faults. */
    private String cacheKey(TopKQuery query) {
        try {
            long ver = currentVersion(query.tenantId());
            return CacheKeys.topK(
                    query.tenantId(),
                    ver,
                    query.window(),
                    query.mode(),
                    query.channel(),
                    query.k());
        } catch (Exception e) {
            logFault("version-read", e);
            return null;
        }
    }

    /** Read the per-tenant cache version; absent ⇒ 0 (propagates faults to the caller). */
    private long currentVersion(String tenant) {
        String raw = redis.opsForValue().get(CacheKeys.tenantVersion(tenant));
        return raw == null ? 0L : Long.parseLong(raw.trim());
    }

    /** Deserialize the cached response, or {@code null} on a miss or any fault. */
    private TopKResponse tryRead(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return mapper.readValue(json, TopKResponse.class);
        } catch (Exception e) {
            logFault("read", e);
            return null;
        }
    }

    /** {@code SET lockKey id NX PX lockTtl}: true if this caller won the lease, false otherwise. */
    private boolean acquireLease(String lockKey) {
        try {
            String id = Long.toHexString(ThreadLocalRandom.current().nextLong());
            Boolean won =
                    redis.opsForValue()
                            .setIfAbsent(lockKey, id, props.cache().lockTtl());
            return Boolean.TRUE.equals(won);
        } catch (Exception e) {
            logFault("lock", e);
            return false;
        }
    }

    /** Poll the value key while the leader computes, up to {@code lockTtl}; {@code null} on timeout. */
    private TopKResponse pollForValue(String key) {
        long deadline = System.nanoTime() + props.cache().lockTtl().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
            TopKResponse hit = tryRead(key);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** Serialize and store the response with a jittered TTL; best-effort (never throws). */
    private void cache(String key, TopKResponse value) {
        try {
            String json = mapper.writeValueAsString(value);
            redis.opsForValue()
                    .set(key, json, ttlWithJitter(props.cache().baseTtl(), props.cache().jitterPct()));
        } catch (Exception e) {
            logFault("write", e);
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Spread an expiry uniformly across {@code base ± jitterPct%} so co-written keys never expire in
     * lock-step (anti-stampede, §7). Package-private + static so it is unit-testable in isolation.
     */
    static Duration ttlWithJitter(Duration base, int jitterPct) {
        if (jitterPct <= 0) {
            return base;
        }
        long baseMs = base.toMillis();
        long span = baseMs * jitterPct / 100; // max deviation in each direction
        long delta = ThreadLocalRandom.current().nextLong(-span, span + 1);
        return Duration.ofMillis(baseMs + delta);
    }

    private void logFault(String op, Exception e) {
        if (faultLogged.compareAndSet(false, true)) {
            log.warn(
                    "Cache fault during {} — failing open to direct compute "
                            + "(subsequent faults logged at debug)",
                    op,
                    e);
        } else {
            log.debug("Cache fault during {} — failing open", op, e);
        }
    }
}
