package com.topsales.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.topsales.common.domain.AggregateDelta;
import com.topsales.common.domain.Channel;
import com.topsales.common.domain.EventType;
import com.topsales.common.domain.SaleEvent;
import com.topsales.common.domain.TenantConfig;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.common.repository.TenantConfigRepository;
import com.topsales.ingestion.repo.BucketingEventLedger;
import com.topsales.ingestion.repo.QuarantineRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;

/** Unit tests for the ingestion pipeline; all ports mocked (no Docker, surefire). */
class IngestionServiceTest {

    private static final String AUTHED = "tenant_a";
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    private BucketingEventLedger ledger;
    private AggregateRepository aggregates;
    private TenantConfigRepository tenantConfig;
    private QuarantineRepository quarantine;
    private IngestionService service;

    @BeforeEach
    void setUp() {
        ledger = mock(BucketingEventLedger.class);
        aggregates = mock(AggregateRepository.class);
        tenantConfig = mock(TenantConfigRepository.class);
        quarantine = mock(QuarantineRepository.class);

        // A real TransactionTemplate over a stub tx manager: execute() just runs the callback.
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(null);

        service =
                new IngestionService(
                        ledger,
                        aggregates,
                        tenantConfig,
                        quarantine,
                        JsonMapper.builder().build(),
                        txManager);
    }

    private void knownTenant() {
        when(tenantConfig.find(AUTHED))
                .thenReturn(Optional.of(new TenantConfig(AUTHED, LA, "USD")));
    }

    private SaleEvent sale(String orderId, BigDecimal amount, Instant eventTime) {
        return new SaleEvent(
                "t_body", orderId, "cat_office", Channel.ONLINE, amount, "USD", EventType.SALE,
                eventTime, null);
    }

    @Test
    void missingRequiredField_isQuarantined_andNeverPersisted() {
        knownTenant();
        SaleEvent bad =
                new SaleEvent(
                        "t_body",
                        "o_1",
                        null, // missing categoryId
                        Channel.ONLINE,
                        new BigDecimal("10.00"),
                        "USD",
                        EventType.SALE,
                        Instant.parse("2026-06-20T14:03:00Z"),
                        null);

        IngestResult result = service.ingest(AUTHED, bad);

        assertThat(result).isEqualTo(new IngestResult(1, 0, 0, 1));
        verify(quarantine).quarantine(eq(AUTHED), any(), eq("missing categoryId"));
        verify(ledger, never()).record(any(), any());
        verify(aggregates, never()).upsertAdditive(any());
    }

    @Test
    void missingChannel_isQuarantined_andNeverPersisted() {
        knownTenant();
        SaleEvent bad =
                new SaleEvent(
                        "t_body",
                        "o_1",
                        "cat_office",
                        null, // missing channel
                        new BigDecimal("10.00"),
                        "USD",
                        EventType.SALE,
                        Instant.parse("2026-06-20T14:03:00Z"),
                        null);

        IngestResult result = service.ingest(AUTHED, bad);

        assertThat(result).isEqualTo(new IngestResult(1, 0, 0, 1));
        verify(quarantine).quarantine(eq(AUTHED), any(), eq("missing channel"));
        verify(aggregates, never()).upsertAdditive(any());
    }

    @Test
    void amountScaleTooLarge_isQuarantined() {
        knownTenant();
        SaleEvent bad = sale("o_scale", new BigDecimal("10.005"), Instant.parse("2026-06-20T14:03:00Z"));

        IngestResult result = service.ingest(AUTHED, bad);

        assertThat(result.quarantined()).isEqualTo(1);
        verify(quarantine).quarantine(eq(AUTHED), any(), eq("amount scale exceeds 2"));
        verify(aggregates, never()).upsertAdditive(any());
    }

