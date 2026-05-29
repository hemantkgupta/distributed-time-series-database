# Distributed Time-Series Database

A Java 17 multi-module reference implementation of a label-indexed, Gorilla-compressed time-series database, built around the **block-and-inverted-index** shape pioneered by Prometheus and now standard across the LGTM stack (Grafana Mimir), VictoriaMetrics, and M3DB.

Companion code for the [`distributed-time-series-database`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-time-series-database/distributed-time-series-database.md) and [`-full`](https://github.com/hemantkgupta/CSE-Raw/blob/main/raw-blog/distributed-time-series-database/distributed-time-series-database-full.md) blog posts in the CSE wiki.

## Status

**Phase 1 complete** — Gorilla compression (delta-of-delta timestamps + XOR floats), Write-Ahead Log with append + replay, in-memory head with per-series Gorilla chunks, and crash recovery via WAL replay. Phases 2-4 scaffolded with a detailed implementation plan; see [`docs/implementation-plan.md`](docs/implementation-plan.md).

The phase plan that fits this topic:

**Phase 1 — Foundation (compression + WAL + head):**
* **CP1** — `tsdb-common`: foundational types (Sample, Series, LabelSet, ChunkRef)
* **CP2** — `tsdb-compression`: Gorilla chunk — delta-of-delta timestamps + XOR float encoding
* **CP3** — `tsdb-wal`: Write-Ahead Log — append + segment rotation + replay iterator
* **CP4** — `tsdb-head`: in-memory head with per-series open Gorilla chunks
* **CP5** — `tsdb-head`: crash recovery — rebuild head from WAL on startup

**Phase 2 — Indexed persistent blocks (scaffolded, planned):**
* **CP6** — `tsdb-index`: postings list format (sorted IDs + varbyte deltas)
* **CP7** — `tsdb-block`: block writer (chunks dir + index file)
* **CP8** — `tsdb-block`: block reader (label match → series → chunks)
* **CP9** — `tsdb-head`: flush head → block at 2h boundary
* **CP10** — `tsdb-index`: Roaring-bitmap-style sparse-bitmap postings

**Phase 3 — Query + compaction (planned):**
* **CP11** — `tsdb-query`: label-matcher engine (`=`, `!=`, `=~`, `!~`)
* **CP12** — `tsdb-query`: vector + range selectors + `rate()` / `sum()` / `avg()`
* **CP13** — `tsdb-query`: aggregation operators (`sum by`, `max by`, `count by`)
* **CP14** — `tsdb-compact`: vertical compaction (HA-replica dedup)
* **CP15** — `tsdb-compact`: horizontal compaction (merge adjacent blocks; shrink index)

**Phase 4 — Multi-tenant + HTTP + demo (planned):**
* **CP16** — `tsdb-tenant`: tenant isolation + cardinality budget
* **CP17** — `tsdb-http`: HTTP ingest (subset of Prometheus `remote_write` semantics)
* **CP18** — `tsdb-http`: HTTP query (subset of PromQL)
* **CP19** — `tsdb-node`: end-to-end binary (ingest + query + background compact)
* **CP20** — `tsdb-bench`: demo loader (1M samples, query latency, compact)

## Build

Requires JDK 17 (pinned via `jenv local 17.0`).

```sh
./gradlew build
./gradlew :tsdb-compression:test
./gradlew :tsdb-head:test
```

## Module Structure

```
distributed-time-series-database/
├── tsdb-common/        # Sample, Series, LabelSet, ChunkRef value types
├── tsdb-compression/   # Gorilla chunk — delta-of-delta + XOR encoding
├── tsdb-wal/           # Write-Ahead Log — append + segment rotation + replay
├── tsdb-head/          # In-memory head with per-series Gorilla chunks + recovery
├── tsdb-index/         # Postings list format (scaffolded)
├── tsdb-block/         # Persistent block format (scaffolded)
└── docs/               # implementation-plan.md, decisions.md
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

- **Single-tenant by default.** Multi-tenancy is Phase 4 work; Phases 1-2 assume one logical tenant.
- **JDK-only, no JNI.** No native Roaring, no LZ4 native; bitmap postings are pure-Java in Phase 2.
- **No replication, no clustering, no Raft.** Single-node by design. Mimir-style horizontal scaling is out of scope.
- **No HTTP wire format yet** — Phase 4 will add a subset of Prometheus `remote_write`; Phases 1-2 use in-process Java APIs.
- **2-hour blocks, not configurable.** Hard-coded to match Prometheus's default; production engines tune this.

## License

Internal reference implementation; not for external distribution.
