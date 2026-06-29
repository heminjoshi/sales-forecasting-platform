package com.topsales.api.web;

import com.topsales.api.error.TenantMismatchException;
import com.topsales.api.service.ActualsService;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
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

    private static final int MIN_K = 1;
    private static final int MAX_K = 50;

    private final ActualsService actualsService;

    public TopCategoriesController(ActualsService actualsService) {
        this.actualsService = actualsService;
    }

    @GetMapping("/api/v1/tenants/{tenantId}/top-categories")
    public TopKResponse topCategories(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "forecast") String mode,
            @RequestParam(defaultValue = "month") String window,
            @RequestParam(defaultValue = "10") int k,
            @RequestParam(defaultValue = "all") String channel,
            HttpServletRequest request) {

        // 400s first: validate shape before authorization. Window/Mode/ChannelFilter parse
        // case-insensitively via the enums' @JsonCreator factory (Spring's default enum converter is
        // case-sensitive and would reject the lowercase wire values), throwing
        // IllegalArgumentException → 400.
        if (k < MIN_K || k > MAX_K) {
            throw new IllegalArgumentException("k must be between " + MIN_K + " and " + MAX_K + ", got " + k);
        }
        Mode parsedMode = Mode.from(mode);
        Window parsedWindow = Window.from(window);
        ChannelFilter parsedChannel = ChannelFilter.from(channel);

        // 403: the path tenant must equal the authenticated tenant published by TenantScopeFilter.
        String authed = (String) request.getAttribute(TenantScopeFilter.AUTHED_TENANT_ATTR);
        if (authed == null || authed.isBlank() || !authed.equals(tenantId)) {
            throw new TenantMismatchException(tenantId);
        }

        TopKQuery query = new TopKQuery(tenantId, parsedWindow, parsedMode, k, parsedChannel);
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
