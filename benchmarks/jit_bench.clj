(ns benchmarks.jit-bench)
(require '[mino.bench :as bench])

;; Micro-bench for the copy-and-patch JIT.
;;
;; The harness compares `(fn ...)` shapes whose bodies match the
;; runtime's current stencil set against the interpreter baseline.
;; Each top-level fn is warmed past the JIT hot threshold (100 calls)
;; before benchmarking; this lets the bench measure the steady-state
;; native dispatch cost rather than the first-call-through-interpreter
;; cost.
;;
;; The harness intentionally stays a thin wrapper around bench/run-suite
;; so the run shape matches the rest of mino-bench (one EDN line per
;; result, human-readable preamble, no special-case post-processing).
;;
;; Each suite covers shapes whose stencils land in successive cycle
;; releases. Numbers move as the stencil set widens; the cycle close
;; rolls them into a comparison table.

;; Trivial pass-through and constant-returning -- already JIT-eligible
;; in v0.195.0 thanks to OP_MOVE / OP_LOAD_K / OP_RETURN_IMM and the
;; fused OP_LOAD_K_RETURN superinstruction.
(def id-fn      (fn [x]   x))
(def const42-fn (fn []    42))
(def pick-a-fn  (fn [a b] a))

;; Arith shapes -- not yet JIT-eligible. Measurements stay at
;; interpreter speed until v0.198.0+ adds the corresponding stencils.
;; Carrying them here now gives the cycle a stable baseline to chart
;; the wins against.
(def add-fn  (fn [a b] (+ a b)))
(def lt-fn   (fn [a b] (< a b)))
(def inc-fn  (fn [x]   (inc x)))

;; Counted-loop body shapes. Not yet eligible -- waiting on v0.201.0's
;; control flow + v0.202.0's counted-loop fused stencils.
(def sum-to-fn (fn [n]
                 (loop [i 0 acc 0]
                   (if (< i n)
                     (recur (inc i) (+ acc i))
                     acc))))

;; Pre-warm every fn under test. The JIT threshold is 100 calls; 200
;; iterations comfortably crosses it. Fns whose shape isn't currently
;; eligible saturate the hot counter (see v0.194.0); subsequent calls
;; skip the per-call eligibility check.
(dotimes [_ 200]
  (id-fn 1)
  (const42-fn)
  (pick-a-fn 1 2)
  (add-fn 1 2)
  (lt-fn 1 2)
  (inc-fn 1)
  (sum-to-fn 10))

(defn run []
  (bench/run-suite "JIT-eligible micro-shapes (v0.195.0 stencil set)"
    [["(id 7)              x 1M" 1000000 (fn [] (id-fn 7))]
     ["(const42)           x 1M" 1000000 (fn [] (const42-fn))]
     ["(pick-a 1 2)        x 1M" 1000000 (fn [] (pick-a-fn 1 2))]])
  (bench/run-suite "Arith shapes (interpreter today, v0.198.0+ JIT'd)"
    [["(add 1 2)           x 1M" 1000000 (fn [] (add-fn 1 2))]
     ["(lt? 1 2)           x 1M" 1000000 (fn [] (lt-fn 1 2))]
     ["(inc 1)             x 1M" 1000000 (fn [] (inc-fn 1))]])
  (bench/run-suite "Counted-loop body (interpreter today, v0.202.0+ JIT'd)"
    [["(sum-to 100)        x  10K"   10000 (fn [] (sum-to-fn 100))]
     ["(sum-to 1000)       x   1K"    1000 (fn [] (sum-to-fn 1000))]
     ["(sum-to 10000)      x  100"     100 (fn [] (sum-to-fn 10000))]]))

(run)
