(ns benchmarks.jit-loops-advanced)
(require '[mino.bench :as bench])

;; JIT loop-shape coverage matrix. Two measurement modes per row:
;;
;;   raw     : (time ...) on a top-level call to the named fn. Measures
;;             the JIT's inner-loop floor without the per-call dispatch
;;             overhead introduced by passing a closure through any
;;             wrapping fn. This is the "headline" number that matches
;;             the user's `(time (run))` measurement style.
;;
;;   harness : mino.bench/run-suite path. Calls the body-fn through a
;;             closure passed across an fn boundary; today this path
;;             hides JIT wins because the IC dispatch to the JITted
;;             body-fn from inside the bench loop still routes through
;;             apply_callable_argv. The pair-mode keeps both numbers
;;             visible so the call-overhead phase (Phase F of the
;;             cycle) has a clear before/after target.
;;
;; The 4 row shapes correspond to the 4 fused loop opcodes the
;; bytecode compiler can emit. fib(30) covers the call-bound case.

(def N 10000000)

(defn dec-only [n]
  (loop [i n]
    (if (zero? i) i (recur (dec i)))))

;; Two-binding reverse counter with carry: OP_LOOP_INT_DEC_INC.
;; This is the exact shape in the user's headline benchmark.
(defn dec-inc [n]
  (loop [i 0 j n]
    (if (zero? j) i (recur (inc i) (dec j)))))

(defn lt-inc [n]
  (loop [i 0 k 0]
    (if (< i n) (recur (inc i) (inc k)) k)))

(defn lt-only [n]
  (loop [i 0]
    (if (< i n) (recur (inc i)) i)))

(defn fib [n]
  (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))

(defn pipeline [n]
  (->> (range n)
       (map inc)
       (filter odd?)
       (map (fn [x] (* x x)))
       (reduce + 0)))

;; Warm-up every fn past the AUTO hot threshold (100 calls).
(dotimes [_ 200]
  (dec-only 1000)
  (dec-inc 1000)
  (lt-inc 1000)
  (lt-only 1000)
  (fib 20)
  (pipeline 1000))

;; ---- raw mode ----
;;
;; Each line is `time` on a single top-level call. Repeated 7 times so
;; the first iter (potentially slower) can be discarded by eye.

(println "== JIT loop shapes -- raw `time` (7 samples per shape) ==")
(println)
(println "  dec-only 10M")
(dotimes [_ 7] (time (dec-only N)))
(println)
(println "  dec-inc 10M (headline)")
(dotimes [_ 7] (time (dec-inc N)))
(println)
(println "  lt-inc 10M")
(dotimes [_ 7] (time (lt-inc N)))
(println)
(println "  lt-only 10M (control)")
(dotimes [_ 7] (time (lt-only N)))
(println)
(println "  fib(30)")
(dotimes [_ 7] (time (fib 30)))
(println)
(println "  pipeline 50k")
(dotimes [_ 7] (time (pipeline 50000)))
(println)

;; ---- harness mode ----

(bench/run-suite "JIT loop shapes via harness (closure dispatch)"
  [["dec-only 10M             x 5" 5 (fn [] (dec-only N))]
   ["dec-inc  10M (headline)  x 5" 5 (fn [] (dec-inc  N))]
   ["lt-inc   10M             x 5" 5 (fn [] (lt-inc   N))]
   ["lt-only  10M (control)   x 5" 5 (fn [] (lt-only  N))]
   ["fib(30)                  x 5" 5 (fn [] (fib 30))]
   ["pipeline 50k             x 5" 5 (fn [] (pipeline 50000))]])
