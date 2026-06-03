package com.hkg.tsdb.block;

import com.hkg.tsdb.common.ChunkRef;
import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;
import com.hkg.tsdb.compression.GorillaChunk;
import com.hkg.tsdb.index.PostingsList;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads immutable blocks written by {@link BlockWriter}.
 */
public final class BlockReader {

    private static final Pattern META_PATTERN = Pattern.compile(
        "\\{\"id\":\"([^\"]+)\",\"minTs\":(\\d+),\"maxTs\":(\\d+),\"seriesCount\":(\\d+),\"sampleCount\":(\\d+)\\}");

    private final Path blockDir;
    private final BlockMeta meta;
    private final BlockIndex index;

    public BlockReader(Path blockDir) throws IOException {
        this.blockDir = blockDir;
        this.meta = readMeta(blockDir.resolve(BlockWriter.META_FILE));
        this.index = new BlockIndex(readIndex(blockDir.resolve(BlockWriter.INDEX_FILE)));
    }

    public Path blockDir() {
        return blockDir;
    }

    public BlockMeta meta() {
        return meta;
    }

    public List<BlockIndexEntry> entries() {
        return index.entries();
    }

    public Map<Series, List<Sample>> select(Map<String, String> exactLabels, long minTs, long maxTs) throws IOException {
        Map<Series, List<Sample>> out = new LinkedHashMap<>();
        PostingsList ids = index.exactMatch(exactLabels);
        for (long id : ids.toArray()) {
            BlockIndexEntry entry = index.entry(id).orElseThrow();
            List<Sample> samples = readSamples(entry, minTs, maxTs);
            if (!samples.isEmpty()) {
                out.put(entry.series(), samples);
            }
        }
        return out;
    }

    public Map<Series, List<Sample>> all(long minTs, long maxTs) throws IOException {
        Map<Series, List<Sample>> out = new LinkedHashMap<>();
        for (BlockIndexEntry entry : index.entries()) {
            List<Sample> samples = readSamples(entry, minTs, maxTs);
            if (!samples.isEmpty()) {
                out.put(entry.series(), samples);
            }
        }
        return out;
    }

    public List<Sample> readSamples(BlockIndexEntry entry, long minTs, long maxTs) throws IOException {
        List<Sample> out = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(blockDir.resolve(BlockWriter.CHUNKS_FILE).toFile(), "r")) {
            for (ChunkRef ref : entry.chunks()) {
                if (!ref.overlaps(minTs, maxTs)) continue;
                raf.seek(ref.offset());
                long storedMinTs = raf.readLong();
                long storedMaxTs = raf.readLong();
                int sampleCount = raf.readInt();
                int bits = raf.readInt();
                int payloadLen = raf.readInt();
                byte[] payload = new byte[payloadLen];
                raf.readFully(payload);
                if (storedMinTs != ref.minTs() || storedMaxTs != ref.maxTs()) {
                    throw new IOException("chunk metadata mismatch at offset " + ref.offset());
                }
                for (Sample sample : GorillaChunk.decode(payload, bits, sampleCount)) {
                    if (sample.timestampMs() >= minTs && sample.timestampMs() <= maxTs) {
                        out.add(sample);
                    }
                }
            }
        }
        return out;
    }

    static BlockMeta readMeta(Path path) throws IOException {
        String json = Files.readString(path).trim();
        Matcher m = META_PATTERN.matcher(json);
        if (!m.matches()) {
            throw new IOException("invalid meta.json: " + json);
        }
        return new BlockMeta(
            m.group(1),
            Long.parseLong(m.group(2)),
            Long.parseLong(m.group(3)),
            Integer.parseInt(m.group(4)),
            Long.parseLong(m.group(5))
        );
    }

    static List<BlockIndexEntry> readIndex(Path path) throws IOException {
        List<BlockIndexEntry> entries = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int seriesCount = in.readInt();
            for (int i = 0; i < seriesCount; i++) {
                long id = in.readLong();
                int labelCount = in.readInt();
                Map<String, String> labels = new LinkedHashMap<>();
                for (int j = 0; j < labelCount; j++) {
                    labels.put(in.readUTF(), in.readUTF());
                }
                int chunkCount = in.readInt();
                List<ChunkRef> refs = new ArrayList<>();
                for (int j = 0; j < chunkCount; j++) {
                    refs.add(new ChunkRef(in.readLong(), in.readLong(), in.readLong(), in.readInt()));
                }
                entries.add(new BlockIndexEntry(new Series(id, LabelSet.of(labels)), refs));
            }
        }
        return entries;
    }
}
