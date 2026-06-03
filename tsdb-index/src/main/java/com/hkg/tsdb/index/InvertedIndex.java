package com.hkg.tsdb.index;

import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.common.Series;

import java.util.Arrays;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * In-memory label-value inverted index used by persistent blocks and query tests.
 *
 * A production block index writes this to a compact binary symbol table and
 * postings section. This class keeps the same logical contract: every
 * (label-key,label-value) pair maps to sorted series IDs; multi-label predicates
 * are resolved by postings-list intersection.
 */
public final class InvertedIndex {

    private final NavigableMap<String, NavigableSet<Long>> postings = new TreeMap<>();
    private final NavigableMap<Long, LabelSet> labelsBySeriesId = new TreeMap<>();

    public void add(Series series) {
        labelsBySeriesId.put(series.id(), series.labels());
        for (Map.Entry<String, String> e : series.labels().labels().entrySet()) {
            postings.computeIfAbsent(key(e.getKey(), e.getValue()), ignored -> new TreeSet<>())
                .add(series.id());
        }
    }

    public Optional<LabelSet> labels(long seriesId) {
        return Optional.ofNullable(labelsBySeriesId.get(seriesId));
    }

    public PostingsList postings(String labelName, String labelValue) {
        NavigableSet<Long> ids = postings.get(key(labelName, labelValue));
        return ids == null ? new PostingsList(0) : toPostings(ids);
    }

    public PostingsList allSeries() {
        return toPostings(labelsBySeriesId.navigableKeySet());
    }

    public PostingsList exactMatch(Map<String, String> labels) {
        if (labels.isEmpty()) {
            return allSeries();
        }
        PostingsList result = null;
        for (Map.Entry<String, String> e : labels.entrySet()) {
            PostingsList next = postings(e.getKey(), e.getValue());
            result = result == null ? next : PostingsList.intersect(result, next);
            if (result.isEmpty()) {
                return result;
            }
        }
        return result == null ? new PostingsList(0) : result;
    }

    private static String key(String labelName, String labelValue) {
        return labelName + "\u0000" + labelValue;
    }

    private static PostingsList toPostings(Iterable<Long> ids) {
        long[] arr = new long[8];
        int n = 0;
        for (long id : ids) {
            if (n == arr.length) arr = Arrays.copyOf(arr, arr.length * 2);
            arr[n++] = id;
        }
        return PostingsList.fromSorted(Arrays.copyOf(arr, n));
    }
}
