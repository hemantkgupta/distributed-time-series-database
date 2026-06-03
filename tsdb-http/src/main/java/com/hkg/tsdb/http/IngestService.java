package com.hkg.tsdb.http;

import com.hkg.tsdb.common.Series;
import com.hkg.tsdb.head.Head;
import com.hkg.tsdb.tenant.TenantId;
import com.hkg.tsdb.tenant.TenantSeriesLimiter;

import java.io.IOException;

/**
 * In-process ingest adapter used by the HTTP handler and integration tests.
 */
public final class IngestService {

    private final Head head;
    private final TenantSeriesLimiter limiter;

    public IngestService(Head head, TenantSeriesLimiter limiter) {
        this.head = head;
        this.limiter = limiter;
    }

    public int ingest(TenantId tenant, String body) throws IOException {
        int accepted = 0;
        for (WriteRequest request : RemoteWriteParser.parse(body)) {
            limiter.accept(tenant, request.labels());
            Series series = head.getOrCreate(request.labels());
            head.append(series.id(), request.timestampMs(), request.value());
            accepted++;
        }
        head.sync();
        return accepted;
    }
}
