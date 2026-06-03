package com.hkg.tsdb.block;

import com.hkg.tsdb.common.ChunkRef;
import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;
import com.hkg.tsdb.compression.GorillaChunk;
import com.hkg.tsdb.head.Head;
import com.hkg.tsdb.head.HeadSeries;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Writes immutable Prometheus-style TSDB blocks: meta.json, chunks.bin, index.bin.
 *
 * The format is intentionally compact but pedagogical: chunks are Gorilla
 * payloads referenced by byte offsets in index.bin; labels are stored once in
 * the block index and label predicates resolve through postings lists.
 */
public final class BlockWriter {

    public static final String META_FILE = "meta.json";
    public static final String CHUNKS_FILE = "chunks.bin";
    public static final String INDEX_FILE = "index.bin";

    private final int chunkMaxSamples;

    public BlockWriter() {
        this(GorillaChunk.DEFAULT_MAX_SAMPLES);
    }

    public BlockWriter(int chunkMaxSamples) {
        this.chunkMaxSamples = chunkMaxSamples;
    }

    public BlockMeta write(Path blockDir, Collection<BlockSeries> input) throws IOException {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("block needs at least one series");
        }
        Files.createDirectories(blockDir);
        List<BlockSeries> series = input.stream()
            .sorted(Comparator.comparingLong(s -> s.series().id()))
            .toList();

        long minTs = series.stream().mapToLong(BlockSeries::minTs).min().orElseThrow();
        long maxTs = series.stream().mapToLong(BlockSeries::maxTs).max().orElseThrow();
        long sampleCount = series.stream().mapToLong(s -> s.samples().size()).sum();
        String id = String.format("block-%013d-%013d", minTs, maxTs);

        List<BlockIndexEntry> entries = new ArrayList<>();
        Path chunksPath = blockDir.resolve(CHUNKS_FILE);
        long offset = 0L;
        try (DataOutputStream chunksOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(chunksPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
            for (BlockSeries s : series) {
                List<ChunkRef> refs = new ArrayList<>();
                for (List<Sample> batch : batches(s.samples(), chunkMaxSamples)) {
                    GorillaChunk chunk = new GorillaChunk(chunkMaxSamples);
                    for (Sample sample : batch) {
                        chunk.append(sample.timestampMs(), sample.value());
                    }
                    byte[] payload = chunk.bytes();
                    int recordLength = Long.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES + Integer.BYTES + payload.length;
                    chunksOut.writeLong(chunk.minTs());
                    chunksOut.writeLong(chunk.maxTs());
                    chunksOut.writeInt(chunk.sampleCount());
                    chunksOut.writeInt(chunk.bitsWritten());
                    chunksOut.writeInt(payload.length);
                    chunksOut.write(payload);
                    refs.add(new ChunkRef(chunk.minTs(), chunk.maxTs(), offset, recordLength));
                    offset += recordLength;
                }
                entries.add(new BlockIndexEntry(s.series(), refs));
            }
        }

        writeIndex(blockDir.resolve(INDEX_FILE), entries);
        BlockMeta meta = new BlockMeta(id, minTs, maxTs, series.size(), sampleCount);
        writeMeta(blockDir.resolve(META_FILE), meta);
        return meta;
    }

    public BlockMeta flushHead(Path blockDir, Head head) throws IOException {
        List<BlockSeries> out = new ArrayList<>();
        for (Map.Entry<Long, HeadSeries> e : head.snapshotSeries().entrySet()) {
            List<Sample> samples = e.getValue().readAll();
            if (!samples.isEmpty()) {
                out.add(new BlockSeries(e.getValue().series(), samples));
            }
        }
        return write(blockDir, out);
    }

    static void writeMeta(Path path, BlockMeta meta) throws IOException {
        String json = "{"
            + "\"id\":\"" + meta.id() + "\","
            + "\"minTs\":" + meta.minTs() + ","
            + "\"maxTs\":" + meta.maxTs() + ","
            + "\"seriesCount\":" + meta.seriesCount() + ","
            + "\"sampleCount\":" + meta.sampleCount()
            + "}\n";
        Files.writeString(path, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    static void writeIndex(Path path, List<BlockIndexEntry> entries) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
            out.writeInt(entries.size());
            for (BlockIndexEntry e : entries) {
                Series series = e.series();
                LabelSet labels = series.labels();
                out.writeLong(series.id());
                out.writeInt(labels.labels().size());
                for (Map.Entry<String, String> label : labels.labels().entrySet()) {
                    out.writeUTF(label.getKey());
                    out.writeUTF(label.getValue());
                }
                out.writeInt(e.chunks().size());
                for (ChunkRef ref : e.chunks()) {
                    out.writeLong(ref.minTs());
                    out.writeLong(ref.maxTs());
                    out.writeLong(ref.offset());
                    out.writeInt(ref.length());
                }
            }
        }
    }

    private static List<List<Sample>> batches(List<Sample> samples, int max) {
        List<List<Sample>> out = new ArrayList<>();
        for (int i = 0; i < samples.size(); i += max) {
            out.add(samples.subList(i, Math.min(i + max, samples.size())));
        }
        return out;
    }
}
