package com.topsales.forecast.batch;

import com.topsales.common.cache.CacheKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Event-driven cache invalidation for the batch (docs/lld.md §7): after a tenant's serving rows are
 * written, {@code INCR tenantver:{tenant}} so every cached {@code topk:...} key (which embeds the old
 * version) is never read again. O(1), no key scan; shares the key format with the read API via
 * {@link CacheKeys}.
 *
 * <p>Fail-open: the cache is an accelerator, not a correctness dependency. Any Redis fault is logged
 * and swallowed so the batch always completes — even with Redis down (the next read just recomputes and
 * the version catches up on the following run).
 */
@Component
public class RedisCacheVersionBumper implements CacheVersionBumper {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheVersionBumper.class);

    private final StringRedisTemplate redis;

    public RedisCacheVersionBumper(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void bump(String tenant) {
        try {
            Long ver = redis.opsForValue().increment(CacheKeys.tenantVersion(tenant));
            log.debug("Bumped cache version: tenant={} ver={}", tenant, ver);
        } catch (Exception e) {
            log.warn("Cache-version bump failed for tenant={} — continuing (cache stays warm)", tenant, e);
        }
    }
}
