package com.hkg.tsdb.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkRefTest {

    @Test
    void overlap_atEndpointsIsInclusive() {
        ChunkRef c = new ChunkRef(100, 200, 0, 0);
        assertThat(c.overlaps(200, 300)).isTrue();
        assertThat(c.overlaps(50, 100)).isTrue();
        assertThat(c.overlaps(0, 99)).isFalse();
        assertThat(c.overlaps(201, 300)).isFalse();
    }

    @Test
    void invalidTimeRange_rejected() {
        assertThatThrownBy(() -> new ChunkRef(200, 100, 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
