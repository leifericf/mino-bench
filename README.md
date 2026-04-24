# mino-bench

Benchmarks, stress tests, and fuzz testing for [mino](https://github.com/leifericf/mino).

## Bootstrap

```
git submodule update --init
cd mino && cc -std=c99 -O2 -Isrc -o mino src/*.c main.c -lm && cd ..
```

## Tasks

```
./mino/mino task              # list all tasks
./mino/mino task build        # build mino binary and C benchmarks
./mino/mino task clean        # remove build artifacts

./mino/mino task bench        # mino-level benchmarks (end-to-end)
./mino/mino task bench-c      # C-level benchmarks (raw data structures)
./mino/mino task bench-c-vec  # C benchmark: vectors only
./mino/mino task bench-c-map  # C benchmark: maps only
./mino/mino task bench-c-seq  # C benchmark: sequences only
./mino/mino task bench-c-perf # C benchmark: core profiling

./mino/mino task stress          # GC stress test
./mino/mino task stress-sharded  # all GC stress shards
./mino/mino task fuzz-build      # build fuzz reader

./mino/mino task perf-gate         # run perf regression gate vs baseline (exit 1 on fail)
./mino/mino task perf-gate-record  # re-record baseline from current build
```

## Perf regression gate

`benchmarks/perf_gate.mino` runs a small, stable subset of micro-benches and
compares each measurement to `baselines/perf_baseline.edn`. The gate fails
(exit 1) if any bench is outside the configured threshold (+15% regression
or -30% speedup; a detected speedup is treated as a failure so the baseline
gets refreshed in the same commit that earned the win). mino's CI runs the
gate against the current mino SHA on every push and PR.

When you intentionally change the eval floor (a perf optimization or a
correctness fix with a known cost), record a new baseline in the same
commit:

```
./mino/mino task perf-gate-record
git add baselines/perf_baseline.edn
git commit -m "..."
```

## License

ISC
