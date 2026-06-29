package com.topsales.api.service;

import com.topsales.api.error.UnknownTenantException;
import com.topsales.common.api.Interval;
import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.Status;
import com.topsales.common.forecast.ForecastProvider;
import com.topsales.common.forecast.ServingResult;
import com.topsales.common.forecast.ServingRow;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * The forecast read path: resolves the {@code forecast}-mode top-k by walking the four-rung
 * degradation ladder (docs/lld.md §5). Reads never fail closed — the only exception that escapes is
 * {@link UnknownTenantException} (→ 404), thrown by the always-available actuals floor for a tenant
 * that has no config row.
 *
 * <p><b>Ladder</b> (highest fidelity first; first rung that yields rows wins):
 * <ol>
 *   <li><b>{@code fresh}</b> — active serving rows whose {@code asOf} is within the freshness SLO.</li>
 *   <li><b>{@code stale}</b> — active serving rows present but older than the SLO (last-good).</li>
 *   <li><b>{@code degraded}</b> — JVM seasonal-naive recomputed from actuals when serving rows are
 *       absent/unreadable ({@link SeasonalNaiveFallback}); intervals omitted, confidence LOW.</li>
 *   <li><b>{@code pending}</b> — plain actuals top-k relabeled, the always-available floor when no
 *       forecast version exists yet ({@link ActualsService}).</li>
 * </ol>
 *
 * <p>Rungs 1–2 share one source ({@link ForecastProvider}); freshness only chooses the status. The
 * provider call is wrapped so any {@link RuntimeException} (e.g. a serving-table read fault) is
 * treated as a miss and falls through to the lower, still-honest rungs rather than surfacing a 5xx
 * for a degraded forecast (§14).
 */
@Service
public class ForecastReadService {

    private final ForecastProvider provider;
    private final SeasonalNaiveFallback seasonalNaiveFallback;
    private final ActualsService actualsService;
    private final TopsalesProperties props;

    public ForecastReadService(
            ForecastProvider provider,
            SeasonalNaiveFallback seasonalNaiveFallback,
            ActualsService actualsService,
            TopsalesProperties props) {
        this.provider = provider;
        this.seasonalNaiveFallback = seasonalNaiveFallback;
        this.actualsService = actualsService;
        this.props = props;
    }

    /**
     * Resolve the forecast-mode top-k for {@code query} via the degradation ladder (§5).
     *
     * @throws UnknownTenantException if the tenant has no {@code tenant_config} row (→ 404), thrown by
     *     the actuals floor on rung 4
     */
    public TopKResponse handle(TopKQuery query) {
        // Rungs 1–2: precomputed serving rows. A read fault is a miss, never a thrown 5xx.
        Optional<ServingResult> serving = readServing(query);
        if (serving.isPresent() && !serving.get().rows().isEmpty()) {
            return fromServing(query, serving.get());
        }

        // Rung 3: on-the-fly seasonal-naive from actuals (degraded, intervals omitted).
        Optional<TopKResponse> degraded = seasonalNaiveFallback.tryDegraded(query);
        if (degraded.isPresent()) {
            return degraded.get();
        }

        // Rung 4: always-available floor — plain actuals relabeled pending. Also the 404 source.
        return pendingFromActuals(query);
    }

    /** Provider call with the fail-soft contract: any runtime fault → absent → drop to lower rungs. */
    private Optional<ServingResult> readServing(TopKQuery query) {
        try {
            return provider.getTopK(query);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Map active serving rows (rung 1/2) to a response, choosing {@code fresh} vs {@code stale} by
     * the freshness SLO. Rows arrive ranked ascending and top-50; we keep the first {@code k}.
     */
    private TopKResponse fromServing(TopKQuery query, ServingResult serving) {
        Duration age = Duration.between(serving.asOf(), Instant.now());
        // Negative age (asOf in the future) is treated as fresh — clock skew should not flag stale.
        Status status =
                age.compareTo(props.forecast().freshnessSlo()) <= 0 ? Status.FRESH : Status.STALE;

        List<ServingRow> rows = serving.rows();
        int limit = Math.min(query.k(), rows.size());
        List<TopKItem> items = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            items.add(toItem(rows.get(i)));
        }

        // Serving rows carry no date range; the UI derives the covered span from asOf + window.
        return new TopKResponse(
                query.tenantId(),
                query.mode(),
                query.window(),
                query.channel(),
                query.k(),
                status,
                serving.asOf(),
                null,
                null,
                null,
                items);
    }

    /** ServingRow → TopKItem (1:1). Interval is built only when both bounds are present. */
    private static TopKItem toItem(ServingRow row) {
        BigDecimal low = row.intervalLow();
        BigDecimal high = row.intervalHigh();
        Interval interval = (low != null && high != null) ? new Interval(low, high) : null;
        return new TopKItem(
                row.rank(),
                row.categoryId(),
                row.value(),
                row.deltaVsPrior(),
                row.confidence(),
                interval);
    }

    /** Rung 4: the actuals aggregation relabeled {@code pending} (honest "no forecast yet" floor). */
    private TopKResponse pendingFromActuals(TopKQuery query) {
        TopKResponse actuals = actualsService.topCategories(query); // 404 if unknown tenant
        return new TopKResponse(
                actuals.tenantId(),
                actuals.mode(),
                actuals.window(),
                actuals.channel(),
                actuals.k(),
                Status.PENDING,
                actuals.asOf(),
                actuals.windowFrom(),
                actuals.windowTo(),
                actuals.insight(),
                actuals.items());
    }
}
