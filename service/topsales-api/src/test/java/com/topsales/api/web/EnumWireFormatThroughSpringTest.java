package com.topsales.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;

import java.math.BigDecimal;
import java.time.Instant;
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
    void responseUsesLowercaseEnums_andOmitsNullForecastFields() {
        TopKResponse response =
                new TopKResponse(
                        "t_demo",
                        Mode.ACTUALS,
                        Window.MONTH,
                        10,
                        Status.FRESH,
                        Instant.parse("2026-06-28T00:00:00Z"),
                        null,
                        List.of(new TopKItem(1, "cat_office", new BigDecimal("100.00"), null, null, null)));

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"mode\":\"actuals\"");
        assertThat(json).contains("\"window\":\"month\"");
        assertThat(json).contains("\"status\":\"fresh\"");
        // forecast-only fields are null → omitted under non_null inclusion
        assertThat(json).doesNotContain("interval");
        assertThat(json).doesNotContain("deltaVsPrior");
        assertThat(json).doesNotContain("confidence");
        assertThat(json).doesNotContain("insight");
    }
}
