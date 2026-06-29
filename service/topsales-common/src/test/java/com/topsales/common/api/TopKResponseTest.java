package com.topsales.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The §13 response shape the dashboard renders. With non-null inclusion (the app's Jackson
 * config), forecast-only fields are omitted in actuals — so the UI can hide those columns.
 */
class TopKResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void actualsItemOmitsForecastOnlyFields() throws Exception {
        TopKItem item = new TopKItem(1, "Office Supplies", new BigDecimal("5400.00"),
                null, null, null);
        TopKResponse resp = new TopKResponse("t_123", Mode.ACTUALS, Window.MONTH, 10,
                Status.FRESH, Instant.parse("2026-06-28T06:00:00Z"), "Office Supplies leads.",
                List.of(item));

        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"mode\":\"actuals\"", "\"status\":\"fresh\"", "\"window\":\"month\"");
        assertThat(json).doesNotContain("interval", "confidence", "deltaVsPrior");
    }

    @Test
    void forecastItemKeepsIntervalAndConfidence() throws Exception {
        TopKItem item = new TopKItem(1, "Office Supplies", new BigDecimal("5400.00"),
                new BigDecimal("0.12"), com.topsales.common.domain.Confidence.HIGH,
                new Interval(new BigDecimal("4900"), new BigDecimal("5900")));
        String json = mapper.writeValueAsString(item);
        assertThat(json).contains("\"deltaVsPrior\":0.12", "\"confidence\":\"HIGH\"", "\"low\":4900");
    }
}
