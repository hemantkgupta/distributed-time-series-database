package com.hkg.tsdb.wal;

import com.hkg.tsdb.common.LabelSet;

/**
 * Sealed hierarchy of WAL record types.
 *
 * SERIES: registers a series ID -> label-set mapping.
 * SAMPLE: appends (ts, value) to a series.
 */
public sealed interface WalRecord permits WalRecord.Series, WalRecord.Sample {

    byte SERIES_TYPE = 0x01;
    byte SAMPLE_TYPE = 0x02;

    record Series(long seriesId, LabelSet labels) implements WalRecord {}

    record Sample(long seriesId, long timestampMs, double value) implements WalRecord {}
}
