package com.topsales.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Proves the lowercase enum wire contract and null-field omission hold under <b>Jackson 3</b> (the
 * mapper Spring Boot 4.1 actually uses), not just Jackson 2. The common module's enum
 * {@code @JsonValue}/{@code @JsonCreator} annotations are the foundation; this guards against a
 * silent serializer regression when the app boots.
 */
class EnumWireFormatThroughSpringTest {

    // Mirror application.yml: spring.jackson.default-property-inclusion=non_null.
    private final JsonMapper mapper =
            JsonMapper.builder()
                    .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                    .build();

    @Test
    void lowercaseEnumsSerializeLowercase() {
        assertThat(mapper.writeValueAsString(Window.MONTH)).isEqualTo("\"month\"");
        assertThat(mapper.writeValueAsString(Mode.ACTUALS)).isEqualTo("\"actuals\"");
        assertThat(mapper.writeValueAsString(Status.PENDING)).isEqualTo("\"pending\"");
    }

    @Test
    void responseUsesLowercaseEnums_carriesInsight_andOmitsNullForecastFields() {
        TopKResponse response =
                new TopKResponse(
                        "t_demo",
                        Mode.ACTUALS,
                        Window.MONTH,
                        ChannelFilter.ALL,
                        10,
                        Status.FRESH,
                        Instant.parse("2026-06-28T00:00:00Z"),
                        LocalDate.parse("2026-05-30"),
                        LocalDate.parse("2026-06-28"),
                        // Phase 5: the grounded insight line is now populated (InsightAttacher); it is
                        // present on the wire rather than omitted.
                        "Top category leads the month at 100.",
                        List.of(new TopKItem(1, "cat_office", new BigDecimal("100.00"), null, null, null)));

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"mode\":\"actuals\"");
        assertThat(json).contains("\"window\":\"month\"");
        assertThat(json).contains("\"channel\":\"all\"");
        assertThat(json).contains("\"windowFrom\":\"2026-05-30\"", "\"windowTo\":\"2026-06-28\"");
        assertThat(json).contains("\"status\":\"fresh\"");
        // forecast-only fields are null → omitted under non_null inclusion
        assertThat(json).doesNotContain("interval");
        assertThat(json).doesNotContain("deltaVsPrior");
        assertThat(json).doesNotContain("confidence");
        // the grounded insight is non-null now → present on the wire
        assertThat(json).contains("\"insight\":\"Top category leads the month at 100.\"");
    }
}
