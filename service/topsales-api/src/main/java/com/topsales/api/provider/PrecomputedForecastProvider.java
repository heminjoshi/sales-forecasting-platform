package com.topsales.api.provider;

import com.topsales.common.api.TopKQuery;
import com.topsales.common.forecast.ForecastProvider;
import com.topsales.common.forecast.ServingKey;
import com.topsales.common.forecast.ServingResult;
import com.topsales.common.forecast.ServingTableRepository;

import java.util.Optional;

/**
 * The built (`local`) {@link ForecastProvider}: reads the active-version ranked rows the batch
 * precomputed into the serving table (DR-1, docs/lld.md §4, §5). A thin adapter — it composes the
 * serving partition key and delegates to {@link ServingTableRepository#readActive(String)}; it does
 * <b>no</b> freshness/status logic (that is the read pipeline's job) and never throws on a miss: an
 * empty {@link Optional} is the signal that drives the degradation chain.
 *
 * <p>The designed {@code aws} swap (DynamoDB-backed) implements the same port; on-demand/hybrid
 * variants (HLD §14) likewise slot in here without touching the read path.
 */
public class PrecomputedForecastProvider implements ForecastProvider {

    private final ServingTableRepository servingTable;

    public PrecomputedForecastProvider(ServingTableRepository servingTable) {
        this.servingTable = servingTable;
    }

    @Override
    public Optional<ServingResult> getTopK(TopKQuery query) {
        // Same ServingKey builder the batch writes with, so the two planes can never drift on format.
        String pk =
                ServingKey.of(query.tenantId(), query.window(), query.mode(), query.channel());
        return servingTable.readActive(pk);
    }
}
