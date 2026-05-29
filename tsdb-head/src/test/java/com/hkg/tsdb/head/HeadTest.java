package com.hkg.tsdb.head;

import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeadTest {

    @Test
    void appendAndReadBack(@TempDir Path tmp) throws IOException {
        try (Head h = new Head(tmp)) {
            LabelSet ls = LabelSet.of("service", "checkout", "instance", "pod-1");
            Series s = h.getOrCreate(ls);
            long t0 = 1_700_000_000_000L;
            for (int i = 0; i < 60; i++) {
                h.append(s.id(), t0 + i * 1000L, 1.0 + i * 0.1);
            }
            h.sync();
            List<Sample> samples = h.readAll(s.id());
            assertThat(samples).hasSize(60);
            assertThat(samples.get(0).timestampMs()).isEqualTo(t0);
            assertThat(samples.get(59).value()).isCloseTo(1.0 + 59 * 0.1, org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    @Test
    void getOrCreate_idempotent(@TempDir Path tmp) throws IOException {
        try (Head h = new Head(tmp)) {
            LabelSet ls = LabelSet.of("service", "checkout");
            Series a = h.getOrCreate(ls);
            Series b = h.getOrCreate(ls);
            assertThat(a.id()).isEqualTo(b.id());
            assertThat(h.seriesCount()).isEqualTo(1);
        }
    }

    @Test
    void unknownSeriesAppend_rejected(@TempDir Path tmp) throws IOException {
        try (Head h = new Head(tmp)) {
            assertThatThrownBy(() -> h.append(12345L, 1L, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void crashRecovery_rebuildsHeadFromWal(@TempDir Path tmp) throws IOException {
        LabelSet a = LabelSet.of("service", "checkout");
        LabelSet b = LabelSet.of("service", "payment");

        long aId, bId;
        try (Head h = new Head(tmp)) {
            aId = h.getOrCreate(a).id();
            bId = h.getOrCreate(b).id();
            for (int i = 0; i < 100; i++) {
                h.append(aId, 1_000_000L + i * 1000L, i * 0.5);
                h.append(bId, 1_000_000L + i * 1000L, 100.0 + i);
            }
            h.sync();
        }

        try (Head recovered = Head.recover(tmp)) {
            assertThat(recovered.seriesCount()).isEqualTo(2);
            List<Sample> aSamples = recovered.readAll(aId);
            List<Sample> bSamples = recovered.readAll(bId);
            assertThat(aSamples).hasSize(100);
            assertThat(bSamples).hasSize(100);
            assertThat(aSamples.get(0).value()).isEqualTo(0.0);
            assertThat(aSamples.get(99).value()).isEqualTo(49.5);
            assertThat(bSamples.get(50).value()).isEqualTo(150.0);
        }
    }

    @Test
    void chunkRotation_acrossManySamples(@TempDir Path tmp) throws IOException {
        try (Head h = new Head(tmp, 32)) {  // small chunks to force rotations
            LabelSet ls = LabelSet.of("service", "checkout");
            long id = h.getOrCreate(ls).id();
            for (int i = 0; i < 100; i++) {
                h.append(id, 1L + i * 1000L, i * 1.0);
            }
            h.sync();
            HeadSeries hs = h.snapshotSeries().get(id);
            // 100 samples / 32-per-chunk = 3 closed chunks + 4 in open
            assertThat(hs.closedChunkCount()).isEqualTo(3);
            assertThat(hs.openChunkSampleCount()).isEqualTo(4);
            assertThat(h.readAll(id)).hasSize(100);
        }
    }

    @Test
    void readRangePruning(@TempDir Path tmp) throws IOException {
        try (Head h = new Head(tmp)) {
            LabelSet ls = LabelSet.of("service", "checkout");
            long id = h.getOrCreate(ls).id();
            for (int i = 0; i < 100; i++) {
                h.append(id, 1_000L + i * 1000L, i * 1.0);
            }
            List<Sample> result = h.read(id, 50_000L, 60_000L);
            // Samples from i=49 (50000) to i=59 (60000) inclusive
            assertThat(result).hasSize(11);
            assertThat(result.get(0).timestampMs()).isEqualTo(50_000L);
            assertThat(result.get(result.size() - 1).timestampMs()).isEqualTo(60_000L);
        }
    }
}
