package com.topsales.api.service;

import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Confidence;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.TenantConfig;
import com.topsales.common.forecast.ForecastContext;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.common.repository.TenantConfigRepository;
import com.topsales.forecast.model.SeasonalNaiveForecaster;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

/**
 * Tier 3 of the read-path degradation chain (docs/lld.md §5, status {@code degraded}): when no
 * precomputed serving rows are available, recompute the top-k on the fly with the seasonal-naive
 * baseline over the tenant's actuals history. Reuses the exact {@link SeasonalNaiveForecaster} the
 * batch uses so the on-the-fly shape matches the precomputed one — only the labeling differs.
 *
 * <p>Degraded forecasts are explicitly uncertain, so per §5 they <b>omit prediction intervals</b>,
 * carry {@link Confidence#LOW}, and drop {@code deltaVsPrior}: the response signals "computed live,
 * trust it less" rather than dressing a fallback up as a full forecast.
 *
 * <p>Returns {@link Optional#empty()} (never throws) when it cannot honestly produce a degraded
 * result — an unknown tenant or an empty history — so the caller drops to tier 4 ({@code pending}),
 * which is where the unknown-tenant 404 is raised.
 */
@Service
public class SeasonalNaiveFallback {

    private final AggregateRepository aggregates;
    private final TenantConfigRepository tenants;
    private final SeasonalNaiveForecaster forecaster;
    private final TopsalesProperties props;

    public SeasonalNaiveFallback(
            AggregateRepository aggregates,
            TenantConfigRepository tenants,
            SeasonalNaiveForecaster forecaster,
            TopsalesProperties props) {
        this.aggregates = aggregates;
        this.tenants = tenants;
        this.forecaster = forecaster;
        this.props = props;
    }

    /**
     * Attempt a degraded ({@code degraded}) forecast for {@code query}.
     *
     * @return the on-the-fly top-k, or empty if the tenant is unknown or has no history (→ tier 4)
     */
    public Optional<TopKResponse> tryDegraded(TopKQuery query) {
        Optional<TenantConfig> config = tenants.find(query.tenantId());
        if (config.isEmpty()) {
            // Let tier 4 (actuals) raise the canonical 404 rather than swallowing it here.
            return Optional.empty();
        }
        ZoneId zone = config.get().timezone();

        LocalDate today = LocalDate.now(zone);
        int historyDays = props.forecast().historyDays();
        LocalDate from = today.minusDays(historyDays - 1L);

        List<AggregateRow> rows =
                aggregates.rangeByCategory(query.tenantId(), from, today, query.channel());
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        // Group history by category. For channel=ALL the rows mix channels per day; SeriesPrep inside
        // the forecaster sums multiple rows per day defensively, so passing the mixed rows straight
        // through yields the correct per-day total. LinkedHashMap only for stable pre-sort iteration.
        Map<String, List<AggregateRow>> byCategory = new LinkedHashMap<>();
        for (AggregateRow row : rows) {
            byCategory.computeIfAbsent(row.categoryId(), c -> new ArrayList<>()).add(row);
        }

        int windowDays = props.windowDays().forWindow(query.window());

        // The seasonal-naive point for the whole window is the ForecastValue at horizon == windowDays.
        record Scored(String categoryId, BigDecimal value) {}
        List<Scored> scored = new ArrayList<>(byCategory.size());
        for (Map.Entry<String, List<AggregateRow>> e : byCategory.entrySet()) {
            ForecastContext ctx =
                    new ForecastContext(
                            query.tenantId(), e.getKey(), new int[] {windowDays}, query.window());
            List<ForecastValue> values = forecaster.forecast(e.getValue(), ctx);
            BigDecimal point = pointFor(values, windowDays);
            if (point != null) {
                scored.add(new Scored(e.getKey(), point));
            }
        }

        // Rank descending by forecast value; stable tie-break ascending by category id; take top-k.
        List<Scored> ranked =
                scored.stream()
                        .sorted(
                                Comparator.comparing(Scored::value, Comparator.reverseOrder())
                                        .thenComparing(Scored::categoryId))
                        .limit(query.k())
                        .toList();

        List<TopKItem> items = new ArrayList<>(ranked.size());
        int rank = 1;
        for (Scored s : ranked) {
            // Degraded: value only — interval omitted, confidence LOW, no delta (§5).
            items.add(new TopKItem(rank++, s.categoryId(), s.value(), null, Confidence.LOW, null));
        }

        return Optional.of(
                new TopKResponse(
                        query.tenantId(),
                        query.mode(),
                        query.window(),
                        query.channel(),
                        query.k(),
                        Status.DEGRADED,
                        Instant.now(),
                        null,
                        null,
                        null,
                        items));
    }

    /** The point estimate for the window horizon, or null if the forecaster produced none for it. */
    private static BigDecimal pointFor(List<ForecastValue> values, int windowDays) {
        for (ForecastValue v : values) {
            if (v.horizon() == windowDays) {
                return v.pointValue();
            }
        }
        return null;
    }
}
