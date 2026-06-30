package com.topsales.api.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.topsales.api.cache.NoOpCacheShell;
import com.topsales.api.service.ActualsService;
import com.topsales.api.service.ForecastReadService;
import com.topsales.api.service.InsightAttacher;
import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** Standalone MockMvc tests over the read controller + the RFC 7807 advice (service mocked). */
class TopCategoriesControllerTest {

    private static final String TENANT = "tenant_a";

    private MockMvc mvc;
    private ActualsService actualsService;
    private ForecastReadService forecastReadService;

    // Mirrors application.yml's topsales.read.* / topsales.window-days.* (the defaults + bounds).
    private static final TopsalesProperties PROPS =
            new TopsalesProperties(
                    new TopsalesProperties.Read(
                            10, 1, 50, List.of(5, 7, 10), "month", "forecast", "all"),
                    new TopsalesProperties.WindowDays(7, 30, 365),
                    new TopsalesProperties.Forecast(
                            Duration.ofHours(36),
                            730,
                            50,
                            3,
                            new TopsalesProperties.Forecast.HoltWinters(0.3, 0.1, 0.3, 7),
                            new TopsalesProperties.Forecast.Interval(1.28, 0.15, 0.40),
                            new TopsalesProperties.Forecast.Eval(84, 7, 7, 12, 0.20, 0.40)),
                    new TopsalesProperties.Cache(Duration.ofMinutes(15), 20, Duration.ofSeconds(2)),
                    null,
                    new TopsalesProperties.Rawlog("./data/rawlog"),
                    null);

    @BeforeEach
    void setUp() {
        actualsService = mock(ActualsService.class);
        forecastReadService = mock(ForecastReadService.class);
        // Real attacher over a deterministic fake generator — exercises the read-edge wiring without
        // pulling in topsales-insight; these routing tests don't assert on the insight line.
        InsightAttacher insightAttacher = new InsightAttacher(req -> "Top category leads the month.");
        // NoOpCacheShell is a pass-through: it runs the controller's supplier (forecastReadService)
        // unchanged, so these tests exercise the controller's routing, not the cache.
        mvc =
                standaloneSetup(
                                new TopCategoriesController(
                                        actualsService,
                                        forecastReadService,
                                        insightAttacher,
                                        new NoOpCacheShell(),
                                        PROPS))
                        .setControllerAdvice(new ApiExceptionHandler())
                        .build();
    }

    private TopKResponse response(Mode mode, Status status) {
        return new TopKResponse(
                TENANT,
                mode,
                Window.MONTH,
                ChannelFilter.ALL,
                10,
                status,
                Instant.parse("2026-06-28T00:00:00Z"),
                LocalDate.parse("2026-05-30"),
                LocalDate.parse("2026-06-28"),
                null,
                List.of(new TopKItem(1, "cat_office", new BigDecimal("100.00"), null, null, null)));
    }

    @Test
    void actualsHappyPath_returns200_withLowercaseEnums() throws Exception {
        when(actualsService.topCategories(any())).thenReturn(response(Mode.ACTUALS, Status.FRESH));

        mvc.perform(
                        get("/api/v1/tenants/{t}/top-categories", TENANT)
                                .param("mode", "actuals")
                                .param("window", "month")
                                .param("k", "10")
                                .requestAttr(TenantScopeFilter.AUTHED_TENANT_ATTR, TENANT)
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("actuals"))
                .andExpect(jsonPath("$.window").value("month"))
                .andExpect(jsonPath("$.status").value("fresh"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].category").value("cat_office"));
    }

    @Test
    void forecastMode_delegatesToForecastReadService_andReturnsItsStatus() throws Exception {
        // The controller no longer relabels: it hands the forecast path to ForecastReadService (via
        // the cache shell), whose degradation ladder owns the status. Here the service reports degraded.
        when(forecastReadService.handle(any()))
                .thenReturn(response(Mode.FORECAST, Status.DEGRADED));

        mvc.perform(
                        get("/api/v1/tenants/{t}/top-categories", TENANT)
                                .param("mode", "forecast")
                                .requestAttr(TenantScopeFilter.AUTHED_TENANT_ATTR, TENANT)
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("forecast"))
                .andExpect(jsonPath("$.status").value("degraded"));
    }

    @Test
    void tenantMismatch_returns403_problemJson() throws Exception {
        mvc.perform(
                        get("/api/v1/tenants/{t}/top-categories", TENANT)
                                .param("mode", "actuals")
                                .requestAttr(TenantScopeFilter.AUTHED_TENANT_ATTR, "t_other")
                                .header("X-Tenant-Id", "t_other"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Tenant mismatch"));
    }

    @Test
    void missingAuthedTenant_returns403() throws Exception {
        mvc.perform(
                        get("/api/v1/tenants/{t}/top-categories", TENANT)
                                .param("mode", "actuals"))
                .andExpect(status().isForbidden());
    }

    @Test
    void kBelowRange_returns400() throws Exception {
        mvc.perform(
                        get("/api/v1/tenants/{t}/top-categories", TENANT)
                                .param("k", "0")
                                .requestAttr(TenantScopeFilter.AUTHED_TENANT_ATTR, TENANT)
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void kAboveRange_returns400() throws Exception {
        mvc.perform(
                        get("/api/v1/tenants/{t}/top-categories", TENANT)
                                .param("k", "99")
                                .requestAttr(TenantScopeFilter.AUTHED_TENANT_ATTR, TENANT)
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isBadRequest());
    }

    @Test
    void badWindowEnum_returns400() throws Exception {
        mvc.perform(
                        get("/api/v1/tenants/{t}/top-categories", TENANT)
                                .param("window", "fortnight")
                                .requestAttr(TenantScopeFilter.AUTHED_TENANT_ATTR, TENANT)
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isBadRequest());
    }

    @Test
    void badChannelEnum_returns400() throws Exception {
        mvc.perform(
                        get("/api/v1/tenants/{t}/top-categories", TENANT)
                                .param("channel", "instore")
                                .requestAttr(TenantScopeFilter.AUTHED_TENANT_ATTR, TENANT)
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isBadRequest());
    }

    @Test
    void defaultChannelAll_returns200() throws Exception {
        when(actualsService.topCategories(any())).thenReturn(response(Mode.ACTUALS, Status.FRESH));

        mvc.perform(
                        get("/api/v1/tenants/{t}/top-categories", TENANT)
                                .param("mode", "actuals")
                                .requestAttr(TenantScopeFilter.AUTHED_TENANT_ATTR, TENANT)
                                .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk());
    }
}
