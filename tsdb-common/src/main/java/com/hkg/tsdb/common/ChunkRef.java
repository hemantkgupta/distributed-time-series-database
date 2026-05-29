package com.hkg.tsdb.common;

/**
 * A pointer to a Gorilla chunk on disk or in memory.
 * Time range [minTs, maxTs] is inclusive on both ends.
 */
public record ChunkRef(long minTs, long maxTs, long offset, int length) {

    public ChunkRef {
        if (minTs > maxTs) {
            throw new IllegalArgumentException("minTs > maxTs: " + minTs + " > " + maxTs);
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must be >= 0, got " + length);
        }
    }

    public boolean overlaps(long start, long end) {
        return minTs <= end && maxTs >= start;
    }
}
