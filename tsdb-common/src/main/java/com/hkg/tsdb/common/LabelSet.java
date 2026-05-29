package com.hkg.tsdb.common;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Canonical, sorted set of (key, value) label pairs identifying a time series.
 *
 * Equality and hash are based on the canonical sorted form. The 64-bit
 * fingerprint is a stable hash used as series ID.
 */
public final class LabelSet {

    private final SortedMap<String, String> labels;
    private final long fingerprint;

    private LabelSet(SortedMap<String, String> labels) {
        this.labels = Collections.unmodifiableSortedMap(labels);
        this.fingerprint = computeFingerprint(labels);
    }

    public static LabelSet of(Map<String, String> raw) {
        Objects.requireNonNull(raw, "labels");
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("LabelSet must contain at least one label");
        }
        for (var e : raw.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) {
                throw new IllegalArgumentException("Label key must be non-empty");
            }
            if (e.getValue() == null) {
                throw new IllegalArgumentException("Label value must not be null");
            }
        }
        return new LabelSet(new TreeMap<>(raw));
    }

    public static LabelSet of(String... kv) {
        if (kv.length == 0 || (kv.length & 1) != 0) {
            throw new IllegalArgumentException("of(...) requires non-empty even number of args");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return of(m);
    }

    public SortedMap<String, String> labels() {
        return labels;
    }

    public String get(String key) {
        return labels.get(key);
    }

    public long fingerprint() {
        return fingerprint;
    }

    public String canonical() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (var e : labels.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(quote(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    // FNV-1a 64 over the canonical form. Cheap, stable across runs, well-mixed.
    private static long computeFingerprint(SortedMap<String, String> labels) {
        long h = 0xcbf29ce484222325L;
        for (var e : labels.entrySet()) {
            h = fnvBytes(h, e.getKey().getBytes(StandardCharsets.UTF_8));
            h = fnvByte(h, (byte) '=');
            h = fnvBytes(h, e.getValue().getBytes(StandardCharsets.UTF_8));
            h = fnvByte(h, (byte) ';');
        }
        return h;
    }

    private static long fnvBytes(long h, byte[] data) {
        for (byte b : data) h = fnvByte(h, b);
        return h;
    }

    private static long fnvByte(long h, byte b) {
        h ^= (b & 0xffL);
        h *= 0x100000001b3L;
        return h;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LabelSet that)) return false;
        return fingerprint == that.fingerprint && labels.equals(that.labels);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(fingerprint);
    }

    @Override
    public String toString() {
        return canonical();
    }
}
