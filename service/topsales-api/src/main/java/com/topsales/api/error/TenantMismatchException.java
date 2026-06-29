package com.topsales.api.error;

/**
 * The path {@code {tenantId}} does not equal the authenticated tenant (or none was authenticated).
 * No query ever trusts a path/body tenant id — it must match the auth context, else {@code 403}
 * (docs/lld.md §11, §14).
 */
public class TenantMismatchException extends RuntimeException {

    public TenantMismatchException(String pathTenant) {
        super("Path tenant '" + pathTenant + "' does not match the authenticated tenant.");
    }
}
