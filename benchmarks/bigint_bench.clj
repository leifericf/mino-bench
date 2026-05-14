(ns benchmarks.bigint-bench)
(require '[mino.bench :as bench])

(defn run []
  (bench/run-suite "BigInt arithmetic"
    [["bigint-add range 1-1000" 100
      (fn [] (reduce + 0N (range 1 1000)))]
     ["bigint-mul' range 1-200" 100
      (fn [] (reduce *' 1 (range 1 200)))]
     ["bigint-mul' range 1-1000" 10
      (fn [] (reduce *' 1 (range 1 1000)))]
     ["int-add range 1-1000 (control)" 1000
      (fn [] (reduce + 0 (range 1 1000)))]]))

(run)
