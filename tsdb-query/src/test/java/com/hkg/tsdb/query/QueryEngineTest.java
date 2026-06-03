package com.hkg.tsdb.query;

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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class QueryEngineTest {

    @Test
    void rangeSelector_supportsEqAndRegexMatchers(@TempDir Path tmp) throws IOException {
        BlockReader block = writeBlock(tmp);
        QueryEngine engine = new QueryEngine(List.of(block));

        List<SeriesRange> ranges = engine.range(List.of(
            LabelMatcher.eq("service", "checkout"),
            LabelMatcher.regex("status", "5..")
        ), 2_000L, 6_000L);

        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0).samples()).hasSize(5);
    }

    @Test
    void rateSumBy_groupsCounterRate(@TempDir Path tmp) throws IOException {
        BlockReader block = writeBlock(tmp);
        QueryEngine engine = new QueryEngine(List.of(block));

        var rates = engine.rateSumBy(List.of(LabelMatcher.eq("service", "checkout")), "status", 0L, 9_000L);

        assertThat(rates).containsOnlyKeys("200", "500");
        assertThat(rates.get("200")).isCloseTo(1.0, offset(0.0001));
        assertThat(rates.get("500")).isCloseTo(2.0, offset(0.0001));
    }

    @Test
    void counterRate_handlesReset() {
        List<Sample> samples = List.of(
            new Sample(0L, 10.0),
            new Sample(1_000L, 15.0),
            new Sample(2_000L, 2.0),
            new Sample(3_000L, 5.0)
        );
        assertThat(QueryEngine.counterRate(samples)).isCloseTo((5.0 + 2.0 + 3.0) / 3.0, offset(0.0001));
    }

    private static BlockReader writeBlock(Path dir) throws IOException {
        Series ok = Series.of(LabelSet.of("service", "checkout", "status", "200"));
        Series err = Series.of(LabelSet.of("service", "checkout", "status", "500"));
        Series payment = Series.of(LabelSet.of("service", "payment", "status", "200"));
        new BlockWriter(6).write(dir, List.of(
            new BlockSeries(ok, samples(1.0, 1.0)),
            new BlockSeries(err, samples(2.0, 2.0)),
            new BlockSeries(payment, samples(10.0, 1.0))
        ));
        return new BlockReader(dir);
    }

    private static List<Sample> samples(double start, double step) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            out.add(new Sample(i * 1_000L, start + i * step));
        }
        return out;
    }
}
