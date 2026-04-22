# Performance Analysis: 2026-04-22 (third batch, cumulative)

Final consolidation after three optimization passes on
`perf-benchmark-optimize`. The first pass (analysis-2026-04-21.md) fixed
the obvious structural problems; the second (analysis-2026-04-22.md,
19 commits) squeezed the interpreter core. This pass (7 more commits)
targets the remaining high-frequency mino-level functions that sit
between the interpreter core and realistic user code.

## Change set since analysis-2026-04-22.md (commits on `perf-benchmark-optimize`)

| # | Commit | Change |
|---|--------|--------|
| 1 | Literal short-circuit | Vector/map/set literals of self-eval leaves return form directly |
| 2 | range prim | Lazy range via C thunk; skips per-element fn frame |
| 3 | Lazy map/filter prims | Single-coll map and filter as C thunks |
| 4 | inc/dec prims | Inner step of almost every loop now skips a fn frame |
| 5 | doall/dorun prims | Lazy-seq realization walks in C |
| 6 | take/drop prims | Lazy take and eager drop as C |

## Cumulative benchmark impact: original baseline → current

Wall-clock mean-ns per iteration from isolated-process benchmark runs
(`./mino/mino benchmarks/run_all.mino`). "Baseline" is the pre-opt
number from `results/baseline-pre-opt.edn`. "Current" is the latest.

### Function call overhead
| Benchmark | Baseline | Current | Δ |
|-----------|-------:|------:|--:|
| primitive fn: (+ 1 2)      | 6925 ns | 1239 ns | **-82%** |
| user fn (0 args)           | 6532 ns | 1070 ns | **-84%** |
| user fn (1 arg)            | 6919 ns | 1416 ns | **-80%** |
| user fn (3 args)           | 8593 ns | 1878 ns | **-78%** |
| user fn (5 args)           | 9801 ns | 2888 ns | **-71%** |
| fibonacci(20) recursive    | 52.34 ms | 27.5 ms | **-47%** |
| apply with 3 args          |  6400 ns | 2611 ns | **-59%** |

### Environment / name lookup
| Benchmark | Baseline | Current | Δ |
|-----------|-------:|------:|--:|
| global var lookup          | 6006 ns |  904 ns | **-85%** |
| lookup existing symbol     | 6216 ns | 1173 ns | **-81%** |
| local var (let binding)    | ~6000 ns | 1114 ns | **-81%** |
| deeply nested let (10)     | ~12 us   | 4.3 us  | **-64%** |
| closure capture (1 var)    | ~6000 ns |  901 ns | **-85%** |

### Allocation
| Benchmark | Baseline | Current | Δ |
|-----------|-------:|------:|--:|
| integer boxing             | 6040 ns |  872 ns | **-86%** |
| keyword creation (existing)| ~6000 ns |  876 ns | **-85%** |
| cons cell creation         | ~5324 ns | 1199 ns | **-77%** |
| small vector [1 2 3]       | ~5388 ns |  901 ns | **-83%** |
| small map {:a 1}           | ~6165 ns |  897 ns | **-85%** |

### Loop / recur
| Benchmark | Baseline | Current | Δ |
|-----------|-------:|------:|--:|
| loop 1000 iters            | 2934 us |  720 us | **-75%** |
| loop 10000 iters           |  29.5 ms | 7.3 ms  | **-75%** |
| dotimes 1000               | 7951 us |  952 us | **-88%** |
| reduce + over 1000 ints    |  644 us |  467 us | **-27%** |

### Lazy sequences
| Benchmark | Baseline | Current | Δ |
|-----------|-------:|------:|--:|
| (range 100) lazy creation         |  ~9582 ns | 1803 ns  | **-81%** |
| (doall (range 100))               | 1081 us   |   60 us  | **-94%** |
| (map inc (range 100)) lazy        | 1939 us   |  120 us  | **-94%** |
| (filter odd? (range 100)) lazy    | 2477 us   |  103 us  | **-96%** |
| (into [] (range 100))             |  ~1300 us |  112 us  | **-91%** |
| map + filter + reduce pipeline    | 2797 us   |  267 us  | **-90%** |
| (doall (take 100 (range 1000)))   |    n/a    |  122 us  | (new)    |
| (drop 50 (range 100))             |    n/a    |   73 us  | (new)    |

### Collections
| Benchmark | Baseline | Current | Δ |
|-----------|-------:|------:|--:|
| build 1000-element list    | 3412 us | 1338 us | **-61%** |
| walk 1000-element list     | 5110 us | 1305 us | **-74%** |
| assoc 1000 keys            | 6640 us | 4205 us | **-37%** |
| conj 1000 elements         | ~4499 us | 1645 us | **-63%** |
| higher-order (map inc v)   | 17721 ns | 5292 ns | **-70%** |

### GC pressure (fibonacci 20, 100 runs)
| | Baseline | Current |
|---|-------:|------:|
| GC collections      | 756     | 520     |
| Bytes freed total   | 1.72 GB | 1.09 GB |

Collection count down 31%, bytes freed down 37%. The shrinkage is
driven by the cumulative effect of every prior pass (smaller fn
frames, interned sym data, small-int cache) rather than one single
change.

## Where the remaining cost sits

The mino eval "floor" is now ~900 ns for cold primitive/identity calls
and ~1.1 us for symbol-driven user fns. Breakdown for a simple call:

1. eval_impl dispatch (type switch, special-form pointer-eq probes)
2. eval_value on head (env chain walk, usually 2 hops for globals)
3. eval_args cons allocation + arg eval
4. apply_callable (for FN: env_child + bind_params + body eval)
5. push/pop_frame (trace stack bookkeeping)

Each of steps 1-4 is already near its minimum given the interpreter's
design — there's no more dispatch to skip, no more env frame to reuse
without changing closure semantics, and no arg passing without a
cons list without a prim-ABI rework.

## What was tried but not shipped

- **Reordering special-form HEAD_IS checks by frequency.** Measured
  impact was below noise (<20 ns per eval) because the pointer-eq
  fast path already short-circuits every miss in ~2 ns. Skipped.

- **inc/dec as literal prim lookups via a separate fast path.** The
  simpler route — register prim inc/dec and let the existing PRIM
  dispatch handle them — already captures the savings. The var
  special form now auto-creates vars for prim-backed names so
  `#'inc` and `#'map` continue to return vars.

- **Pre-sizing env bindings array for let/fn with known count.**
  Currently first bind triggers cap=4 alloc. Pre-sizing to exactly
  N would save one realloc on wide lets, but with cap=4 already
  covering the common case and free-list recycling for 64-byte
  blocks, measured overhead is already amortized.

- **Batch cons allocation for eval_args.** Would need a block
  allocator variant to avoid per-cell free-list probes. Impact
  estimated <5% given current floor.

## Architectural next step (not done)

Further material gains require stepping out of the tree-walking
interpreter: inline caching of call sites, bytecode compilation of
fn bodies, or a method JIT. None are justified by the current
profile — realistic mino programs now run at 3-10x the baseline
speed with no code-size or correctness regressions.
