(ns benchmarks.realistic-bench)
(require '[mino.bench :as bench])

;; Programs that exercise eval + alloc + collections + sequence
;; processing together. Target run time per iteration is long enough
;; that GC share is visible above clock noise. Use these for the
;; generational-GC vs. prim-ABI decision; the micro benches are only
;; the floor.
;;
;; Note: avoids str-keyed HAMT churn at scale -- triggers a separate
;; pre-existing correctness bug in the map/GC interaction.

(defn- build-int-map [n]
  (loop [i 0 m {}]
    (if (>= i n) m (recur (+ i 1) (assoc m i (* i 3))))))

(defn- sum-vals [m]
  (reduce + 0 (vals m)))

(defn- bump-values [m ks]
  (reduce (fn [acc k] (assoc acc k (+ 1 (get acc k)))) m ks))

(defn- pipeline [n]
  (->> (range n)
       (map inc)
       (filter odd?)
       (map (fn [x] (* x x)))
       (reduce + 0)))

(defn- nested-vec [n inner]
  (loop [i 0 outer []]
    (if (>= i n) outer
        (recur (+ i 1) (conj outer (vec (range inner)))))))

(defn run []
  (bench/run-suite "Realistic multi-subsystem workloads"
    [;; Build a 5k-entry integer-keyed map, sum values: stresses HAMT
     ;; + small-int cache + reduce.
     ["build 5k int-map and sum" 10
      (fn [] (sum-vals (build-int-map 5000)))]

     ;; Bump every value by 1: structural sharing under repeated assoc
     ;; over a 5k map.
     ["bump 5k int-map values" 5
      (fn [] (let [m  (build-int-map 5000)
                   ks (vec (range 5000))]
               (bump-values m ks)))]

     ;; Long sequence pipeline: lazy map/filter/map through reduce.
     ;; Stresses lazy-seq realization and GC of transient cons cells.
     ["map/filter/map/reduce over 50k" 10
      (fn [] (pipeline 50000))]

     ;; Build nested vectors: 500 x 100 = 50k ints in a 500-vector
     ;; of 100-vectors. Stresses vector-node allocation and GC.
     ["nested vectors 500x100" 10
      (fn [] (nested-vec 500 100))]

     ;; Walk a lazy chain: range + take + doall forces cons building
     ;; through a lazy sequence realization.
     ["realize 10k of lazy range" 20
      (fn [] (doall (take 10000 (range))))]

     ;; Recursive fib(25): allocation-light but GC root churn through
     ;; deep call tree.
     ["fibonacci(25)" 10
      (fn [] ((fn fib [n]
                (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
              25))]]))

(run)
