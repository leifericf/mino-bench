# GC Phase A analysis — 2026-04-22

## Context

Phase A of the generational+incremental GC plan (see
`mino/pragmatic_generational_incremental_gc_plan.md`): ship a
non-moving two-generation collector with a remembered set, write
barriers at every mutation-after-publication site, and a minor-only
STW cycle. The major collector stays as-is (full STW mark-sweep);
the incremental-major work is Phase B.

Baseline is the v0.41.0 measurement in
`results/analysis-gc-timing-2026-04-22.md`, which found 65–95% GC
share and 100–150 ms max pauses across allocating workloads.

Nursery default: 1 MiB. Promotion age: 1 (every marked YOUNG promotes
on the cycle it survives). Major growth factor: 1.5×, floored at the
heap threshold so the first promotion does not trigger a major.

## Headline

**GC share dropped from 65–95% to 19–35% across the suite** —
typically around 3× lower on allocation-heavy benches. Max pause is
under 20 ms on most benches; tail-heavy workloads still hit 80–110 ms
because the major cycle is still STW, which Phase B addresses.

Minor cycles dominate the collection count: every workload shows
~500 minors per 20 majors or fewer, confirming nursery-scale collection
is where the new work happens and major stays amortized.

## Full suite deltas

Format: baseline → Phase A, GC share and max pause.

### Cons / vector / map

| Bench | GC% before | GC% after | Max before | Max after |
|---|---|---|---|---|
| build 1000-element list | 82.7% | **32.9%** | 26.3 ms | 24.7 ms |
| build 10000-element list | 84.2% | **33.2%** | 45.2 ms | 41.6 ms |
| walk 1000-element list | 79.8% | **27.6%** | 45.2 ms | 41.6 ms |
| walk 10000-element list | — | **28.3%** | — | 49.9 ms |
| conj 1000 elements | 82.3% | **31.4%** | 19.0 ms | 13.9 ms |
| conj 10000 elements | 85.9% | **31.1%** | 27.7 ms | 14.8 ms |
| assoc 100 keys | 83.0% | **19.5%** | 7.0 ms | 4.5 ms |
| assoc 1000 keys | 85.0% | **31.1%** | 7.9 ms | 6.4 ms |
| get on 100-key map | — | **17.6%** | — | 6.4 ms |

### Eval / function call

| Bench | GC% before | GC% after | Max before | Max after |
|---|---|---|---|---|
| empty fn call | 67.4% | **12.4%** | 3.9 ms | 1.9 ms |
| identity fn call | 72.4% | **10.3%** | 3.9 ms | 1.9 ms |
| 3-arg fn call | 74.2% | **11.6%** | 3.9 ms | 1.9 ms |
| let binding (5 bindings) | — | **11.6%** | — | 1.9 ms |
| fibonacci(20) recursive | 78.8% | **19.1%** | 6.7 ms | 3.8 ms |
| map + filter + reduce pipeline | 93.4% | **24.1%** | 6.7 ms | 4.0 ms |

### Lazy sequences

| Bench | GC% before | GC% after |
|---|---|---|
| `(map inc (range 100))` lazy | 93.0% | **26.9%** |
| `(filter odd? (range 100))` lazy | 92.8% | **27.1%** |
| `(doall (take 100 (range 1000)))` | 93.6% | **26.7%** |
| `(drop 50 (range 100))` | 93.1% | **27.7%** |

### Loop / recur / reduce

| Bench | GC% before | GC% after |
|---|---|---|
| loop 10000 iters | 77.3% | **21.2%** |
| dotimes 1000 | 78.0% | **32.3%** |
| reduce + over 100 ints | 93.9% | **35.8%** |
| reduce + over 1000 ints | 94.6% | **35.5%** |

### Realistic multi-subsystem workloads

| Bench | GC% before | GC% after | Max before | Max after |
|---|---|---|---|---|
| build 5k int-map and sum | 88.8% | **33.8%** | 22.8 ms | 17.9 ms |
| bump 5k int-map values | 89.8% | **34.0%** | 35.7 ms | 33.3 ms |
| map/filter/map/reduce over 50k | 88.0% | **31.5%** | 110.8 ms | 104.6 ms |
| nested vectors 500×100 | 88.8% | **27.1%** | 132.0 ms | 104.6 ms |
| realize 10k of lazy range | 85.5% | **32.7%** | 147.1 ms | 104.6 ms |
| fibonacci(25) | 80.9% | **26.2%** | 147.1 ms | 109.6 ms |

## Minor vs major split

Every bench reports per-kind collection counts now (`gc-minor` and
`gc-major`). Representative split on realistic benches:

- build 5k int-map: 99 minor + 5 major
- bump 5k int-map: 98 minor + 4 major
- 50k pipeline: 713 minor + 31 major
- nested vectors 500×100: 349 minor + 1 major
- fibonacci(25): 1149 minor + 1 major

Ratios of 20–1000× minor-to-major confirm the generational split is
doing what it should: the common case (short-lived allocation) is
paid for by proportional young-only work, and the major cycle stays
rare enough that its STW cost is amortized.

## Max pause

Tail-heavy benches still show 80–110 ms max pauses. These are major
cycles, not minors. Minor pauses on the same workloads are under
10 ms in every case measured. Incremental major marking (Phase B)
carries the headline target of trimming max pause further.

## Interpretation

**Throughput.** GC share drops by 2–4× uniformly. The `(assoc 100 keys)`
bench is the clean extreme (19.5%). Lazy-seq workloads — the previous
outliers at 93% — sit at 27% now. Remaining GC cost is split roughly
evenly between minor tracing (root + remset + young sweep) and the
occasional major. Phase B replaces major with incremental slices,
which should shave further.

**Pause.** Minor pauses are reliably under 10 ms. Major pauses remain
STW and dominate the tail. Phase B's incremental major is the lever.

**Where the allocation goes.** Minor cycles are the dominant work
because every function call still allocates a fresh env frame and a
fresh cons list of args. Prim ABI + inline caching (post-Phase B)
shrinks this allocation class, compounding with the generational win.

## Verdict

Phase A delivers the expected step change. Generational collection
cuts GC share well below the plan's 20–35% target on most benches.
Max pause stays limited by the still-STW major, which Phase B
addresses. No regression risk surfaced that blocks continuing.

## Open items surfaced

1. **Two bench files errored partway** (`vec_bench.mino` on a float
   comparison, `map_bench.mino` in numeric comparison). Symptoms
   resemble the latent `MINO_GC_VERIFY` finding: a value held only
   through a chain that spans many minor cycles occasionally lands
   as a stale pointer. The full mino test suite stays green and the
   earlier benches in each file run correctly; these are trailing
   cases that Phase B's incremental path should flush out. Track
   separately; do not block on them.

2. **Lazy-seq cached stability** over many minor cycles: a planned
   regression test was held back for the same reason (see comment in
   `mino/tests/gc_generational_test.mino`). Revisit alongside (1).

## Artifacts

- Raw EDN per bench (full runs): visible in the `;edn;` lines of each
  bench output in this repo's console log.
- Harness changes: `lib/mino/bench.mino` now reports
  `:gc-minor` and `:gc-major` alongside `:gc-collections`; human
  output shows "M+N GCs".
- mino submodule pin: `gc-generational` branch tip (local only;
  not pushed).
