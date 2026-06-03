# distributed-time-series-database — implementation plan

This document captures the phase plan + checkpoint detail for the companion code repo. Phases 1-4 are implemented and green. The structure is patterned after the LSM-block-and-postings-index shape that Prometheus pioneered and Mimir / VictoriaMetrics / M3DB inherited.

## §0 How to use this document

Read top-to-bottom. Each phase lists its checkpoints (CPs); each CP names the module, the new types/classes, the tests that must be green before the CP is "done". Phase boundaries are commit boundaries — a green Gradle build with all tests passing at the boundary is the gate.

## §1 Goals and non-goals

**Goals:**
- Implement Gorilla compression end-to-end with realistic compression ratios on stable-cadence time-series data
- Demonstrate the block-and-inverted-index storage shape with persistent disk format
- Demonstrate PromQL-subset query execution with vector + range selectors and basic aggregation
- Demonstrate vertical + horizontal compaction
- Single-tenant, single-node — explicitly not a production engine

**Non-goals:**
- Multi-tenancy at scale (Phase 4 enforces budgets, but does not model a distributed tenant-control plane)
- Replication, HA, Raft consensus
- Object-storage tier (Mimir's S3 store-gateway)
- Distributed query (Monarch's hierarchical pushdown)
- Full PromQL — only the subset needed for end-to-end demo
- Native (JNI) acceleration — pure JDK throughout

**Faithful-to-the-source departures:**

| Aspect | Production | Here |
|---|---|---|
| Block boundary | 2h, configurable | 2h hard-coded |
| Chunks per block | thousands | dozens for tests |
| Postings encoding | Roaring bitmaps / bit-packed postings | Sorted IDs + delta-varint encoding |
| WAL format | record-checksummed segments | segments + per-record CRC32 |
| Compaction trigger | adaptive size-tiered | fixed 2h → 6h → 24h schedule |
| HTTP wire format | Prometheus remote_write protobuf | Prometheus-text-inspired line format |

## §2 The paper's context

The block-and-inverted-index shape that this repo demonstrates was introduced in 2017 by Fabian Reinartz's Prometheus TSDB rewrite (v2.0). The decision to use Gorilla compression in the head (over the existing variable-length encoding) came from Pelkonen et al.'s VLDB 2015 paper. The 2h block boundary, postings-list per label-value, and immutable on-disk block format are the load-bearing decisions; everything since (Mimir's compactor variants, VictoriaMetrics's MergeTree parts, M3DB's mmap Bloom filters) extends or specialises this shape.

## §3 Repo layout

```
distributed-time-series-database/
├── tsdb-common/        # Sample, Series, LabelSet, ChunkRef, SeriesId
├── tsdb-compression/   # GorillaChunk: encode + iterator + bit IO
├── tsdb-wal/           # WAL segment writer + replay iterator
├── tsdb-head/          # Head with per-series open Gorilla chunks + WAL recovery
├── tsdb-index/         # PostingsList + SymbolTable + IndexFile format
├── tsdb-block/         # BlockWriter + BlockReader (chunks + index files)
├── tsdb-query/         # Label matchers + range/instant selectors + rate/group-by
├── tsdb-compact/       # Vertical HA dedup + horizontal adjacent-window compaction
├── tsdb-tenant/        # Per-tenant active-series budget gate
├── tsdb-http/          # Minimal remote-write text parser + ingest adapter
├── tsdb-node/          # In-process node composition
└── tsdb-bench/         # Deterministic demo loader
```

Dependency rule: foundational types (`tsdb-common`) at the bottom. Compression / index don't depend on each other. WAL depends on common + compression (it logs raw float samples but uses compression's bit-IO helpers). Block depends on common + compression + index + head. Query depends on common + block. Compact depends on block.

## §4 Wire format

WAL record:
```
[u8 type][u16 length][u8 payload[length]][u32 crc32_castagnoli]
type=0x01 SAMPLE: u64 series_id || u64 timestamp_ms || f64 value
type=0x02 SERIES: u64 series_id || varint label_count || (varint kv_len || utf8 key=value)* 
```

Block file layout:
```
block/
  meta.json   - { id, minTs, maxTs, seriesCount, sampleCount }
  chunks.bin  - concatenated Gorilla chunk records; offsets in index.bin
  index.bin   - series labels + chunk references; logical postings rebuilt on read
```

## §5 Data structures

```java
// tsdb-common
public record Sample(long timestampMs, double value) {}
public record LabelSet(SortedMap<String,String> labels) {
  String canonical(); // canonical serialization for hashing
  long fingerprint(); // 64-bit hash for series ID
}
public record Series(long id, LabelSet labels) {}
public record ChunkRef(long minTs, long maxTs, long offset, int length) {}
public record SeriesId(long value) {}

// tsdb-compression
public final class GorillaChunk {
  void append(long timestampMs, double value);
  int sampleCount();
  long minTs();
  long maxTs();
  byte[] bytes();
  static GorillaChunkReader read(byte[] bytes);
}

// tsdb-wal
public final class WriteAheadLog implements Closeable {
  void appendSeries(long seriesId, LabelSet labels) throws IOException;
  void appendSample(long seriesId, long ts, double value) throws IOException;
  void sync() throws IOException;
  static Iterator<WalRecord> replay(Path walDir);
}

// tsdb-head
public final class Head implements Closeable {
  Series getOrCreate(LabelSet labels);
  void append(long seriesId, long ts, double value) throws IOException;
  Iterable<Sample> read(long seriesId, long minTs, long maxTs);
  static Head recover(Path walDir);
}
```

## §6 Protocols (Phase 1 — Append/Recover flow)

```
Client.append(labels, ts, value):
  Head.getOrCreate(labels):              -> assigns series_id if first time
    if first time: WAL.appendSeries
  Head.append(series_id, ts, value):
    WAL.appendSample(series_id, ts, value)
    series.openChunk.append(ts, value)   # GorillaChunk encode
    if chunk full: rotate chunk

Crash recovery:
  Head.recover(walDir):
    replay WAL records in order:
      SERIES record -> assign series_id, register label set
      SAMPLE record -> series.openChunk.append
```

## §7 Persistence

Phase 1 persistence is WAL-only. The head is rebuilt from WAL on startup. Persistent blocks are Phase 2 (head flushes to disk every 2h, then WAL is truncated past the flushed window).

## §8 Phasing

See README.md for the canonical phase list. Each CP is a Gradle module + tests that produce a green build.

Implemented checkpoints:

- Phase 1 (CP1-CP5): foundational types, Gorilla compression, WAL, head, WAL recovery.
- Phase 2 (CP6-CP10): postings codec, block writer/reader, head flush.
- Phase 3 (CP11-CP15): label matchers, selectors, rate/group-by, vertical and horizontal compaction.
- Phase 4 (CP16-CP20): tenant cardinality budgets, minimal remote-write parser, node composition, demo loader.

## §9 Failure modes

| Failure | Detection | Recovery |
|---|---|---|
| Process crash during head append | WAL record present, head missing | WAL replay on next startup rebuilds head |
| Partial WAL record on crash | CRC32 mismatch on last record | Truncate to last good record |
| Corrupt block file | meta.json checksum mismatch | Block excluded from queries; alert |
| WAL disk full | Append throws IOException | Caller policy (reject ingest); Phase 4 returns 503 |
| Series ID collision | Should never happen with 64-bit fingerprint | Test asserts injection bias; panic if observed |

## §10 ADRs to be authored

- 0001: Why immutable 2h blocks (not mutable or longer windows)
- 0002: Why Gorilla in head AND in flushed blocks (not different codecs)
- 0003: Why pure-Java Gorilla bit-IO (not native LZ4 / Roaring)
- 0004: Why postings-list per label-value (not bitmap-indexed labels)
- 0005: Why fixed 2h compaction schedule (not adaptive size-tiered)

## §11 Test plan

- `tsdb-compression` — GorillaChunk encode/decode roundtrip; stable-cadence ratio test (>10× on synthetic 60s data); jitter tolerance test
- `tsdb-wal` — append + replay roundtrip; CRC-detect corruption; segment rotation
- `tsdb-head` — getOrCreate idempotence; append-then-read; crash + recover roundtrip
- `tsdb-index` — postings encode/decode; intersection/union correctness; label-value exact match
- `tsdb-block` — write + read roundtrip; label match correctness; head flush
- `tsdb-query` — `=`, `=~`, range selectors, `rate()` and `sum by`
- `tsdb-compact` — vertical HA dedup and horizontal adjacent-window merge
- `tsdb-tenant` — active-series budget acceptance/rejection
- `tsdb-http` — minimal line parser and ingest-to-head adapter
- `tsdb-node` / `tsdb-bench` — append, flush, query, deterministic sample-count smoke test

## §12 Configuration knobs

```properties
# Phase 1 defaults
tsdb.wal.dir=/var/lib/tsdb/wal
tsdb.wal.segment.maxBytes=134217728     # 128 MiB
tsdb.head.chunkMaxSamples=120           # Gorilla chunk closes at 120 samples
tsdb.head.chunkMaxAgeMillis=7200000     # or 2h, whichever first

# Phase 2 additions
tsdb.block.dir=/var/lib/tsdb/blocks
tsdb.block.windowMillis=7200000         # 2h
```

## §13 Departures

- No replication; no Raft; single-node
- No JNI; pure JDK
- Minimal remote-write parser, not protobuf
- PromQL subset only; in-process Java query API
- Object-storage tier is represented by local immutable block directories

## §14 Glossary

- **Sample** — a `(timestamp, float64)` data point
- **Series** — a label-set with a stable integer ID
- **LabelSet** — canonical sorted set of `(key, value)` label pairs
- **Chunk** — a Gorilla-compressed batch of samples (default 120)
- **Head** — the in-memory tier holding open Gorilla chunks per series
- **Block** — an immutable 2h slice of time, stored as chunks + index on disk
- **Postings list** — sorted list of series IDs for a label-value
- **Symbol table** — deduplicated label string table per block
- **WAL** — Write-Ahead Log; durable record of head appends for crash recovery

## §15 Connection to the arc

- Companion blog (synthesis): [`raw-blog/distributed-time-series-database/distributed-time-series-database.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-time-series-database/distributed-time-series-database.md)
- Companion blog (full): [`...-full.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-time-series-database/distributed-time-series-database-full.md)
- Design walkthrough: [`wiki/my-explanations/distributed-time-series-database.md`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-time-series-database.md)

## §16 Sanity checks (a builder should be able to answer without re-reading)

1. Why 120 samples per Gorilla chunk? — Trades open-chunk overhead vs in-memory random-access; matches Prometheus default and gives ~120-minute coverage at 1-min scrape rate.
2. Why a WAL even though blocks are durable? — Recent samples (post last flush, before next block) only live in the head; without WAL, crashes lose them.
3. Why is the head's series-ID assignment a hash, not a sequence? — Stable across process restarts; same label-set always yields same ID; no global counter to coordinate.
4. Why are blocks immutable? — Sequential writes only; Gorilla doesn't support in-place update; trivially cacheable; simple compaction.
5. Why is the postings list sorted? — Enables O(n+m) intersection across multiple label-value lookups.
6. Why 2h blocks specifically? — Compromise: larger = fewer files to query; smaller = less in-flight data in head, faster compaction, smaller index churn.
7. Why does the symbol table live per-block, not globally? — Per-block immutability; merging across blocks (horizontal compaction) is where global dedup happens.
