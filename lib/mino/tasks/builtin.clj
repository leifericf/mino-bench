(ns mino.tasks.builtin)

(require '[clojure.string :as str])

;; Build configuration

(def ^:private cc      (or (getenv "CC") "cc"))
(def ^:private include-flags
  (str "-Imino/src -Imino/src/public -Imino/src/runtime -Imino/src/gc"
       " -Imino/src/eval -Imino/src/collections -Imino/src/prim"
       " -Imino/src/async -Imino/src/interop"
       " -Imino/src/diag -Imino/src/vendor/imath"))
(def ^:private cflags  (str/split (or (getenv "CFLAGS")
                                  (str "-std=c99 -Wall -Wpedantic -Wextra -O2 "
                                       include-flags)) " "))
(def ^:private ldflags (let [v (or (getenv "LDFLAGS") "")]
                         (if (= v "") [] (str/split v " "))))
(def ^:private libs    (str/split (or (getenv "LIBS") "-lm") " "))

(def ^:private mino-bin "mino/mino")

;; Mino library sources: discover every .c file under mino/src/ at
;; task-load time. Matches whatever the pinned submodule SHA carries
;; without requiring this list to be edited when the C tree moves.
(def ^:private mino-srcs
  (vec (filter #(str/ends-with? % ".c") (file-seq "mino/src"))))

(def ^:private mino-bin-srcs (conj mino-srcs "mino/main.c"))

;; C benchmark binaries
(def ^:private c-benchmarks
  {"src/vector_bench" "src/vector_bench.c"
   "src/map_bench"    "src/map_bench.c"
   "src/seq_bench"    "src/seq_bench.c"
   "src/perf_profile" "src/perf_profile.c"})

(defn- src->obj [src]
  (str (subs src 0 (- (count src) 2)) ".o"))

(defn- stale?
  "True if output does not exist or any input is newer."
  [inputs output]
  (let [out-mtime (file-mtime output)]
    (if (nil? out-mtime)
      true
      (some #(let [in-mtime (file-mtime %)]
               (and in-mtime (> in-mtime out-mtime)))
            inputs))))

;; ---- gen-core-header (needed before compiling mino/src/prim.c) ----

(defn- gen-core-header []
  (when (stale? ["mino/src/core.clj"] "mino/src/core_mino.h")
    (let [src     (slurp "mino/src/core.clj")
          src     (if (str/ends-with? src "\n")
                    (subs src 0 (- (count src) 1))
                    src)
          escaped (-> src
                      (str-replace "\\" "\\\\")
                      (str-replace "\"" "\\\""))
          body    (str-replace escaped "\n" "\\n\"\n    \"")]
      (spit "mino/src/core_mino.h"
            (str "static const char *core_mino_src =\n    \""
                 body "\\n\"\n    ;\n")))
    (println "  gen-core-header: mino/src/core_mino.h updated")))

;; ---- Build ----

(defn build
  "Build the mino binary and C benchmark binaries."
  []
  (gen-core-header)
  (let [compiled (atom 0)]
    ;; Compile all .o files
    (doseq [src mino-bin-srcs]
      (let [obj (src->obj src)]
        (when (stale? [src] obj)
          (let [args (into [cc] (concat cflags ["-c" "-o" obj src]))]
            (println (str "  " (str/join " " args)))
            (apply sh! args)
            (swap! compiled inc)))))
    ;; Link mino binary inside submodule (binary_dir = mino/ for resolver)
    (let [objs      (mapv src->obj mino-bin-srcs)
          need-link (or (> @compiled 0) (not (file-exists? mino-bin)))]
      (when need-link
        (let [args (into [cc] (concat cflags ldflags ["-o" mino-bin] objs libs))]
          (println (str "  " (str/join " " args)))
          (apply sh! args))))
    ;; Build C benchmark binaries
    (let [mino-objs (mapv src->obj mino-srcs)]
      (doseq [[bin src] c-benchmarks]
        (when (or (> @compiled 0) (not (file-exists? bin)) (stale? [src] bin))
          (let [args (into [cc] (concat cflags ldflags ["-o" bin src] mino-objs libs))]
            (println (str "  " (str/join " " args)))
            (apply sh! args)))))
    (when (= @compiled 0)
      (println "  nothing to compile"))))

(defn clean
  "Remove build artifacts (never touches the mino/ submodule checkout)."
  []
  ;; Object files compiled from submodule sources
  (doseq [src mino-bin-srcs]
    (let [obj (src->obj src)]
      (when (file-exists? obj) (rm-rf obj))))
  ;; Generated header inside submodule
  (when (file-exists? "mino/src/core_mino.h") (rm-rf "mino/src/core_mino.h"))
  ;; Mino binary inside submodule
  (when (file-exists? mino-bin) (rm-rf mino-bin))
  (doseq [[bin _] c-benchmarks]
    (when (file-exists? bin) (rm-rf bin)))
  (when (file-exists? "fuzz/fuzz_reader") (rm-rf "fuzz/fuzz_reader"))
  (println "  cleaned"))

;; ---- C-level benchmarks ----

(defn bench-c
  "Run all C-level benchmarks."
  []
  (doseq [[bin _] (sort c-benchmarks)]
    (println (str "== " bin " =="))
    (println (sh! (str "./" bin)))
    (println)))

(defn bench-c-vec  [] (println (sh! "./src/vector_bench")))
(defn bench-c-map  [] (println (sh! "./src/map_bench")))
(defn bench-c-seq  [] (println (sh! "./src/seq_bench")))
(defn bench-c-perf [] (println (sh! "./src/perf_profile")))

;; ---- Mino-level benchmarks ----

(defn bench
  "Run all mino-level benchmarks."
  []
  (println (sh! mino-bin "benchmarks/run_all.clj")))

;; ---- Perf regression gate ----

(defn perf-gate
  "Run the perf regression gate against the pinned baseline. Exits non-zero
   on regression so this is safe to wire into CI."
  []
  (let [r (sh mino-bin "benchmarks/perf_gate.clj")]
    (println (:out r))
    (exit (:exit r))))

(defn perf-gate-record
  "Re-record the perf baseline from the current build. Run this in the same
   commit as an intentional eval-floor change."
  []
  (let [r (sh "env" "MINO_PERF_GATE_RECORD=1" mino-bin "benchmarks/perf_gate.clj")]
    (println (:out r))
    (exit (:exit r))))

;; ---- Stress tests ----

(defn stress
  "Run GC stress test."
  []
  (println (sh! "env" "MINO_GC_STRESS=1" mino-bin "stress/stress_test.clj")))

(defn stress-sharded
  "Run all GC stress shards."
  []
  (doseq [i (range 1 12)]
    (let [shard (str "stress/run_gc_shard" i ".clj")]
      (print (str "  shard " i "/11... "))
      (flush)
      (println (sh! "env" "MINO_GC_STRESS=1" mino-bin shard)))))

;; ---- Fuzz ----

(defn fuzz-build
  "Build the fuzz reader binary."
  []
  (gen-core-header)
  (let [args (into [cc] (concat cflags ldflags
                                ["-DFUZZ_STDIN" "-o" "fuzz/fuzz_reader"
                                 "fuzz/fuzz_reader.c"]
                                (mapv identity mino-srcs) libs))]
    (println (str "  " (str/join " " args)))
    (apply sh! args)))

(defn fuzz-build-libfuzzer
  "Build the libFuzzer-instrumented fuzz reader binary. Requires clang
   with -fsanitize=fuzzer,address available. The output binary accepts
   libFuzzer's -runs, -max_total_time, and corpus-directory arguments."
  []
  (gen-core-header)
  (let [cc-fuzz (or (getenv "CC") "clang")
        flags   ["-g" "-O1" "-std=c99" "-Wall" "-Wextra" "-Imino/src"
                 "-fsanitize=fuzzer,address,undefined"
                 "-fno-omit-frame-pointer"]
        args    (into [cc-fuzz] (concat flags
                                        ["-o" "fuzz/fuzz_reader_libfuzzer"
                                         "fuzz/fuzz_reader.c"]
                                        (mapv identity mino-srcs) libs))]
    (println (str "  " (str/join " " args)))
    (apply sh! args)))

(defn fuzz-smoke
  "Replay every corpus seed through the stdin-mode fuzz reader and
   report ok/FAIL per file. Meant for CI: a seed that crashes the
   reader is a regression even if the libFuzzer job is not running."
  []
  (gen-core-header)
  ;; Build stdin-mode reader if missing.
  (when (not (file-exists? "fuzz/fuzz_reader"))
    (fuzz-build))
  (let [listing (sh! "ls" "fuzz/corpus")
        seeds   (sort (filterv (fn [s] (and (not= s "") (str/ends-with? s ".clj")))
                               (str/split listing "\n")))
        failed  (atom [])]
    (doseq [seed seeds]
      (let [path (str "fuzz/corpus/" seed)
            r    (sh "sh" "-c" (str "./fuzz/fuzz_reader < " path))]
        (if (= 0 (:exit r))
          (println (str "  ok    " path))
          (do (println (str "  FAIL  " path))
              (swap! failed conj path)))))
    (if (empty? @failed)
      (println (str "fuzz-smoke: all " (count seeds) " seeds parsed without crashing."))
      (do (println (str "fuzz-smoke: " (count @failed) " seed(s) crashed the reader."))
          (exit 1)))))
