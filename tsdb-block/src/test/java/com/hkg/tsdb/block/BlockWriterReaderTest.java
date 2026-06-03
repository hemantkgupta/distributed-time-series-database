package com.hkg.tsdb.block;

import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;
import com.hkg.tsdb.head.Head;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlockWriterReaderTest {

    @Test
    void writeReadRoundTrip_filtersByLabelsAndTime(@TempDir Path tmp) throws IOException {
        Series checkout = Series.of(LabelSet.of("service", "checkout", "status", "500"));
        Series payment = Series.of(LabelSet.of("service", "payment", "status", "500"));
        BlockWriter writer = new BlockWriter(4);
        BlockMeta meta = writer.write(tmp, List.of(
            new BlockSeries(checkout, samples(0, 10, 1000L, 1.0)),
            new BlockSeries(payment, samples(0, 10, 1000L, 10.0))
        ));

        BlockReader reader = new BlockReader(tmp);
        assertThat(reader.meta()).isEqualTo(meta);
        Map<Series, List<Sample>> selected = reader.select(Map.of("service", "checkout"), 2_000L, 5_000L);
        assertThat(selected).containsOnlyKeys(checkout);
        assertThat(selected.get(checkout)).hasSize(4);
        assertThat(selected.get(checkout).get(0).timestampMs()).isEqualTo(2_000L);
    }

    @Test
    void flushHead_writesPersistentBlock(@TempDir Path tmp) throws IOException {
        Path wal = tmp.resolve("wal");
        Path block = tmp.resolve("block");
        LabelSet labels = LabelSet.of("service", "checkout");
        try (Head head = new Head(wal, 8)) {
            long id = head.getOrCreate(labels).id();
            for (int i = 0; i < 20; i++) {
                head.append(id, 1_000L + i * 1_000L, i);
            }
            BlockMeta meta = new BlockWriter(8).flushHead(block, head);
            assertThat(meta.seriesCount()).isEqualTo(1);
        }

        BlockReader reader = new BlockReader(block);
        assertThat(reader.select(Map.of("service", "checkout"), 0L, 30_000L).values().iterator().next())
            .hasSize(20);
    }

    private static List<Sample> samples(int start, int count, long stepMs, double base) {
        List<Sample> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new Sample((start + i) * stepMs, base + i));
        }
        return out;
    }
}
