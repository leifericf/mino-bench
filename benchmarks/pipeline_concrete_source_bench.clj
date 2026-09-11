(ns benchmarks.pipeline-concrete-source-bench)
(require '[mino.bench :as bench])

;; mapv / filterv / into over a CONCRETE vector or range source (not a
;; lazy map/filter/take pipeline head). Before the seq-fusion coverage
;; cycle these fell back to seq-iter + per-element apply_callable; the
;; concrete-source phase routes them through the shared pipeline_walk
;; fast path (its inline vector-drain / int-range loop) as a one-stage
;; pipeline, eliminating the per-element seq-iter dispatch.
;;
;; The source vector / range is built once outside the timed fn: a
;; concrete vector has no lazy cells to cache, so reuse does not mask
;; the win (unlike the lazy-pipeline benches).

(def ^:private v (vec (range 1000)))

(bench/run-suite "Pipeline concrete source"
  [["mapv-inc-vector" 1000
    (fn [] (mapv inc v))]
   ["filterv-even-vector" 1000
    (fn [] (filterv even? v))]
   ["into-vector-from-vector" 1000
    (fn [] (into [] v))]
   ["mapv-inc-range" 1000
    (fn [] (mapv inc (range 1000)))]
   ["filterv-even-range" 1000
    (fn [] (filterv even? (range 1000)))]
   ["into-vector-from-range" 1000
    (fn [] (into [] (range 1000)))]])
