;; coldstart_compare.clj — wall-time cold-start comparison between
;; mino, Lua 5.5, Janet, and Babashka. Each interpreter is invoked
;; with the equivalent "evaluate (+ 1 2) and exit" expression, after
;; three discarded warmup runs. Reports min / median / p90 / max in
;; milliseconds.
;;
;; Lua, Janet, and Babashka are picked up from $PATH (`brew install`
;; on this host); mino is the locally-built submodule binary so the
;; comparison reflects the current mino tip rather than the released
;; Homebrew bottle.

(require '[clojure.string :as str])

(def ^:private mino-bin "./mino/mino")
(def ^:private cold-runs 50)

(defn- which [bin]
  (let [r (sh "which" bin)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn- median [xs]
  (let [v (vec (sort xs)) n (count v)]
    (when (pos? n) (nth v (quot n 2)))))

(defn- p90 [xs]
  (let [v (vec (sort xs)) n (count v)]
    (when (pos? n) (nth v (max 0 (dec (long (* 0.9 n))))))))

(defn- ns->ms [x]
  (/ (long (/ x 10000)) 100.0))

(defn- time-spawn [argv]
  (let [t0 (nano-time)]
    (apply sh argv)
    (- (nano-time) t0)))

(defn- bench [label argv]
  (println (str "  " label))
  (dotimes [_ 3] (time-spawn argv))
  (let [samples (vec (repeatedly cold-runs (fn [] (time-spawn argv))))
        sorted-vec (sort samples)]
    {:label  label
     :argv   argv
     :runs   cold-runs
     :min-ms (ns->ms (first sorted-vec))
     :median-ms (ns->ms (median samples))
     :p90-ms (ns->ms (p90 samples))
     :max-ms (ns->ms (last sorted-vec))}))

(defn- file-size [path]
  (let [r (sh "stat" "-c%s" path)]
    (when (zero? (:exit r)) (read-string (str/trim (:out r))))))

(defn- binary-size-kb [bin-path]
  (when bin-path
    (let [r (sh "readlink" "-f" bin-path)
          real (if (zero? (:exit r)) (str/trim (:out r)) bin-path)
          tmp "results/.tmp_cold_compare_stripped"]
      (when (zero? (:exit (sh "strip" "--strip-all" "-o" tmp real)))
        (let [n (file-size tmp)]
          (sh "rm" "-f" tmp)
          (when n (quot n 1024)))))))

(defn- fmt-row [name r kb]
  (str "  " name " "
       (apply str (repeat (max 0 (- 12 (count name))) " "))
       (format "%6.2f ms median  p90 %5.2f ms  range %5.2f–%5.2f  (%s KB stripped)"
               (:median-ms r) (:p90-ms r) (:min-ms r) (:max-ms r)
               (or kb "?"))))

(println "=== cold start comparison (eval (+ 1 2) and exit) ===")
(println (str "host: " (str/trim (:out (sh "uname" "-srm")))))
(println (str "runs: " cold-runs " per interpreter, after 3 warmup runs"))
(println)

(let [results
      (->> [["mino"     mino-bin                 [mino-bin "-e" "(+ 1 2)"]]
            ["lua"      (which "lua")            [(which "lua")    "-e" "print(1+2)"]]
            ["janet"    (which "janet")          [(which "janet")  "-e" "(print (+ 1 2))"]]
            ["babashka" (which "bb")             [(which "bb")     "-e" "(println (+ 1 2))"]]]
           (filterv (fn [[_ p _]] (and p (zero? (:exit (sh "test" "-x" p))))))
           (mapv (fn [[name bin argv]]
                   (let [r  (bench name argv)
                         kb (binary-size-kb bin)]
                     (assoc r :name name :bin bin :stripped-kb kb)))))]
  (println)
  (println "=== results ===")
  (doseq [r (sort-by :median-ms results)]
    (println (fmt-row (:name r) r (:stripped-kb r))))
  (spit "results/coldstart_compare.edn" (pr-str results))
  (println)
  (println "wrote results/coldstart_compare.edn"))
