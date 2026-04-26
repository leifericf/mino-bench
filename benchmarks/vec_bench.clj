(ns benchmarks.vec-bench)
(require '[mino.bench :as bench])

(defn run []
  (bench/run-suite "Vector operations"
    [["conj 1000 elements"  1000
      (fn [] (loop [i 0 v []]
               (if (>= i 1000) v (recur (inc i) (conj v i)))))]
     ["conj 10000 elements" 100
      (fn [] (loop [i 0 v []]
               (if (>= i 10000) v (recur (inc i) (conj v i)))))]
     ["nth random on 1000-vec" 10000
      (let [v (into [] (range 1000))]
        (fn [] (nth v 500)))]
     ["nth random on 10000-vec" 10000
      (let [v (into [] (range 10000))]
        (fn [] (nth v 5000)))]
     ["assoc on 1000-vec" 1000
      (let [v (into [] (range 1000))]
        (fn [] (assoc v 500 :updated)))]
     ["pop 1000-vec to empty" 100
      (let [v (into [] (range 1000))]
        (fn [] (loop [acc v]
                 (if (empty? acc) acc (recur (pop acc))))))]]))

(run)
