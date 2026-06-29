package com.topsales.api.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Derives the authenticated tenant and exposes it for the rest of the request (docs/lld.md §11).
 * Runs first ({@link Ordered#HIGHEST_PRECEDENCE}) so every downstream handler sees the same tenant.
 *
 * <p>Locally the dev header {@code X-Tenant-Id} stands in for upstream auth. This filter does
 * <em>not</em> reject anything — it only publishes the tenant as the {@value #AUTHED_TENANT_ATTR}
 * request attribute; the controller asserts the path tenant matches it (403) and the service maps
 * an unknown tenant to 404.
 *
 * <p>It also seeds the SLF4J {@link MDC} for structured logging (Phase 6): a {@value #REQUEST_ID_MDC}
 * (echoed back on the {@value #REQUEST_ID_HEADER} response header for client/trace correlation) and,
 * when present, the {@value #TENANT_ID_MDC}. Both keys are cleared in a {@code finally} block so a
 * tenant id can never leak across pooled servlet threads — the security-adjacent correctness point
 * of this phase.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantScopeFilter implements Filter {

    /** Request attribute holding the authenticated tenant id (null when no header was supplied). */
    public static final String AUTHED_TENANT_ATTR = "authedTenantId";

    /** Dev-time stand-in for upstream auth. */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    /** Inbound/outbound correlation id header; generated when the client does not supply one. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC key carrying the tenant id into every log line for this request. */
    public static final String TENANT_ID_MDC = "tenantId";

    /** MDC key carrying the request/correlation id into every log line for this request. */
    public static final String REQUEST_ID_MDC = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            String tenant = http.getHeader(TENANT_HEADER);
            if (tenant != null && !tenant.isBlank()) {
                tenant = tenant.trim();
                request.setAttribute(AUTHED_TENANT_ATTR, tenant);
                MDC.put(TENANT_ID_MDC, tenant);
            }

            String inbound = http.getHeader(REQUEST_ID_HEADER);
            String requestId =
                    (inbound != null && !inbound.isBlank()) ? inbound.trim() : UUID.randomUUID().toString();
            MDC.put(REQUEST_ID_MDC, requestId);
            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(REQUEST_ID_HEADER, requestId);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // Clear before the thread returns to the pool — prevents tenant-id leakage across requests.
            MDC.remove(TENANT_ID_MDC);
            MDC.remove(REQUEST_ID_MDC);
        }
    }
}
