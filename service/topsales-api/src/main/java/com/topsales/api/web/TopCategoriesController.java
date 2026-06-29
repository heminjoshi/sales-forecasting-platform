package com.topsales.api.web;

import com.topsales.api.error.TenantMismatchException;
import com.topsales.api.service.ActualsService;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
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
 * <p>Phase 2 is actuals-only. {@code mode=actuals} returns the ranked actuals with
 * {@code status=fresh}. {@code mode=forecast} returns the <em>same</em> actuals aggregation but with
 * {@code status=pending} — the always-available floor and a one-line nod to the degradation chain;
 * the real serving-row lookup and last-good/seasonal-naive fallbacks arrive in Phase 4.
 */
@RestController
public class TopCategoriesController {

    private final ActualsService actualsService;
    private final TopsalesProperties props;

    public TopCategoriesController(ActualsService actualsService, TopsalesProperties props) {
        this.actualsService = actualsService;
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
        TopKResponse response = actualsService.topCategories(query); // 404 if unknown tenant

        if (parsedMode == Mode.FORECAST) {
            // No serving rows yet (Phase 2): same aggregation, honest pending status.
            response =
                    new TopKResponse(
                            response.tenantId(),
                            response.mode(),
                            response.window(),
                            response.channel(),
                            response.k(),
                            Status.PENDING,
                            response.asOf(),
                            response.windowFrom(),
                            response.windowTo(),
                            response.insight(),
                            response.items());
        }
        return response;
    }
}
