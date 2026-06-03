package com.hkg.tsdb.bench;

import com.hkg.tsdb.block.BlockMeta;
import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.node.TsdbNode;
import com.hkg.tsdb.tenant.TenantId;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Deterministic sample generator for smoke-testing the full node path.
 */
public final class DemoLoader {

    private DemoLoader() {
    }

    public static BenchmarkResult load(TsdbNode node, TenantId tenant, Path blockDir, int seriesCount, int samplesPerSeries)
        throws IOException {
        int samples = 0;
        for (int s = 0; s < seriesCount; s++) {
            LabelSet labels = LabelSet.of(
                "__name__", "http_requests_total",
                "service", "svc-" + (s % 5),
                "instance", "pod-" + s
            );
            for (int i = 0; i < samplesPerSeries; i++) {
                node.append(tenant, labels, i * 15_000L, s * 1_000.0 + i);
                samples++;
            }
        }
        BlockMeta meta = node.flush(blockDir);
        return new BenchmarkResult(seriesCount, samples, meta.sampleCount());
    }
}
