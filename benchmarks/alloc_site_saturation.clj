(ns benchmarks.alloc-site-saturation)
(require '[mino.bench :as bench])

;; Allocates across the full set of GC tag classes in roughly
;; equal volumes so the v0.348 alloc-site sampler dump
;; (MINO_ALLOC_SAMPLE=1) sees broad site coverage rather than
;; the val-heavy fingerprint the existing mix workload
;; produces.
;;
;; Tags exercised (per src/gc/internal.h GC_T_* enum):
;;
;;   :val       -- mino_cons + tagged-int boxing on overflow
;;   :valarr    -- vec node trees
;;   :raw       -- POD buffers (strings, byte arrays)
;;   :env       -- env_child + env_bind from fn entry
;;   :vec-node  -- vec branch nodes (>32 elems)
;;   :hamt-node -- map branch nodes (>=8 entries)
;;   :hamt-entry -- map leaf entries
;;   :ptrarr    -- internal pointer arrays
;;   :rb-node   -- sorted-map nodes
;;   :bc        -- fn-template records (closure compile)

(def N 10000)

(defn val-heavy [n]
  (loop [i 0 acc nil]
    (if (< i n) (recur (inc i) (cons i acc)) acc)))

(defn vec-heavy [n]
  (loop [i 0 v []]
    (if (< i n) (recur (inc i) (conj v i)) v)))

(defn map-heavy [n]
  (loop [i 0 m {}]
    (if (< i n) (recur (inc i) (assoc m i (str i))) m)))

(defn sorted-map-heavy [n]
  ;; `into` over a sorted-map preserves the sorted-map identity
  ;; and walks the seq without a transient (unsupported on
  ;; sorted-map); exercises rb-node allocs per insertion.
  (into (sorted-map) (map (fn [i] [i (str i)]) (range n))))

(defn closure-heavy [n]
  (loop [i 0 fs nil]
    (if (< i n)
      (recur (inc i) (cons (fn [x] (+ x i)) fs))
      (count fs))))

(defn string-heavy [n]
  (loop [i 0 acc ""]
    (if (< i n) (recur (inc i) (str acc i)) (count acc))))

(dotimes [_ 50]
  (val-heavy 100)
  (vec-heavy 100)
  (map-heavy 100)
  (sorted-map-heavy 100)
  (closure-heavy 50)
  (string-heavy 100))

(println "== Alloc-site saturation -- raw time (5 samples per shape) ==")
(println)
(println "  val-heavy 10k cons spine")
(dotimes [_ 5] (time (val-heavy N)))
(println)
(println "  vec-heavy 10k conj")
(dotimes [_ 5] (time (vec-heavy N)))
(println)
(println "  map-heavy 10k assoc")
(dotimes [_ 5] (time (map-heavy N)))
(println)
(println "  sorted-map-heavy 10k assoc")
(dotimes [_ 5] (time (sorted-map-heavy N)))
(println)
(println "  closure-heavy 5k closures")
(dotimes [_ 5] (time (closure-heavy 5000)))
(println)
(println "  string-heavy 10k str chain")
(dotimes [_ 5] (time (string-heavy N)))
(println)

(bench/run-suite "Alloc-site saturation via harness"
  [["val-heavy        10k x 3" 3 (fn [] (val-heavy        N))]
   ["vec-heavy        10k x 3" 3 (fn [] (vec-heavy        N))]
   ["map-heavy        10k x 3" 3 (fn [] (map-heavy        N))]
   ["sorted-map-heavy 10k x 3" 3 (fn [] (sorted-map-heavy N))]
   ["closure-heavy    5k  x 3" 3 (fn [] (closure-heavy    5000))]
   ["string-heavy     10k x 3" 3 (fn [] (string-heavy     N))]])
