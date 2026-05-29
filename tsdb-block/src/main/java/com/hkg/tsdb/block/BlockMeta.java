package com.hkg.tsdb.block;

/**
 * Phase 2 scaffold: lightweight block metadata record.
 *
 * Real implementation will write this as JSON to {@code block-NNN/meta.json} alongside
 * the chunks file and the index file. See docs/implementation-plan.md §7.
 */
public record BlockMeta(
    String id,
    long minTs,
    long maxTs,
    int seriesCount,
    long sampleCount
) {

    public BlockMeta {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Block ID must not be empty");
        }
        if (minTs > maxTs) {
            throw new IllegalArgumentException("minTs > maxTs");
        }
        if (seriesCount < 0 || sampleCount < 0) {
            throw new IllegalArgumentException("Counts must be non-negative");
        }
    }
}
