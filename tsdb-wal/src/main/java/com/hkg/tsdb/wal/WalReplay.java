package com.hkg.tsdb.wal;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Replay iterator over a WAL directory.
 *
 * Iterates segments in ascending numeric order, decoding records framed as
 * {@code [u8 type][u16 length][payload][u32 CRC32-Castagnoli]}. On CRC mismatch
 * or EOF mid-record, replay stops at the last valid record (no exception
 * thrown — partial-tail tolerance is the recovery contract).
 */
public final class WalReplay {

    private WalReplay() {}

    public static Iterator<WalRecord> replay(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.<WalRecord>of().iterator();
        }
        List<Path> segments = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, "wal-*.log")) {
            for (Path p : stream) segments.add(p);
        }
        segments.sort(Comparator.naturalOrder());
        return new SegmentIterator(segments);
    }

    /**
     * Eager replay: reads all records into a list. Convenient for small WALs and tests.
     */
    public static List<WalRecord> replayAll(Path dir) throws IOException {
        List<WalRecord> out = new ArrayList<>();
        Iterator<WalRecord> it = replay(dir);
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    private static final class SegmentIterator implements Iterator<WalRecord> {

        private final List<Path> segments;
        private int segmentIdx;
        private DataInputStream in;
        private WalRecord next;
        private boolean exhausted;

        SegmentIterator(List<Path> segments) throws IOException {
            this.segments = segments;
            this.segmentIdx = 0;
            advanceSegment();
            advance();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public WalRecord next() {
            WalRecord r = next;
            if (r == null) throw new java.util.NoSuchElementException();
            try {
                advance();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return r;
        }

        private void advance() throws IOException {
            next = null;
            while (!exhausted) {
                WalRecord rec = tryReadOne();
                if (rec != null) {
                    next = rec;
                    return;
                }
                // null = current segment exhausted (or truncated tail); move on.
                advanceSegment();
            }
        }

        private WalRecord tryReadOne() throws IOException {
            if (in == null) return null;
            try {
                int type = in.read();
                if (type < 0) return null;                 // clean EOF
                int len = in.readUnsignedShort();
                byte[] payload = in.readNBytes(len);
                if (payload.length < len) return null;     // truncated tail
                int storedCrc = in.readInt();
                long actualCrc = crc32c((byte) type, payload);
                if ((storedCrc & 0xFFFFFFFFL) != actualCrc) {
                    // Stop at first corrupt record; partial-tail tolerance.
                    return null;
                }
                return WalCodec.decode((byte) type, payload);
            } catch (EOFException e) {
                return null;
            }
        }

        private void advanceSegment() throws IOException {
            if (in != null) {
                in.close();
                in = null;
            }
            while (segmentIdx < segments.size()) {
                Path p = segments.get(segmentIdx++);
                InputStream stream = new BufferedInputStream(Files.newInputStream(p));
                in = new DataInputStream(stream);
                return;
            }
            exhausted = true;
        }

        private static long crc32c(byte type, byte[] payload) {
            CRC32C crc = new CRC32C();
            crc.update(new byte[]{type});
            int len = payload.length;
            crc.update(new byte[]{(byte) (len >>> 8), (byte) (len & 0xff)});
            crc.update(payload);
            return crc.getValue() & 0xFFFFFFFFL;
        }
    }
}
