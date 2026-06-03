package com.hkg.tsdb.query;

import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;

import java.util.List;

/**
 * Samples for one series returned by a range selector.
 */
public record SeriesRange(Series series, List<Sample> samples) {

    public SeriesRange {
        if (series == null) throw new IllegalArgumentException("series must not be null");
        if (samples == null) throw new IllegalArgumentException("samples must not be null");
        samples = List.copyOf(samples);
    }
}
