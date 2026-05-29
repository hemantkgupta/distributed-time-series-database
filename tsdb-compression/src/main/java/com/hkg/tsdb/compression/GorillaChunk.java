package com.hkg.tsdb.compression;

import com.hkg.tsdb.common.Sample;

import java.util.ArrayList;
import java.util.List;

/**
 * Gorilla-compressed chunk of (timestamp, float64) samples.
 *
 * Timestamps: delta-of-delta with the variable-length prefix code from the
 * Pelkonen et al. 2015 paper. Values: XOR-with-previous + leading-zero/run-length
 * prefix code, same paper.
 *
 * First sample: full 64-bit timestamp + 64-bit value.
 * Second sample: 14-bit delta + value (XOR-encoded relative to first).
 * Subsequent: dd-of-delta + XOR-encoded value.
 *
 * Chunks are append-only and have a configured maximum sample count.
 */
public final class GorillaChunk {

    public static final int DEFAULT_MAX_SAMPLES = 120;

    private final int maxSamples;
    private final BitWriter writer;

    private int count;
    private long firstTs;
    private long lastTs;
    private long lastDelta;     // delta between sample i-1 and i-2
    private double lastValue;
    private long lastValueBits;
    private int lastLeadingZeros = -1;
    private int lastTrailingZeros = -1;

    public GorillaChunk() {
        this(DEFAULT_MAX_SAMPLES);
    }

    public GorillaChunk(int maxSamples) {
        if (maxSamples < 1) {
            throw new IllegalArgumentException("maxSamples must be >= 1");
        }
        this.maxSamples = maxSamples;
        this.writer = new BitWriter();
    }

    public boolean isFull() {
        return count >= maxSamples;
    }

    public int sampleCount() {
        return count;
    }

    public long minTs() {
        if (count == 0) throw new IllegalStateException("empty chunk");
        return firstTs;
    }

    public long maxTs() {
        if (count == 0) throw new IllegalStateException("empty chunk");
        return lastTs;
    }

    public int bitsWritten() {
        return writer.bitsWritten();
    }

    public byte[] bytes() {
        return writer.bytes();
    }

    public void append(long timestampMs, double value) {
        if (isFull()) {
            throw new IllegalStateException("GorillaChunk is full (" + maxSamples + " samples)");
        }
        if (count > 0 && timestampMs <= lastTs) {
            throw new IllegalArgumentException("Timestamps must be strictly increasing: "
                + timestampMs + " <= " + lastTs);
        }
        long valueBits = Double.doubleToRawLongBits(value);
        if (count == 0) {
            writer.writeBits(timestampMs, 64);
            writer.writeBits(valueBits, 64);
            firstTs = timestampMs;
            lastTs = timestampMs;
            lastDelta = 0;
            lastValue = value;
            lastValueBits = valueBits;
        } else if (count == 1) {
            long delta = timestampMs - lastTs;
            // Gorilla paper uses 14 bits here, tuned for 60s scrapes. We widen to 32 bits
            // so we can handle ms-resolution timestamps at any cadence without the tuned
            // assumption. One-time cost per chunk.
            if (delta < 0 || delta > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("Second-sample delta out of 32-bit range: " + delta);
            }
            writer.writeBits(delta, 32);
            encodeXorValue(valueBits);
            lastTs = timestampMs;
            lastDelta = delta;
            lastValue = value;
            lastValueBits = valueBits;
        } else {
            long delta = timestampMs - lastTs;
            long dd = delta - lastDelta;
            encodeDeltaOfDelta(dd);
            encodeXorValue(valueBits);
            lastTs = timestampMs;
            lastDelta = delta;
            lastValue = value;
            lastValueBits = valueBits;
        }
        count++;
    }

    private void encodeDeltaOfDelta(long dd) {
        if (dd == 0) {
            writer.writeBit(0);                       // 0
        } else if (dd >= -63 && dd <= 64) {
            writer.writeBits(0b10, 2);                // 10
            writer.writeBits(dd & 0x7FL, 7);
        } else if (dd >= -255 && dd <= 256) {
            writer.writeBits(0b110, 3);               // 110
            writer.writeBits(dd & 0x1FFL, 9);
        } else if (dd >= -2047 && dd <= 2048) {
            writer.writeBits(0b1110, 4);              // 1110
            writer.writeBits(dd & 0xFFFL, 12);
        } else {
            writer.writeBits(0b1111, 4);              // 1111
            writer.writeBits(dd, 32);
        }
    }

