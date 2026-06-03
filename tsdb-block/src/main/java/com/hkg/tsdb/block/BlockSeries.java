package com.hkg.tsdb.block;

import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One series's samples as materialised into an immutable TSDB block.
 */
public record BlockSeries(Series series, List<Sample> samples) {

    public BlockSeries {
        if (series == null) throw new IllegalArgumentException("series must not be null");
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        List<Sample> sorted = new ArrayList<>(samples);
        sorted.sort(Comparator.comparingLong(Sample::timestampMs));
        long previous = Long.MIN_VALUE;
        for (Sample s : sorted) {
            if (s.timestampMs() <= previous) {
                throw new IllegalArgumentException("sample timestamps must be unique and increasing");
            }
            previous = s.timestampMs();
        }
        samples = List.copyOf(sorted);
    }

    public long minTs() {
        return samples.get(0).timestampMs();
    }

    public long maxTs() {
        return samples.get(samples.size() - 1).timestampMs();
    }
}
