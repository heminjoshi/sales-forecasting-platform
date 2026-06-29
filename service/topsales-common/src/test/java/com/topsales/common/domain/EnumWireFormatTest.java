package com.topsales.common.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire contract for enums: Window/Mode/Status are lowercase (and parse case-insensitively),
 * while EventType/Confidence stay uppercase. The dashboard and OpenAPI depend on this exactly.
 */
class EnumWireFormatTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void lowercaseEnumsSerializeLowercase() throws Exception {
        assertThat(mapper.writeValueAsString(Window.MONTH)).isEqualTo("\"month\"");
        assertThat(mapper.writeValueAsString(Mode.FORECAST)).isEqualTo("\"forecast\"");
        assertThat(mapper.writeValueAsString(Status.DEGRADED)).isEqualTo("\"degraded\"");
    }

    @Test
    void lowercaseEnumsParseCaseInsensitively() throws Exception {
        assertThat(mapper.readValue("\"week\"", Window.class)).isEqualTo(Window.WEEK);
        assertThat(mapper.readValue("\"ACTUALS\"", Mode.class)).isEqualTo(Mode.ACTUALS);
        assertThat(mapper.readValue("\"Pending\"", Status.class)).isEqualTo(Status.PENDING);
    }

    @Test
    void uppercaseEnumsStayUppercase() throws Exception {
        assertThat(mapper.writeValueAsString(EventType.SALE)).isEqualTo("\"SALE\"");
        assertThat(mapper.writeValueAsString(Confidence.HIGH)).isEqualTo("\"HIGH\"");
        assertThat(mapper.readValue("\"ADJUSTMENT\"", EventType.class)).isEqualTo(EventType.ADJUSTMENT);
    }
}