    @Test
    void unknownTenant_isQuarantined() {
        when(tenantConfig.find(AUTHED)).thenReturn(Optional.empty());
        SaleEvent event = sale("o_2", new BigDecimal("10.00"), Instant.parse("2026-06-20T14:03:00Z"));

        IngestResult result = service.ingest(AUTHED, event);

        assertThat(result).isEqualTo(new IngestResult(1, 0, 0, 1));
        verify(quarantine).quarantine(eq(AUTHED), any(), eq("unknown tenant"));
        verify(ledger, never()).record(any(), any());
    }

    @Test
    void bucketsOnTenantLocalDay_notUtcDay() {
        knownTenant();
        when(ledger.record(any(), any())).thenReturn(true);
        // 06:30Z on the 20th is 23:30 on the 19th in America/Los_Angeles (UTC-7 in June).
        SaleEvent event =
                sale("o_3", new BigDecimal("42.50"), Instant.parse("2026-06-20T06:30:00Z"));

        service.ingest(AUTHED, event);

        ArgumentCaptor<AggregateDelta> delta = ArgumentCaptor.forClass(AggregateDelta.class);
        verify(aggregates).upsertAdditive(delta.capture());
        assertThat(delta.getValue().bucketDate()).isEqualTo(LocalDate.of(2026, 6, 19));
    }

    @Test
    void duplicate_skipsAggregateUpsert() {
        knownTenant();
        when(ledger.record(any(), any())).thenReturn(false);
        SaleEvent event = sale("o_4", new BigDecimal("10.00"), Instant.parse("2026-06-20T14:03:00Z"));

        IngestResult result = service.ingest(AUTHED, event);

        assertThat(result).isEqualTo(new IngestResult(1, 0, 1, 0));
        verify(aggregates, never()).upsertAdditive(any());
    }

    @Test
    void signedReturn_flowsThroughNegative() {
        knownTenant();
        when(ledger.record(any(), any())).thenReturn(true);
        SaleEvent ret =
                new SaleEvent(
                        "t_body",
                        "o_5",
                        "cat_office",
                        Channel.OFFLINE,
                        new BigDecimal("-19.99"),
                        "USD",
                        EventType.RETURN,
                        Instant.parse("2026-06-20T14:03:00Z"),
                        null);

        service.ingest(AUTHED, ret);

        ArgumentCaptor<AggregateDelta> delta = ArgumentCaptor.forClass(AggregateDelta.class);
        verify(aggregates).upsertAdditive(delta.capture());
        assertThat(delta.getValue().amount()).isEqualByComparingTo("-19.99");
        assertThat(delta.getValue().channel()).isEqualTo(Channel.OFFLINE);
    }

    @Test
    void bodyTenantId_isIgnored_authedTenantWins() {
        knownTenant();
        when(ledger.record(any(), any())).thenReturn(true);
        SaleEvent event = sale("o_6", new BigDecimal("10.00"), Instant.parse("2026-06-20T14:03:00Z"));

        service.ingest(AUTHED, event); // event.tenantId() is "t_body"

        ArgumentCaptor<SaleEvent> recorded = ArgumentCaptor.forClass(SaleEvent.class);
        verify(ledger).record(recorded.capture(), any());
        assertThat(recorded.getValue().tenantId()).isEqualTo(AUTHED);

        ArgumentCaptor<AggregateDelta> delta = ArgumentCaptor.forClass(AggregateDelta.class);
        verify(aggregates).upsertAdditive(delta.capture());
        assertThat(delta.getValue().tenantId()).isEqualTo(AUTHED);
    }

    @Test
    void batch_countsAppliedDedupedQuarantinedSeparately() {
        knownTenant();
        Instant t = Instant.parse("2026-06-20T14:03:00Z");
        SaleEvent applied = sale("o_a", new BigDecimal("10.00"), t);
        SaleEvent duped = sale("o_b", new BigDecimal("20.00"), t);
        SaleEvent invalid = sale("o_c", null, t); // missing amount -> quarantined

        when(ledger.record(any(), any())).thenReturn(true).thenReturn(false);

        IngestResult result = service.ingestBatch(AUTHED, List.of(applied, duped, invalid));

        assertThat(result).isEqualTo(new IngestResult(3, 1, 1, 1));
    }
}
