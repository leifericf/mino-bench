(ns benchmarks.pipeline-consumers-bench)
(require '[mino.bench :as bench])

;; Pipeline-fusion fast paths in non-reduce seq consumers. The
;; v0.157.0 release wired (reduce f acc (->> coll (map f) (filter p)
;; (take n))) into a single walker that skips the intermediate
;; lazy-seq cells; v0.159.0 extends the same fusion to into / mapv /
;; filterv / dorun so the saving applies across the seq-consumer
;; surface rather than just reduce.
;;
;; Each bench constructs a fresh pipeline per iter (the lazy cells
;; cache once realized, so reusing the same head would mask the
;; per-allocation win from the second iter onward).

(defn make-pipeline []
  (->> (range 10000)
       (map inc)
       (filter odd?)
       (take 1000)))

(bench/run-suite "Pipeline consumers"
  [["into-vec-pipeline" 1000
    (fn [] (into [] (make-pipeline)))]
   ["mapv-pipeline" 1000
    (fn [] (mapv inc (make-pipeline)))]
   ["filterv-pipeline" 1000
    (fn [] (filterv even? (make-pipeline)))]
   ["dorun-pipeline" 1000
    (fn [] (dorun (make-pipeline)))]])
