# mino-bench

Benchmarks, stress tests, and fuzz testing for [mino](https://github.com/leifericf/mino).

## Bootstrap

```
git submodule update --init
cd mino
printf 'static const char *core_mino_src =\n' > src/core_mino.h
sed 's/\\/\\\\/g; s/"/\\"/g; s/^/    "/; s/$/\\n"/' src/core.clj >> src/core_mino.h
printf '    ;\n' >> src/core_mino.h
cc -std=c99 -O2 \
  -Isrc -Isrc/public -Isrc/runtime -Isrc/gc -Isrc/eval \
  -Isrc/collections -Isrc/prim -Isrc/async -Isrc/interop \
  -Isrc/diag -Isrc/vendor/imath \
  -o mino \
  src/public/*.c src/runtime/*.c src/gc/*.c src/eval/*.c \
  src/collections/*.c src/prim/*.c src/async/*.c src/interop/*.c \
  src/regex/*.c src/diag/*.c src/vendor/imath/*.c \
  main.c -lm
cd ..
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
./mino/mino task fuzz-build             # build stdin-mode fuzz reader
./mino/mino task fuzz-build-libfuzzer   # build libFuzzer-instrumented reader (clang)
./mino/mino task fuzz-smoke             # replay every corpus seed through the reader

./mino/mino task perf-gate         # run perf regression gate vs baseline (exit 1 on fail)
./mino/mino task perf-gate-record  # re-record baseline from current build
```

## Perf regression gate

`benchmarks/perf_gate.clj` runs a small, stable subset of micro-benches and
compares each measurement to `baselines/perf_baseline.edn`. The gate fails
(exit 1) if any bench is outside the configured threshold (local: +15%
regression / -30% speedup; CI: +50% regression / -30% speedup; a detected
speedup is treated as a failure so the baseline gets refreshed in the same
commit that earned the win). mino's CI runs the gate against the current
mino SHA on every push and PR.

The committed baseline is recorded on `ubuntu-22.04` GitHub-hosted runners,
which is the same hardware class the gate runs on. Local runs on developer
machines will typically show large `:speedup` deltas against this baseline,
because developer hardware is faster than the shared GH runners; this is
expected and not a regression. Only re-record the baseline from a CI run
unless you explicitly want a local-grounded baseline for ad-hoc work.

To re-record the CI-grounded baseline, trigger the perf-gate workflow with
`record=true`:

```
gh workflow run perf-gate.yml -f record=true
gh run download <run-id> -n perf_baseline
git add baselines/perf_baseline.edn
git commit -m "..."
```

When you intentionally change the eval floor (a perf optimization or a
correctness fix with a known cost), record a new baseline in the same
commit; for local-only iteration there is also a host-side recorder:

```
./mino/mino task perf-gate-record
git add baselines/perf_baseline.edn
git commit -m "..."
```

## Fuzzing

The reader fuzz corpus lives under `fuzz/corpus/` and covers 22 seed
categories: atoms, collections, nested forms, quotes, character
literals, unicode strings, deep nesting, large numbers, special
floats, metadata, reader conditionals, regex literals, symbol/keyword
edge cases, token boundaries, syntax-quote forms, comments, mixed
forms, whitespace edges, string escapes, and four malformed families
(unterminated lists / strings / reader macros).

`fuzz-smoke` replays every seed through the stdin-mode reader and
fails on any crash. It runs on every push and PR.

`fuzz-build-libfuzzer` builds a clang libFuzzer + ASAN + UBSAN target;
the nightly `fuzz` workflow runs it for 24 hours against the corpus.
Local use:

```
CC=clang ./mino/mino task fuzz-build-libfuzzer
./fuzz/fuzz_reader_libfuzzer -max_total_time=60 -max_len=65536 fuzz/corpus
```

## License

ISC
