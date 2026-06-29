package com.topsales.api.cache;

import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;

import java.util.function.Supplier;

/**
 * Cache-aside shell over the forecast read path (docs/lld.md §7). The read pipeline calls
 * {@link #getOrCompute} with the query and a supplier that runs the (cache-miss) degradation chain;
 * an implementation returns a cached {@link TopKResponse} on a hit, otherwise computes, populates,
 * and returns.
 *
 * <p>The cache is an accelerator, never a correctness dependency: implementations must <b>fail
 * open</b> — on any cache-layer fault, run {@code compute} and return its result rather than throwing.
 * The built impl ({@code RedisCacheShell}) adds per-tenant version keying
 * ({@code topk:{tenant}:{ver}:{window}:{mode}:{channel}:{k}}), jittered TTL, and a single-flight
 * lease. {@link NoOpCacheShell} is the trivial pass-through used as a fallback / in tests.
 */
public interface CacheShell {

    /**
     * Return the cached response for {@code query}, or run {@code compute} (the degradation chain),
     * cache it, and return it. Never throws on a cache-layer fault — falls through to {@code compute}.
     */
    TopKResponse getOrCompute(TopKQuery query, Supplier<TopKResponse> compute);
}
