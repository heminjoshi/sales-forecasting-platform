package com.topsales.common.cache;

import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Window;

/**
 * The single builders for the Redis cache keys (docs/lld.md §7), shared by both planes — the read API
 * ({@code RedisCacheShell}) reads/writes these, and the forecast batch ({@code RedisCacheVersionBumper})
 * bumps the per-tenant version — so the two can never drift on the key format (mirrors
 * {@link com.topsales.common.forecast.ServingKey}).
 *
 * <p>Pure string/enum helpers: this module has no Redis (or Spring) dependency, so nothing here may.
 *
 * <p>The top-k value key embeds the per-tenant cache version, so event-driven invalidation is O(1): the
 * batch just {@code INCR}s {@link #tenantVersion(String)} after writing serving rows, and every old key
 * (which carries the stale version) is simply never read again — no key scan. The {@code {channel}}
 * segment (added beyond §7's literal example) keeps the channel views (Phase 2.5) from colliding.
 */
public final class CacheKeys {

    private CacheKeys() {}

    /** Per-tenant cache-version counter ({@code INCR}'d by the batch; absent ⇒ treated as 0). */
    public static String tenantVersion(String tenant) {
        return "tenantver:" + tenant;
    }

    /**
     * The cached {@code TopKResponse} key:
     * {@code topk:{tenant}:{ver}:{window}:{mode}:{channel}:{k}}, using the enums' lowercase wire forms.
     */
    public static String topK(
            String tenant, long ver, Window window, Mode mode, ChannelFilter channel, int k) {
        return "topk:"
                + tenant
                + ":"
                + ver
                + ":"
                + window.wire()
                + ":"
                + mode.wire()
                + ":"
                + channel.wire()
                + ":"
                + k;
    }

    /** The single-flight lease key for a value key: {@code {topKKey}:lock}. */
    public static String lock(String topKKey) {
        return topKKey + ":lock";
    }
}
