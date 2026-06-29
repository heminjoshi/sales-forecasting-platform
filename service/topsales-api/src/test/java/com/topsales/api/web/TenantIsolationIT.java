package com.topsales.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.topsales.api.it.AbstractPostgresRedisIT;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * End-to-end multi-tenant isolation on the read endpoint, asserting both the HTTP status <em>and</em>
 * the RFC 7807 {@code application/problem+json} body the {@link ApiExceptionHandler} emits
 * (docs/lld.md §11, §14). The path tenant is authorized against the {@code X-Tenant-Id} header
 * published by {@link TenantScopeFilter}: a mismatch (or a missing header) is a 403
 * {@code tenant-mismatch}; a self-consistent but non-existent tenant is a 404 {@code unknown-tenant}.
 *
 * <p>{@code t_demo}/{@code t_acme} are seeded by Flyway (V4/V7); {@code t_nope} is a deliberately
 * absent id for the unknown-tenant case.
 */
class TenantIsolationIT extends AbstractPostgresRedisIT {

    /** The subset of the problem+json body these tests assert on (extra fields ignored). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Problem(String type, String title, String instance) {}

    /**
     * GET the read endpoint, suppressing RestClient's default 4xx/5xx throw so the error body can be
     * read back. {@code tenantHeader == null} sends no {@code X-Tenant-Id} at all.
     */
    private ResponseEntity<String> get(String uri, String tenantHeader) {
        RestClient.RequestHeadersSpec<?> spec = client().get().uri(uri);
        if (tenantHeader != null) {
            spec = spec.header("X-Tenant-Id", tenantHeader);
        }
        return spec.retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, res) -> {})
                .toEntity(String.class);
    }

    /**
     * IT-RD-30: a caller authenticated as {@code t_acme} asking for {@code t_demo}'s data is rejected
     * before any read — 403 with a {@code tenant-mismatch} problem whose instance is the request URI.
     */
    @Test
    void crossTenant_pathNeHeader_returns403() throws Exception {
        String uri = "/api/v1/tenants/t_demo/top-categories?mode=actuals&window=month&k=10";
        ResponseEntity<String> resp = get(uri, "t_acme");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Problem problem = objectMapper.readValue(resp.getBody(), Problem.class);
        assertThat(problem.type()).endsWith("/tenant-mismatch");
        assertThat(problem.title()).isEqualTo("Tenant mismatch");
        assertThat(problem.instance()).isEqualTo("/api/v1/tenants/t_demo/top-categories");
    }

    /**
     * IT-RD-31: no {@code X-Tenant-Id} means no authenticated tenant, which can never equal the path
     * tenant — same 403 {@code tenant-mismatch} as an explicit mismatch (the filter never rejects;
     * the controller does).
     */
    @Test
    void missingTenantHeader_returns403() throws Exception {
        String uri = "/api/v1/tenants/t_demo/top-categories?mode=actuals&window=month&k=10";
        ResponseEntity<String> resp = get(uri, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Problem problem = objectMapper.readValue(resp.getBody(), Problem.class);
        assertThat(problem.type()).endsWith("/tenant-mismatch");
        assertThat(problem.title()).isEqualTo("Tenant mismatch");
    }

    /**
     * IT-RD-32: a self-consistent path==header for a tenant with no {@code tenant_config} row passes
     * the 403 check but the always-available actuals floor maps the unknown tenant to a 404
     * {@code unknown-tenant} problem.
     */
    @Test
    void unknownTenant_pathEqualsHeader_returns404() throws Exception {
        String uri = "/api/v1/tenants/t_nope/top-categories?mode=actuals&window=month&k=10";
        ResponseEntity<String> resp = get(uri, "t_nope");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Problem problem = objectMapper.readValue(resp.getBody(), Problem.class);
        assertThat(problem.type()).endsWith("/unknown-tenant");
        assertThat(problem.title()).isEqualTo("Unknown tenant");
    }
}
