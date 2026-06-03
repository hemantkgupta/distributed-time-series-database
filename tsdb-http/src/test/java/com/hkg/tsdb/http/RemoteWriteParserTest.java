package com.hkg.tsdb.http;

import com.hkg.tsdb.head.Head;
import com.hkg.tsdb.tenant.CardinalityBudget;
import com.hkg.tsdb.tenant.TenantId;
import com.hkg.tsdb.tenant.TenantSeriesLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteWriteParserTest {

    @Test
    void parserAddsMetricNameAsLabel() {
        WriteRequest request = RemoteWriteParser.parse(
            "http_requests_total{service=\"checkout\",status=\"500\"} 1700000000000 42.0").get(0);

        assertThat(request.labels().get("__name__")).isEqualTo("http_requests_total");
        assertThat(request.labels().get("service")).isEqualTo("checkout");
        assertThat(request.value()).isEqualTo(42.0);
    }

    @Test
    void ingestServiceWritesAcceptedSamplesToHead(@TempDir Path tmp) throws IOException {
        try (Head head = new Head(tmp.resolve("wal"))) {
            IngestService ingest = new IngestService(head, new TenantSeriesLimiter(new CardinalityBudget(10)));
            int accepted = ingest.ingest(new TenantId("team-a"),
                "http_requests_total{service=\"checkout\",status=\"200\"} 1000 1.0\n"
                    + "http_requests_total{service=\"checkout\",status=\"200\"} 2000 2.0\n");

            assertThat(accepted).isEqualTo(2);
            assertThat(head.seriesCount()).isEqualTo(1);
        }
    }
}
