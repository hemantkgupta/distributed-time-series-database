package com.hkg.tsdb.tenant;

/**
 * Per-tenant active-series limit.
 */
public record CardinalityBudget(int maxActiveSeries) {

    public CardinalityBudget {
        if (maxActiveSeries < 1) {
            throw new IllegalArgumentException("maxActiveSeries must be positive");
        }
    }
}
