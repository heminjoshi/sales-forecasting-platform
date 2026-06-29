package com.topsales.api.web;

import com.topsales.common.api.TenantsResponse;
import com.topsales.common.repository.TenantConfigRepository;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/tenants} — the tenant catalog backing the dashboard's tenant picker.
 *
 * <p>A demo/dev affordance: it lists every configured tenant so the dashboard can offer a dropdown
 * instead of a free-text field. In production this would not be a public, unscoped listing — tenant
 * discovery belongs to the admin/auth plane (multi-tenant isolation); see {@link TenantScopeFilter}.
 */
@RestController
public class TenantsController {

    private final TenantConfigRepository tenants;

    public TenantsController(TenantConfigRepository tenants) {
        this.tenants = tenants;
    }

    @GetMapping("/api/v1/tenants")
    public TenantsResponse listTenants() {
        return new TenantsResponse(tenants.allTenantIds());
    }
}
