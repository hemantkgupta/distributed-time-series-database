package com.hkg.tsdb.common;

/**
 * One (timestamp_ms, float64 value) data point.
 */
public record Sample(long timestampMs, double value) {

    public Sample {
        if (timestampMs < 0) {
            throw new IllegalArgumentException("timestampMs must be >= 0, got " + timestampMs);
        }
    }
}
