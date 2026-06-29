package com.topsales.common.forecast;

import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Window;

/**
 * The single builder for a serving-table partition key, {@code tenant#window#mode#channel} (ADR-0010,
 * docs/lld.md §3). Used by both the batch writer (Phase 3) and the read provider (Phase 4) so the two
 * can never drift on the key format. All segments use the enums' lowercase wire forms.
 */
public final class ServingKey {

    private ServingKey() {}

    public static String of(String tenantId, Window window, Mode mode, ChannelFilter channel) {
        return tenantId + "#" + window.wire() + "#" + mode.wire() + "#" + channel.wire();
    }
}
