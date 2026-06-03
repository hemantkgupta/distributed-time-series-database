package com.hkg.tsdb.bench;

/**
 * Deterministic demo-load summary.
 */
public record BenchmarkResult(int seriesCount, int sampleCount, long blockSampleCount) {
}