    private void encodeXorValue(long valueBits) {
        long xor = valueBits ^ lastValueBits;
        if (xor == 0) {
            writer.writeBit(0);
            return;
        }
        writer.writeBit(1);
        int leading = Long.numberOfLeadingZeros(xor);
        int trailing = Long.numberOfTrailingZeros(xor);
        // Per Gorilla paper: cap leading at 31 so it fits in 5 bits.
        if (leading > 31) leading = 31;

        if (lastLeadingZeros != -1 && leading >= lastLeadingZeros && trailing >= lastTrailingZeros) {
            // Re-use previous window
            writer.writeBit(0);
            int meaningfulBits = 64 - lastLeadingZeros - lastTrailingZeros;
            long mantissa = (xor >>> lastTrailingZeros) & ((meaningfulBits == 64) ? -1L : ((1L << meaningfulBits) - 1));
            writer.writeBits(mantissa, meaningfulBits);
        } else {
            // New window
            writer.writeBit(1);
            writer.writeBits(leading, 5);
            int meaningfulBits = 64 - leading - trailing;
            // meaningfulBits range 1..64; encoded as 6 bits with 0 -> 64 by convention
            writer.writeBits(meaningfulBits & 0x3F, 6);
            long mantissa = (xor >>> trailing) & ((meaningfulBits == 64) ? -1L : ((1L << meaningfulBits) - 1));
            writer.writeBits(mantissa, meaningfulBits);
            lastLeadingZeros = leading;
            lastTrailingZeros = trailing;
        }
    }

    /**
     * Decode all samples from the given chunk bytes.
     * @param bytes the chunk's compressed payload
     * @param totalBits the number of bits actually written (use {@link #bitsWritten()} to record this)
     * @param count the number of samples in the chunk
     */
    public static List<Sample> decode(byte[] bytes, int totalBits, int count) {
        if (count == 0) return List.of();
        BitReader r = new BitReader(bytes, totalBits);
        List<Sample> out = new ArrayList<>(count);

        long ts = r.readBits(64);
        long valueBits = r.readBits(64);
        out.add(new Sample(ts, Double.longBitsToDouble(valueBits)));

        if (count == 1) return out;

        // window holds {lastLeading, lastTrailing} updated in-place by decodeXorValue when a new
        // window is established. Shared across all subsequent value decodes.
        int[] window = new int[]{-1, -1};

        long delta = r.readBits(32);
        valueBits = decodeXorValue(r, valueBits, window);
        ts = ts + delta;
        out.add(new Sample(ts, Double.longBitsToDouble(valueBits)));

        for (int i = 2; i < count; i++) {
            long dd = decodeDeltaOfDelta(r);
            delta = delta + dd;
            ts = ts + delta;
            valueBits = decodeXorValue(r, valueBits, window);
            out.add(new Sample(ts, Double.longBitsToDouble(valueBits)));
        }
        return out;
    }

    private static long decodeDeltaOfDelta(BitReader r) {
        if (r.readBit() == 0) return 0;
        if (r.readBit() == 0) return signExtend(r.readBits(7), 7);
        if (r.readBit() == 0) return signExtend(r.readBits(9), 9);
        if (r.readBit() == 0) return signExtend(r.readBits(12), 12);
        return signExtend(r.readBits(32), 32);
    }

    private static long signExtend(long v, int bits) {
        long signBit = 1L << (bits - 1);
        if ((v & signBit) != 0) {
            v |= -1L << bits;
        }
        return v;
    }

    /**
     * Decode one XOR-encoded value. Updates window[0]=lastLeading, window[1]=lastTrailing
     * when a new window is established.
     */
    private static long decodeXorValue(BitReader r, long prevValueBits, int[] window) {
        if (r.readBit() == 0) {
            return prevValueBits;
        }
        if (r.readBit() == 0) {
            // Reuse previous window
            int leading = window[0];
            int trailing = window[1];
            if (leading == -1) {
                // We didn't have a window yet — but the encoder wouldn't emit "reuse" in that case.
                throw new IllegalStateException("Cannot reuse window before one is established");
            }
            int meaningful = 64 - leading - trailing;
            long mantissa = r.readBits(meaningful);
            long xor = mantissa << trailing;
            return prevValueBits ^ xor;
        }
        // New window
        int leading = (int) r.readBits(5);
        int meaningful = (int) r.readBits(6);
        if (meaningful == 0) meaningful = 64;
        int trailing = 64 - leading - meaningful;
        long mantissa = r.readBits(meaningful);
        long xor = mantissa << trailing;
        window[0] = leading;
        window[1] = trailing;
        return prevValueBits ^ xor;
    }
}
