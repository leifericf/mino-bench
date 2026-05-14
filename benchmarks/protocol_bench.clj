(ns benchmarks.protocol-bench)
(require '[mino.bench :as bench])

;; Protocol-dispatch microbenches. Establishes the cost of
;; protocol-method invocation across the monomorphic/polymorphic/
;; megamorphic spectrum + a realistic reduce-over-mixed-types loop.
;;
;; Baseline at v0.157.0 (measured 2026-05-14 on M-class Mac):
;;   proto-mono-area    ~3 477 ns/call
;;   proto-bi-area      ~4 295 ns/call
;;   proto-tri-area    ~43 830 ns/call (case overhead dominates)
;;   proto-reduce-sum   ~1.2 ms/iter   (~4 µs per shape × 300 shapes)
;;   kw-fn-record-loop ~20 µs/iter    (existing fast lane)
;;
;; A protocol-keyed IC implementation should drop the mono case to
;; ~100-200 ns and the reduce-sum case proportionally.

(defprotocol Shape
  (area  [s])
  (peri  [s]))

(defrecord Circle    [r])
(defrecord Rectangle [w h])
(defrecord Triangle  [a b c])

(extend-protocol Shape
  Circle
  (area [c] (* 3.14159 (:r c) (:r c)))
  (peri [c] (* 2 3.14159 (:r c)))
  Rectangle
  (area [r] (* (:w r) (:h r)))
  (peri [r] (* 2 (+ (:w r) (:h r))))
  Triangle
  (area [t] (let [s (/ (+ (:a t) (:b t) (:c t)) 2)]
              (* s (- s (:a t)) (- s (:b t)) (- s (:c t)))))
  (peri [t] (+ (:a t) (:b t) (:c t))))

(defn run []
  (bench/run-suite "Protocol dispatch"
    [["proto-mono-area" 100000
      (let [c (->Circle 5)]
        (fn [] (area c)))]
     ["proto-bi-area" 100000
      (let [c (->Circle 5)
            r (->Rectangle 3 4)
            which (volatile! true)]
        (fn []
          (vswap! which not)
          (if @which (area c) (area r))))]
     ["proto-tri-area" 100000
      (let [c   (->Circle 5)
            r   (->Rectangle 3 4)
            t   (->Triangle 3 4 5)
            cnt (volatile! 0)]
        (fn []
          (vswap! cnt inc)
          (let [k (mod @cnt 3)]
            (case k 0 (area c) 1 (area r) (area t)))))]
     ["proto-reduce-sum" 1000
      (let [shapes (vec (concat (repeat 100 (->Circle 5))
                                (repeat 100 (->Rectangle 3 4))
                                (repeat 100 (->Triangle 3 4 5))))]
        (fn [] (reduce + 0 (map area shapes))))]
     ["kw-fn-record-loop" 1000
      (let [shapes (vec (repeat 100 (->Circle 5)))]
        (fn [] (reduce + 0 (map :r shapes))))]]))

(run)
