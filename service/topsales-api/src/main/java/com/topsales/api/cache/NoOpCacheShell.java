package com.topsales.api.cache;

import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * Pass-through {@link CacheShell}: always runs the supplier, caches nothing. The default bean so the
 * app boots and the read path works before/without Redis; the Redis-backed {@code RedisCacheShell}
 * is marked {@code @Primary} and wins injection where present. Also the obvious test double.
 */
@Component
public class NoOpCacheShell implements CacheShell {

    @Override
    public TopKResponse getOrCompute(TopKQuery query, Supplier<TopKResponse> compute) {
        return compute.get();
    }
}
