(ns benchmarks.cons-bench)
(require '[mino.bench :as bench])

(defn run []
  (bench/run-suite "Cons list operations"
    [["build 1000-element list"  1000
      (fn [] (loop [i 0 acc nil]
               (if (>= i 1000) acc (recur (inc i) (cons i acc)))))]
     ["build 10000-element list" 100
      (fn [] (loop [i 0 acc nil]
               (if (>= i 10000) acc (recur (inc i) (cons i acc)))))]
     ["walk 1000-element list"   1000
      (let [lst (loop [i 0 acc nil]
                  (if (>= i 1000) acc (recur (inc i) (cons i acc))))]
        (fn [] (loop [l lst n 0]
                 (if (nil? l) n (recur (rest l) (inc n))))))]
     ["walk 10000-element list"  100
      (let [lst (loop [i 0 acc nil]
                  (if (>= i 10000) acc (recur (inc i) (cons i acc))))]
        (fn [] (loop [l lst n 0]
                 (if (nil? l) n (recur (rest l) (inc n))))))]]))

(run)
