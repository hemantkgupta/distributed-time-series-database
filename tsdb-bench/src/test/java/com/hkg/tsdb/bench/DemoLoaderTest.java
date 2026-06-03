package com.hkg.tsdb.bench;

import com.hkg.tsdb.node.TsdbNode;
import com.hkg.tsdb.tenant.CardinalityBudget;
import com.hkg.tsdb.tenant.TenantId;
import com.hkg.tsdb.tenant.TenantSeriesLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DemoLoaderTest {

    @Test
    void demoLoaderFlushesExpectedSampleCount(@TempDir Path tmp) throws IOException {
        try (TsdbNode node = new TsdbNode(tmp.resolve("wal"), new TenantSeriesLimiter(new CardinalityBudget(100)))) {
            BenchmarkResult result = DemoLoader.load(node, new TenantId("demo"), tmp.resolve("block"), 8, 25);

            assertThat(result.seriesCount()).isEqualTo(8);
            assertThat(result.sampleCount()).isEqualTo(200);
            assertThat(result.blockSampleCount()).isEqualTo(200);
        }
    }
}
