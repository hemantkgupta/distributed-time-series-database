package com.hkg.tsdb.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SampleTest {

    @Test
    void negativeTimestamp_rejected() {
        assertThatThrownBy(() -> new Sample(-1L, 0.0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordSemantics() {
        Sample s = new Sample(1_700_000_000_000L, 42.5);
        assertThat(s.timestampMs()).isEqualTo(1_700_000_000_000L);
        assertThat(s.value()).isEqualTo(42.5);
    }
}
