package com.hkg.tsdb.tenant;

import com.hkg.tsdb.common.LabelSet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Non-bypassable cardinality gate for ingestion.
 *
 * The limiter tracks active series fingerprints per tenant and rejects a sample
 * before it reaches the head block if accepting it would create a new series
 * above the tenant's budget. This models the operational fix for tag explosion:
 * cardinality is controlled at ingest, not after blocks are already polluted.
 */
public final class TenantSeriesLimiter {

    private final CardinalityBudget defaultBudget;
    private final Map<TenantId, CardinalityBudget> budgets = new ConcurrentHashMap<>();
    private final Map<TenantId, Set<Long>> activeSeries = new ConcurrentHashMap<>();

    public TenantSeriesLimiter(CardinalityBudget defaultBudget) {
        this.defaultBudget = defaultBudget;
    }

    public void setBudget(TenantId tenant, CardinalityBudget budget) {
        budgets.put(tenant, budget);
    }

    public void accept(TenantId tenant, LabelSet labels) {
        Set<Long> series = activeSeries.computeIfAbsent(tenant, ignored -> ConcurrentHashMap.newKeySet());
        long fingerprint = labels.fingerprint();
        if (series.contains(fingerprint)) {
            return;
        }
        CardinalityBudget budget = budgets.getOrDefault(tenant, defaultBudget);
        if (series.size() >= budget.maxActiveSeries()) {
            throw new CardinalityLimitExceededException(
                "tenant " + tenant.value() + " exceeds active-series budget " + budget.maxActiveSeries());
        }
        series.add(fingerprint);
    }

    public int activeSeries(TenantId tenant) {
        return activeSeries.getOrDefault(tenant, Set.of()).size();
    }
}
