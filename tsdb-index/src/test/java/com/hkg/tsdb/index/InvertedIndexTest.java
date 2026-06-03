package com.hkg.tsdb.index;

import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.common.Series;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InvertedIndexTest {

    @Test
    void exactMatch_intersectsLabelPostings() {
        InvertedIndex index = new InvertedIndex();
        Series checkoutOk = Series.of(LabelSet.of("service", "checkout", "status", "200"));
        Series checkoutErr = Series.of(LabelSet.of("service", "checkout", "status", "500"));
        Series paymentErr = Series.of(LabelSet.of("service", "payment", "status", "500"));

        index.add(checkoutOk);
        index.add(checkoutErr);
        index.add(paymentErr);

        assertThat(index.exactMatch(Map.of("service", "checkout", "status", "500")).toArray())
            .containsExactly(checkoutErr.id());
        assertThat(index.postings("status", "500").toArray())
            .containsExactlyInAnyOrder(checkoutErr.id(), paymentErr.id());
    }
}
