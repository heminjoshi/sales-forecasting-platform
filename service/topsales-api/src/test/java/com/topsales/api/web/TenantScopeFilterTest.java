package com.topsales.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Unit test: the filter publishes the X-Tenant-Id header as the authed-tenant request attribute. */
class TenantScopeFilterTest {

    private final TenantScopeFilter filter = new TenantScopeFilter();

    @Test
    void headerPresent_setsAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantScopeFilter.TENANT_HEADER, "t_demo");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(TenantScopeFilter.AUTHED_TENANT_ATTR)).isEqualTo("t_demo");
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
}
