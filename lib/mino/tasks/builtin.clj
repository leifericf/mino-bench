(ns mino.tasks.builtin)

;; mino-bench's local task runner. This file shadows the bundled
;; mino.tasks.builtin namespace because:
;;   - tasks here run from the mino-bench/ working directory and
;;     resolve sources via the mino/ submodule prefix;
;;   - mino-bench needs bench-/fuzz-/stress-only tasks
;;     (bench-c-vec, perf-gate, fuzz-build, stress-sharded, etc.)
;;     that don't belong in the upstream task table.
;;
;; The shape of bundled-stdlib is kept in lockstep with upstream's
;; mino/lib/mino/tasks/builtin.clj so that future mino releases
;; that thread additional fields through stay drop-in compatible.
;;
;; First-time bootstrap: `cd mino && make && cd ..`. After that,
;; every rebuild goes through `./mino/mino task build`.

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
;;
;; Exclude src/eval/bc/stencils/*.c: those files use
;; __attribute__((musttail)) return ... which gcc doesn't support and
;; clang only supports under specific options. They're meant for
;; compile-and-extract by `gen-stencils` (output bytes baked into
;; stencils_<triple>.h headers), NOT linked into a runtime binary.
;; The host build (mino's Makefile) drops them implicitly via its
;; SRCS glob too.
(def ^:private mino-srcs
  (vec (filter (fn [p]
                 (and (str/ends-with? p ".c")
                      (not (str/starts-with? p "mino/src/eval/bc/stencils/"))))
               (file-seq "mino/src"))))

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

;; ---- gen-core-header / gen-stdlib-headers ----
;; install_stdlib.c #includes one C string-literal header per bundled
;; namespace; these are gitignored generated artifacts, regenerated
;; whenever the source .clj is newer than the existing header.

(defn- escape-source [src]
  (let [src     (if (str/ends-with? src "\n")
                  (subs src 0 (- (count src) 1))
                  src)
        escaped (-> src
                    (str/replace "\\" "\\\\")
                    (str/replace "\"" "\\\""))]
    (str/replace escaped "\n" "\\n\"\n    \"")))

(defn- gen-core-header []
  (when (stale? ["mino/src/core.clj"] "mino/src/core_mino.h")
    (let [body (escape-source (slurp "mino/src/core.clj"))]
      (spit "mino/src/core_mino.h"
            (str "static const char *core_mino_src =\n    \""
                 body "\\n\"\n    ;\n")))
    (println "  gen-core-header: mino/src/core_mino.h updated")))

;; Schema parity with upstream's bundled-stdlib in
;; mino/lib/mino/tasks/builtin.clj — `[src-path ns-name c-symbol]`.
;; mino-bench keeps its own copy (and its own gen-stdlib-headers
;; helper) because tasks here run from the mino-bench/ working
;; directory and need to find sources via the mino/ submodule prefix.
;; ns-name is unused locally but kept so future upstream evolutions
;; that thread it through stay drop-in compatible.
(def ^:private bundled-stdlib
  [["lib/clojure/string.clj"           "clojure.string"           "lib_clojure_string"]
   ["lib/clojure/set.clj"              "clojure.set"              "lib_clojure_set"]
   ["lib/clojure/walk.clj"             "clojure.walk"             "lib_clojure_walk"]
   ["lib/clojure/edn.clj"              "clojure.edn"              "lib_clojure_edn"]
   ["lib/clojure/pprint.clj"           "clojure.pprint"           "lib_clojure_pprint"]
   ["lib/clojure/zip.clj"              "clojure.zip"              "lib_clojure_zip"]
   ["lib/clojure/data.clj"             "clojure.data"             "lib_clojure_data"]
   ["lib/clojure/test.clj"             "clojure.test"             "lib_clojure_test"]
   ["lib/clojure/template.clj"         "clojure.template"         "lib_clojure_template"]
   ["lib/clojure/repl.clj"             "clojure.repl"             "lib_clojure_repl"]
   ["lib/clojure/stacktrace.clj"       "clojure.stacktrace"       "lib_clojure_stacktrace"]
   ["lib/clojure/datafy.clj"           "clojure.datafy"           "lib_clojure_datafy"]
   ["lib/clojure/core/protocols.clj"   "clojure.core.protocols"   "lib_clojure_core_protocols"]
   ["lib/clojure/instant.clj"          "clojure.instant"          "lib_clojure_instant"]
   ["lib/clojure/spec/alpha.clj"       "clojure.spec.alpha"       "lib_clojure_spec_alpha"]
   ["lib/clojure/core/specs/alpha.clj" "clojure.core.specs.alpha" "lib_clojure_core_specs_alpha"]
   ["lib/mino/deps.clj"                "mino.deps"                "lib_mino_deps"]
   ["lib/mino/tasks.clj"               "mino.tasks"               "lib_mino_tasks"]
   ["lib/mino/tasks/builtin.clj"       "mino.tasks.builtin"       "lib_mino_tasks_builtin"]])

(defn- gen-stdlib-headers []
  (doseq [[src-path _ns-name c-symbol] bundled-stdlib]
    (let [src-full (str "mino/" src-path)
          out-path (str "mino/src/" c-symbol ".h")]
      (when (stale? [src-full] out-path)
        (spit out-path
              (str "static const char *" c-symbol "_src =\n    \""
                   (escape-source (slurp src-full))
                   "\\n\"\n    ;\n"))
        (println (str "  gen-stdlib-headers: " out-path " updated"))))))

;; ---- Build ----

(defn build
  "Build the mino binary and C benchmark binaries."
  []
  (gen-core-header)
  (gen-stdlib-headers)
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
  ;; Generated headers inside submodule (core + bundled stdlib)
  (when (file-exists? "mino/src/core_mino.h") (rm-rf "mino/src/core_mino.h"))
  (doseq [[_ _ c-symbol] bundled-stdlib]
    (let [hpath (str "mino/src/" c-symbol ".h")]
      (when (file-exists? hpath) (rm-rf hpath))))
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
        ;; Reuse the same -I set as the plain fuzz build so internal
        ;; headers (`diag.h`, `host_threads.h`, etc.) resolve from
        ;; their nested directories under mino/src.
        flags   (into ["-g" "-O1" "-std=c99" "-Wall" "-Wextra"]
                      (concat (str/split include-flags " ")
                              ["-fsanitize=fuzzer,address,undefined"
                               "-fno-omit-frame-pointer"]))
        args    (into [cc-fuzz] (concat flags
                                        ["-o" "fuzz/fuzz_reader_libfuzzer"
                                         "fuzz/fuzz_reader.c"]
                                        (mapv identity mino-srcs) libs))]
    (println (str "  " (str/join " " args)))
    (apply sh! args)))

(def ^:private fuzz-targets-c
  "Static stdin-mode fuzz targets under fuzz/. Each is a single .c file
   built against the amalgamated mino source set. Targets must exit 0
   on every input -- crash-free is the contract."
  ["fuzz_reader" "fuzz_image" "fuzz_store"])

(defn fuzz-build-all
  "Build every stdin-mode fuzz target in fuzz/."
  []
  (gen-core-header)
  (doseq [target fuzz-targets-c]
    (let [src (str "fuzz/" target ".c")
          out (str "fuzz/" target)
          args (into [cc] (concat cflags ldflags
                                  ["-DFUZZ_STDIN" "-o" out src]
                                  (mapv identity mino-srcs) libs))]
      (println (str "  " (str/join " " args)))
      (apply sh! args))))

(defn fuzz-build-image
  "Build the SLAD image loader fuzz target."
  []
  (gen-core-header)
  (let [args (into [cc] (concat cflags ldflags
                                ["-DFUZZ_STDIN" "-o" "fuzz/fuzz_image"
                                 "fuzz/fuzz_image.c"]
                                (mapv identity mino-srcs) libs))]
    (println (str "  " (str/join " " args)))
    (apply sh! args)))

(defn fuzz-build-store
  "Build the mino.store tx-data fuzz target."
  []
  (gen-core-header)
  (let [args (into [cc] (concat cflags ldflags
                                ["-DFUZZ_STDIN" "-o" "fuzz/fuzz_store"
                                 "fuzz/fuzz_store.c"]
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

(defn fuzz-smoke-image
  "Smoke the SLAD image loader fuzz target against a small set of
   deliberately corrupt inputs. Each input must exit 0 (no crash).
   Builds the target first if missing."
  []
  (gen-core-header)
  (when (not (file-exists? "fuzz/fuzz_image")) (fuzz-build-image))
  ;; Generate a fresh valid image as one of the seeds, plus a few
  ;; adversarial shapes that have regressed before (truncated v1,
  ;; bad magic, CRC mismatch, mid-body garbage).
  (let [tmp-dir ".local/fuzz-image-seeds"
        _       (do (when (file-exists? tmp-dir) (rm-rf tmp-dir))
                    (mkdir-p tmp-dir))
        valid   (str tmp-dir "/valid.img")
        _       (sh "sh" "-c"
                    (str "./mino/mino -e \"(save-image \\\"" valid "\\\")\""))
        content (slurp valid)
        bad-crc (clojure.string/replace content #"CRC32 [0-9a-f]+\n"
                                        "CRC32 deadbeef\n")
        trunc   (subs content 0 (max 20 (quot (count content) 2)))
        seeds   {"valid"     content
                 "truncated" trunc
                 "bad-crc"   bad-crc
                 "wrong-magic" "WRONG-MAGIC/9\nGARBAGE\n"
                 "empty"     ""
                 "garbage"   "NOT AN IMAGE\n%%%bad\n"}]
    (doseq [[name data] seeds]
      (let [path (str tmp-dir "/" name ".img")
            _    (spit path data)
            r    (sh "sh" "-c" (str "./fuzz/fuzz_image < " path))]
        (if (= 0 (:exit r))
          (println (str "  ok    fuzz-image " name))
          (println (str "  FAIL  fuzz-image " name " (exit " (:exit r) ")")))))
    (println "fuzz-smoke-image: done")))

(defn fuzz-smoke-store
  "Smoke the mino.store tx-data fuzz target against a small set of
   inputs covering valid, malformed, and adversarial shapes."
  []
  (gen-core-header)
  (when (not (file-exists? "fuzz/fuzz_store")) (fuzz-build-store))
  (let [seeds {"valid-add"       "[:db/add 1 :name \"Alice\"]"
               "valid-map"       "{1 {:name \"Bob\" :age 30}}"
               "valid-nested"    "([:db/add 1 :a 1] [:db/add 2 :b 2])"
               "arity-3-add"     "[:db/add 1 :name]"
               "arity-5-add"     "[:db/add 1 :name \"X\" :extra]"
               "unknown-op"      "[:db/foo 1 :name \"X\"]"
               "garbage"         "((((not even clojure"
               "empty"           ""
               "random-bytes"    (apply str (map (fn [_] (char (+ 32 (rand 95))))
                                                 (range 200)))}
        tmp-dir ".local/fuzz-store-seeds"]
    (when (not (file-exists? tmp-dir)) (mkdir-p tmp-dir))
    (doseq [[name data] seeds]
      ;; Pipe via a per-seed tmp file rather than `echo -n '...'` so
      ;; bytes that collide with shell quoting (`'`, `;`, `$`, etc.)
      ;; pass through verbatim.
      (let [path (str tmp-dir "/" name ".seed")
            _    (spit path data)
            r    (sh "sh" "-c" (str "./fuzz/fuzz_store < " path))]
        (if (= 0 (:exit r))
          (println (str "  ok    fuzz-store " name))
          (println (str "  FAIL  fuzz-store " name " (exit " (:exit r) ")")))))
    (println "fuzz-smoke-store: done")))

(defn fuzz-smoke-all
  "Run fuzz-smoke (reader) + fuzz-smoke-image + fuzz-smoke-store.
   Used by nightly CI as the crash-free contract across every
   fuzz target."
  []
  (fuzz-smoke)
  (fuzz-smoke-image)
  (fuzz-smoke-store))

;; ---- Multi-target fuzzing (zig-built persistent-loop runtime) ----
;;
;; fuzz/targets/<name>.c each implement mino_fuzz_init + mino_fuzz_one
;; (fuzz/targets/fuzz_target.h) against mino.h only; fuzz/rt/loop.c is
;; the shared runtime (replay + mutation-loop modes). Built with the
;; pinned `zig cc` so the QA lane reproduces across machines -- the
;; same toolchain the reproducible sanitizer lanes use. UBSan is on
;; (zig ships its UBSan runtime); ASan is not (zig ships no ASan
;; runtime, matching mino's sanitize-zig boundary), so the libFuzzer
;; build below stays the host-clang coverage-guided + ASan path.

(def ^:private fuzz-targets
  ["reader" "print_roundtrip" "eval" "regex"])

(def ^:private fuzz-cc
  (str/split (or (getenv "FUZZ_CC") "zig cc") " "))

(defn- fuzz-target-bin [name] (str "fuzz/bin/mino_fuzz_" name))

(defn- build-fuzz-target [name]
  (gen-core-header)
  (sh! "mkdir" "-p" "fuzz/bin")
  (let [flags (into ["-g" "-O1" "-std=c99" "-fno-omit-frame-pointer"
                     "-fsanitize=undefined" "-fno-sanitize-recover=undefined"
                     "-funwind-tables" "-Ifuzz/targets"]
                    (str/split include-flags " "))
        out   (fuzz-target-bin name)
        args  (into (vec fuzz-cc)
                    (concat flags
                            ["-o" out
                             "fuzz/rt/loop.c"
                             (str "fuzz/targets/" name ".c")]
                            mino-srcs
                            libs
                            ;; zig's default musl folds in pthread;
                            ;; -lunwind covers the crash handler's
                            ;; _Unwind_* symbols on non-glibc targets.
                            (when (= (first fuzz-cc) "zig") ["-lunwind"])))]
    (println (str "  building fuzz target " name))
    (apply sh! args)
    (println (str "  fuzz target -> " out))))

(defn fuzz-build-targets
  "Build every fuzz target (reader / print_roundtrip / eval / regex)
   against the persistent-loop runtime with the pinned `zig cc` under
   UBSan. Each binary takes `replay <file>...` or
   `fuzz <corpus> [opts]` (see fuzz/rt/loop.c)."
  []
  (doseq [t fuzz-targets] (build-fuzz-target t))
  (println (str "  fuzz-build-targets: " (count fuzz-targets) " targets OK")))

(defn fuzz-smoke-targets
  "Replay every fuzz/corpus seed through every target binary. A crash
   in any target on any seed fails the lane. The reader corpus seeds
   double as seeds for the other targets (the eval / round-trip / regex
   targets all accept arbitrary bytes). CI-friendly: this is the
   regression net even when the time-boxed fuzz lane is not running."
  []
  (doseq [t fuzz-targets]
    (when-not (file-exists? (fuzz-target-bin t))
      (build-fuzz-target t)))
  (let [listing (sh! "ls" "fuzz/corpus")
        seeds   (sort (filterv (fn [s] (and (not= s "")
                                            (str/ends-with? s ".clj")))
                               (str/split listing "\n")))
        paths   (mapv (fn [s] (str "fuzz/corpus/" s)) seeds)
        failed  (atom [])]
    (doseq [t fuzz-targets]
      (let [r (apply sh (concat [(fuzz-target-bin t) "replay"] paths))]
        (if (= 0 (:exit r))
          (println (str "  ok    " t " (" (count paths) " seeds)"))
          (do (println (str "  FAIL  " t))
              (println (:out r))
              (swap! failed conj t)))))
    (if (empty? @failed)
      (println (str "fuzz-smoke-targets: all " (count fuzz-targets)
                    " targets replayed " (count paths) " seeds cleanly."))
      (do (println (str "fuzz-smoke-targets: " (count @failed)
                        " target(s) crashed."))
          (exit 1)))))

(defn fuzz-run
  "Time-boxed mutation fuzz over every target. FUZZ_SECS (default 60)
   bounds each target's run; FUZZ_SEED (default 0) makes the run
   deterministic. A crash leaves the reproducer at
   fuzz/artifacts/<target>/.current and fails the lane. Intended for a
   nightly time-boxed CI lane; crashers triage into mino/.local/BUGS.md."
  []
  (doseq [t fuzz-targets]
    (when-not (file-exists? (fuzz-target-bin t))
      (build-fuzz-target t)))
  (let [secs (or (getenv "FUZZ_SECS") "60")
        seed (or (getenv "FUZZ_SEED") "0")
        failed (atom [])]
    (doseq [t fuzz-targets]
      (let [art (str "fuzz/artifacts/" t)]
        (sh! "mkdir" "-p" art)
        (println (str "  fuzzing " t " for " secs "s (seed " seed ")"))
        (let [r (sh (fuzz-target-bin t) "fuzz" "fuzz/corpus"
                    "--secs" secs "--seed" seed "--artifacts" art)]
          (println (:out r))
          (when-not (= 0 (:exit r))
            (println (str "  CRASH in " t " -- reproducer at " art "/.current"))
            (swap! failed conj t)))))
    (if (empty? @failed)
      (println (str "fuzz-run: " (count fuzz-targets)
                    " targets survived " secs "s each."))
      (do (println (str "fuzz-run: " (count @failed) " target(s) crashed."))
          (exit 1)))))
