(ns benchmarks.pipeline-widen-stages-bench)
(require '[mino.bench :as bench])

;; Widened-stage pipeline fusion (seq-fusion coverage cycle, phase 3).
;; Before this phase remove / keep / map-indexed / drop broke the fused
;; walk: remove routed through filter+complement (an extra closure call
;; per element), and keep / map-indexed were pure-Clojure lazy-seq
;; implementations whose heads the walker did not recognise, so the
;; pipeline fell to per-element interpreted lazy cells. This phase adds
;; C lazy thunks + recognizers + stage variants so a pipeline built from
;; these stages fuses through the same walker map / filter / take use.
;;
;; A fresh pipeline is built per iteration: lazy cells cache once
;; realized, so reusing one head would mask the per-iteration
;; allocation win these stages are meant to eliminate.

(defn run []
  (bench/run-suite "Pipeline widened stages"
    [;; remove: filter-complement, fused as its own inverted-filter stage
     ["reduce-remove" 1000
      (fn [] (reduce + 0 (remove even? (range 1000))))]
     ;; keep: map + nil-drop
     ["reduce-keep" 1000
      (fn [] (reduce + 0 (keep (fn [x] (when (odd? x) x)) (range 1000))))]
     ;; map-indexed: map + stateful index counter crossing chunk bounds
     ["reduce-map-indexed" 1000
      (fn [] (reduce + 0 (map-indexed (fn [i x] (+ i x)) (range 1000))))]
     ;; drop: stateful skip counter crossing chunk bounds, as a stage
     ["reduce-drop" 1000
      (fn [] (reduce + 0 (map inc (drop 100 (range 1000)))))]
     ;; the plan's headline mixed shape: keep over remove over range
     ["reduce-keep-remove" 1000
      (fn [] (reduce + 0
                     (keep (fn [x] (when (< x 800) x))
                           (remove (fn [x] (zero? (mod x 3)))
                                   (range 1000)))))]
     ;; count over the widened stages (terminal consumer path)
     ["count-remove-keep" 1000
      (fn [] (count (keep (fn [x] (when (odd? x) x))
                          (remove (fn [x] (< x 100)) (range 1000)))))]]))

(run)
