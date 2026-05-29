package com.hkg.tsdb.compression;

/**
 * MSB-first bit reader. Reads from a byte[] starting at offset 0.
 * Mirror of {@link BitWriter}.
 */
public final class BitReader {

    private final byte[] buf;
    private final int totalBits;
    private int bitIndex;

    public BitReader(byte[] buf, int totalBits) {
        if (buf == null) {
            throw new IllegalArgumentException("buf must not be null");
        }
        if (totalBits < 0 || totalBits > buf.length * 8) {
            throw new IllegalArgumentException("totalBits out of range: " + totalBits);
        }
        this.buf = buf;
        this.totalBits = totalBits;
        this.bitIndex = 0;
    }

    public boolean hasNext(int n) {
        return bitIndex + n <= totalBits;
    }

    public int readBit() {
        if (bitIndex >= totalBits) {
            throw new IllegalStateException("BitReader exhausted at bit " + bitIndex);
        }
        int byteIdx = bitIndex >>> 3;
        int bitInByte = 7 - (bitIndex & 7);
        bitIndex++;
        return (buf[byteIdx] >>> bitInByte) & 1;
    }

    public long readBits(int n) {
        if (n < 1 || n > 64) {
            throw new IllegalArgumentException("n must be 1..64, got " + n);
        }
        long v = 0L;
        for (int i = 0; i < n; i++) {
            v = (v << 1) | readBit();
        }
        return v;
    }

    public int bitsRead() {
        return bitIndex;
    }
}
