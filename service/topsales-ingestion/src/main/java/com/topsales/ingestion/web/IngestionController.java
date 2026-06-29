package com.topsales.ingestion.web;

import com.topsales.common.domain.SaleEvent;
import com.topsales.ingestion.service.IngestResult;
import com.topsales.ingestion.service.IngestionService;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Local stand-in for the Kinesis ingest path: {@code POST /api/v1/events} (docs/lld.md §3.1).
 * Accepts a single {@link SaleEvent} or a JSON array of them and returns {@code 202 Accepted} with
 * the {@link IngestResult} counters. The authoritative tenant is the {@code X-Tenant-Id} header
 * (§11), not any body field. Events that cannot be parsed into a {@code SaleEvent} are quarantined.
 */
@RestController
@Profile("local")
public class IngestionController {

    private final IngestionService ingestionService;
    private final ObjectMapper objectMapper;

    public IngestionController(IngestionService ingestionService, ObjectMapper objectMapper) {
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/api/v1/events")
    public ResponseEntity<IngestResult> ingest(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
            @RequestBody JsonNode body) {

        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing X-Tenant-Id header");
        }

        IngestResult result = body.isArray() ? ingestArray(tenantId, body) : ingestOne(tenantId, body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    private IngestResult ingestArray(String tenantId, JsonNode array) {
        List<SaleEvent> events = new ArrayList<>();
        IngestResult unparseable = IngestResult.EMPTY;
        for (JsonNode node : array) {
            SaleEvent event = tryParse(node);
            if (event != null) {
                events.add(event);
            } else {
                unparseable = unparseable.plus(quarantineRaw(tenantId, node));
            }
        }
        return ingestionService.ingestBatch(tenantId, events).plus(unparseable);
    }

    private IngestResult ingestOne(String tenantId, JsonNode node) {
        SaleEvent event = tryParse(node);
        return event != null
                ? ingestionService.ingest(tenantId, event)
                : quarantineRaw(tenantId, node);
    }

    private SaleEvent tryParse(JsonNode node) {
        try {
            return objectMapper.treeToValue(node, SaleEvent.class);
        } catch (JacksonException | IllegalArgumentException e) {
            return null;
        }
    }

    private IngestResult quarantineRaw(String tenantId, JsonNode node) {
        return ingestionService.quarantineRaw(tenantId, node.toString(), "unparseable event payload");
    }
}
