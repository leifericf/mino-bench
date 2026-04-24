# Changelog

## Unreleased

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

### Changed

- Bumped mino submodule pin to v0.46.0.

## v0.1.0

Initial release. Extracted from the main mino repository.

- Vector, map, and sequence benchmarks
- Performance profiling harness
- Fuzz testing harness with corpus
- GC stress test shards (11 shards)
- Stress and failure stress tests
