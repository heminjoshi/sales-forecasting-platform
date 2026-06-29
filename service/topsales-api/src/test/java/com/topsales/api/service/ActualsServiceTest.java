package com.topsales.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.topsales.api.error.UnknownTenantException;
import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKQuery;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Channel;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.TenantConfig;
import com.topsales.common.domain.Window;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.common.repository.TenantConfigRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Pure unit tests for the actuals ranking/window logic (repos mocked). */
class ActualsServiceTest {

    private static final String TENANT = "t_demo";
    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");

    private AggregateRepository aggregates;
    private TenantConfigRepository tenants;
    private ActualsService service;

    @BeforeEach
    void setUp() {
        aggregates = mock(AggregateRepository.class);
        tenants = mock(TenantConfigRepository.class);
        service = new ActualsService(aggregates, tenants);
        when(tenants.find(TENANT))
                .thenReturn(Optional.of(new TenantConfig(TENANT, ZONE, "USD")));
    }

    private AggregateRow row(String category, String amount) {
        return new AggregateRow(
                TENANT, category, Channel.ONLINE, LocalDate.of(2026, 6, 20),
                new BigDecimal(amount), 1, "USD");
    }

    private TopKQuery query(Window window, int k) {
        return new TopKQuery(TENANT, window, Mode.ACTUALS, k, ChannelFilter.ALL);
    }

    @Test
    void ranksDescending_andTruncatesToK() {
        // 12 categories with strictly descending totals; k=10 keeps the top ten.
        List<AggregateRow> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(row(String.format("cat_%02d", i), String.valueOf(1000 - i * 10)));
        }
        when(aggregates.rangeByCategory(eq(TENANT), any(), any(), any())).thenReturn(rows);

        TopKResponse response = service.topCategories(query(Window.MONTH, 10));

        assertThat(response.items()).hasSize(10);
        assertThat(response.items()).extracting(TopKItem::rank)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(response.items()).extracting(TopKItem::category)
                .containsExactly(
                        "cat_00", "cat_01", "cat_02", "cat_03", "cat_04",
                        "cat_05", "cat_06", "cat_07", "cat_08", "cat_09");
        assertThat(response.items().get(0).value()).isEqualByComparingTo("1000");
        // actuals carry no forecast fields
        assertThat(response.items().get(0).deltaVsPrior()).isNull();
        assertThat(response.items().get(0).confidence()).isNull();
        assertThat(response.items().get(0).interval()).isNull();
        assertThat(response.status()).isEqualTo(Status.FRESH);
        assertThat(response.insight()).isNull();
        assertThat(response.asOf()).isNotNull();
    }

    @Test
    void stableTieBreak_byCategoryIdAscending_whenSumsEqual() {
        when(aggregates.rangeByCategory(eq(TENANT), any(), any(), any()))
                .thenReturn(List.of(row("cat_z", "100"), row("cat_a", "100"), row("cat_m", "100")));

        TopKResponse response = service.topCategories(query(Window.MONTH, 10));

        assertThat(response.items()).extracting(TopKItem::category)
                .containsExactly("cat_a", "cat_m", "cat_z");
    }

    @Test
    void sumsAmountAcrossMultipleDays_forOneCategory() {
        AggregateRow d1 = new AggregateRow(
                TENANT, "cat_office", Channel.ONLINE, LocalDate.of(2026, 6, 19),
                new BigDecimal("10.00"), 1, "USD");
        AggregateRow d2 = new AggregateRow(
                TENANT, "cat_office", Channel.OFFLINE, LocalDate.of(2026, 6, 20),
                new BigDecimal("5.50"), 1, "USD");
        when(aggregates.rangeByCategory(eq(TENANT), any(), any(), any())).thenReturn(List.of(d1, d2));

        TopKResponse response = service.topCategories(query(Window.MONTH, 10));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).value()).isEqualByComparingTo("15.50");
    }

    @Test
    void windowRange_week_is7DaysTrailingTodayInclusive() {
        assertWindowSpan(Window.WEEK, 7);
    }

    @Test
    void windowRange_month_is30DaysTrailingTodayInclusive() {
        assertWindowSpan(Window.MONTH, 30);
    }

    @Test
    void windowRange_year_is365DaysTrailingTodayInclusive() {
        assertWindowSpan(Window.YEAR, 365);
    }

    private void assertWindowSpan(Window window, int days) {
        when(aggregates.rangeByCategory(eq(TENANT), any(), any(), any())).thenReturn(List.of());

        service.topCategories(query(window, 10));

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(aggregates)
                .rangeByCategory(eq(TENANT), from.capture(), to.capture(), any());

        assertThat(to.getValue()).isEqualTo(LocalDate.now(ZONE)); // trailing window ends today (tz)
        // inclusive window of exactly `days` calendar days → span between endpoints is days-1
        assertThat(ChronoUnit.DAYS.between(from.getValue(), to.getValue())).isEqualTo(days - 1L);
    }

    @Test
    void unknownTenant_throwsUnknownTenantException() {
        when(tenants.find("t_missing")).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                service.topCategories(
                                        new TopKQuery(
                                                "t_missing",
                                                Window.MONTH,
                                                Mode.ACTUALS,
                                                10,
                                                ChannelFilter.ALL)))
                .isInstanceOf(UnknownTenantException.class);
    }

    @Test
    void forwardsChannelFilter_toRepository() {
        when(aggregates.rangeByCategory(eq(TENANT), any(), any(), eq(ChannelFilter.ONLINE)))
                .thenReturn(List.of(row("cat_office", "10.00")));

        TopKResponse response =
                service.topCategories(
                        new TopKQuery(TENANT, Window.MONTH, Mode.ACTUALS, 10, ChannelFilter.ONLINE));

        assertThat(response.items()).hasSize(1);
        org.mockito.Mockito.verify(aggregates)
                .rangeByCategory(eq(TENANT), any(), any(), eq(ChannelFilter.ONLINE));
    }
}
