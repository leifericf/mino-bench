(ns benchmarks.pipeline-terminal-consumers-bench)
(require '[mino.bench :as bench])

;; Terminal (non-reduce) seq consumers over a lazy map/filter pipeline.
;; Before this phase count / some / every? / frequencies / group-by
;; forced or seq-iterated the whole pipeline through intermediate lazy
;; cells; the seq-fusion coverage cycle routes them through the shared
;; pipeline_walk fast path (the same walker reduce / into / mapv use).
;;
;; A fresh pipeline is built per iteration: lazy cells cache once
;; realized, so reusing one head would mask the per-iteration
;; allocation win these consumers are meant to eliminate.

(defn make-pipeline []
  (->> (range 1000)
       (map inc)
       (filter odd?)))

(bench/run-suite "Pipeline terminal consumers"
  [["count-pipeline" 1000
    (fn [] (count (make-pipeline)))]
   ["frequencies-pipeline" 1000
    (fn [] (frequencies (make-pipeline)))]
   ["group-by-pipeline" 1000
    (fn [] (group-by odd? (make-pipeline)))]
   ;; some / every? early-hit: short-circuit must stay cheap (should
   ;; barely touch the pipeline). Late/never-hit walks the whole seq.
   ["some-early" 1000
    (fn [] (some #(> % 5) (make-pipeline)))]
   ["some-never" 1000
    (fn [] (some #(> % 100000) (make-pipeline)))]
   ["every-early-false" 1000
    (fn [] (every? #(< % 5) (make-pipeline)))]
   ["every-all-true" 1000
    (fn [] (every? odd? (make-pipeline)))]])
