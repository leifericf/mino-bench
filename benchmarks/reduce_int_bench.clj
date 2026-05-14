(ns benchmarks.reduce-int-bench)
(require '[mino.bench :as bench])

;; Reducer hot-path coverage for the unboxed-int accumulator fast lane
;; landed in v0.164.0. Each variant covers a distinct source shape so
;; the matrix can spot regressions in any individual reducer-walk
;; entry point (range / vec / list / set / map / pipeline).

(def ^:private v1k     (vec (range 1000)))
(def ^:private v100k   (vec (range 100000)))
(def ^:private l1k     (apply list (range 1000)))
(def ^:private l100k   (apply list (range 100000)))
(def ^:private s1k     (set (range 1000)))
(def ^:private s100k   (set (range 100000)))

(bench/run-suite "Reduce int-acc fast lane"
  [["reduce-sum-range-1m"    50
    (fn [] (reduce + (range 1000000)))]
   ["reduce-mul-range-1k"    1000
    (fn [] (reduce * (range 1 1000)))]
   ["reduce-sum-vec-100k"    100
    (fn [] (reduce + v100k))]
   ["reduce-sum-vec-1k"      10000
    (fn [] (reduce + v1k))]
   ["reduce-sum-list-100k"   100
    (fn [] (reduce + l100k))]
   ["reduce-sum-list-1k"     10000
    (fn [] (reduce + l1k))]
   ["reduce-sum-set-100k"    100
    (fn [] (reduce + s100k))]
   ["reduce-sum-set-1k"      10000
    (fn [] (reduce + s1k))]
   ["reduce-sum-map-inc"     100
    (fn [] (reduce + (map inc (range 100000))))]
   ["reduce-sum-filter-odd"  100
    (fn [] (reduce + 0 (filter odd? (range 100000))))]
   ["reduce-sum-map-filter"  100
    (fn [] (reduce + (filter odd? (map inc (range 100000)))))]
   ["reduce-bit-or-vec"      1000
    (fn [] (reduce bit-or 0 v1k))]
   ["reduce-bit-and-vec"     1000
    (fn [] (reduce bit-and -1 v1k))]
   ["reduce-bit-xor-vec"     1000
    (fn [] (reduce bit-xor 0 v1k))]])
