package com.hkg.tsdb.http;

import com.hkg.tsdb.common.LabelSet;

/**
 * Minimal remote-write sample used by the HTTP adapter.
 */
public record WriteRequest(LabelSet labels, long timestampMs, double value) {
}
