package com.hkg.tsdb.query;

import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;

/**
 * One instant-vector point.
 */
public record SeriesPoint(Series series, Sample sample) {
}
