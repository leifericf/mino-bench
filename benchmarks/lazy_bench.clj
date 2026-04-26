(ns benchmarks.lazy-bench)
(require '[mino.bench :as bench])

;; Five suites exposing the cost delta between the C-backed lazy
;; primitives (lazy-map-1, lazy-filter, lazy-take) and pure-mino
;; equivalents written directly over lazy-seq + cons. Each C primitive
;; saves a mino-level fn-call frame per element; this bench measures
;; how much that saving actually buys.
;;
;;   1. Realization of a single lazy op over small/medium/large seq
;;   2. Stacked chain depth (map . map . map, filter . map, etc.)
;;   3. take N from a longer lazy chain (partial realization)
;;   4. Lazy vs eager (map/range vs mapv/rangev) reduction
;;   5. Realistic pipeline (range -> filter -> map -> take -> reduce)
;;
;; A pure-mino variant is defined inline for direct comparison and is
;; run side-by-side with the C-backed version. The Gate B threshold
;; (2x slowdown on realistic pipeline) decides whether the lazy C
;; fast-paths are worth the ~360 LOC of prim_lazy.c.

;; --- Pure-mino equivalents (no lazy-map-1 / lazy-filter / lazy-take) ---

(defn map-pure [f coll]
  (lazy-seq
    (let [s (seq coll)]
      (when s
        (cons (f (first s)) (map-pure f (rest s)))))))

(defn filter-pure [pred coll]
  (lazy-seq
    (let [s (seq coll)]
      (when s
        (let [x (first s) r (rest s)]
          (if (pred x)
            (cons x (filter-pure pred r))
            (filter-pure pred r)))))))

(defn take-pure [n coll]
  (lazy-seq
    (when (pos? n)
      (let [s (seq coll)]
        (when s
          (cons (first s) (take-pure (dec n) (rest s))))))))

(defn run []
  ;; --- Suite 1: single-op realization ---
  (bench/run-suite "lazy: single-op realization (reduce over result)"
    [["C: (reduce + 0 (map inc (range 100)))" 5000
      (fn [] (reduce + 0 (map inc (range 100))))]
     ["mino: (reduce + 0 (map-pure inc (range 100)))" 5000
      (fn [] (reduce + 0 (map-pure inc (range 100))))]
     ["C: (reduce + 0 (map inc (range 1000)))" 1000
      (fn [] (reduce + 0 (map inc (range 1000))))]
     ["mino: (reduce + 0 (map-pure inc (range 1000)))" 1000
      (fn [] (reduce + 0 (map-pure inc (range 1000))))]
     ["C: (reduce + 0 (filter odd? (range 1000)))" 1000
      (fn [] (reduce + 0 (filter odd? (range 1000))))]
     ["mino: (reduce + 0 (filter-pure odd? (range 1000)))" 1000
      (fn [] (reduce + 0 (filter-pure odd? (range 1000))))]])

  ;; --- Suite 2: stacked chain depth ---
  (bench/run-suite "lazy: stacked chains (3-4 ops)"
    [["C: map . map . map over 500" 1000
      (fn [] (reduce + 0 (map inc (map inc (map inc (range 500))))))]
     ["mino: map-pure . map-pure . map-pure over 500" 1000
      (fn [] (reduce + 0 (map-pure inc (map-pure inc (map-pure inc (range 500))))))]
     ["C: filter . map . filter over 500" 1000
      (fn [] (reduce + 0 (filter odd? (map inc (filter pos? (range 500))))))]
     ["mino: pure filter . map . filter over 500" 1000
      (fn [] (reduce + 0 (filter-pure odd? (map-pure inc (filter-pure pos? (range 500))))))]])

  ;; --- Suite 3: take subset (partial realization) ---
  (bench/run-suite "lazy: take subset (partial realization)"
    [["C: (take 10 (map inc (range 10000)))" 5000
      (fn [] (reduce + 0 (take 10 (map inc (range 10000)))))]
     ["mino: (take-pure 10 (map-pure inc (range 10000)))" 5000
      (fn [] (reduce + 0 (take-pure 10 (map-pure inc (range 10000)))))]
     ["C: (take 100 (filter odd? (range 10000)))" 1000
      (fn [] (reduce + 0 (take 100 (filter odd? (range 10000)))))]
     ["mino: (take-pure 100 (filter-pure odd? (range 10000)))" 1000
      (fn [] (reduce + 0 (take-pure 100 (filter-pure odd? (range 10000)))))]])

  ;; --- Suite 4: lazy vs eager ---
  (bench/run-suite "lazy: vs eager (mapv + rangev)"
    [["eager: (reduce + 0 (mapv inc (rangev 1000)))" 1000
      (fn [] (reduce + 0 (mapv inc (rangev 1000))))]
     ["C lazy: (reduce + 0 (map inc (range 1000)))" 1000
      (fn [] (reduce + 0 (map inc (range 1000))))]
     ["mino lazy: (reduce + 0 (map-pure inc (range 1000)))" 1000
      (fn [] (reduce + 0 (map-pure inc (range 1000))))]])

  ;; --- Suite 5: realistic pipeline ---
  (bench/run-suite "lazy: realistic pipeline"
    [["C: range -> filter -> map -> take -> reduce" 1000
      (fn []
        (reduce +
                0
                (take 50 (map (fn [x] (* x x))
                              (filter odd? (range 10000))))))]
     ["mino: pure range -> filter -> map -> take -> reduce" 1000
      (fn []
        (reduce +
                0
                (take-pure 50 (map-pure (fn [x] (* x x))
                                        (filter-pure odd? (range 10000))))))]
     ["C: double pipeline" 500
      (fn []
        (reduce +
                0
                (map (fn [x] (+ x 1))
                     (filter even?
                             (map (fn [x] (* x 2))
                                  (take 200 (range 10000)))))))]
     ["mino: pure double pipeline" 500
      (fn []
        (reduce +
                0
                (map-pure (fn [x] (+ x 1))
                          (filter-pure even?
                                       (map-pure (fn [x] (* x 2))
                                                 (take-pure 200 (range 10000)))))))]]))

(run)
