package com.hkg.tsdb.compression;

import com.hkg.tsdb.common.Sample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GorillaChunkTest {

    @Test
    void singleSample_roundtrip() {
        GorillaChunk c = new GorillaChunk();
        c.append(1_700_000_000_000L, 42.5);
        List<Sample> out = GorillaChunk.decode(c.bytes(), c.bitsWritten(), c.sampleCount());
        assertThat(out).containsExactly(new Sample(1_700_000_000_000L, 42.5));
    }

    @Test
    void stableCadence_compressesAggressively() {
        // 120 samples at 60s cadence; values drifting slowly.
        // Raw: 120 * 16 = 1920 bytes. Floats are strictly monotone in low mantissa bits, which
        // is a pessimistic case for Gorilla XOR-window reuse (window shape shifts each sample).
        // Even so we should beat raw by ~3x. Real-world counter / gauge data with frequent
        // repeats compresses far better (see identicalValues_compressEvenMore below).
        GorillaChunk c = new GorillaChunk(120);
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 120; i++) {
            c.append(t0 + i * 60_000L, 12.3 + i * 0.0001);
        }
        int compressedBits = c.bitsWritten();
        int compressedBytes = (compressedBits + 7) / 8;
        assertThat(compressedBytes)
            .as("stable-cadence chunk should compress at least ~2.5x vs raw 1920B; got %dB", compressedBytes)
            .isLessThan(800);

        List<Sample> out = GorillaChunk.decode(c.bytes(), compressedBits, c.sampleCount());
        assertThat(out).hasSize(120);
        for (int i = 0; i < 120; i++) {
            assertThat(out.get(i).timestampMs()).isEqualTo(t0 + i * 60_000L);
            assertThat(out.get(i).value()).isCloseTo(12.3 + i * 0.0001, org.assertj.core.data.Offset.offset(1e-12));
        }
    }

    @Test
    void jittery_timestamps_decodeCorrectly() {
        GorillaChunk c = new GorillaChunk(64);
        long t = 1_700_000_000_000L;
        List<Long> sentTs = new ArrayList<>();
        List<Double> sentVals = new ArrayList<>();
        Random rng = new Random(42);
        for (int i = 0; i < 64; i++) {
            // 60s nominal cadence with up to ±5s jitter
            t = t + 60_000L + (rng.nextInt(11) - 5) * 1000L;
            double v = 100.0 + rng.nextGaussian() * 2.0;
            c.append(t, v);
            sentTs.add(t);
            sentVals.add(v);
        }
        List<Sample> out = GorillaChunk.decode(c.bytes(), c.bitsWritten(), c.sampleCount());
        for (int i = 0; i < 64; i++) {
            assertThat(out.get(i).timestampMs()).isEqualTo(sentTs.get(i));
            assertThat(out.get(i).value()).isEqualTo(sentVals.get(i));
        }
    }

    @Test
    void identicalValues_compressEvenMore() {
        // All values identical -> each value XOR = 0 -> 1 bit per value after the first.
        GorillaChunk c = new GorillaChunk(120);
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 120; i++) {
            c.append(t0 + i * 1000L, 1.0);
        }
        // First sample: 64 + 64 = 128 bits. Then 14-bit delta + 1-bit value for sample 2.
        // Subsequent: ~1-bit dd + 1-bit value = ~2 bits each. So total ~ 128 + 15 + 118*2 ≈ 379 bits ≈ 48 bytes.
        int bytes = (c.bitsWritten() + 7) / 8;
        assertThat(bytes).isLessThan(70);
    }

    @Test
    void appendOnFullChunk_rejected() {
        GorillaChunk c = new GorillaChunk(3);
        c.append(1, 1.0);
        c.append(2, 2.0);
        c.append(3, 3.0);
        assertThatThrownBy(() -> c.append(4, 4.0))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonIncreasingTimestamp_rejected() {
        GorillaChunk c = new GorillaChunk();
        c.append(1000, 1.0);
        assertThatThrownBy(() -> c.append(1000, 2.0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.append(999, 2.0))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
