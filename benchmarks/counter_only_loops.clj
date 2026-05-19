(ns benchmarks.counter-only-loops)
(require '[mino.bench :as bench])

;; Counter-only loop shapes that match the JIT's fused
;; loop-stencil patterns. Closes a corpus dark spot the
;; cycle E synthesis dashboard surfaced: the v0.348 native
;; sampler showed zero hits on `(loop [i 0] (recur (inc i)))`-
;; style tight loops because the existing jit_loops_advanced
;; rows only exercised two-binding shapes.
;;
;; Each row's body shape and the corresponding fused opcode:
;;
;;   forward-lt    -> OP_LOOP_INT_LT
;;   reverse-dec   -> OP_LOOP_INT_DEC
;;   commuted-plus -> OP_LOOP_INT_LT (matches (+ i 1) as well)
;;
;; The "raw" measurements run a top-level (time ...) per
;; shape so the inner-loop floor is visible without the
;; closure-call overhead of the harness path.

(def N 10000000)

(defn forward-lt [n]
  (loop [i 0]
    (if (< i n) (recur (inc i)) i)))

(defn reverse-dec [n]
  (loop [i n]
    (if (zero? i) i (recur (dec i)))))

(defn commuted-plus [n]
  (loop [i 0]
    (if (< i n) (recur (+ i 1)) i)))

(dotimes [_ 200]
  (forward-lt 1000)
  (reverse-dec 1000)
  (commuted-plus 1000))

(println "== Counter-only loops -- raw time (7 samples per shape) ==")
(println)
(println "  forward-lt 10M (OP_LOOP_INT_LT)")
(dotimes [_ 7] (time (forward-lt N)))
(println)
(println "  reverse-dec 10M (OP_LOOP_INT_DEC)")
(dotimes [_ 7] (time (reverse-dec N)))
(println)
(println "  commuted-plus 10M (OP_LOOP_INT_LT via (+ i 1))")
(dotimes [_ 7] (time (commuted-plus N)))
(println)

(bench/run-suite "Counter-only loops via harness"
  [["forward-lt   10M  x 5" 5 (fn [] (forward-lt   N))]
   ["reverse-dec  10M  x 5" 5 (fn [] (reverse-dec  N))]
   ["commuted-plus 10M x 5" 5 (fn [] (commuted-plus N))]])
