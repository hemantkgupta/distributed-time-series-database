package com.hkg.tsdb.head;

import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;
import com.hkg.tsdb.wal.WalRecord;
import com.hkg.tsdb.wal.WalReplay;
import com.hkg.tsdb.wal.WriteAheadLog;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory head holding active series. Backed by an append-only WAL for crash recovery.
 *
 * Single-tenant by design (Phase 1); a wrapper layer adds per-tenant heads in Phase 4.
 */
public final class Head implements Closeable {

    public static final int DEFAULT_CHUNK_MAX_SAMPLES = 120;

    private final Path walDir;
    private final WriteAheadLog wal;
    private final int chunkMaxSamples;
    private final Map<Long, HeadSeries> seriesById = new ConcurrentHashMap<>();

    public Head(Path walDir) throws IOException {
        this(walDir, DEFAULT_CHUNK_MAX_SAMPLES);
    }

    public Head(Path walDir, int chunkMaxSamples) throws IOException {
        this.walDir = walDir;
        this.chunkMaxSamples = chunkMaxSamples;
        this.wal = new WriteAheadLog(walDir);
    }

    /**
     * Get-or-create a series for the given label set. If new, an entry is appended to the WAL.
     * Same LabelSet returns the same Series.id across recovery.
     */
    public Series getOrCreate(LabelSet labels) throws IOException {
        long id = labels.fingerprint();
        HeadSeries existing = seriesById.get(id);
        if (existing != null) return existing.series();

        // Register: append SERIES record to WAL then add to map.
        Series s = Series.of(labels);
        wal.appendSeries(id, labels);
        seriesById.putIfAbsent(id, new HeadSeries(s, chunkMaxSamples));
        return s;
    }

    /**
     * Append a sample to the named series. The series must exist (via {@link #getOrCreate}).
     */
    public void append(long seriesId, long timestampMs, double value) throws IOException {
        HeadSeries hs = seriesById.get(seriesId);
        if (hs == null) {
            throw new IllegalArgumentException("Unknown series ID: " + seriesId + " (must getOrCreate first)");
        }
        wal.appendSample(seriesId, timestampMs, value);
        hs.append(timestampMs, value);
    }

    /**
     * Read all samples in [minTs, maxTs] for the given series.
     */
    public List<Sample> read(long seriesId, long minTs, long maxTs) {
        HeadSeries hs = seriesById.get(seriesId);
        if (hs == null) return List.of();
        return hs.read(minTs, maxTs);
    }

    /**
     * Read all samples across the entire retention of the named series.
     */
    public List<Sample> readAll(long seriesId) {
        HeadSeries hs = seriesById.get(seriesId);
        if (hs == null) return List.of();
        return hs.readAll();
    }

    public int seriesCount() {
        return seriesById.size();
    }

    public Map<Long, HeadSeries> snapshotSeries() {
        return Collections.unmodifiableMap(new HashMap<>(seriesById));
    }

    public void sync() throws IOException {
        wal.sync();
    }

    @Override
    public void close() throws IOException {
        wal.close();
    }

    /**
     * Crash recovery. Reads the WAL in order, reconstructing series + appending samples.
     * After recovery, the returned Head is open for further writes (a new WAL segment may
     * be created by the constructor invocation that follows).
     */
    public static Head recover(Path walDir) throws IOException {
        return recover(walDir, DEFAULT_CHUNK_MAX_SAMPLES);
    }

    public static Head recover(Path walDir, int chunkMaxSamples) throws IOException {
        // Replay first (closes its file handles), then open a fresh Head (new WAL segment).
        Map<Long, HeadSeries> recovered = new HashMap<>();
        Iterator<WalRecord> iter = WalReplay.replay(walDir);
        while (iter.hasNext()) {
            WalRecord r = iter.next();
            if (r instanceof WalRecord.Series s) {
                recovered.computeIfAbsent(s.seriesId(),
                    id -> new HeadSeries(new Series(id, s.labels()), chunkMaxSamples));
            } else if (r instanceof WalRecord.Sample s) {
                HeadSeries hs = recovered.get(s.seriesId());
                if (hs == null) {
                    // Stray sample for an unknown series; tolerated (WAL corruption).
                    continue;
                }
                hs.append(s.timestampMs(), s.value());
            }
        }

        Head h = new Head(walDir, chunkMaxSamples);
        h.seriesById.putAll(recovered);
        return h;
    }
}
