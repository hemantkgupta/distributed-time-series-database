package com.hkg.tsdb.wal;

import com.hkg.tsdb.common.LabelSet;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/**
 * Append-only Write-Ahead Log. Records have the shape:
 *
 *   [u8 type][u16 length][payload[length]][u32 CRC32-Castagnoli over type+length+payload]
 *
 * Segments rotate at {@link #segmentMaxBytes}. The active segment is named
 * `wal-NNNNNNNN.log`; old segments are immutable.
 */
public final class WriteAheadLog implements Closeable {

    public static final long DEFAULT_SEGMENT_MAX_BYTES = 128L * 1024 * 1024;

    private final Path dir;
    private final long segmentMaxBytes;

    private long segmentNumber;
    private Path activeSegment;
    private DataOutputStream out;
    private long writtenInSegment;

    public WriteAheadLog(Path dir) throws IOException {
        this(dir, DEFAULT_SEGMENT_MAX_BYTES);
    }

    public WriteAheadLog(Path dir, long segmentMaxBytes) throws IOException {
        if (segmentMaxBytes < 1024) {
            throw new IllegalArgumentException("segmentMaxBytes too small");
        }
        this.dir = dir;
        this.segmentMaxBytes = segmentMaxBytes;
        Files.createDirectories(dir);
        this.segmentNumber = highestExistingSegmentNumber(dir) + 1;
        openNewSegment();
    }

    public void appendSeries(long seriesId, LabelSet labels) throws IOException {
        WalCodec.SerializedRecord rec = WalCodec.encodeSeries(seriesId, labels);
        writeFramed(WalRecord.SERIES_TYPE, rec.payload());
    }

    public void appendSample(long seriesId, long ts, double value) throws IOException {
        WalCodec.SerializedRecord rec = WalCodec.encodeSample(seriesId, ts, value);
        writeFramed(WalRecord.SAMPLE_TYPE, rec.payload());
    }

    public void sync() throws IOException {
        out.flush();
        // Note: real production WAL would also fsync the underlying channel.
        // Phase 1 uses buffered output flush only.
    }

    @Override
    public void close() throws IOException {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
        }
    }

    public Path activeSegmentPath() {
        return activeSegment;
    }

    private void writeFramed(byte type, byte[] payload) throws IOException {
        if (payload.length > 0xFFFF) {
            throw new IOException("WAL record payload exceeds 64KiB: " + payload.length);
        }
        if (writtenInSegment + 1 + 2 + payload.length + 4 > segmentMaxBytes) {
            rotateSegment();
        }
        out.writeByte(type);
        out.writeShort(payload.length);
        out.write(payload);
        long crc = crc32c(type, payload);
        out.writeInt((int) crc);
        writtenInSegment += 1 + 2 + payload.length + 4;
    }

    private static long crc32c(byte type, byte[] payload) {
        CRC32C crc = new CRC32C();
        crc.update(new byte[]{type});
        int len = payload.length;
        crc.update(new byte[]{(byte) (len >>> 8), (byte) (len & 0xff)});
        crc.update(payload);
        return crc.getValue() & 0xFFFFFFFFL;
    }

    private void rotateSegment() throws IOException {
        close();
        segmentNumber++;
        openNewSegment();
    }

    private void openNewSegment() throws IOException {
        activeSegment = dir.resolve(String.format("wal-%08d.log", segmentNumber));
        out = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(activeSegment, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        ));
        writtenInSegment = 0;
    }

    /** Find the highest existing wal-NNNNNNNN.log segment number, or -1 if none. */
    static long highestExistingSegmentNumber(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return -1L;
        long max = -1L;
        try (var stream = Files.newDirectoryStream(dir, "wal-*.log")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.length() == "wal-".length() + 8 + ".log".length()) {
                    try {
                        long n = Long.parseLong(name.substring(4, 12));
                        if (n > max) max = n;
                    } catch (NumberFormatException ignore) { /* skip */ }
                }
            }
        }
        return max;
    }

    /** UTF-8 byte length helper for tests. */
    public static int utf8Length(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
