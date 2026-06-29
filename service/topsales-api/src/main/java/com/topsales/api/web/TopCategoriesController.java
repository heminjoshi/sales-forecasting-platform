package com.topsales.api.web;

import com.topsales.api.cache.CacheShell;
import com.topsales.api.error.TenantMismatchException;
import com.topsales.api.service.ActualsService;
import com.topsales.api.service.ForecastReadService;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Window;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read/serving endpoint the dashboard polls (docs/lld.md §13):
 * {@code GET /api/v1/tenants/{tenantId}/top-categories}.
 *
 * <p>{@code mode=actuals} returns the ranked actuals with {@code status=fresh}, bypassing the
 * forecast cache and degradation chain. {@code mode=forecast} runs the cache-aside shell over the
 * {@link ForecastReadService} degradation ladder (fresh → stale → degraded → pending, §5), so the
 * status reflects the best available source rather than a fixed label.
 */
@RestController
public class TopCategoriesController {

    private final ActualsService actualsService;
    private final ForecastReadService forecastReadService;
    private final CacheShell cacheShell;
    private final TopsalesProperties props;

    public TopCategoriesController(
            ActualsService actualsService,
            ForecastReadService forecastReadService,
            CacheShell cacheShell,
            TopsalesProperties props) {
        this.actualsService = actualsService;
        this.forecastReadService = forecastReadService;
        this.cacheShell = cacheShell;
        this.props = props;
    }

    @GetMapping("/api/v1/tenants/{tenantId}/top-categories")
    public TopKResponse topCategories(
            @PathVariable String tenantId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) Integer k,
            @RequestParam(required = false) String channel,
            HttpServletRequest request) {

        // Omitted params fall back to the central read defaults (topsales.read.*) — kept as wire
        // strings/ints here so the enum parse and bounds-check below behave exactly as before.
        TopsalesProperties.Read read = props.read();
        int kValue = k != null ? k : read.kDefault();

        // 400s first: validate shape before authorization. Window/Mode/ChannelFilter parse
        // case-insensitively via the enums' @JsonCreator factory (Spring's default enum converter is
        // case-sensitive and would reject the lowercase wire values), throwing
        // IllegalArgumentException → 400.
        if (kValue < read.kMin() || kValue > read.kMax()) {
            throw new IllegalArgumentException(
                    "k must be between " + read.kMin() + " and " + read.kMax() + ", got " + kValue);
        }
        Mode parsedMode = Mode.from(mode != null ? mode : read.modeDefault());
        Window parsedWindow = Window.from(window != null ? window : read.windowDefault());
        ChannelFilter parsedChannel = ChannelFilter.from(channel != null ? channel : read.channelDefault());

        // 403: the path tenant must equal the authenticated tenant published by TenantScopeFilter.
        String authed = (String) request.getAttribute(TenantScopeFilter.AUTHED_TENANT_ATTR);
        if (authed == null || authed.isBlank() || !authed.equals(tenantId)) {
            throw new TenantMismatchException(tenantId);
        }

        TopKQuery query = new TopKQuery(tenantId, parsedWindow, parsedMode, kValue, parsedChannel);

        // Actuals bypass the forecast cache + degradation chain — always fresh from aggregates (§5).
        // Forecast reads run the cache-aside shell over the degradation ladder; the supplier is the
        // cache-miss compute. Both still raise UnknownTenantException → 404 for an unknown tenant.
        if (parsedMode == Mode.FORECAST) {
            return cacheShell.getOrCompute(query, () -> forecastReadService.handle(query));
        }
        return actualsService.topCategories(query); // 404 if unknown tenant
    }
}
