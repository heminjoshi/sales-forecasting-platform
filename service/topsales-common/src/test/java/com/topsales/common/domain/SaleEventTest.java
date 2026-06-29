package com.topsales.common.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SaleEventTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void idempotencyKeyDefaultsToOrderIdAndType() {
        SaleEvent e = new SaleEvent("t_123", "o_998", "cat_office", Channel.ONLINE,
                new BigDecimal("42.50"),
                "USD", EventType.SALE, Instant.parse("2026-06-20T14:03:00Z"), null);
        assertThat(e.effectiveIdempotencyKey()).isEqualTo("o_998:SALE");
    }

    @Test
    void explicitIdempotencyKeyWins() {
        SaleEvent e = new SaleEvent("t_123", "o_998", "cat_office", Channel.ONLINE,
                new BigDecimal("42.50"),
                "USD", EventType.SALE, Instant.parse("2026-06-20T14:03:00Z"), "custom-key");
        assertThat(e.effectiveIdempotencyKey()).isEqualTo("custom-key");
    }

    @Test
    void deserializesFromTheContractShape() throws Exception {
        String json = """
                { "tenantId":"t_123","orderId":"o_998","categoryId":"cat_office","channel":"ONLINE",
                  "amount":42.50,"currency":"USD","eventType":"SALE",
                  "eventTime":"2026-06-20T14:03:00Z","idempotencyKey":"o_998:SALE" }
                """;
        SaleEvent e = mapper.readValue(json, SaleEvent.class);
        assertThat(e.tenantId()).isEqualTo("t_123");
        assertThat(e.channel()).isEqualTo(Channel.ONLINE);
        assertThat(e.amount()).isEqualByComparingTo("42.50");
        assertThat(e.eventType()).isEqualTo(EventType.SALE);
        assertThat(e.eventTime()).isEqualTo(Instant.parse("2026-06-20T14:03:00Z"));
    }
}
