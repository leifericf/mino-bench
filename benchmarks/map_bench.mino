(ns benchmarks.map-bench)
(require '[mino.bench :as bench])

(defn run []
  (bench/run-suite "Map operations"
    [["assoc 100 keys"  1000
      (fn [] (loop [i 0 m {}]
               (if (>= i 100) m (recur (inc i) (assoc m (keyword (str "k" i)) i)))))]
     ["assoc 1000 keys" 100
      (fn [] (loop [i 0 m {}]
               (if (>= i 1000) m (recur (inc i) (assoc m (keyword (str "k" i)) i)))))]
     ["get on 100-key map" 10000
      (let [m (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 100)))]
        (fn [] (get m :k50)))]
     ["get on 1000-key map" 10000
      (let [m (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 1000)))]
        (fn [] (get m :k500)))]
     ["dissoc from 100-key map" 1000
      (let [m (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 100)))]
        (fn [] (dissoc m :k50)))]]))

(run)
