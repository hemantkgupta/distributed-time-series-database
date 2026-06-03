package com.hkg.tsdb.node;

import com.hkg.tsdb.common.LabelSet;
import com.hkg.tsdb.query.LabelMatcher;
import com.hkg.tsdb.tenant.CardinalityBudget;
import com.hkg.tsdb.tenant.TenantId;
import com.hkg.tsdb.tenant.TenantSeriesLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TsdbNodeTest {

    @Test
    void appendFlushQueryEndToEnd(@TempDir Path tmp) throws IOException {
        try (TsdbNode node = new TsdbNode(tmp.resolve("wal"), new TenantSeriesLimiter(new CardinalityBudget(10)))) {
            TenantId tenant = new TenantId("team-a");
            LabelSet labels = LabelSet.of("__name__", "http_requests_total", "service", "checkout", "status", "200");
            for (int i = 0; i < 10; i++) {
                node.append(tenant, labels, i * 1_000L, i);
            }
            node.flush(tmp.resolve("block"));

            var ranges = node.range(List.of(LabelMatcher.eq("service", "checkout")), 0L, 9_000L);

            assertThat(ranges).hasSize(1);
            assertThat(ranges.get(0).samples()).hasSize(10);
        }
    }
}
