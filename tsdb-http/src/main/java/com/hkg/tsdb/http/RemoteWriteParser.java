package com.hkg.tsdb.http;

import com.hkg.tsdb.common.LabelSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for a small Prometheus text exposition inspired write format:
 *
 * metric_name{label="value",other="value"} 1700000000000 42.0
 *
 * It is deliberately not protobuf remote_write. The goal is to exercise the
 * ingestion API and label canonicalisation without pulling in generated code.
 */
public final class RemoteWriteParser {

    private RemoteWriteParser() {
    }

    public static List<WriteRequest> parse(String body) {
        List<WriteRequest> out = new ArrayList<>();
        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(parseLine(line));
        }
        return out;
    }

    static WriteRequest parseLine(String line) {
        int firstSpace = line.indexOf(' ');
        int secondSpace = line.indexOf(' ', firstSpace + 1);
        if (firstSpace < 0 || secondSpace < 0) {
            throw new IllegalArgumentException("line must contain metric, timestamp, value: " + line);
        }
        String metricAndLabels = line.substring(0, firstSpace);
        long ts = Long.parseLong(line.substring(firstSpace + 1, secondSpace));
        double value = Double.parseDouble(line.substring(secondSpace + 1));

        int brace = metricAndLabels.indexOf('{');
        String metric = brace < 0 ? metricAndLabels : metricAndLabels.substring(0, brace);
        if (metric.isEmpty()) throw new IllegalArgumentException("metric name must not be empty");
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("__name__", metric);
        if (brace >= 0) {
            int close = metricAndLabels.lastIndexOf('}');
            if (close < brace) throw new IllegalArgumentException("missing closing brace");
            String inside = metricAndLabels.substring(brace + 1, close);
            if (!inside.isBlank()) {
                for (String kv : inside.split(",")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) throw new IllegalArgumentException("bad label: " + kv);
                    String key = kv.substring(0, eq).trim();
                    String quoted = kv.substring(eq + 1).trim();
                    if (quoted.length() < 2 || quoted.charAt(0) != '"' || quoted.charAt(quoted.length() - 1) != '"') {
                        throw new IllegalArgumentException("label value must be quoted: " + kv);
                    }
                    labels.put(key, quoted.substring(1, quoted.length() - 1));
                }
            }
        }
        return new WriteRequest(LabelSet.of(labels), ts, value);
    }
}
