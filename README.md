# Distributed Time-Series Database

A Java 17 multi-module reference implementation of a label-indexed, Gorilla-compressed time-series database, built around the **block-and-inverted-index** shape pioneered by Prometheus and now standard across the LGTM stack (Grafana Mimir), VictoriaMetrics, and M3DB.

Companion code for the [`distributed-time-series-database`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-time-series-database/distributed-time-series-database.md) and [`-full`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-time-series-database/distributed-time-series-database-full.md) blog posts in the CSE wiki.

## Status

**Mode B companion repo complete through Phase 4** — 20 checkpoints across 12 modules. The repo now demonstrates the full single-node teaching path: Gorilla compression, WAL-backed head, persistent immutable blocks with label postings, range/rate query primitives, vertical and horizontal compaction, per-tenant cardinality budgets, a minimal remote-write text ingest adapter, node composition, and deterministic demo loading.

The phase plan that fits this topic:

**Phase 1 — Foundation (compression + WAL + head):**
* **CP1** — `tsdb-common`: foundational types (Sample, Series, LabelSet, ChunkRef)
* **CP2** — `tsdb-compression`: Gorilla chunk — delta-of-delta timestamps + XOR float encoding
* **CP3** — `tsdb-wal`: Write-Ahead Log — append + segment rotation + replay iterator
* **CP4** — `tsdb-head`: in-memory head with per-series open Gorilla chunks
* **CP5** — `tsdb-head`: crash recovery — rebuild head from WAL on startup

**Phase 2 — Indexed persistent blocks:**
* **CP6** — `tsdb-index`: postings list format (sorted IDs + varbyte deltas)
* **CP7** — `tsdb-block`: block writer (chunks dir + index file)
* **CP8** — `tsdb-block`: block reader (label match → series → chunks)
* **CP9** — `tsdb-head`: flush head → block at 2h boundary
* **CP10** — `tsdb-index`: compact sorted postings primitives

**Phase 3 — Query + compaction:**
* **CP11** — `tsdb-query`: label-matcher engine (`=`, `!=`, `=~`, `!~`)
* **CP12** — `tsdb-query`: instant + range selectors
* **CP13** — `tsdb-query`: `rate()`, `sum by`, and `avg by`
* **CP14** — `tsdb-compact`: vertical compaction (HA-replica dedup)
* **CP15** — `tsdb-compact`: horizontal compaction (merge adjacent blocks)

**Phase 4 — Multi-tenant + HTTP + demo:**
* **CP16** — `tsdb-tenant`: tenant isolation + cardinality budget
* **CP17** — `tsdb-http`: minimal Prometheus-text remote-write parser
* **CP18** — `tsdb-node`: query integration over flushed blocks
* **CP19** — `tsdb-node`: end-to-end composition (tenant gate + head + block + query)
* **CP20** — `tsdb-bench`: deterministic demo loader

## Build

Requires JDK 17 (pinned via `jenv local 17.0`).

```sh
./gradlew build
./gradlew :tsdb-compression:test
./gradlew :tsdb-node:test
```

## Module Structure

```
distributed-time-series-database/
├── tsdb-common/        # Sample, Series, LabelSet, ChunkRef value types
├── tsdb-compression/   # Gorilla chunk — delta-of-delta + XOR encoding
├── tsdb-wal/           # Write-Ahead Log — append + segment rotation + replay
├── tsdb-head/          # In-memory head with per-series Gorilla chunks + recovery
├── tsdb-index/         # Postings list + delta-varint codec + inverted index
├── tsdb-block/         # Persistent block writer/reader: meta.json + chunks.bin + index.bin
├── tsdb-query/         # Label matchers, range/instant selectors, rate and group-by
├── tsdb-compact/       # Vertical HA dedup + horizontal adjacent-window compaction
├── tsdb-tenant/        # Per-tenant active-series cardinality budgets
├── tsdb-http/          # Minimal remote-write text parser + ingest adapter
├── tsdb-node/          # In-process node composition for ingest, flush, query
├── tsdb-bench/         # Deterministic demo loader
└── docs/               # implementation-plan.md
```

## Architectural Anchors

The implementation follows the engineering decisions captured in the wiki:

- **Gorilla compression** — [`concepts/gorilla-compression`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/gorilla-compression.md), [`concepts/delta-of-delta-encoding`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/delta-of-delta-encoding.md)
- **Postings list** — [`concepts/postings-list`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/postings-list.md) — inverted-index primitive used by Prometheus, Mimir, M3DB
- **Cardinality budget** — [`concepts/cardinality-budgeting`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/concepts/cardinality-budgeting.md)
- **Compaction** — [`patterns/compaction-strategies`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/compaction-strategies.md), vertical (HA-replica dedup) + horizontal (index shrink)
- **System reference** — [`systems/prometheus-tsdb`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/systems/prometheus-tsdb.md), [`systems/grafana-mimir`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/systems/grafana-mimir.md), [`systems/victoriametrics`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/systems/victoriametrics.md)
- **Design walkthrough** — [`my-explanations/distributed-time-series-database`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/my-explanations/distributed-time-series-database.md)

## Pedagogical scope (vs production reality)

This is a reference implementation focused on the load-bearing storage-engine decisions. Departures from production:

- **Single-node by design.** Multi-tenancy means per-tenant cardinality budgets, not a replicated cluster.
- **JDK-only, no JNI.** No native Roaring, no LZ4 native; postings are pure-Java sorted IDs with delta-varint encoding.
- **No replication, no clustering, no Raft.** Single-node by design. Mimir-style horizontal scaling is out of scope.
- **Minimal HTTP-adjacent wire format.** `tsdb-http` parses a Prometheus-text-inspired line format, not protobuf `remote_write`.
- **2-hour blocks, not configurable.** Hard-coded to match Prometheus's default; production engines tune this.

## License

Internal reference implementation; not for external distribution.
