(ns benchmarks.set-bench)
(require '[mino.bench :as bench])

(defn run []
  (bench/run-suite "Set operations"
    [["conj 100 elements" 1000
      (fn [] (loop [i 0 s #{}]
               (if (>= i 100) s (recur (inc i) (conj s i)))))]
     ["conj 1000 elements" 100
      (fn [] (loop [i 0 s #{}]
               (if (>= i 1000) s (recur (inc i) (conj s i)))))]
     ["contains? on 1000-set" 10000
      (let [s (into #{} (range 1000))]
        (fn [] (contains? s 500)))]
     ["transient conj! 1000 elements" 100
      (fn [] (persistent! (loop [i 0 t (transient #{})]
                            (if (>= i 1000) t
                                (recur (inc i) (conj! t i))))))]
     ["into #{} from 1000-vec" 100
      (let [v (vec (range 1000))]
        (fn [] (into #{} v)))]
     ["disj from 100-set" 1000
      (let [s (into #{} (range 100))]
        (fn [] (disj s 50)))]]))

(run)
