(ns benchmarks.recur-shape-bench)
(require '[mino.bench :as bench])

;; Forward-counted loop shapes for the recur-shape fusion expansion
;; landed in v0.167.0. Each variant exercises one shape that the
;; pre-cycle matcher missed: 1-binding < / >= / <= / > and 2-binding
;; counter+carry. Range source kept out of these intentionally --
;; (range) goes through reduce_int_range, not the fused-loop opcodes.

(bench/run-suite "Recur-shape fusion"
  [["loop-1b-ge 100"      10000
    (fn [] (loop [i 0] (if (>= i 100) i (recur (inc i)))))]
   ["loop-1b-ge 1k"       1000
    (fn [] (loop [i 0] (if (>= i 1000) i (recur (inc i)))))]
   ["loop-1b-ge 10k"      100
    (fn [] (loop [i 0] (if (>= i 10000) i (recur (inc i)))))]
   ["loop-1b-lt 10k"      100
    (fn [] (loop [i 0] (if (< i 10000) (recur (inc i)) i)))]
   ["loop-1b-gt 10k"      100
    (fn [] (loop [i 0] (if (> 10000 i) (recur (inc i)) i)))]
   ["loop-1b-le 10k"      100
    (fn [] (loop [i 0] (if (<= 10000 i) i (recur (inc i)))))]
   ["loop-2b-ge 10k"      100
    (fn [] (loop [i 0 j 0] (if (>= i 10000) j (recur (inc i) (inc j)))))]
   ["loop-2b-lt 10k"      100
    (fn [] (loop [i 0 j 0] (if (< i 10000) (recur (inc i) (inc j)) j)))]])
