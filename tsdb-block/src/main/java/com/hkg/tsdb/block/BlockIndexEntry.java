package com.hkg.tsdb.block;

import com.hkg.tsdb.common.ChunkRef;
import com.hkg.tsdb.common.Series;

import java.util.List;

/**
 * A persistent-block index entry: labels plus chunk references for one series.
 */
public record BlockIndexEntry(Series series, List<ChunkRef> chunks) {

    public BlockIndexEntry {
        if (series == null) throw new IllegalArgumentException("series must not be null");
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        chunks = List.copyOf(chunks);
    }
}
