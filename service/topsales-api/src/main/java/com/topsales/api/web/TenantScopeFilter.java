package com.topsales.api.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

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
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantScopeFilter implements Filter {

    /** Request attribute holding the authenticated tenant id (null when no header was supplied). */
    public static final String AUTHED_TENANT_ATTR = "authedTenantId";

    /** Dev-time stand-in for upstream auth. */
    public static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            String tenant = http.getHeader(TENANT_HEADER);
            if (tenant != null && !tenant.isBlank()) {
                request.setAttribute(AUTHED_TENANT_ATTR, tenant.trim());
            }
        }
        chain.doFilter(request, response);
    }
}
