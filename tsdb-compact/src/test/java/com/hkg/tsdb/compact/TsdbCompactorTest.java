package com.hkg.tsdb.compact;

import com.hkg.tsdb.block.BlockReader;
import com.hkg.tsdb.block.BlockSeries;
import com.hkg.tsdb.block.BlockWriter;
import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TsdbCompactorTest {

    @Test
    void verticalCompaction_deduplicatesReplicaSamples(@TempDir Path tmp) throws IOException {
        Series series = Series.of(LabelSet.of("service", "checkout"));
        BlockReader a = write(tmp.resolve("a"), series, List.of(new Sample(0L, 1.0), new Sample(1_000L, 2.0)));
        BlockReader b = write(tmp.resolve("b"), series, List.of(new Sample(0L, 1.0), new Sample(1_000L, 2.0)));

        new TsdbCompactor(new BlockWriter(4)).verticalCompact(List.of(a, b), tmp.resolve("out"));

        BlockReader out = new BlockReader(tmp.resolve("out"));
        assertThat(out.select(Map.of("service", "checkout"), 0L, 10_000L).values().iterator().next())
            .hasSize(2);
    }

    @Test
    void horizontalCompaction_mergesAdjacentTimeWindows(@TempDir Path tmp) throws IOException {
        Series series = Series.of(LabelSet.of("service", "checkout"));
        BlockReader a = write(tmp.resolve("a"), series, List.of(new Sample(0L, 1.0), new Sample(1_000L, 2.0)));
        BlockReader b = write(tmp.resolve("b"), series, List.of(new Sample(2_000L, 3.0), new Sample(3_000L, 4.0)));

        new TsdbCompactor(new BlockWriter(4)).horizontalCompact(List.of(a, b), tmp.resolve("out"));

        BlockReader out = new BlockReader(tmp.resolve("out"));
        assertThat(out.meta().sampleCount()).isEqualTo(4);
        assertThat(out.select(Map.of("service", "checkout"), 0L, 10_000L).values().iterator().next())
            .extracting(Sample::timestampMs)
            .containsExactly(0L, 1_000L, 2_000L, 3_000L);
    }

    private static BlockReader write(Path dir, Series series, List<Sample> samples) throws IOException {
        new BlockWriter(4).write(dir, List.of(new BlockSeries(series, samples)));
        return new BlockReader(dir);
    }
}
