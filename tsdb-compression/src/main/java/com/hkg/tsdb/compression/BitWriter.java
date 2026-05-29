package com.hkg.tsdb.compression;

import java.util.Arrays;

/**
 * MSB-first bit writer. Writes are buffered in a byte array that grows as needed.
 * On flush, partial bytes are zero-padded to the next byte boundary.
 */
public final class BitWriter {

    private byte[] buf;
    private int byteIndex;     // next byte position to write to
    private int bitOffset;     // 0..7 within buf[byteIndex]; bit 7 is most-significant

    public BitWriter() {
        this(64);
    }

    public BitWriter(int initialCapacity) {
        this.buf = new byte[Math.max(1, initialCapacity)];
        this.byteIndex = 0;
        this.bitOffset = 0;
    }

    public void writeBit(int bit) {
        ensure(1);
        if ((bit & 1) != 0) {
            buf[byteIndex] |= (byte) (1 << (7 - bitOffset));
        }
        advance(1);
    }

    /**
     * Writes the lowest {@code n} bits of {@code value}, most-significant of those n bits first.
     * @param value the value; bits above {@code n} are ignored
     * @param n 1..64
     */
    public void writeBits(long value, int n) {
        if (n < 1 || n > 64) {
            throw new IllegalArgumentException("n must be 1..64, got " + n);
        }
        for (int i = n - 1; i >= 0; i--) {
            writeBit((int) ((value >>> i) & 1L));
        }
    }

    public int bitsWritten() {
        return byteIndex * 8 + bitOffset;
    }

    public byte[] bytes() {
        int len = byteIndex + (bitOffset == 0 ? 0 : 1);
        return Arrays.copyOf(buf, len);
    }

    private void ensure(int bits) {
        int needed = byteIndex + (bitOffset + bits + 7) / 8 + 1;
        if (needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(needed, buf.length * 2));
        }
    }

    private void advance(int bits) {
        int total = bitOffset + bits;
        byteIndex += total / 8;
        bitOffset = total % 8;
    }
}
