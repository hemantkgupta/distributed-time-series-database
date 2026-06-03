package com.hkg.tsdb.query;

import com.hkg.tsdb.block.BlockReader;
import com.hkg.tsdb.common.Sample;
import com.hkg.tsdb.common.Series;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query engine over immutable blocks.
 *
 * The supported subset is intentionally small but load-bearing: range selectors,
 * instant selectors, counter rate, and group-by aggregation. That demonstrates
 * why TSDB blocks expose label metadata and time-ranged chunk refs to the query
 * layer instead of behaving like a generic key-value scan.
 */
public final class QueryEngine {

    private final List<BlockReader> blocks;

    public QueryEngine(List<BlockReader> blocks) {
        this.blocks = List.copyOf(blocks);
    }

    public List<SeriesRange> range(List<LabelMatcher> matchers, long startTs, long endTs) throws IOException {
        Map<Series, List<Sample>> merged = new LinkedHashMap<>();
        for (BlockReader block : blocks) {
            for (Map.Entry<Series, List<Sample>> e : block.all(startTs, endTs).entrySet()) {
                if (!matches(e.getKey(), matchers)) continue;
                merged.computeIfAbsent(e.getKey(), ignored -> new ArrayList<>()).addAll(e.getValue());
            }
        }
        List<SeriesRange> out = new ArrayList<>();
        for (Map.Entry<Series, List<Sample>> e : merged.entrySet()) {
            List<Sample> samples = e.getValue().stream()
                .sorted(Comparator.comparingLong(Sample::timestampMs))
                .toList();
            out.add(new SeriesRange(e.getKey(), samples));
        }
        out.sort(Comparator.comparingLong(r -> r.series().id()));
        return out;
    }

    public List<SeriesPoint> instant(List<LabelMatcher> matchers, long ts) throws IOException {
        List<SeriesPoint> out = new ArrayList<>();
        for (SeriesRange range : range(matchers, 0L, ts)) {
            if (!range.samples().isEmpty()) {
                out.add(new SeriesPoint(range.series(), range.samples().get(range.samples().size() - 1)));
            }
        }
        return out;
    }

    public Map<String, Double> rateSumBy(List<LabelMatcher> matchers, String groupLabel, long startTs, long endTs)
        throws IOException {
        Map<String, Double> out = new LinkedHashMap<>();
        for (SeriesRange range : range(matchers, startTs, endTs)) {
            if (range.samples().size() < 2) continue;
            String group = range.series().labels().get(groupLabel);
            if (group == null) group = "";
            out.merge(group, counterRate(range.samples()), Double::sum);
        }
        return out;
    }

    public Map<String, Double> avgBy(List<LabelMatcher> matchers, String groupLabel, long startTs, long endTs)
        throws IOException {
        Map<String, double[]> totals = new LinkedHashMap<>();
        for (SeriesRange range : range(matchers, startTs, endTs)) {
            String group = range.series().labels().get(groupLabel);
            if (group == null) group = "";
            double sum = range.samples().stream().mapToDouble(Sample::value).sum();
            double[] agg = totals.computeIfAbsent(group, ignored -> new double[2]);
            agg[0] += sum;
            agg[1] += range.samples().size();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : totals.entrySet()) {
            out.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
        }
        return out;
    }

    public static double counterRate(List<Sample> samples) {
        if (samples.size() < 2) return 0.0;
        double increase = 0.0;
        double previous = samples.get(0).value();
        for (int i = 1; i < samples.size(); i++) {
            double current = samples.get(i).value();
            if (current >= previous) {
                increase += current - previous;
            } else {
                increase += current; // counter reset
            }
            previous = current;
        }
        long elapsedMs = samples.get(samples.size() - 1).timestampMs() - samples.get(0).timestampMs();
        return elapsedMs <= 0 ? 0.0 : increase / (elapsedMs / 1000.0);
    }

    private static boolean matches(Series series, List<LabelMatcher> matchers) {
        for (LabelMatcher matcher : matchers) {
            if (!matcher.matches(series.labels())) return false;
        }
        return true;
    }
}
