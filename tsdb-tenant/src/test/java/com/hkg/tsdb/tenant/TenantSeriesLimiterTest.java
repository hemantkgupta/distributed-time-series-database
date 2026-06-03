package com.hkg.tsdb.tenant;

import com.hkg.tsdb.common.LabelSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantSeriesLimiterTest {

    @Test
    void sameSeriesDoesNotConsumeBudgetTwice() {
        TenantSeriesLimiter limiter = new TenantSeriesLimiter(new CardinalityBudget(1));
        TenantId tenant = new TenantId("team-a");
        LabelSet labels = LabelSet.of("service", "checkout");

        limiter.accept(tenant, labels);
        limiter.accept(tenant, labels);

        assertThat(limiter.activeSeries(tenant)).isEqualTo(1);
    }

    @Test
    void newSeriesAboveBudgetRejected() {
        TenantSeriesLimiter limiter = new TenantSeriesLimiter(new CardinalityBudget(1));
        TenantId tenant = new TenantId("team-a");

        limiter.accept(tenant, LabelSet.of("service", "checkout"));

        assertThatThrownBy(() -> limiter.accept(tenant, LabelSet.of("service", "payment")))
            .isInstanceOf(CardinalityLimitExceededException.class);
    }
}
