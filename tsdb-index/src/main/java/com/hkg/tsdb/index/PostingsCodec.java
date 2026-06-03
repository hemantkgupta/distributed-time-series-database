package com.hkg.tsdb.index;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Delta + unsigned-varint codec for sorted postings lists.
 *
 * Production engines use roaring bitmaps or more specialised bit-packing. This
 * codec keeps the architectural property that postings are monotonic series IDs
 * and compress by storing deltas, which is the key idea the block reader needs.
 */
public final class PostingsCodec {

    private PostingsCodec() {
    }

    public static byte[] encode(PostingsList postings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long previous = 0L;
        for (long id : postings.toArray()) {
            if (id < previous) {
                throw new IllegalArgumentException("postings must be sorted");
            }
            writeVarLong(out, id - previous);
            previous = id;
        }
        return out.toByteArray();
    }

    public static PostingsList decode(byte[] bytes) {
        List<Long> ids = new ArrayList<>();
        long previous = 0L;
        int i = 0;
        while (i < bytes.length) {
            long delta = 0L;
            int shift = 0;
            while (true) {
                if (i >= bytes.length) {
                    throw new IllegalArgumentException("truncated varint");
                }
                int b = bytes[i++] & 0xff;
                delta |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
                if (shift > 63) {
                    throw new IllegalArgumentException("varint too long");
                }
            }
            long id = previous + delta;
            ids.add(id);
            previous = id;
        }
        long[] sorted = new long[ids.size()];
        for (int j = 0; j < ids.size(); j++) sorted[j] = ids.get(j);
        return PostingsList.fromSorted(sorted);
    }

    private static void writeVarLong(ByteArrayOutputStream out, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        while ((value & ~0x7fL) != 0) {
            out.write((int) (value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }
}
