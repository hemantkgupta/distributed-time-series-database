package com.hkg.tsdb.head;

import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;
import com.hkg.tsdb.compression.GorillaChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * One series's in-memory presence in the head: the immutable Series identity +
 * a list of closed Gorilla chunks + one open Gorilla chunk that accepts appends.
 *
 * Chunks close when full ({@link GorillaChunk#isFull()}) or when {@link #closeOpenChunk()}
 * is called explicitly (e.g., during a flush).
 */
public final class HeadSeries {

    private final Series series;
    private final int chunkMaxSamples;
    private final List<ClosedChunk> closed = new ArrayList<>();
    private GorillaChunk openChunk;

    public HeadSeries(Series series, int chunkMaxSamples) {
        this.series = series;
        this.chunkMaxSamples = chunkMaxSamples;
        this.openChunk = new GorillaChunk(chunkMaxSamples);
    }

    public Series series() {
        return series;
    }

    public synchronized void append(long timestampMs, double value) {
        if (openChunk.isFull()) {
            rotate();
        }
        openChunk.append(timestampMs, value);
    }

    public synchronized List<Sample> readAll() {
        List<Sample> out = new ArrayList<>();
        for (ClosedChunk c : closed) {
            out.addAll(GorillaChunk.decode(c.bytes(), c.totalBits(), c.sampleCount()));
        }
        if (openChunk.sampleCount() > 0) {
            out.addAll(GorillaChunk.decode(openChunk.bytes(), openChunk.bitsWritten(), openChunk.sampleCount()));
        }
        return out;
    }

    public synchronized List<Sample> read(long minTs, long maxTs) {
        List<Sample> out = new ArrayList<>();
        for (ClosedChunk c : closed) {
            if (c.maxTs() < minTs || c.minTs() > maxTs) continue;
            for (Sample s : GorillaChunk.decode(c.bytes(), c.totalBits(), c.sampleCount())) {
                if (s.timestampMs() >= minTs && s.timestampMs() <= maxTs) out.add(s);
            }
        }
        if (openChunk.sampleCount() > 0) {
            for (Sample s : GorillaChunk.decode(openChunk.bytes(), openChunk.bitsWritten(), openChunk.sampleCount())) {
                if (s.timestampMs() >= minTs && s.timestampMs() <= maxTs) out.add(s);
            }
        }
        return out;
    }

    public synchronized int closedChunkCount() {
        return closed.size();
    }

    public synchronized int openChunkSampleCount() {
        return openChunk.sampleCount();
    }

    public synchronized void closeOpenChunk() {
        if (openChunk.sampleCount() > 0) {
            rotate();
        }
    }

    private void rotate() {
        if (openChunk.sampleCount() > 0) {
            closed.add(new ClosedChunk(
                openChunk.minTs(),
                openChunk.maxTs(),
                openChunk.bytes(),
                openChunk.bitsWritten(),
                openChunk.sampleCount()
            ));
        }
        openChunk = new GorillaChunk(chunkMaxSamples);
    }

    /** Closed chunk: immutable byte payload + metadata. */
    public record ClosedChunk(long minTs, long maxTs, byte[] bytes, int totalBits, int sampleCount) {}
}
