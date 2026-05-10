# perf-push cycle: cumulative perf gate delta

Snapshot of `perf_baseline.edn` deltas after the mino non-JIT
performance push closed. Numbers are min-of-3 mean-ns/op.

| Bench               | Before | After | Δ      |
|---------------------|-------:|------:|-------:|
| arith-add           |  4603  |  3425 | -25.6% |
| arith-inc           |  4401  |  3210 | -27.0% |
| assoc-small         |  5415  |  4309 | -20.4% |
| cons-create         |  4764  |  3625 | -23.9% |
| count-vec3          |  4497  |  3346 | -25.6% |
| do-block            |  4174  |  2967 | -28.9% |
| fn-call-identity    |  4139  |  3025 | -26.9% |
| if-branch           |  3954  |  2839 | -28.2% |
| let-local-lookup    |  4051  |  2950 | -27.2% |
| loop-recur-5        |  8449  |  7252 | -14.2% |
| re-find-simple      |  5358  |  4288 | -20.0% |
| read-int            |  4477  |  3444 | -23.1% |
| read-list           |  4902  |  3750 | -23.5% |
| small-map           |  4288  |  3156 | -26.4% |
| vec3-create         |  4372  |  3191 | -27.0% |

Average improvement across the gate: ~24%.

Workload-level (off-gate) microbenchmarks measured during the
cycle (first run; later runs drift up due to GC settling -- see
mino CHANGELOG note):

- `(loop [i 0 acc 0] ...)` 1M iters: 941 ms → 375 ms (-60%)
- `(reduce + (range 1M))`:           870 ms → 514 ms (-41%)
- `(clojure.core/+ ...)` vs `(+ ...)` over 100k: 314 ms vs 303 ms
  → both at ~174 ms (qualified-symbol tax eliminated)

Allocation shape is unchanged on every gate bench: per-op byte
counts in the baseline file are identical before and after the
cycle. The gains come from cutting per-call overhead, not from
allocating less per call.
