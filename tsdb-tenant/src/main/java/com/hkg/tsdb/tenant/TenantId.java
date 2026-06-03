package com.hkg.tsdb.tenant;

/**
 * Tenant identifier used for per-tenant cardinality isolation.
 */
public record TenantId(String value) {

    public TenantId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tenant id must not be blank");
        }
    }
}
