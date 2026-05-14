# Changelog

## Unreleased

- New `benchmarks/protocol_bench.clj` (wired into `run_all`):
  five-bench microsuite covering monomorphic, bi-morphic, and
  tri-morphic protocol-method invocation plus a realistic
  reduce-over-mixed-types loop. At v0.157.0 the monomorphic case
  measures ~3.4 µs/call (~50x typical fn-call cost in mino); the
  bench establishes the baseline that a future protocol-keyed
  inline cache will be measured against.

- Submodule pointer bumped to mino v0.157.1. Covers the
  bc-frontiers cycle (v0.152.0–v0.157.1): write-side fast-lane
  opcodes (`OP_CONJ_VEC`, `OP_ASSOC`), small-prim inlining for
  vectors (`OP_FIRST_VEC` / `OP_COUNT_VEC` / `OP_EMPTY_VEC`),
  record fast path + `(:kw coll)` inlining inside
  `OP_GET_KW_MAP`, inline-cached call sites (`OP_CALL_CACHED`),
  generic get + arity-2 dissoc fast lanes, transducer fusion in
  `prim_reduce` via thunk-pointer inspection, and the
  `MINO_BC_OP_COUNTS=1` build flag for per-opcode dispatch
  profiling. Bench wins to expect across the matrix: pipeline-sum
  -77%, get-str-map -81%, get-kw-record -93%, fib-30 -13%,
  conj-vec -34%, count-vec -94%.

- Tracking mino v0.101.0 (STM cycle): refs, `dosync`, `alter`,
  `commute`, `ensure`, `ref-set`, `io!`, watches and validators on
  refs and vars; agents (`agent`, `send`, `send-off`, `await`,
  `agent-error`, `restart-agent`, error-mode / error-handler);
  Layer 2a C API mirroring the Clojure surface (`mino_tx_ref`,
  `mino_tx_run`, `mino_tx_alter_c`, `mino_tx_commute_c`,
  `mino_tx_ensure`, `mino_tx_ref_set`, `mino_tx_ref_deref`,
  `mino_is_tx_ref`); cross-state ref defense via MST007;
  `mino_pcall` API change adding an `out_ex` parameter; plus the
  intermediate cycle of additions that landed since v0.98.5:
  MINO_HOST_ARRAY, MINO_MAP_ENTRY, MINO_FLOAT32 value types;
  strict integer overflow on `+`/`-`/`*`; `bigdec`÷`ratio` widens
  to bigdec; `cons` returns non-list shape; `aset` for host
  arrays; fixed-arity enforcement at fn apply. Submodule bumped
  from `022b83a` (v0.98.5) to `b66c3e5` (v0.101.0). No bench-side
  code changes -- nothing in `benchmarks/` or `src/` calls
  `mino_pcall` directly.

- Tracking mino v0.98.5 (Hygiene + Closure cycle: macro hygiene fix
  in `qq_qualify_symbol` so syntax-quoted bare symbols inside a
  macro body qualify against the macro's defining namespace not the
  consumer's `*ns*` (closes the silent
  `with-out-str`-after-`:refer :all` miscompile and the
  `unbound symbol: chan*` failure for `(a/go ...)` called from
  outside `clojure.core.async`); `compare` gains the canon
  cross-type total order
  `nil < false < true < numbers < strings < symbols < keywords`;
  `clojure.string/split` gains the 3-arg `limit` arity; vector seqs
  and lazy `range` auto-chunk into 32-element chunks so
  `(chunked-seq? (seq [1 2 3]))` is `true` and
  `(reduce + (map inc (filter odd? (range 1e6))))`-style pipelines
  run end-to-end chunked; `array-map` insertion-order semantics
  verified to already match canon; `random-seed!` primitive plus a
  minimal `clojure.test.check` port (generators, properties,
  `quick-check`; shrinking deferred) backing
  `clojure.spec.alpha/gen` and `clojure.spec.alpha/exercise`). The
  bench-side fork of `mino.tasks.builtin` realigns to upstream's
  3-tuple `bundled-stdlib` schema and grows the three new
  `lib_clojure_test_check*` entries so a clean checkout build
  generates the right headers; the bootstrap recipe in `mino.edn`
  is replaced by `cd mino && make && cd ..`, which delegates to
  the submodule's Makefile.
- Tracking mino v0.97.5 (Kwargs + Audit + Hygiene cycle: kwargs
  destructuring matches Clojure 1.11 (inline pairs, trailing map,
  mixed; `:or` defaults eval correctly inside the C-level binder);
  `iteration` rewritten to canon `& {:keys [...]}` shape; `sort-by`
  and `reductions` gain multi-arity; `src/core.clj` 80-char wrap
  with no behavioral churn; `defn` lifts to top-of-file so six
  bootstrap `def + fn` forms become regular `defn`;
  `clojure.core.async` gains canon `reduce` / `transduce` / `split`
  / `partition-by` and excludes them from `clojure.core`; and
  `clojure.spec.alpha` gains `abbrev` / `describe`). The bench
  build task now also generates the `lib_<ns>.h` headers that
  `install_stdlib.c` includes, and the three `str-replace` calls in
  the local fork of the bundled task are qualified to `str/replace`
  — both pre-existing latent issues in the bench-side fork that
  surfaced once `core.clj` changed enough to retire stale headers.
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
