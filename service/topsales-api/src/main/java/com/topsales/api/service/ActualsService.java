package com.topsales.api.service;

import com.topsales.api.error.UnknownTenantException;
import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.TenantConfig;
import com.topsales.common.domain.Window;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.common.repository.TenantConfigRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * The actuals read path: rank a tenant's categories by summed actual sales over a trailing window
 * (docs/lld.md §5, §13). No forecasting, AI, or caching here — those are Phases 3–5. This is also
 * the always-available floor the forecast path falls back to (the controller relabels its status to
 * {@code pending} in Phase 2 until serving rows exist).
 *
 * <p><b>Window definition.</b> Each window is a fixed-length <em>trailing</em> range ending today
 * in the tenant's timezone, inclusive of today: {@code week}=7, {@code month}=30, {@code year}=365
 * calendar days. So {@code to = today(tz)} and {@code from = to.minusDays(days - 1)}. Calendar-day
 * counts (not calendar months) keep the math timezone-correct and trivially testable; richer
 * calendar windows can come later behind the same seam.
 */
@Service
public class ActualsService {

    private static final Map<Window, Integer> WINDOW_DAYS =
            Map.of(Window.WEEK, 7, Window.MONTH, 30, Window.YEAR, 365);

    private final AggregateRepository aggregates;
    private final TenantConfigRepository tenants;

    public ActualsService(AggregateRepository aggregates, TenantConfigRepository tenants) {
        this.aggregates = aggregates;
        this.tenants = tenants;
    }

    /**
     * Rank the top-{@code k} categories by summed actual sales over the query's trailing window.
     *
     * @throws UnknownTenantException if the tenant has no {@code tenant_config} row (→ 404)
     */
    public TopKResponse topCategories(TopKQuery query) {
        TenantConfig config =
                tenants.find(query.tenantId())
                        .orElseThrow(() -> new UnknownTenantException(query.tenantId()));

        ZoneId zone = config.timezone();
        LocalDate to = LocalDate.now(zone);
        int days = WINDOW_DAYS.get(query.window());
        LocalDate from = to.minusDays(days - 1L);

        List<AggregateRow> rows =
                aggregates.rangeByCategory(query.tenantId(), from, to, query.channel());

        // Sum each category's per-day rollups into a single window total. For channel=ALL this also
        // sums across both channels (the read-time `all` rollup); a single channel filters in SQL.
        // LinkedHashMap is only for determinism of iteration before the explicit sort below.
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (AggregateRow row : rows) {
            totals.merge(row.categoryId(), row.sumAmount(), BigDecimal::add);
        }

        // Rank descending by value; stable tie-break ascending by category id. BigDecimal natural
        // order compares by numeric value (scale-insensitive), which is what ranking wants.
        List<Map.Entry<String, BigDecimal>> ranked =
                totals.entrySet().stream()
                        .sorted(
                                Comparator.comparing(
                                                Map.Entry<String, BigDecimal>::getValue,
                                                Comparator.reverseOrder())
                                        .thenComparing(Map.Entry::getKey))
                        .limit(query.k())
                        .toList();

        List<TopKItem> items = new ArrayList<>(ranked.size());
        int rank = 1;
        for (Map.Entry<String, BigDecimal> e : ranked) {
            // Actuals carry no delta/confidence/interval — those are forecast-only (and omitted on
            // the wire by the non_null inclusion config).
            items.add(new TopKItem(rank++, e.getKey(), e.getValue(), null, null, null));
        }

        return new TopKResponse(
                query.tenantId(),
                query.mode(),
                query.window(),
                query.channel(),
                query.k(),
                Status.FRESH,
                Instant.now(),
                null, // insight is Phase 5; deterministic template is the floor there
                items);
    }
}
