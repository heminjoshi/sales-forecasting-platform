package com.topsales.forecast.batch;

/**
 * Seam the forecast batch uses to invalidate a tenant's read cache after its serving rows land
 * (docs/lld.md §7). Bumping the per-tenant cache version ({@code tenantver:{tenant}}) makes every old
 * {@code topk:...} key (which embeds the stale version) unreachable in O(1) — no key scan.
 *
 * <p>Keeping this an interface lets {@link ForecasterJob} stay DB/Redis-free in unit tests (pass a
 * no-op lambda), while {@link RedisCacheVersionBumper} wires the real {@code INCR} at runtime.
 */
@FunctionalInterface
public interface CacheVersionBumper {

    /** Invalidate all cached reads for {@code tenant}. Must never throw — fail-open (the batch wins). */
    void bump(String tenant);
}
