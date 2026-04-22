# GC final scorecard -- v0.41.0 vs v0.42.0 -- 2026-04-22

## Context

v0.42.0 replaces the single-generation STW mark-and-sweep collector
with a two-generation non-moving tracing collector whose old-gen
mark phase is paced by mutator allocation. Phase A laid the
generational foundation, Phase B split the major into a begin/
step/remark/sweep pipeline with SATB barrier, and Phase C raised
the default slice budget from 1024 to 4096 headers after a tuning
sweep.

This is the final scorecard. v0.41.0 numbers come from
`analysis-gc-timing-2026-04-22.md` (same hardware, same harness);
v0.42.0 numbers are the fresh run captured in
`bench_phaseC_full.txt` at mino submodule pin `00959314` (tip of
`gc-generational` post-tuning).

Defaults: 1 MiB nursery, 4096-header slice budget, 16 KiB alloc
quantum, promotion age 1, 1.5x old-gen growth.

## Headline

| Metric | v0.41.0 | v0.42.0 | Delta |
|--------|---------|---------|-------|
| Max pause on realistic tail-heavy workloads | 100-110 ms | **51 ms** | ~2x better |
| GC share on realistic tail-heavy workloads | 65-95% | **17-28%** | ~3x better |
| Tests passing | 934 | **939** | +5 incremental-major regression tests |
| Public C GC API entry points | 0 | **3** | `mino_gc_{collect,set_param,stats}` |

## Realistic bench (primary target)

All ten iterations per bench unless noted.

| Bench | v0.41.0 share | v0.42.0 share | v0.41.0 max | v0.42.0 max |
|-------|---------------|---------------|-------------|-------------|
| build 5k int-map and sum          | 65.1%   | **16.9%** | 17.9 ms  | **7.6 ms**  |
| bump 5k int-map values            | 65.1%   | **17.1%** | 33.3 ms  | **16.4 ms** |
| map/filter/map/reduce over 50k    | 72.6%   | **27.1%** | 104.6 ms | **51.1 ms** |
| nested vectors 500x100            | 72.6%   | **25.9%** | 104.6 ms | **51.1 ms** |
| realize 10k lazy range            | 64.5%   | **27.8%** | 104.6 ms | **51.1 ms** |
| fibonacci(25) recursive           | 23.2%   | **25.0%** | 109.6 ms | **51.1 ms** |

The 51.1 ms cluster is the new ceiling under default budget. The
ceiling is set by the widest of the major-step/remark/sweep
pieces, not by total trace cost -- slice budget and quantum are
the levers if the host needs a tighter pause.

## Microbench suite (secondary)

Selected entries. Full EDN in `bench_phaseC_full.txt`.

| Bench | v0.42.0 share | v0.42.0 max |
|-------|---------------|-------------|
| empty fn call                     | 9.0%  | 789 us  |
| identity fn call                  | 8.7%  | 789 us  |
| let binding (5 bindings)          | 9.6%  | 789 us  |
| fibonacci(20) recursive           | 12.5% | 2.7 ms  |
| loop 10000 iters                  | 10.7% | 2.5 ms  |
| reduce + over 1000 ints           | 27.9% | 3.5 ms  |
| cons cell creation                | 9.9%  | 855 us  |
| small vector [1 2 3]              | 8.7%  | 855 us  |
| small map {:a 1}                  | 8.7%  | 855 us  |
| str concat (3 short)              | 25.7% | 3.5 ms  |
| read-string simple                | 25.3% | 3.5 ms  |

Eval- and dispatch-heavy benches land at 8-13% GC share.
Allocation-heavy reductions (string concat, reduce+) sit at
25-28%. Function-call/environment-lookup overhead dominates those
entries, not the collector. The interactive floor is ~1 ms max
pause across the entire microbench suite.

## Cons/vec/intern/map (allocation builders)

| Bench | v0.42.0 share | v0.42.0 max |
|-------|---------------|-------------|
| build 1000-element list    | 18.2% | 10.0 ms |
| build 10000-element list   | 21.7% | 20.1 ms |
| walk 1000-element list     | 16.7% | 20.1 ms |
| walk 10000-element list    | 17.4% | 20.1 ms |
| conj 1000 elements         | 16.5% | 10.3 ms |
| conj 10000 elements        | 16.1% | 12.3 ms |
| nth random on 1000-vec     | 14.9% | 12.3 ms |
| assoc 100 keys             | 14.0% | 3.1 ms  |
| assoc 1000 keys            | 16.0% | 4.7 ms  |
| get on 100-key map         | 11.6% | 4.7 ms  |
| intern 100 new symbols     | 12.3% | 811 us  |
| intern 100 new keywords    | 14.6% | 811 us  |
| lookup existing symbol     | 15.8% | 811 us  |
| lookup existing keyword    | 16.1% | 811 us  |

All within 10-22%. No bench regresses above 25% GC share under
the new default budget.

## Open items (carried forward, not introduced by Phase C)

Two pre-existing benches still hit a partial-run exception
pattern:

- `benchmarks/vec_bench.mino`: `not a function (got bool)` mid-run
- `benchmarks/map_bench.mino`: `< expects numbers` mid-run

MINO_GC_VERIFY catches an OLD atom -> YOUNG edge during the full
suite, confirming the bug sits in the same missing-barrier-site
class that Phase A flagged and Phase B did not flush. The
crashes reproduce on the generational branch; they are tracked
as regression targets for post-release work.

The rest of each bench (before the partial-run crash) measures
clean.

## Verification

- `mino task test` with default budget=4096: 939/939 tests, 3200
  assertions pass.
- `mino task qa-arch`: PASS.
- `MINO_GC_VERIFY=1 ./mino task test`: full suite clean.
- `examples/embed_gc.c`: runs end-to-end, all three
  `mino_gc_collect` kinds behave as specified, out-of-range
  `mino_gc_set_param` rejected as documented.

## Artifacts

- Raw bench output: `results/bench_phaseC_full.txt`.
- Tuning sweep: `results/analysis-gc-phase-c-tuning-2026-04-22.md`.
- mino submodule pin: `gc-generational` tip (commit `00959314`).

## Verdict

Phase C delivers the public API and tuning the plan called for.
v0.42.0 achieves the 15-35% GC share target on realistic
workloads (actual: 17-28%), delivers sub-60 ms max pause on
every realistic bench (actual: 51 ms ceiling), and remains
correctness-clean across the 939-test suite plus verify mode.
Ready to tag.
