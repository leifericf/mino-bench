(ns benchmarks.jit-blocker-histogram)

;; Drive realistic_bench.clj with MINO_CPJIT_STATS=1 and emit a sorted
;; blocker histogram. The output names which opcodes are causing
;; eligibility rejection in the workloads users actually care about;
;; that pruned list is the JIT cycle's stencil backlog.
;;
;; Usage:
;;   ./mino/mino benchmarks/jit_blocker_histogram.clj
;;
;; The cpjit-stats facility (mino v0.284.0+) prints each blocker row
;; as "  op=<id>  <OP_NAME>  <N> fns", so this script doesn't need to
;; carry its own opcode-name table.

(require '[clojure.string :as str])

(def ^:private workload "benchmarks/realistic_bench.clj")
(def ^:private breakdown-prefix
  "[cpjit-stats] ---- unknown-op breakdown ----")

(defn- parse-stats [combined]
  (let [lines (str/split combined "\n")
        idx   (loop [i 0 xs lines]
                (cond
                  (empty? xs)                     nil
                  (= (first xs) breakdown-prefix) i
                  :else                           (recur (inc i) (rest xs))))]
    (when idx
      (->> (drop (inc idx) lines)
           (take-while (fn [s]
                         (and (str/starts-with? s "  op=")
                              (not (= s "")))))
           (mapv (fn [s]
                   ;; "  op=17   OP_MAKE_LAZY                    9 fns"
                   (let [toks (filterv #(not (= "" %)) (str/split s " "))
                         op   (parse-long (subs (toks 0) 3))
                         name (toks 1)
                         n    (parse-long (toks 2))]
                     {:op op :name name :count n})))))))

(let [r    (sh "env" "MINO_JIT=on" "MINO_CPJIT_STATS=1"
                "./mino/mino" workload)
      out  (str (:out r))
      hist (parse-stats out)]
  (println "=== JIT blocker histogram (workload: realistic_bench.clj) ===")
  (println (str "  child mino exit: " (:exit r)))
  (if (empty? hist)
    (println "  (no blockers reported -- check MINO_CPJIT_STATS support)")
    (do
      (println)
      (println "  | opcode                     | id  | fns blkd |")
      (println "  | -------------------------- | --- | -------- |")
      (doseq [{:keys [op name count]} (sort-by (fn [m] (- (:count m))) hist)]
        (println (str "  | "
                      (subs (str name "                          ") 0 26)
                      " | "
                      (format "%3d" op)
                      " | "
                      (format "%8d" count)
                      " |"))))))
