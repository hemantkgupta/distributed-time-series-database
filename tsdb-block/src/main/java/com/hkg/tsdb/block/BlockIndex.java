package com.hkg.tsdb.block;

import com.hkg.tsdb.index.InvertedIndex;
import com.hkg.tsdb.index.PostingsList;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Logical block index: series metadata, chunk refs, and label-value postings.
 */
public final class BlockIndex {

    private final Map<Long, BlockIndexEntry> entriesBySeriesId;
    private final InvertedIndex invertedIndex;

    public BlockIndex(Collection<BlockIndexEntry> entries) {
        this.entriesBySeriesId = new TreeMap<>();
        this.invertedIndex = new InvertedIndex();
        for (BlockIndexEntry e : entries) {
            entriesBySeriesId.put(e.series().id(), e);
            invertedIndex.add(e.series());
        }
    }

    public List<BlockIndexEntry> entries() {
        return List.copyOf(entriesBySeriesId.values());
    }

    public Optional<BlockIndexEntry> entry(long seriesId) {
        return Optional.ofNullable(entriesBySeriesId.get(seriesId));
    }

    public PostingsList exactMatch(Map<String, String> labels) {
        return invertedIndex.exactMatch(labels);
    }
}
