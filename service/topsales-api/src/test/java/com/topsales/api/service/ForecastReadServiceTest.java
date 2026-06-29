package com.topsales.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.topsales.api.error.UnknownTenantException;
import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Confidence;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;
import com.topsales.common.forecast.ForecastProvider;
import com.topsales.common.forecast.ServingResult;
import com.topsales.common.forecast.ServingRow;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure unit tests for the forecast-path degradation ladder (docs/lld.md §5; collaborators mocked). */
class ForecastReadServiceTest {

    private static final String TENANT = "t_demo";

    // Mirrors application.yml; freshness SLO = 36h is what drives fresh vs stale.
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
                    new TopsalesProperties.Rawlog("./data/rawlog"));

    private ForecastProvider provider;
    private SeasonalNaiveFallback fallback;
    private ActualsService actualsService;
    private ForecastReadService service;

    @BeforeEach
    void setUp() {
        provider = mock(ForecastProvider.class);
        fallback = mock(SeasonalNaiveFallback.class);
        actualsService = mock(ActualsService.class);
        service = new ForecastReadService(provider, fallback, actualsService, PROPS);
    }

    private TopKQuery query(int k) {
        return new TopKQuery(TENANT, Window.MONTH, Mode.FORECAST, k, ChannelFilter.ALL);
    }

    private ServingRow servingRow(int rank, String category, String value) {
        return new ServingRow(
                rank,
                category,
                new BigDecimal(value),
                new BigDecimal("90.00"),
                new BigDecimal("110.00"),
                Confidence.HIGH,
                new BigDecimal("0.12"));
    }

    // ---- Rung 1: fresh serving rows ----------------------------------------------------------

    @Test
    void servingFresh_whenAsOfWithinSlo_returnsFreshWithMappedItems() {
        ServingResult result =
                new ServingResult(
                        List.of(servingRow(1, "cat_office", "100.00")), 7, Instant.now());
        when(provider.getTopK(any())).thenReturn(Optional.of(result));

        TopKResponse response = service.handle(query(10));

        assertThat(response.status()).isEqualTo(Status.FRESH);
        assertThat(response.mode()).isEqualTo(Mode.FORECAST);
        assertThat(response.asOf()).isEqualTo(result.asOf());
        // Serving rows carry no date range — UI derives it from asOf + window.
        assertThat(response.windowFrom()).isNull();
        assertThat(response.windowTo()).isNull();
        // Insight (Phase 5) is attached at the read edge by InsightAttacher (wired into the
        // controller), not by this service; the raw degradation-ladder response leaves it unset. The
        // populated, non-null insight is covered by InsightAttacherTest + EnumWireFormatThroughSpringTest.

        TopKItem item = response.items().get(0);
        assertThat(item.rank()).isEqualTo(1);
        assertThat(item.category()).isEqualTo("cat_office");
        assertThat(item.value()).isEqualByComparingTo("100.00");
        assertThat(item.deltaVsPrior()).isEqualByComparingTo("0.12");
        assertThat(item.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(item.interval().low()).isEqualByComparingTo("90.00");
        assertThat(item.interval().high()).isEqualByComparingTo("110.00");
    }

    @Test
    void servingFresh_whenAsOfInFuture_isStillFresh() {
        ServingResult result =
                new ServingResult(
                        List.of(servingRow(1, "cat_office", "100.00")),
                        7,
                        Instant.now().plus(Duration.ofHours(1)));
        when(provider.getTopK(any())).thenReturn(Optional.of(result));

        assertThat(service.handle(query(10)).status()).isEqualTo(Status.FRESH);
    }

    // ---- Rung 2: stale serving rows ----------------------------------------------------------

    @Test
    void servingStale_whenAsOfBeyondSlo_returnsStale() {
        ServingResult result =
                new ServingResult(
                        List.of(servingRow(1, "cat_office", "100.00")),
                        7,
                        Instant.now().minus(Duration.ofHours(48))); // > 36h SLO
        when(provider.getTopK(any())).thenReturn(Optional.of(result));

        assertThat(service.handle(query(10)).status()).isEqualTo(Status.STALE);
    }

    // ---- Serving-row mapping details ---------------------------------------------------------

    @Test
    void servingRows_truncatedToK() {
        List<ServingRow> rows = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            rows.add(servingRow(i, String.format("cat_%02d", i), String.valueOf(1000 - i)));
        }
        when(provider.getTopK(any()))
                .thenReturn(Optional.of(new ServingResult(rows, 7, Instant.now())));

        TopKResponse response = service.handle(query(5));

        assertThat(response.items()).hasSize(5);
        assertThat(response.items()).extracting(TopKItem::rank).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void servingRows_kLargerThanRows_returnsAllRows() {
        when(provider.getTopK(any()))
                .thenReturn(
                        Optional.of(
                                new ServingResult(
                                        List.of(servingRow(1, "cat_office", "100.00")),
                                        7,
                                        Instant.now())));

        assertThat(service.handle(query(10)).items()).hasSize(1);
    }

    @Test
    void servingRow_intervalNull_whenEitherBoundNull() {
        ServingRow lowNull =
                new ServingRow(
                        1, "cat_a", new BigDecimal("100.00"), null,
                        new BigDecimal("110.00"), Confidence.MEDIUM, null);
        ServingRow highNull =
                new ServingRow(
                        2, "cat_b", new BigDecimal("90.00"), new BigDecimal("80.00"),
                        null, Confidence.MEDIUM, null);
        when(provider.getTopK(any()))
                .thenReturn(
                        Optional.of(new ServingResult(List.of(lowNull, highNull), 7, Instant.now())));

        TopKResponse response = service.handle(query(10));

        assertThat(response.items().get(0).interval()).isNull();
        assertThat(response.items().get(1).interval()).isNull();
    }

    @Test
    void servingPresentButEmptyRows_fallsThroughToLowerRungs() {
        when(provider.getTopK(any()))
                .thenReturn(Optional.of(new ServingResult(List.of(), 7, Instant.now())));
        when(fallback.tryDegraded(any())).thenReturn(Optional.of(degradedResponse()));

        assertThat(service.handle(query(10)).status()).isEqualTo(Status.DEGRADED);
    }

    @Test
    void providerThrows_isTreatedAsMiss_andDegrades() {
        when(provider.getTopK(any())).thenThrow(new RuntimeException("serving table unreadable"));
        when(fallback.tryDegraded(any())).thenReturn(Optional.of(degradedResponse()));

        assertThat(service.handle(query(10)).status()).isEqualTo(Status.DEGRADED);
    }

    // ---- Rung 3: degraded --------------------------------------------------------------------

    @Test
    void serviceAbsent_fallbackPresent_returnsDegraded() {
        when(provider.getTopK(any())).thenReturn(Optional.empty());
        when(fallback.tryDegraded(any())).thenReturn(Optional.of(degradedResponse()));

        TopKResponse response = service.handle(query(10));

        assertThat(response.status()).isEqualTo(Status.DEGRADED);
        assertThat(response.items().get(0).confidence()).isEqualTo(Confidence.LOW);
        assertThat(response.items().get(0).interval()).isNull();
    }

    // ---- Rung 4: pending floor ---------------------------------------------------------------

    @Test
    void serviceAndFallbackAbsent_relabelsActualsAsPending() {
        when(provider.getTopK(any())).thenReturn(Optional.empty());
        when(fallback.tryDegraded(any())).thenReturn(Optional.empty());
        when(actualsService.topCategories(any())).thenReturn(actualsResponse());

        TopKResponse response = service.handle(query(10));

        assertThat(response.status()).isEqualTo(Status.PENDING);
        // The actuals body is preserved; only the status is relabeled.
        assertThat(response.items().get(0).category()).isEqualTo("cat_office");
        assertThat(response.windowFrom()).isNotNull();
        assertThat(response.windowTo()).isNotNull();
    }

    @Test
    void unknownTenant_propagatesFromActualsFloor() {
        when(provider.getTopK(any())).thenReturn(Optional.empty());
        when(fallback.tryDegraded(any())).thenReturn(Optional.empty());
        when(actualsService.topCategories(any()))
                .thenThrow(new UnknownTenantException(TENANT));

        assertThatThrownBy(() -> service.handle(query(10)))
                .isInstanceOf(UnknownTenantException.class);
    }

    private TopKResponse degradedResponse() {
        return new TopKResponse(
                TENANT,
                Mode.FORECAST,
                Window.MONTH,
                ChannelFilter.ALL,
                10,
                Status.DEGRADED,
                Instant.now(),
                null,
                null,
                null,
                List.of(
                        new TopKItem(
                                1, "cat_office", new BigDecimal("100.00"), null,
                                Confidence.LOW, null)));
    }

    private TopKResponse actualsResponse() {
        return new TopKResponse(
                TENANT,
                Mode.FORECAST,
                Window.MONTH,
                ChannelFilter.ALL,
                10,
                Status.FRESH,
                Instant.now(),
                java.time.LocalDate.parse("2026-05-30"),
                java.time.LocalDate.parse("2026-06-28"),
                null,
                List.of(
                        new TopKItem(
                                1, "cat_office", new BigDecimal("100.00"), null, null, null)));
    }
}
