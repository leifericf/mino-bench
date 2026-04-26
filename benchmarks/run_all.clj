;; Run each benchmark file in a separate mino process for isolation.
;; Each process gets a fresh heap, so GC pressure from one benchmark
;; does not affect others.

(def ^:private mino-bin "mino/mino")

(def ^:private bench-files
  ["benchmarks/cons_bench.clj"
   "benchmarks/vec_bench.clj"
   "benchmarks/map_bench.clj"
   "benchmarks/intern_bench.clj"
   "benchmarks/eval_bench.clj"
   "benchmarks/micro_bench.clj"
   "benchmarks/realistic_bench.clj"
   "benchmarks/async_bench.clj"
   "benchmarks/actor_bench.clj"
   "benchmarks/lazy_bench.clj"
   "benchmarks/bot_fleet_bench.clj"])

(defn- extract-edn-lines
  "Extract lines prefixed with ;edn; from output, return as vector of strings."
  [output]
  (filterv #(starts-with? % ";edn; ")
           (split output "\n")))

(defn- strip-prefix [line]
  (subs line 6))

(println "=== mino-bench: mino-level benchmarks (isolated) ===")
(println)

(let [all-edn (atom [])]
  (doseq [f bench-files]
    (let [output   (sh! mino-bin f)
          edn-lines (extract-edn-lines output)
          ;; Print the human-readable lines (non-edn)
          human    (filterv #(not (starts-with? % ";edn; "))
                            (split output "\n"))]
      (doseq [line human]
        (when (not (= line ""))
          (println line)))
      (doseq [line edn-lines]
        (swap! all-edn conj (strip-prefix line)))))

  ;; Write combined report
  (let [report (str ";; mino-bench report (isolated processes)\n"
                    ";; date: " (time-ms) "\n"
                    ";;\n"
                    (apply str (map (fn [line] (str line "\n")) @all-edn)))]
    (spit "results/latest.edn" report)
    (println)
    (println (str "Report written to results/latest.edn (" (count @all-edn) " benchmarks)"))))

(println "=== done ===")
