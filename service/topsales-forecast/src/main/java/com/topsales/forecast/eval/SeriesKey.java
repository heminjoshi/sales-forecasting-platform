package com.topsales.forecast.eval;

import com.topsales.common.domain.Channel;

/**
 * Identity of one forecastable series: {@code (tenant, category, channel)}. The forecaster math is
 * channel-agnostic (it receives a single pre-filtered series), so the channel is part of the key, not
 * something the model sees. {@link Comparable} gives the report a stable total order.
 */
public record SeriesKey(String tenantId, String categoryId, Channel channel)
        implements Comparable<SeriesKey> {

    @Override
    public int compareTo(SeriesKey o) {
        int c = tenantId.compareTo(o.tenantId);
        if (c != 0) {
            return c;
        }
        c = categoryId.compareTo(o.categoryId);
        if (c != 0) {
            return c;
        }
        return channel.compareTo(o.channel);
    }
}
