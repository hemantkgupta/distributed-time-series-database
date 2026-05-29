package com.hkg.tsdb.common;

/**
 * A time series: a 64-bit ID tied to a canonical label set.
 *
 * The ID is the label set's fingerprint. Same LabelSet -> same Series.id
 * across process restarts.
 */
public record Series(long id, LabelSet labels) {

    public static Series of(LabelSet labels) {
        return new Series(labels.fingerprint(), labels);
    }
}
