# Changelog

## Unreleased

- Tracking mino v0.96.8 (Canon-Parity cycle: real `MINO_VOLATILE`
  primitive, stateful-transducer rewrites, lazy-seq recur-on-skip,
  transient reductions, comp/partial/some-fn/every-pred unrolling
  plus `into` 0/1-arg and `unchecked-divide-int`, `iteration` from
  Clojure 1.11, `clojure.core.async` namespace wrap with `merge`/`into`
  renames, the `:refer :all` transitive-drag fix, and the chunked-seq
  family with two new value types and eight primitives). A fresh
  bench run is queued so phase-level wins (volatile in stateful
  transducers, recur-on-skip in lazy combinators, transients in
  `frequencies`/`group-by`, chunked seqs in explicit pipelines) can
  be measured against the pre-cycle baseline.
- Tracking mino v0.74.0 (deferred core surface): `*ns*` is interned as
  a real dynamic var, `bound-fn` / `bound-fn*` capture and replay
  dynamic bindings, `read` accepts an opts map, `clojure.edn/read`
  forces `:read-cond :preserve`, `destructure` surfaces the C-side
  destructuring as a function, and the bundled regex engine grows
  capture groups with `re-matcher` and `re-groups`. The bench suite
  builds and runs against the refreshed submodule.
- Tracking mino v0.73.0 (first-class namespaces): each namespace owns
  its own root binding table, `clojure.core` is the bundled-core
  namespace, vars are first-class objects, auto-resolved keywords and
  namespaced map literals land at read time, and source files use
  `.clj` instead of `.mino`. The bench suite swaps to `.clj` source
  files alongside the migration.

### Added

- Perf regression gate: `benchmarks/perf_gate.mino` runs a small, stable
  subset of micro-benches (identity fn call, let-local lookup, `inc` on
  small int, cons creation, small-vector creation) and compares each
  measurement to `baselines/perf_baseline.edn`. The gate fails on any
  regression greater than +15% or speedup greater than -30% (speedups are
  also treated as failures so the baseline refreshes in the same commit
  that earned the win). Ships with tasks `perf-gate` and
  `perf-gate-record`, plus a GitHub Actions workflow that runs on every
  push and PR. mino's own CI also runs the gate against the current mino
  SHA.
- Fuzz corpus expansion from 4 to 22 seed files covering character
  literals, unicode, deep nesting, large / special numbers, metadata,
  reader conditionals, regex literals, symbol / keyword edges, token
  boundaries, syntax-quote forms, comments, mixed forms, whitespace
  edges, string escapes, and four malformed families (unterminated
  lists / strings / reader macros, stray reader-macro prefixes).
- `fuzz-smoke` task replays every corpus seed through the stdin-mode
  reader and fails on any crash. Runs on every push and PR via the new
  `fuzz` workflow.
- `fuzz-build-libfuzzer` task builds a clang libFuzzer + ASAN + UBSAN
  target. The `fuzz` workflow's scheduled job runs it for 24 hours
  nightly against the corpus and uploads any crash artifacts.

### Changed

- Bumped mino submodule pin to v0.46.0.
- `src/transient.c` added to the bundled mino source list so bench
  binaries link against the transient C API shipped in mino v0.46.0.

## v0.1.0

Initial release. Extracted from the main mino repository.

- Vector, map, and sequence benchmarks
- Performance profiling harness
- Fuzz testing harness with corpus
- GC stress test shards (11 shards)
- Stress and failure stress tests
