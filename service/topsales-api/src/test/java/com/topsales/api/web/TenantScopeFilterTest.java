package com.topsales.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit test: the filter publishes the X-Tenant-Id header as the authed-tenant request attribute. */
class TenantScopeFilterTest {

    private final TenantScopeFilter filter = new TenantScopeFilter();

    @Test
    void headerPresent_setsAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantScopeFilter.TENANT_HEADER, "tenant_a");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(TenantScopeFilter.AUTHED_TENANT_ATTR)).isEqualTo("tenant_a");
    }

    @Test
    void headerAbsent_leavesAttributeNull() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(TenantScopeFilter.AUTHED_TENANT_ATTR)).isNull();
    }

    @Test
    void blankHeader_leavesAttributeNull() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantScopeFilter.TENANT_HEADER, "   ");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(TenantScopeFilter.AUTHED_TENANT_ATTR)).isNull();
    }

    @Test
    void headerPresent_echoesRequestIdAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantScopeFilter.TENANT_HEADER, "tenant_a");
        request.addHeader(TenantScopeFilter.REQUEST_ID_HEADER, "req-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        // The inbound correlation id is echoed back for trace stitching.
        assertThat(response.getHeader(TenantScopeFilter.REQUEST_ID_HEADER)).isEqualTo("req-123");
        // MDC must be empty once the thread returns to the pool (no tenant-id leakage).
        assertThat(MDC.get(TenantScopeFilter.TENANT_ID_MDC)).isNull();
        assertThat(MDC.get(TenantScopeFilter.REQUEST_ID_MDC)).isNull();
    }

    @Test
    void missingRequestId_isGeneratedAndEchoed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(TenantScopeFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(MDC.get(TenantScopeFilter.REQUEST_ID_MDC)).isNull();
    }

    @Test
    void chainThrows_stillClearsMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantScopeFilter.TENANT_HEADER, "tenant_a");
        FilterChain boom = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), boom))
                .isInstanceOf(ServletException.class);

        // The finally block must still have cleared the MDC even though the chain threw.
        assertThat(MDC.get(TenantScopeFilter.TENANT_ID_MDC)).isNull();
        assertThat(MDC.get(TenantScopeFilter.REQUEST_ID_MDC)).isNull();
    }
}
