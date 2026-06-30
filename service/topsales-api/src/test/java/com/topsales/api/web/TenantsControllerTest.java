package com.topsales.api.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.topsales.common.repository.TenantConfigRepository;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/** Standalone MockMvc test over the tenant-catalog endpoint (repo mocked). */
class TenantsControllerTest {

    private MockMvc mvc;
    private TenantConfigRepository tenants;

    @BeforeEach
    void setUp() {
        tenants = mock(TenantConfigRepository.class);
        mvc = standaloneSetup(new TenantsController(tenants)).build();
    }

    @Test
    void listsConfiguredTenantIds() throws Exception {
        when(tenants.allTenantIds()).thenReturn(List.of("tenant_b", "tenant_a"));

        mvc.perform(get("/api/v1/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").isArray())
                .andExpect(jsonPath("$.tenants[0]").value("tenant_b"))
                .andExpect(jsonPath("$.tenants[1]").value("tenant_a"));
    }
}
