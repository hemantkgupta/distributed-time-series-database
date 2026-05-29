package com.hkg.tsdb.block;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockMetaTest {

    @Test
    void validRecord() {
        BlockMeta m = new BlockMeta("01HX...", 1L, 1000L, 5, 100L);
        assertThat(m.id()).isEqualTo("01HX...");
        assertThat(m.seriesCount()).isEqualTo(5);
    }

    @Test
    void emptyId_rejected() {
        assertThatThrownBy(() -> new BlockMeta("", 1L, 2L, 0, 0L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inverseTimeRange_rejected() {
        assertThatThrownBy(() -> new BlockMeta("x", 100L, 50L, 0, 0L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeCounts_rejected() {
        assertThatThrownBy(() -> new BlockMeta("x", 0L, 1L, -1, 0L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlockMeta("x", 0L, 1L, 0, -1L))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
