package com.topsales.ingestion.service;

import com.topsales.common.domain.AggregateDelta;
import com.topsales.common.domain.SaleEvent;
import com.topsales.common.domain.TenantConfig;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.common.repository.TenantConfigRepository;
import com.topsales.ingestion.repo.BucketingEventLedger;
import com.topsales.ingestion.repo.QuarantineRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The local ingestion pipeline (docs/lld.md §6): validate → resolve tenant config → tenant-local
 * bucket (A6) → dedupe gate → additive upsert, all per event inside one transaction. The
 * authoritative tenant comes from auth (the {@code X-Tenant-Id} header), never the body (§11): a
 * body {@code tenantId} is overwritten before anything is persisted.
 *
 * <p>Per-event atomicity is enforced with a {@link TransactionTemplate} rather than
 * {@code @Transactional}, so the public batch method can drive a fresh transaction per event without
 * the Spring self-invocation proxy pitfall.
 */
@Service
@Profile("local")
public class IngestionService {

    /** {@code numeric(18,2)} — amounts may carry at most 2 fractional digits. */
    private static final int MAX_AMOUNT_SCALE = 2;

    private final BucketingEventLedger ledger;
    private final AggregateRepository aggregates;
    private final TenantConfigRepository tenantConfig;
    private final QuarantineRepository quarantine;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public IngestionService(
            BucketingEventLedger ledger,
            AggregateRepository aggregates,
            TenantConfigRepository tenantConfig,
            QuarantineRepository quarantine,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.aggregates = aggregates;
        this.tenantConfig = tenantConfig;
        this.quarantine = quarantine;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Ingest a batch, accumulating counters across each per-event transaction. */
    public IngestResult ingestBatch(String authedTenantId, List<SaleEvent> events) {
        IngestResult total = IngestResult.EMPTY;
        for (SaleEvent event : events) {
            total = total.plus(ingest(authedTenantId, event));
        }
        return total;
    }

    /** Ingest one event in its own transaction. */
    public IngestResult ingest(String authedTenantId, SaleEvent event) {
        return tx.execute(status -> ingestOne(authedTenantId, event));
    }

    /**
     * Quarantine a raw payload the controller could not parse into a {@link SaleEvent} (bad enum,
     * non-numeric amount, …). Counts as one received + one quarantined.
     */
    public IngestResult quarantineRaw(String authedTenantId, String rawJson, String reason) {
        return tx.execute(
                status -> {
                    quarantine.quarantine(authedTenantId, rawJson, reason);
                    return new IngestResult(1, 0, 0, 1);
                });
    }

    private IngestResult ingestOne(String authedTenantId, SaleEvent raw) {
        // §11: the authoritative tenant overrides whatever the body claimed, before persisting.
        SaleEvent event = withTenant(raw, authedTenantId);

        String invalid = validationFailure(event);
        if (invalid != null) {
            quarantine(authedTenantId, event, invalid);
            return new IngestResult(1, 0, 0, 1);
        }

        Optional<TenantConfig> cfg = tenantConfig.find(authedTenantId);
        if (cfg.isEmpty()) {
            quarantine(authedTenantId, event, "unknown tenant");
            return new IngestResult(1, 0, 0, 1);
        }

        // A6: bucket on the tenant's local calendar day, not UTC.
        LocalDate bucketDate = event.eventTime().atZone(cfg.get().timezone()).toLocalDate();

        boolean recorded = ledger.record(event, bucketDate);
        if (!recorded) {
            return new IngestResult(1, 0, 1, 0); // duplicate — skip the upsert
        }

        // Signed amount flows straight through: RETURN/ADJUSTMENT net correctly (§6).
        aggregates.upsertAdditive(
                new AggregateDelta(
                        authedTenantId,
                        event.categoryId(),
                        bucketDate,
                        event.amount(),
                        event.currency()));
        return new IngestResult(1, 1, 0, 0);
    }

    private static SaleEvent withTenant(SaleEvent e, String tenantId) {
        return new SaleEvent(
                tenantId,
                e.orderId(),
                e.categoryId(),
                e.amount(),
                e.currency(),
                e.eventType(),
                e.eventTime(),
                e.idempotencyKey());
    }

    /** @return a failure reason, or {@code null} when the event is structurally valid. */
    private static String validationFailure(SaleEvent e) {
        if (isBlank(e.orderId())) {
            return "missing orderId";
        }
        if (isBlank(e.categoryId())) {
            return "missing categoryId";
        }
        if (e.eventType() == null) {
            return "missing eventType";
        }
        if (e.eventTime() == null) {
            return "missing eventTime";
        }
        if (isBlank(e.currency())) {
            return "missing currency";
        }
        BigDecimal amount = e.amount();
        if (amount == null) {
            return "missing amount";
        }
        if (amount.scale() > MAX_AMOUNT_SCALE) {
            return "amount scale exceeds " + MAX_AMOUNT_SCALE;
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private void quarantine(String tenantId, SaleEvent event, String reason) {
        quarantine.quarantine(tenantId, toJson(event), reason);
    }

    private String toJson(SaleEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            return String.valueOf(event);
        }
    }
}
