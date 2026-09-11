(ns benchmarks.pipeline-transducer-bench)
(require '[mino.bench :as bench])

;; Transducer fast-path fusion (seq-fusion coverage cycle, phase 4).
;; Before this phase a transduce / into-with-xform threaded every element
;; through a chain of interpreted (rf -> rf) closures, so a comp of
;; standard transducers ran ~19x slower than the equivalent fused lazy
;; reduce. This phase tags the standard 1-arity transducers with an
;; inspectable stage tag; comp preserves an ordered stage vector when all
;; operands are tagged, and transduce / into route a fully recognised
;; chain through the same pipeline walker the seq path uses. Opaque,
;; stateful, or user transducers keep the closure path unchanged, so
;; fusion never changes semantics.
;;
;; A comp containing any unrecognised xform gets no stage vector and
;; slow-paths; the "opaque" row below pins that fallback cost.

(defn run []
  (bench/run-suite "Pipeline transducer fusion"
    [;; the headline: comp of two standard stage transducers, scalar fold
     ["transduce-map-filter" 1000
      (fn [] (transduce (comp (map inc) (filter odd?)) + 0 (range 1000)))]
     ;; the fused lazy reduce it is meant to match (reduce-class target)
     ["reduce-map-filter" 1000
      (fn [] (reduce + 0 (filter odd? (map inc (range 1000)))))]
     ;; longer standard comp, still fully fusible
     ["transduce-three-stage" 1000
      (fn [] (transduce (comp (map inc) (filter odd?) (remove #(> % 900)))
                        + 0 (range 1000)))]
     ;; into a vector with an xform (collection-producing consumer)
     ["into-vector-xform" 1000
      (fn [] (into [] (comp (map inc) (filter odd?)) (range 1000)))]
     ;; fallback: an untagged (hand-written) transducer in the comp forces
     ;; the whole chain onto the closure path, pinning the slow-path cost
     ["transduce-opaque" 1000
      (fn [] (let [opaque-inc (fn [rf]
                                (fn ([] (rf))
                                    ([r] (rf r))
                                    ([r x] (rf r (inc x)))))]
               (transduce (comp opaque-inc (filter odd?)) + 0 (range 1000))))]]))

(run)
