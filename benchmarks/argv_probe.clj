(ns benchmarks.argv-probe)
(require '[mino.bench :as bench])
(let [m (loop [i 0 acc {}] (if (>= i 5000) acc (recur (+ i 1) (assoc acc i (* i 3)))))
      ks (vec (range 5000))]
  (bench/run-suite "argv probe"
    [["bump assoc!" 10
      (fn [] (persistent!
              (reduce (fn [acc k] (assoc! acc k (+ 1 (get acc k))))
                      (transient m) ks)))]
     ["just get" 10
      (fn [] (reduce (fn [_ k] (get m k)) nil ks))]]))
