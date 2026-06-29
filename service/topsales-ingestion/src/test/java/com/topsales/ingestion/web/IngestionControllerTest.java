package com.topsales.ingestion.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.topsales.ingestion.service.IngestResult;
import com.topsales.ingestion.service.IngestionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Context-free MockMvc test (standalone setup over the controller + a mocked service). Avoids the
 * {@code @WebMvcTest} slice, whose autoconfigure artifact is not on this module's classpath.
 */
class IngestionControllerTest {

    private static final String EVENT_JSON =
            """
            {"tenantId":"t_body","orderId":"o_1","categoryId":"cat_office","amount":42.50,
             "currency":"USD","eventType":"SALE","eventTime":"2026-06-20T14:03:00Z"}
            """;

    private MockMvc mvc;
    private IngestionService ingestionService;

    @BeforeEach
    void setUp() {
        ingestionService = mock(IngestionService.class);
        ObjectMapper mapper = JsonMapper.builder().build();
        mvc = standaloneSetup(new IngestionController(ingestionService, mapper)).build();
    }

    @Test
    void singleObjectBody_returns202WithCounts() throws Exception {
        when(ingestionService.ingest(eq("t_demo"), any())).thenReturn(new IngestResult(1, 1, 0, 0));

        mvc.perform(
                        post("/api/v1/events")
                                .header("X-Tenant-Id", "t_demo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EVENT_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.received").value(1))
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.deduped").value(0))
                .andExpect(jsonPath("$.quarantined").value(0));

        verify(ingestionService).ingest(eq("t_demo"), any());
    }

    @Test
    void arrayBody_returns202() throws Exception {
        when(ingestionService.ingestBatch(eq("t_demo"), anyList()))
                .thenReturn(new IngestResult(2, 2, 0, 0));

        mvc.perform(
                        post("/api/v1/events")
                                .header("X-Tenant-Id", "t_demo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[" + EVENT_JSON + "," + EVENT_JSON + "]"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.received").value(2))
                .andExpect(jsonPath("$.applied").value(2));

        verify(ingestionService).ingestBatch(eq("t_demo"), anyList());
    }

    @Test
    void missingTenantHeader_returns400() throws Exception {
        mvc.perform(
                        post("/api/v1/events")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EVENT_JSON))
                .andExpect(status().isBadRequest());
    }
}
