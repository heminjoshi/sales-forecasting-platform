package com.topsales.common.api;

import java.util.List;

/**
 * The tenant catalog for the demo dashboard's tenant picker: just the configured tenant ids. This
 * is a demo/dev affordance — in production, tenant discovery is an admin/auth-plane concern, not a
 * public listing (multi-tenant isolation), so callers never enumerate other tenants this way.
 */
public record TenantsResponse(List<String> tenants) {
}
