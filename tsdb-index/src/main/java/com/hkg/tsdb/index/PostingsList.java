package com.hkg.tsdb.index;

import java.util.Arrays;

/**
 * Sorted sequence of series IDs for a single (label, value) entry.
 *
 * Phase 2 scaffold: in-memory sorted long array. CP10 will add delta-of-monotonic
 * + VarByte encoding on disk; CP10/CP11 will add Roaring-bitmap-style sparse
 * representation. See docs/implementation-plan.md.
 */
public final class PostingsList {

    private long[] ids;
    private int size;

    public PostingsList() {
        this(8);
    }

    public PostingsList(int initialCapacity) {
        this.ids = new long[Math.max(1, initialCapacity)];
        this.size = 0;
    }

    /** Append a series ID. Must be strictly greater than the last entry. */
    public void append(long id) {
        if (size > 0 && id <= ids[size - 1]) {
            throw new IllegalArgumentException(
                "PostingsList requires strictly-increasing IDs: " + id + " <= " + ids[size - 1]);
        }
        if (size == ids.length) {
            ids = Arrays.copyOf(ids, ids.length * 2);
        }
        ids[size++] = id;
    }

    public int size() {
        return size;
    }

    public long get(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i);
        return ids[i];
    }

    public long[] toArray() {
        return Arrays.copyOf(ids, size);
    }

    /**
     * Sorted-intersection of two PostingsLists. O(n+m). The intersection
     * primitive that label-set predicates are resolved through.
     */
    public static PostingsList intersect(PostingsList a, PostingsList b) {
        PostingsList out = new PostingsList(Math.min(a.size, b.size));
        int i = 0, j = 0;
        while (i < a.size && j < b.size) {
            long ai = a.ids[i], bj = b.ids[j];
            if (ai == bj) {
                out.append(ai);
                i++;
                j++;
            } else if (ai < bj) {
                i++;
            } else {
                j++;
            }
        }
        return out;
    }
}
