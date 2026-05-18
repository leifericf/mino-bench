(ns benchmarks.alloc-pressure-bench)
(require '[mino.bench :as bench])

;; Per-op allocation pressure swept across three size buckets so the
;; GC profile (mark/sweep/promote split, write-barrier hits) can be
;; measured as a function of object-size rather than object-count. The
;; existing realistic_bench `nested vectors 500x100` covers a single
;; point on this curve; this bench fills the three buckets that a GC
;; cycle needs (small / medium / large) so the bench harness can
;; report bytes-allocated and bytes-promoted alongside the wall-time
;; baseline.
;;
;; "Small" ~64 B per op: a 4-element vector.
;; "Medium" ~1 KB per op: a 64-element vector of ints.
;; "Large" ~16 KB per op: a 1024-element vector of pairs.

(defn- alloc-small [n]
  (loop [i 0 acc 0]
    (if (>= i n)
      acc
      (let [v [i (* i 2) (* i 3) (* i 4)]]
        (recur (+ i 1) (+ acc (nth v 3)))))))

(defn- alloc-medium [n]
  (loop [i 0 acc 0]
    (if (>= i n)
      acc
      (let [v (vec (range 64))]
        (recur (+ i 1) (+ acc (nth v 63)))))))

(defn- alloc-large [n]
  (loop [i 0 acc 0]
    (if (>= i n)
      acc
      (let [v (vec (map (fn [k] [k (* k k)]) (range 1024)))]
        (recur (+ i 1) (+ acc (first (nth v 1023))))))))

(bench/run-suite "Allocation pressure spread"
  [["alloc-small 1k"     1000 (fn [] (alloc-small 1000))]
   ["alloc-small 10k"    100  (fn [] (alloc-small 10000))]
   ["alloc-medium 100"   1000 (fn [] (alloc-medium 100))]
   ["alloc-medium 1k"    100  (fn [] (alloc-medium 1000))]
   ["alloc-large 10"     1000 (fn [] (alloc-large 10))]
   ["alloc-large 100"    100  (fn [] (alloc-large 100))]])
