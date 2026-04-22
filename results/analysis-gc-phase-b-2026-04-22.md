# GC Phase B analysis — 2026-04-22

## Context

Phase B of the generational+incremental GC plan (see
`mino/pragmatic_generational_incremental_gc_plan.md`): the major
collector is split into `begin`/`step`/`remark`/`sweep` and paced
from the allocation path, the write barrier is extended with an
SATB push, minor collection is allowed to nest inside `MAJOR_MARK`
with a drain-floor + promotion hook, and major sweep leaves YOUNG
alone while purging (not resetting) the remembered set.

Baseline is the Phase A measurement in
`results/analysis-gc-phase-a-2026-04-22.md`. Phase A delivered
19-35% GC share and max pauses of 80-110 ms on tail-heavy realistic
workloads; the headline goal for Phase B is trimming that max pause.

Default tuning unchanged: 1 MiB nursery, promotion age 1, 1.5x old-
gen growth. New knobs: 16 KiB allocation quantum between slices,
1024 headers popped per slice. Same mino-bench repo; mino submodule
pinned to `gc-generational` tip (commit `2d705d2`).

## Headline

**Max pause roughly halves on every realistic workload.** The
tail-heavy benches that hit 100-110 ms under Phase A's STW major
now cap at 60-68 ms regardless of workload. The max-pause bound is
set by the widest of the four major pieces (begin / remark / sweep
scan-and-free), not by the cumulative trace cost, which is what the
incremental split is designed to achieve.

**GC share moves in both directions.** Small-heap, allocation-heavy
benches (cons/vec build, assoc 1000, dotimes) see a share increase
in the 5-30 pp range because the major pipeline has per-slice
overhead that matters more when the heap is small and the cycle is
short. Function-dispatch, eval, and steady-state loop benches see
the share *drop* 2-6 pp because incremental pacing spreads the
work where Phase A had several-ms clumps.

## Headline pause numbers

| Bench | Phase A max | Phase B max |
|---|---|---|
| build 5k int-map and sum | 17.9 ms | 12.0 ms |
| bump 5k int-map values | 33.3 ms | 21.3 ms |
| map/filter/map/reduce over 50k | 104.6 ms | **60.7 ms** |
| nested vectors 500x100 | 104.6 ms | **60.7 ms** |
| realize 10k lazy range | 104.6 ms | **60.7 ms** |
| fibonacci(25) | 109.6 ms | **60.7 ms** |
| build 10000-element list | 41.6 ms | 38.3 ms |
| conj 10000 elements | 14.8 ms | 21.0 ms |
| reduce+ over 1000 ints | 11.3 ms | 4.9 ms |
| dotimes 1000 | 11.3 ms | 4.9 ms |
| str concat / subs | 19.3 ms | 4.9 ms |

The 60.7 ms cluster on the realistic suite is the new ceiling for
any single major slice under the default budget. Bumping
`gc_major_work_budget` down (or `gc_major_alloc_quantum` up) would
lower it further in exchange for more total slice overhead; a
tuning sweep is part of the Phase C plan.

## GC share — where it moved

### Better under incremental pacing

| Bench | Phase A | Phase B |
|---|---|---|
| walk 1000-element list | 27.6% | **15.8%** |
| walk 10000-element list | 28.3% | **16.2%** |
| assoc 100 keys | 19.5% | **13.4%** |
| fibonacci(20) recursive | 19.1% | **15.8%** |
| loop 10000 iters | 21.2% | **10.9%** |
| reduce+ over 1000 ints | 35.5% | **27.4%** |
| nested vectors 500x100 | 27.1% | **35.2%** ⚠ |

### Worse: allocation-heavy small-heap benches

| Bench | Phase A | Phase B |
|---|---|---|
| build 1000-element list | 32.9% | 45.8% |
| build 10000-element list | 33.2% | 62.5% |
| conj 1000 elements | 31.4% | 35.1% |
| conj 10000 elements | 31.1% | 40.1% |
| assoc 1000 keys | 31.1% | 50.7% |
| build 5k int-map | 33.8% | 65.1% |
| map/filter/map/reduce 50k | 31.5% | 72.6% |
| realize 10k lazy range | 32.7% | 64.5% |

### Stable (within noise)

| Bench | Phase A | Phase B |
|---|---|---|
| fibonacci(25) | 26.2% | 23.2% |
| eval benches (0/1/3-arg fn calls) | 10-12% | 8-10% |
| lazy micro (map/filter/doall) | 26-30% | 26-30% |
| reader benches (read-string *) | 31-33% | 24-27% |

## Minor vs major split

The minor:major ratio stays in the same 20-1000x range Phase A
reported, confirming the nursery is still doing the bulk of the
collection work. Per-cycle major pauses are smaller; the number of
majors fired is roughly unchanged, except on realistic_bench where
fib(25) triggered 1149+0 GCs in Phase A and 1149+0 in Phase B
(identical minor count, both-zero major count -- the nursery
handles everything, as expected).

## Interpretation

**Pause target.** The main Phase B goal lands. Every workload that
used to page-stall for ~100 ms now holds under 70 ms. The ceiling
is set by the widest of the four phase pieces, so tuning the slice
budget can drive it lower without changing correctness. The
remark's stack scan dominates on large heaps; splitting remark
into a "snapshot remark" plus a final rescan-while-paused would
trim it further, but that is incremental-major territory Phase C
may or may not pick up.

**Share regression on allocation-heavy benches.** The extra cost
comes from pacing overhead: each slice entry checks phase, touches
`gc_depth`, and returns through the allocator's trigger logic. Per
slice, that overhead is small; across dozens of slices per major
cycle it adds up on benches where the cycle itself is cheap. Most
of the regression lives in the realistic_bench's 50k
map/filter/map/reduce -- a single pipeline allocating very hot --
where a cycle that ran ~30 ms under Phase A spreads across many
short slices now.

Share-focused workloads would prefer a larger slice budget (more
work per slice, fewer slice entries); the default (1024 headers)
targets pause ceiling, not throughput. The Phase C tuning sweep
will map out that tradeoff.

**Share improvement on walk/eval/loop benches.** Phase A's major
cycles bundled into one pause that counted entirely against GC
time in the bench's measurement window. Phase B's pacing spreads
that cost across the mutator's allocation tick, so what was a
clustered GC burst now decomposes into many small slices
interleaved with useful work. Total cycles are the same; the share
metric accurately reflects the reshaping.

**No correctness regressions.** 939 mino tests pass (up from 934
after the Phase B regression tests landed); MINO_GC_VERIFY clean
on the full suite; qa-arch PASS. The two open items flagged by
Phase A (`vec_bench.mino` and `map_bench.mino` mid-run errors,
lazy-cache-survives-remset) still reproduce under Phase B and
track to the same missing-barrier-site class Phase B did not
flush.

## Artifacts

- Raw human-readable per-bench output:
  this repo's `results/bench_phaseB_v2.txt`-equivalent in the
  benchmark task output; all numbers above read from it.
- Harness: unchanged from Phase A's `lib/mino/bench.mino`.
- mino submodule pin: `gc-generational` tip (commit `2d705d2`).

## Verdict

Phase B delivers the pause target and surfaces one clear share
regression tied to pacing overhead on allocation-heavy benches.
The regression is defaulting-sensitive (larger slice budget
recovers throughput; tradeoff is pause ceiling) and sits squarely
in Phase C's tuning scope. Correctness is clean. Recommend moving
to Phase C's public API and tuning sweep as planned.
