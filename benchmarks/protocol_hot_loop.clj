(ns benchmarks.protocol-hot-loop)
(require '[mino.bench :as bench])

;; Hot-loop protocol dispatch shapes that exercise the
;; PROTOCOL_CALL_CACHED inline-cache behaviour: a single dispatched
;; method called repeatedly with stable type (cache hit) and
;; alternating types (cache thrash). The existing protocol_bench
;; covers the call-site cost matrix; this bench characterises the
;; cache-line behaviour inside a real hot loop where the call
;; appears thousands of times per outer iteration.

(defprotocol Reading
  (sample [r]))

(defrecord Pulse   [hz])
(defrecord Voltage [v])
(defrecord Temp    [c])

(extend-protocol Reading
  Pulse   (sample [r] (:hz r))
  Voltage (sample [r] (:v r))
  Temp    (sample [r] (:c r)))

(def pulses   (mapv (fn [i] (->Pulse i)) (range 100)))
(def mixed-2  (mapv (fn [i]
                       (if (even? i) (->Pulse i) (->Voltage i)))
                     (range 100)))
(def mixed-3  (mapv (fn [i]
                       (let [m (mod i 3)]
                         (cond
                           (zero? m) (->Pulse i)
                           (= 1 m)   (->Voltage i)
                           :else     (->Temp i))))
                     (range 100)))

;; ---- 1. Stable-type IC hit: 1 record class throughout. -----------

(defn- mono-loop [n coll]
  (loop [i 0 acc 0]
    (if (>= i n)
      acc
      (recur (+ i 1)
             (reduce (fn [a r] (+ a (sample r))) acc coll)))))

;; ---- 2. Two-type IC bounce: alternating Pulse/Voltage. -----------

(defn- bi-loop [n coll]
  (loop [i 0 acc 0]
    (if (>= i n)
      acc
      (recur (+ i 1)
             (reduce (fn [a r] (+ a (sample r))) acc coll)))))

;; ---- 3. Three-type IC churn: Pulse/Voltage/Temp. -----------------

(defn- tri-loop [n coll]
  (loop [i 0 acc 0]
    (if (>= i n)
      acc
      (recur (+ i 1)
             (reduce (fn [a r] (+ a (sample r))) acc coll)))))

(bench/run-suite "Protocol hot-loop dispatch"
  [["mono 100 outer x 100 inner"  100 (fn [] (mono-loop 100 pulses))]
   ["mono 1k outer x 100 inner"   100 (fn [] (mono-loop 1000 pulses))]
   ["bi   100 outer x 100 inner"  100 (fn [] (bi-loop 100 mixed-2))]
   ["bi   1k outer x 100 inner"   100 (fn [] (bi-loop 1000 mixed-2))]
   ["tri  100 outer x 100 inner"  100 (fn [] (tri-loop 100 mixed-3))]
   ["tri  1k outer x 100 inner"   100 (fn [] (tri-loop 1000 mixed-3))]])
