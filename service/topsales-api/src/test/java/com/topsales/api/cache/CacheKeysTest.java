package com.topsales.api.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.cache.CacheKeys;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Window;

import org.junit.jupiter.api.Test;

/**
 * Pins the Redis cache key formats (docs/lld.md §7) so the read API and the forecast batch — which both
 * build keys via {@link CacheKeys} — can never drift. The {@code {channel}} segment is included beyond
 * §7's literal example to keep channel views from colliding (Phase 2.5).
 */
class CacheKeysTest {

    @Test
    void tenantVersionKey() {
        assertThat(CacheKeys.tenantVersion("t1")).isEqualTo("tenantver:t1");
    }

    @Test
    void topKKeyUsesLowercaseWireFormsAndEmbedsVersionAndChannel() {
        String key = CacheKeys.topK("t1", 7L, Window.MONTH, Mode.FORECAST, ChannelFilter.ONLINE, 10);
        assertThat(key).isEqualTo("topk:t1:7:month:forecast:online:10");
    }

    @Test
    void topKKeyDistinguishesChannels() {
        String online = CacheKeys.topK("t1", 0L, Window.WEEK, Mode.FORECAST, ChannelFilter.ONLINE, 5);
        String all = CacheKeys.topK("t1", 0L, Window.WEEK, Mode.FORECAST, ChannelFilter.ALL, 5);
        assertThat(online).isNotEqualTo(all);
        assertThat(all).isEqualTo("topk:t1:0:week:forecast:all:5");
    }

    @Test
    void lockKeyIsValueKeyPlusLockSuffix() {
        String value = CacheKeys.topK("t1", 1L, Window.YEAR, Mode.ACTUALS, ChannelFilter.OFFLINE, 3);
        assertThat(CacheKeys.lock(value)).isEqualTo(value + ":lock");
        assertThat(CacheKeys.lock(value)).isEqualTo("topk:t1:1:year:actuals:offline:3:lock");
    }
}
