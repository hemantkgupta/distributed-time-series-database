package com.hkg.tsdb.wal;

import com.hkg.tsdb.common.LabelSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WriteAheadLogTest {

    @Test
    void appendAndReplay_roundtrip(@TempDir Path tmp) throws IOException {
        LabelSet a = LabelSet.of("service", "checkout", "instance", "pod-1");
        LabelSet b = LabelSet.of("service", "payment", "instance", "pod-2");

        try (WriteAheadLog wal = new WriteAheadLog(tmp)) {
            wal.appendSeries(a.fingerprint(), a);
            wal.appendSample(a.fingerprint(), 1_700_000_000_000L, 42.0);
            wal.appendSeries(b.fingerprint(), b);
            wal.appendSample(b.fingerprint(), 1_700_000_000_000L, 7.5);
            wal.appendSample(a.fingerprint(), 1_700_000_060_000L, 43.0);
            wal.sync();
        }

        List<WalRecord> records = WalReplay.replayAll(tmp);
        assertThat(records).hasSize(5);
        assertThat(records.get(0)).isInstanceOf(WalRecord.Series.class);
        assertThat(((WalRecord.Series) records.get(0)).labels()).isEqualTo(a);
        assertThat(records.get(1)).isInstanceOf(WalRecord.Sample.class);
        assertThat(((WalRecord.Sample) records.get(1)).value()).isEqualTo(42.0);
        assertThat(records.get(4)).isInstanceOf(WalRecord.Sample.class);
        assertThat(((WalRecord.Sample) records.get(4)).value()).isEqualTo(43.0);
    }

    @Test
    void truncatedTail_stopsCleanly(@TempDir Path tmp) throws IOException {
        LabelSet a = LabelSet.of("service", "checkout");
        try (WriteAheadLog wal = new WriteAheadLog(tmp)) {
            wal.appendSeries(a.fingerprint(), a);
            wal.appendSample(a.fingerprint(), 1L, 1.0);
            wal.appendSample(a.fingerprint(), 2L, 2.0);
            wal.sync();
        }

        // Truncate the segment file by a few bytes (mid-CRC of last record).
        Path seg = tmp.resolve("wal-00000000.log");
        long size = Files.size(seg);
        try (var ch = Files.newByteChannel(seg, StandardOpenOption.WRITE)) {
            ch.truncate(size - 3);
        }

        List<WalRecord> records = WalReplay.replayAll(tmp);
        // First two records should still replay; truncated tail dropped.
        assertThat(records).hasSize(2);
    }

    @Test
    void crcMismatch_stopsAtCorruption(@TempDir Path tmp) throws IOException {
        LabelSet a = LabelSet.of("service", "checkout");
        try (WriteAheadLog wal = new WriteAheadLog(tmp)) {
            wal.appendSeries(a.fingerprint(), a);
            wal.appendSample(a.fingerprint(), 1L, 1.0);
            wal.appendSample(a.fingerprint(), 2L, 2.0);
            wal.sync();
        }

        Path seg = tmp.resolve("wal-00000000.log");
        byte[] all = Files.readAllBytes(seg);
        // Flip a byte in the middle (likely in second record's payload).
        all[all.length - 6] ^= 0x55;
        Files.write(seg, all);

        List<WalRecord> records = WalReplay.replayAll(tmp);
        // First record still valid; second (the corrupted one) and onward dropped.
        assertThat(records.size()).isLessThanOrEqualTo(2);
        assertThat(records.get(0)).isInstanceOf(WalRecord.Series.class);
    }

    @Test
    void segmentRotation_keepsAllRecords(@TempDir Path tmp) throws IOException {
        // Tiny segments to force rotation.
        try (WriteAheadLog wal = new WriteAheadLog(tmp, 2048L)) {
            LabelSet a = LabelSet.of("service", "checkout");
            wal.appendSeries(a.fingerprint(), a);
            for (int i = 0; i < 200; i++) {
                wal.appendSample(a.fingerprint(), 1_700_000_000_000L + i * 1000L, i * 0.5);
            }
            wal.sync();
        }
        // Multiple segments should exist.
        try (var stream = Files.newDirectoryStream(tmp, "wal-*.log")) {
            int count = 0;
            for (Path p : stream) count++;
            assertThat(count).isGreaterThan(1);
        }

        List<WalRecord> records = WalReplay.replayAll(tmp);
        // 1 series + 200 samples = 201 records.
        assertThat(records).hasSize(201);
    }

    @Test
    void emptyDir_replaysEmpty(@TempDir Path tmp) throws IOException {
        List<WalRecord> records = WalReplay.replayAll(tmp);
        assertThat(records).isEmpty();
    }

    @Test
    void newWalAfterExisting_doesNotOverwrite(@TempDir Path tmp) throws IOException {
        try (WriteAheadLog wal = new WriteAheadLog(tmp)) {
            LabelSet a = LabelSet.of("service", "checkout");
            wal.appendSeries(a.fingerprint(), a);
            wal.appendSample(a.fingerprint(), 1L, 1.0);
            wal.sync();
        }

        try (WriteAheadLog wal = new WriteAheadLog(tmp)) {
            LabelSet b = LabelSet.of("service", "payment");
            wal.appendSeries(b.fingerprint(), b);
            wal.sync();
        }

        List<WalRecord> records = WalReplay.replayAll(tmp);
        // 2 series + 1 sample = 3 records.
        assertThat(records).hasSize(3);
    }
}
