package com.hkg.tsdb.compact;

import com.hkg.tsdb.block.BlockReader;
import com.hkg.tsdb.block.BlockSeries;
import com.hkg.tsdb.block.BlockWriter;
import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Vertical and horizontal compaction for immutable TSDB blocks.
 *
 * Vertical compaction deduplicates HA replicas that wrote overlapping samples
 * for the same series. Horizontal compaction merges adjacent time windows so the
 * query layer touches fewer block indexes.
 */
public final class TsdbCompactor {

    private final BlockWriter writer;

    public TsdbCompactor() {
        this(new BlockWriter());
    }

    public TsdbCompactor(BlockWriter writer) {
        this.writer = writer;
    }

    public void verticalCompact(List<BlockReader> inputs, Path outputDir) throws IOException {
        writer.write(outputDir, merge(inputs, MergeMode.DEDUPLICATE_SAME_TIMESTAMP));
    }

    public void horizontalCompact(List<BlockReader> inputs, Path outputDir) throws IOException {
        writer.write(outputDir, merge(inputs, MergeMode.APPEND_DISTINCT_TIMESTAMPS));
    }

    private static List<BlockSeries> merge(List<BlockReader> inputs, MergeMode mode) throws IOException {
        Map<Series, Map<Long, Sample>> bySeries = new LinkedHashMap<>();
        for (BlockReader input : inputs) {
            for (Map.Entry<Series, List<Sample>> e : input.all(0L, Long.MAX_VALUE).entrySet()) {
                Map<Long, Sample> samples = bySeries.computeIfAbsent(e.getKey(), ignored -> new TreeMap<>());
                for (Sample sample : e.getValue()) {
                    if (mode == MergeMode.DEDUPLICATE_SAME_TIMESTAMP) {
                        samples.putIfAbsent(sample.timestampMs(), sample);
                    } else {
                        samples.put(sample.timestampMs(), sample);
                    }
                }
            }
        }
        List<BlockSeries> out = new ArrayList<>();
        for (Map.Entry<Series, Map<Long, Sample>> e : bySeries.entrySet()) {
            out.add(new BlockSeries(e.getKey(), new ArrayList<>(e.getValue().values())));
        }
        out.sort(Comparator.comparingLong(s -> s.series().id()));
        return out;
    }

    private enum MergeMode {
        DEDUPLICATE_SAME_TIMESTAMP,
        APPEND_DISTINCT_TIMESTAMPS
    }
}
