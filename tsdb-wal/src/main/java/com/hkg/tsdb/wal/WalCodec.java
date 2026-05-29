package com.hkg.tsdb.wal;

import com.hkg.tsdb.common.LabelSet;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serialization of WAL record payloads.
 *
 * SERIES payload: u64 seriesId | varint labelCount | (varint keyLen | key utf8 | varint valLen | val utf8)*
 * SAMPLE payload: u64 seriesId | u64 timestampMs | f64 value
 */
final class WalCodec {

    private WalCodec() {}

    record SerializedRecord(byte[] payload) {}

    static SerializedRecord encodeSeries(long seriesId, LabelSet labels) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeLong(seriesId);
            writeVarInt(out, labels.labels().size());
            for (Map.Entry<String, String> e : labels.labels().entrySet()) {
                byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] v = e.getValue().getBytes(StandardCharsets.UTF_8);
                writeVarInt(out, k.length);
                out.write(k);
                writeVarInt(out, v.length);
                out.write(v);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new SerializedRecord(bos.toByteArray());
    }

    static SerializedRecord encodeSample(long seriesId, long ts, double value) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(24);
        try (DataOutputStream out = new DataOutputStream(bos)) {
            out.writeLong(seriesId);
            out.writeLong(ts);
            out.writeDouble(value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new SerializedRecord(bos.toByteArray());
    }

    static WalRecord decode(byte type, byte[] payload) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(payload));
        if (type == WalRecord.SERIES_TYPE) {
            long seriesId = in.readLong();
            int labelCount = readVarInt(in);
            Map<String, String> labels = new LinkedHashMap<>();
            for (int i = 0; i < labelCount; i++) {
                int klen = readVarInt(in);
                byte[] kb = in.readNBytes(klen);
                int vlen = readVarInt(in);
                byte[] vb = in.readNBytes(vlen);
                labels.put(new String(kb, StandardCharsets.UTF_8), new String(vb, StandardCharsets.UTF_8));
            }
            return new WalRecord.Series(seriesId, LabelSet.of(labels));
        } else if (type == WalRecord.SAMPLE_TYPE) {
            long seriesId = in.readLong();
            long ts = in.readLong();
            double value = in.readDouble();
            return new WalRecord.Sample(seriesId, ts, value);
        }
        throw new IOException("Unknown WAL record type: 0x" + Integer.toHexString(type & 0xff));
    }

    private static void writeVarInt(DataOutputStream out, int v) throws IOException {
        if (v < 0) throw new IOException("varint cannot encode negative: " + v);
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v & 0x7F);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 28) throw new IOException("varint too long");
        }
    }
}
