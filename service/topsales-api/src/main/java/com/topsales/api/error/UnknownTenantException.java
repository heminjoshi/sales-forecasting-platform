package com.topsales.api.error;

/**
 * The requested tenant has no {@code tenant_config} row. The read path maps this to {@code 404}
 * (docs/lld.md §11, §14). Distinct from a tenant mismatch ({@code 403}): here the tenant simply
 * does not exist, rather than the caller asking about someone else's data.
 */
public class UnknownTenantException extends RuntimeException {

    public UnknownTenantException(String tenantId) {
        super("Unknown tenant: " + tenantId);
    }
}
