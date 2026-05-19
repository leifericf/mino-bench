(ns benchmarks.deopt-trigger)
(require '[mino.bench :as bench])

;; Workloads that exercise the JIT side-exit path. Each row
;; contains a small fn whose body the JIT can stencil up to a
;; point, then hits an op the JIT can't compile (eg. a
;; (throw ...) wrapped in (try ...)) which fires the deopt
;; round-trip. Closes a corpus dark spot the cycle E
;; synthesis dashboard surfaced: the v0.319 deopt-path
;; counters stayed at 0 across the existing bench corpus
;; because none of the rows had a try/throw shape in a hot
;; loop. With these rows wired, the per-fn deopts column in
;; MINO_CPJIT_STATS=tracing should be non-zero on this file.

(def N 10000)

;; Stencilled prefix + throw tail. (try) wraps the throw so the
;; caller observes a value, not an unwind. The catch returns the
;; loop count so the result is deterministic.
(defn throw-after-loop [n]
  (try
    (loop [i 0 acc 0]
      (if (< i n) (recur (inc i) (+ acc i))
          (throw "stop")))
    (catch _ :ok)))

;; Direct stencilled-prefix-then-cold-op shape: a literal-map
;; constructor is on the cold path today, so it forces a deopt
;; after the loop is JIT-compiled.
(defn loop-then-coldmap [n]
  (loop [i 0]
    (if (< i n) (recur (inc i))
        {:done i :flag :marker})))

;; Hot loop with conditional throw on a rare key. The cold
;; (throw ...) tail forces deopt only when the rare condition
;; fires; warmup runs without firing so the loop is stencilled.
(defn rare-throw [n]
  (try
    (loop [i 0]
      (cond
        (zero? (rem i 1000000007)) (throw "rare")
        (< i n)                    (recur (inc i))
        :else                      i))
    (catch _ :ok)))

(dotimes [_ 200]
  (throw-after-loop 100)
  (loop-then-coldmap 100)
  (rare-throw 100))

(println "== Deopt-trigger workloads -- raw time (7 samples per shape) ==")
(println)
(println "  throw-after-loop 10k")
(dotimes [_ 7] (time (throw-after-loop N)))
(println)
(println "  loop-then-coldmap 10k")
(dotimes [_ 7] (time (loop-then-coldmap N)))
(println)
(println "  rare-throw 10k")
(dotimes [_ 7] (time (rare-throw N)))
(println)

(bench/run-suite "Deopt-trigger via harness"
  [["throw-after-loop 10k  x 5" 5 (fn [] (throw-after-loop N))]
   ["loop-then-coldmap 10k x 5" 5 (fn [] (loop-then-coldmap N))]
   ["rare-throw       10k  x 5" 5 (fn [] (rare-throw       N))]])
