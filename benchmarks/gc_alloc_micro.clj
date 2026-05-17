(ns benchmarks.gc-alloc-micro)
(require '[mino.bench :as bench])

;; Allocator-targeted micro benches that isolate the four lever sites for
;; the GC/alloc work: transient builder fast path, persistent HAMT assoc,
;; write-barrier hot path on old->young edges, and pure nursery pressure.
;; Each row should produce alloc-bytes/op and minor/major cycle counts
;; that move only when its specific lever moves.

(defn- transient-build [n]
  (loop [i 0 m (transient {})]
    (if (>= i n)
      (persistent! m)
      (recur (+ i 1) (assoc! m i (* i 3))))))

(defn- persistent-build [n]
  (loop [i 0 m {}]
    (if (>= i n) m (recur (+ i 1) (assoc m i (* i 3))))))

(defn- write-barrier-hot
  "Build an old map by force-promoting it, then in a tight loop assoc a
  young (int*3) value into it. Each assoc fires the write-barrier on an
  old container with a young entry, which is the MAJOR_MARK push path
  this cycle targets for batching."
  [n]
  (let [base (loop [i 0 m {}]
               (if (>= i 200) m (recur (+ i 1) (assoc m i i))))]
    ;; Touch base repeatedly so it ages into the old gen via minor cycles.
    (dotimes [_ 4] (count base))
    (loop [i 0 m base]
      (if (>= i n)
        m
        (recur (+ i 1) (assoc m (mod i 200) (* i 3)))))))

(defn- nursery-pressure
  "Allocate cons-cells in a tight loop without any old-gen interaction.
  Stresses the bump-alloc / freelist + nursery-cycle cadence levers."
  [n]
  (loop [i 0 acc nil]
    (if (>= i n) acc (recur (+ i 1) (cons i acc)))))

(defn run []
  (bench/run-suite "GC/alloc micro benches"
    [["transient-build 5k" 50
      (fn [] (transient-build 5000))]

     ["persistent-build 5k (control)" 50
      (fn [] (persistent-build 5000))]

     ["hamt-assoc 1k tight loop" 100
      (fn [] (loop [i 0 m {}]
               (if (>= i 1000) m (recur (+ i 1) (assoc m i (* i 3))))))]

     ["write-barrier old<-young 5k" 30
      (fn [] (write-barrier-hot 5000))]

     ["nursery-pressure 50k cons" 20
      (fn [] (nursery-pressure 50000))]

     ["alloc-only fixed-size 100k vals" 20
      (fn [] (loop [i 0 acc nil]
               (if (>= i 100000) acc (recur (+ i 1) (list i)))))]]))

(run)
