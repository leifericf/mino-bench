;; refresh_perf.clj — collect every number the mino-site Performance
;; and Landing pages cite, in one re-runnable mino script.
;;
;; Invoke from the mino-bench/ root, after `./mino/mino task build`
;; has produced `./mino/mino`:
;;
;;   ./mino/mino tests/refresh_perf.clj
;;
;; Outputs three artefacts in results/:
;;
;;   results/latest.edn         end-to-end per-suite bench data
;;                              (written by benchmarks/run_all.clj)
;;   results/perf_summary.txt   human-readable rollup
;;   results/perf_summary.edn   machine-readable rollup, intended
;;                              to be the source of truth for the
;;                              Performance / Landing page tables
;;
;; Re-invoke whenever the mino submodule moves to a new SHA. The
;; script (re)builds tests/embed_init_bench and tests/min_embed from
;; the submodule's source on every run, so footprint and init
;; numbers always reflect the currently-checked-out mino.

(require '[clojure.string :as str])

;; ---- Configuration -------------------------------------------------

(def ^:private mino-bin           "./mino/mino")
(def ^:private min-embed-bin      "tests/min_embed")
(def ^:private init-bench-bin     "tests/embed_init_bench")
(def ^:private summary-txt        "results/perf_summary.txt")
(def ^:private summary-edn        "results/perf_summary.edn")
(def ^:private cold-runs          50)
(def ^:private init-runs          50)

(def ^:private includes
  ["-Imino/src" "-Imino/src/public" "-Imino/src/runtime"
   "-Imino/src/gc" "-Imino/src/eval" "-Imino/src/collections"
   "-Imino/src/prim" "-Imino/src/async" "-Imino/src/interop"
   "-Imino/src/diag" "-Imino/src/vendor/imath"])

;; ---- Helpers -------------------------------------------------------

(defn- file-exists? [path]
  (zero? (:exit (sh "test" "-e" path))))

(defn- file-size [path]
  (let [r (sh "stat" "-c%s" path)]
    (when (zero? (:exit r))
      (read-string (str/trim (:out r))))))

(defn- mino-version []
  (let [r (sh mino-bin "-V")]
    (str/trim (str (:out r)))))

(defn- find-c-sources []
  (let [r (sh "find" "mino/src" "-name" "*.c")]
    (-> r :out str/trim (str/split "\n"))))

(defn- find-objs []
  (let [r (sh "find" "mino/src" "-name" "*.o")]
    (let [s (str/trim (str (:out r)))]
      (if (= s "") [] (str/split s "\n")))))

(defn- run-cc!
  "Invoke cc with the listed args; throw with stderr if it fails."
  [args]
  (let [r (apply sh "cc" args)]
    (when-not (zero? (:exit r))
      (println "  cc failed:")
      (println (:err r))
      (throw (ex-info "cc invocation failed" {:args args :err (:err r)})))))

(defn- run-strip! [src dst]
  (let [r (sh "strip" "-o" dst src)]
    (when-not (zero? (:exit r))
      (throw (ex-info "strip failed" {:err (:err r)})))))

;; ---- Build harness binaries ----------------------------------------

(defn- build-init-bench! []
  (println "  building tests/embed_init_bench")
  (let [objs (find-objs)
        srcs (find-c-sources)
        unit (if (seq objs) objs srcs)]
    (run-cc! (concat ["-std=c99" "-O2"]
                     includes
                     ["-o" init-bench-bin "tests/embed_init_bench.c"]
                     unit
                     ["-lm" "-lpthread"]))))

(defn- build-min-embed! []
  (println "  building tests/min_embed (--gc-sections)")
  (run-cc! (concat ["-std=c99" "-O2"
                    "-ffunction-sections" "-fdata-sections"]
                   includes
                   ["-o" min-embed-bin "tests/min_embed.c"]
                   (find-c-sources)
                   ["-Wl,--gc-sections" "-lm" "-lpthread"]))
  (run-strip! min-embed-bin min-embed-bin))

;; ---- Statistical helpers -------------------------------------------

(defn- median [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (when (pos? n) (nth v (quot n 2)))))

(defn- p90 [xs]
  (let [v (vec (sort xs))
        n (count v)]
    (when (pos? n) (nth v (max 0 (dec (long (* 0.9 n))))))))

(defn- summarize-ns
  "Reduce a sample vector of nanosecond durations into a stats map.
   All output values are in milliseconds rounded to two decimals."
  [samples-ns]
  (let [s      (vec samples-ns)
        ns->ms (fn [x] (/ (long (+ (/ x 10000) (if (neg? x) 0 0))) 100.0))]
    {:runs   (count s)
     :min-ms (ns->ms (apply min s))
     :median-ms (ns->ms (median s))
     :p90-ms (ns->ms (p90 s))
     :max-ms (ns->ms (apply max s))}))

;; ---- Cold-start timing ---------------------------------------------

(defn- time-spawn [argv]
  (let [t0 (nano-time)]
    (apply sh argv)
    (- (nano-time) t0)))

(defn- cold-start-stats
  "Measure spawn-to-exit wall time across `runs` invocations of argv
   (after three discarded warmup runs to prime the OS page cache)."
  [label argv runs]
  (println (str "  cold-start: " label " (" runs " runs)"))
  (dotimes [_ 3] (time-spawn argv))
  (let [samples (vec (repeatedly runs (fn [] (time-spawn argv))))]
    (assoc (summarize-ns samples) :label label :argv argv)))

;; ---- In-process init -----------------------------------------------

(defn- parse-init-output
  "Parse tests/embed_init_bench's `iters` / `iters-all` plain-text
   stats into a stats map keyed the same way as cold-start results."
  [output]
  (let [grab (fn [k]
               (when-let [m (re-find
                              (re-pattern
                                (str k "\\s*=\\s*([0-9.]+)\\s*ms"))
                              output)]
                 (read-string (second m))))]
    {:min-ms    (grab "min")
     :median-ms (grab "median")
     :mean-ms   (grab "mean")
     :p90-ms    (grab "p90")
     :max-ms    (grab "max")}))

(defn- in-process-stats [arg runs]
  (println (str "  in-process: " arg " (" runs " runs)"))
  (let [r (sh init-bench-bin arg (str runs))]
    (when-not (zero? (:exit r))
      (throw (ex-info "init bench failed" {:err (:err r)})))
    (assoc (parse-init-output (:out r))
           :argv [init-bench-bin arg (str runs)])))

;; ---- Footprint -----------------------------------------------------

(defn- sum-file-sizes
  "Walk `paths` (file lines from `find`) and sum their sizes in bytes."
  [paths]
  (reduce + 0 (keep file-size (filter seq paths))))

(defn- c-source-bytes []
  (let [r (sh "find" "mino/src" "-name" "*.c" "-not" "-path"
              "*/vendor/*")]
    (sum-file-sizes (str/split (:out r) "\n"))))

(defn- vendor-bytes []
  (let [r (sh "find" "mino/src/vendor" "-type" "f")]
    (sum-file-sizes (str/split (:out r) "\n"))))

(defn- stdlib-header-bytes []
  (let [r (sh "find" "mino/src" "-name" "lib_*.h")]
    (sum-file-sizes (str/split (:out r) "\n"))))

(defn- which [bin]
  (let [r (sh "which" bin)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn- installed-mino
  "If a release-built `mino` binary is on PATH (e.g. the Homebrew
   bottle), return its stripped size for cross-checking. This is the
   size end users actually receive; the local-built submodule may
   differ slightly because of build flags."
  []
  (let [bin (which "mino")
        local-abs (:out (sh "realpath" mino-bin))]
    (when (and bin (not= bin (str/trim (or local-abs ""))))
      (let [tmp "results/.tmp_installed_stripped"]
        (when (zero? (:exit (sh "strip" "--strip-all" "-o" tmp bin)))
          (let [bytes (file-size tmp)]
            (sh "rm" "-f" tmp)
            {:path bin :stripped-bytes bytes}))))))

(defn- footprint []
  (let [tmp "results/.tmp_stripped"]
    (run-strip! mino-bin tmp)
    (let [full     (file-size tmp)
          minemb   (file-size min-embed-bin)
          released (installed-mino)
          base     {:min-embed-bytes        minemb
                    :full-standalone-bytes  full
                    :c-source-bytes         (c-source-bytes)
                    :vendor-bytes           (vendor-bytes)
                    :stdlib-header-bytes    (stdlib-header-bytes)
                    :core-clj-bytes         (file-size "mino/src/core.clj")}]
      (sh "rm" "-f" tmp)
      (if released
        (assoc base :released-binary released)
        base))))

;; ---- Bench suite ---------------------------------------------------

(defn- run-bench-suite! []
  (println "  running bench suite (writes results/latest.edn)")
  (let [r (sh mino-bin "benchmarks/run_all.clj")]
    (when-not (zero? (:exit r))
      (println "  !! bench suite exit " (:exit r))
      (println (:err r)))
    (str (:out r) (:err r))))

;; ---- Reporting -----------------------------------------------------

(defn- ms-str [stats]
  (if (and stats (:median-ms stats))
    (str (:median-ms stats) " ms median (p90 " (:p90-ms stats)
         " ms, range " (:min-ms stats) "–" (:max-ms stats) " ms)")
    "(no data)"))

(defn- bytes-kb [n]
  (when n (str n " bytes (~" (quot n 1024) " KB)")))

(defn- emit-text [summary]
  (let [{:keys [cold-full-plus cold-full-nil cold-min
                init-core init-all footprint mino-version
                bench-tail]} summary]
    (str
      "=== mino-bench perf refresh ===\n\n"
      "Hardware  : " (str/trim (:out (sh "uname" "-srm"))) "\n"
      "Compiler  : " (str/trim (or (-> (sh "cc" "--version") :out
                                       (str/split "\n") first)
                                   "(unknown)")) "\n"
      "mino      : " mino-version "\n\n"
      "-- Cold start (wall time including process spawn) --\n"
      "  full standalone -e '(+ 1 2)' : " (ms-str cold-full-plus) "\n"
      "  full standalone -e nil       : " (ms-str cold-full-nil) "\n"
      "  min embed       '(+ 1 2)'    : " (ms-str cold-min) "\n\n"
      "-- In-process init/teardown (no process spawn) --\n"
      "  state_new + install_core + state_free : " (ms-str init-core) "\n"
      "  state_new + install_all  + state_free : " (ms-str init-all) "\n\n"
      "-- Footprint --\n"
      "  min embed binary (stripped, --gc-sections) : "
        (bytes-kb (:min-embed-bytes footprint)) "\n"
      "  local-built full standalone (stripped)     : "
        (bytes-kb (:full-standalone-bytes footprint)) "\n"
      (if-let [rel (:released-binary footprint)]
        (str "  released binary at " (:path rel) " (strip --strip-all) : "
             (bytes-kb (:stripped-bytes rel)) "\n")
        "  released binary on PATH                    : (not present)\n")
      "  C source tree (no vendor)                  : "
        (bytes-kb (:c-source-bytes footprint)) "\n"
      "  vendor (imath)                             : "
        (bytes-kb (:vendor-bytes footprint)) "\n"
      "  bundled stdlib headers                     : "
        (bytes-kb (:stdlib-header-bytes footprint)) "\n"
      "  core.clj source                            : "
        (bytes-kb (:core-clj-bytes footprint)) "\n\n"
      "-- Bench suite tail (last 30 lines) --\n"
      bench-tail "\n")))

(defn- emit-edn [summary]
  (pr-str (dissoc summary :bench-tail)))

;; ---- Orchestrator --------------------------------------------------

(println "=== refresh_perf.clj ===")
(when-not (file-exists? mino-bin)
  (println (str "  error: " mino-bin
                " not found. Build the submodule first."))
  (System/exit 1))

(sh "mkdir" "-p" "results")

(build-init-bench!)
(build-min-embed!)

(println)
(let [bench-out      (run-bench-suite!)
      bench-tail     (->> (str/split bench-out "\n")
                          (take-last 30)
                          (str/join "\n"))
      cold-full-plus (cold-start-stats
                       "full standalone -e '(+ 1 2)'"
                       [mino-bin "-e" "(+ 1 2)"]
                       cold-runs)
      cold-full-nil  (cold-start-stats
                       "full standalone -e nil"
                       [mino-bin "-e" "nil"]
                       cold-runs)
      cold-min       (cold-start-stats
                       "min embed '(+ 1 2)'"
                       [min-embed-bin "(+ 1 2)"]
                       cold-runs)
      init-core      (in-process-stats "iters" init-runs)
      init-all       (in-process-stats "iters-all" init-runs)
      foot           (footprint)
      summary {:hardware     (str/trim (:out (sh "uname" "-srm")))
               :mino-version (mino-version)
               :runs-cold    cold-runs
               :runs-init    init-runs
               :cold-full-plus cold-full-plus
               :cold-full-nil  cold-full-nil
               :cold-min       cold-min
               :init-core      init-core
               :init-all       init-all
               :footprint      foot
               :bench-tail     bench-tail
               :bench-file     "results/latest.edn"}]
  (spit summary-txt (emit-text summary))
  (spit summary-edn (emit-edn summary))
  (println)
  (println "Wrote:")
  (println (str "  " summary-txt "  (human-readable)"))
  (println (str "  " summary-edn "  (machine-readable)"))
  (println "  results/latest.edn  (bench raw)"))
