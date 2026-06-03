package com.hkg.tsdb.node;

import com.hkg.tsdb.block.BlockMeta;
import com.hkg.tsdb.block.BlockReader;
import com.hkg.tsdb.block.BlockWriter;
import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.common.Series;
import com.hkg.tsdb.head.Head;
import com.hkg.tsdb.query.LabelMatcher;
import com.hkg.tsdb.query.QueryEngine;
import com.hkg.tsdb.query.SeriesRange;
import com.hkg.tsdb.tenant.TenantId;
import com.hkg.tsdb.tenant.TenantSeriesLimiter;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Single-node composition of the TSDB modules: tenant gate, head, flush-to-block,
 * and block query. This is the demo binary's core without networking.
 */
public final class TsdbNode implements Closeable {

    private final Head head;
    private final TenantSeriesLimiter limiter;
    private final BlockWriter blockWriter;
    private final List<BlockReader> blocks = new ArrayList<>();

    public TsdbNode(Path walDir, TenantSeriesLimiter limiter) throws IOException {
        this.head = new Head(walDir);
        this.limiter = limiter;
        this.blockWriter = new BlockWriter();
    }

    public void append(TenantId tenant, LabelSet labels, long timestampMs, double value) throws IOException {
        limiter.accept(tenant, labels);
        Series series = head.getOrCreate(labels);
        head.append(series.id(), timestampMs, value);
    }

    public BlockMeta flush(Path blockDir) throws IOException {
        BlockMeta meta = blockWriter.flushHead(blockDir, head);
        blocks.add(new BlockReader(blockDir));
        return meta;
    }

    public List<SeriesRange> range(List<LabelMatcher> matchers, long startTs, long endTs) throws IOException {
        return new QueryEngine(blocks).range(matchers, startTs, endTs);
    }

    public Map<String, Double> rateSumBy(List<LabelMatcher> matchers, String groupLabel, long startTs, long endTs)
        throws IOException {
        return new QueryEngine(blocks).rateSumBy(matchers, groupLabel, startTs, endTs);
    }

    @Override
    public void close() throws IOException {
        head.close();
    }
}
