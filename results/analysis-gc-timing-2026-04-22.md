# GC timing analysis — 2026-04-22

## Context

After v0.40.0 the eval floor dropped from ~6 µs to ~1 µs. The next
architectural question is what to invest in: a generational /
incremental GC or a prim ABI + inline caching. The decision was
blocked on one missing datum — wall-clock time spent in GC.

This pass added the instrumentation (`:total-gc-ns` and `:max-gc-ns`
on the existing `gc-stats` map), extended the mino-bench harness to
delta them per run and report `:gc-ns` + `:gc-pct-tenths`, and ran
the full suite plus a new multi-subsystem realistic bench. Numbers
below.

Decision thresholds from the plan:
- GC share **> 20%** → generational GC is justified.
- GC share **< 10%** → prim ABI + inline caching is next.
- **10–20%** → consult max pause; incremental vs. generational.

## Headline

**GC spends 65–95% of wall time on every allocating workload.** This
is roughly an order of magnitude over the 20% threshold. Generational
GC is justified on throughput grounds alone, before even considering
pause time.

Non-allocating benches (e.g. `(nth v i)` on a pre-built vector, `conj`
to a transient-sized vec that doesn't trigger a collection) show
0.0% GC, confirming the timing is working correctly. The floor exists;
it's just not where real programs live.

## Full bench suite (78 benches, isolated subprocess per file)

Selected rows, format: `wall total / mean / GC count / GC share / max pause`.
Full EDN data in `results/latest.edn`; human-readable log in
`results/latest-human.log`.

### Cons / vector / map operations

| Bench | Wall | Mean/op | GCs | GC% | Max pause |
|---|---|---|---|---|---|
| build 1000-element list | 1.79 s | 1.79 ms | 107 | 82.7% | 26.3 ms |
| build 10000-element list | 2.89 s | 28.91 ms | 74 | 84.2% | 45.2 ms |
| walk 1000-element list | 1.88 s | 1.88 ms | 43 | 79.8% | 45.2 ms |
| conj 1000 elements | 1.98 s | 1.98 ms | 150 | 82.3% | 19.0 ms |
| conj 10000 elements | 2.88 s | 28.77 ms | 143 | 85.9% | 27.7 ms |
| assoc 100 keys | 372 ms | 372 µs | 49 | 83.0% | 7.0 ms |
| assoc 1000 keys | 459 ms | 4.59 ms | 56 | 85.0% | 7.9 ms |
| dissoc from 100-key map | 145 ms | 145 µs | 20 | **90.9%** | 7.9 ms |
| nth random on 1000-vec | 4.6 ms | 462 ns | 0 | **0.0%** | — |
| assoc on 1000-vec | 585 µs | 585 ns | 0 | **0.0%** | — |

### Eval / function call / environment lookup

| Bench | Wall | Mean/op | GCs | GC% | Max pause |
|---|---|---|---|---|---|
| empty fn call | 105 ms | 1.05 µs | 19 | 67.4% | 3.9 ms |
| identity fn call | 133 ms | 1.33 µs | 26 | 72.4% | 3.9 ms |
| 3-arg fn call | 205 ms | 2.05 µs | 42 | 74.2% | 3.9 ms |
| fibonacci(20) recursive | 2.95 s | 29.47 ms | **511** | 78.8% | 6.7 ms |
| map + filter + reduce pipeline | 234 ms | 234 µs | 47 | **93.4%** | 6.7 ms |
| local var (let binding) | 115 ms | 1.15 µs | 21 | 70.3% | 4.2 ms |
| closure capture (5 vars) | 161 ms | 1.61 µs | 31 | 71.0% | 4.4 ms |

### Allocation pressure / dispatch

| Bench | Wall | GC% |
|---|---|---|
| cons cell creation | 129 ms | 71.6% |
| small vector `[1 2 3]` | 96 ms | 68.4% |
| small map `{:a 1}` | 95 ms | 68.4% |
| integer boxing | 943 ms | 68.3% |
| primitive `(+ 1 2)` | 134 ms | 73.3% |
| user fn (5 args) | 315 ms | 79.1% |

### Lazy sequences (the most GC-heavy category)

| Bench | GC% |
|---|---|
| `(doall (range 100))` | **93.4%** |
| `(into [] (range 100))` | **93.3%** |
| `(map inc (range 100))` lazy | **93.0%** |
| `(filter odd? (range 100))` lazy | **92.8%** |
| `(doall (take 100 (range 1000)))` | **93.6%** |
| `(drop 50 (range 100))` | **93.1%** |

### Loop / recur / reduce

| Bench | GC% |
|---|---|
| loop 10000 iters | 77.3% |
| dotimes 1000 | 78.0% |
| reduce + over 100 ints | **93.9%** |
| reduce + over 1000 ints | **94.6%** |

### Realistic multi-subsystem workloads (new)

| Bench | Wall | Mean | GCs | GC% | Max pause |
|---|---|---|---|---|---|
| build 5k int-map and sum | 319 ms | 31.9 ms | 20 | 88.8% | 22.8 ms |
| bump 5k int-map values | 417 ms | 83.3 ms | 14 | 89.8% | 35.7 ms |
| map/filter/map/reduce over 50k | 4.74 s | 473.7 ms | 55 | 88.0% | 110.8 ms |
| nested vectors 500×100 | 2.91 s | 291.3 ms | 23 | 88.8% | 132.0 ms |
| realize 10k of lazy range | 1.06 s | 53.1 ms | 10 | 85.5% | 147.1 ms |
| fibonacci(25) | 7.54 s | 754.0 ms | 77 | 80.9% | 147.1 ms |

## Test-suite fragment end-to-end

Ran the first ~27 test files from `tests/run.mino` as a timed block
(monkey-patching `exit`/`run-tests` at scale breaks async internals,
so the number here is a subset, not the whole 923-test suite):

- 467 tests, 1053 assertions, all passed.
- Wall time: 2971 ms
- GC time: 2350 ms (**79.1%**)
- Collections: 140
- Max pause: 28 ms
- Bytes freed: 969 MB

79% GC share on a realistic mix of 467 tests exercising nearly every
language feature matches the bench picture exactly.

## Interpretation

**Throughput.** GC share is 65–95% on allocating workloads, bottoming
near 80% on the full-system realistic benches and the test-suite
fragment. These are not microbenchmark artifacts — lazy sequences,
fibonacci recursion, map/vector churn, and reduce pipelines all
converge on 80–95% GC. The interpreter allocates a fresh cons cell
per argument on every function call (tree-walking, no inline caching
of arg lists), a new small-vec on every `conj`, and a new intermediate
collection on every lazy-seq step. Most of these allocations live for
microseconds. A mark-sweep collector scans the entire heap every
~500 KB of allocation and the marked-as-dead fraction is overwhelmingly
the dominant cost.

**Pause.** Max pauses on realistic workloads reach 100–150 ms (see the
50k pipeline and nested vectors rows). Even on the test-suite
fragment, max pause is 28 ms. For an interactive REPL that's already
noticeable; for any async/timing-sensitive program it's out of bounds.

**Where the signal points.** A generational GC's nursery collection
cost is O(survivors), not O(allocation). In mino's allocation pattern
(most cons cells die young), the large majority of allocation never
escapes the nursery, so nursery collection does almost no work per
byte allocated. Even a simple two-generation mostly-non-moving
collector is likely to cut GC share from ~80% to a fraction of that
on current workloads.

A prim ABI + inline caching would help the remaining ~20%
(non-GC time: eval dispatch, arg marshalling, env lookup). Significant,
but only after the GC has stopped eating 80%.

## Verdict

**Pursue generational GC as the next milestone.** GC share is
measured at 4× the 20% threshold on every realistic workload; max
pause is already outside acceptable bounds for interactive use.

Prim ABI + inline caching should follow, not precede: (a) its benefit
is a single-digit multiplier on ~20% of current wall time; (b) its
complexity interacts with the GC's root set, so landing it on top of
a new generational collector is cleaner than doing it first and
reworking it when the GC changes.

## Open bug discovered en route

While constructing `realistic_bench.mino`, a pre-existing correctness
bug in mino's HAMT-under-churn path surfaced: `(reduce (fn [acc k]
(assoc acc k ...)) m str-keyed-keys)` over a map of >= 2 000 entries
with string keys produced by `(str "k" i)` returns a map with the
wrong `count` (observed 2 630 instead of 2 000) and sometimes
SIGSEGVs. Not related to this measurement work — reproducible against
`v0.40.0`. Flagged separately; the bench uses integer keys to avoid
the trigger so the measurement can proceed.

## Artifacts

- Raw EDN: `results/latest.edn` (78 benches with new fields).
- Human log: `results/latest-human.log`.
- New bench: `benchmarks/realistic_bench.mino`.
- Submodule pin: `mino` at `ab3ff580` (gc-timing-measurement tip).
- Harness changes: `lib/mino/bench.mino` — `:gc-ns`, `:gc-pct-tenths`,
  `:max-gc-ns`; `report-human` appends `gc: <ns> <pct>, <ns> max`.
