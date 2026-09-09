(ns mino.tasks.builtin)

;; mino-bench's local task runner. This file shadows the bundled
;; mino.tasks.builtin namespace because:
;;   - tasks here run from the mino-bench/ working directory and
;;     link against the mino/ submodule's amalgamation;
;;   - mino-bench needs bench-/fuzz-/stress-only tasks
;;     (bench-c-vec, perf-gate, fuzz-build, stress-sharded, etc.)
;;     that don't belong in the upstream task table.
;;
;; The build consumes the mino amalgamation (mino/dist/mino.{c,h}), the
;; single embedding boundary mino ships (ADR-62). Every C artifact here
;; compiles against dist/mino.o and includes dist/mino.h only; nothing
;; reaches into mino's private src/** tree. A mino C-tree reorg is a
;; no-op for this build once the submodule pin is bumped.
;;
;; First-time bootstrap: `cd mino && make && cd ..`. After that,
;; every rebuild goes through `./mino/mino task build`, which
;; regenerates the amalgamation only when the submodule pin changes.

(require '[clojure.string :as str])
(require '[mino.tasks.amalgam :as amalgam])

;;;; Build configuration

(def ^:private cc      (or (getenv "CC") "cc"))

;; The amalgamation is the sole mino include root: dist/mino.h is the
;; only mino header any consumer source includes.
(def ^:private include-flags "-Imino/dist")

;; -DMINO_CPJIT=1 and -fno-strict-aliasing mirror mino's Makefile CFLAGS:
;; the JIT define keeps benches on the same runtime config CI ships, and
;; the aliasing flag matches the runtime's type-punning assumptions. The
;; amalgam object is compiled with the same posture so its JIT is present.
(def ^:private cflags  (str/split (or (getenv "CFLAGS")
                                  (str "-std=c99 -Wall -Wpedantic -Wextra -O2"
                                       " -fno-strict-aliasing -DMINO_CPJIT=1 "
                                       include-flags)) " "))
(def ^:private ldflags (let [v (or (getenv "LDFLAGS") "")]
                         (if (= v "") [] (str/split v " "))))
(def ^:private libs    (str/split (or (getenv "LIBS") "-lm -lpthread") " "))

(def ^:private mino-bin "mino/mino")

;; Amalgamation materialization is the mino/-shipped shared helper
;; mino.tasks.amalgam (ADR 62): its dist-c / dist-obj / dist-pin paths,
;; stale? mtime guard, and ensure-dist! recipe live in one place that
;; ships with mino, so a C-tree reorg in the submodule never drifts a
;; copy here. This runner passes its own cc/cflags (JIT posture) into
;; amalgam/ensure-dist! so the amalgam object keeps -DMINO_CPJIT=1.

;; C benchmark binaries. Each supplies its own main() and links against
;; the amalgam object.
(def ^:private c-benchmarks
  {"src/vector_bench" "src/vector_bench.c"
   "src/map_bench"    "src/map_bench.c"
   "src/seq_bench"    "src/seq_bench.c"
   "src/perf_profile" "src/perf_profile.c"})

;;;; Build

(defn build
  "Build the mino binary and C benchmark binaries against the amalgam."
  []
  (amalgam/ensure-dist! cc cflags)
  (let [built (atom 0)]
    (doseq [[bin src] c-benchmarks]
      (when (or (not (file-exists? bin))
                (amalgam/stale? [src amalgam/dist-obj] bin))
        (let [args (into [cc] (concat cflags ldflags
                                      ["-o" bin src amalgam/dist-obj] libs))]
          (println (str "  " (str/join " " args)))
          (apply sh! args)
          (swap! built inc))))
    (when (zero? @built)
      (println "  bench binaries up to date"))))

(defn clean
  "Remove build artifacts (never touches the mino/ submodule checkout)."
  []
  (doseq [[bin _] c-benchmarks]
    (when (file-exists? bin) (rm-rf bin)))
  (when (file-exists? "fuzz/fuzz_reader") (rm-rf "fuzz/fuzz_reader"))
  (println "  cleaned"))

;;;; C-level benchmarks

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

;;;; Mino-level benchmarks

(defn bench
  "Run all mino-level benchmarks."
  []
  (println (sh! mino-bin "benchmarks/run_all.clj")))

;;;; Perf regression gate

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

;;;; Stress tests

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

;;;; Fuzz
;; Targets compile the amalgam TU under the target's sanitizer flags; crash-free is the contract.

(defn- build-stdin-fuzz-target!
  "Build one stdin-mode fuzz target. TARGET is the base name (e.g.
   \"fuzz_reader\"); src and out are derived under fuzz/."
  [target]
  (amalgam/ensure-dist! cc cflags)
  (let [src  (str "fuzz/" target ".c")
        out  (str "fuzz/" target)
        args (into [cc] (concat cflags ldflags
                                ["-DFUZZ_STDIN" "-o" out src amalgam/dist-c]
                                libs))]
    (println (str "  " (str/join " " args)))
    (apply sh! args)))

(defn fuzz-build
  "Build the fuzz reader binary."
  []
  (build-stdin-fuzz-target! "fuzz_reader"))

(def ^:private libfuzzer-flags
  "Shared clang flags for every libFuzzer-instrumented target. The
   fuzzer + address + ub sanitizers cover every deserializer surface."
  (into ["-g" "-O1" "-std=c99" "-Wall" "-Wextra" include-flags]
        ["-fsanitize=fuzzer,address,undefined"
         "-fno-omit-frame-pointer"]))

(defn- build-libfuzzer-target
  "Build one libFuzzer-instrumented target. SRC is the .c path under
   fuzz/, OUT is the output binary path under fuzz/."
  [src out]
  (amalgam/ensure-dist! cc cflags)
  (let [args (into [fuzz-cc-libfuzzer] (concat libfuzzer-flags
                                               ["-o" out src amalgam/dist-c]
                                               libs))]
    (println (str "  " (str/join " " args)))
    (apply sh! args)))

(defn fuzz-build-libfuzzer
  "Build the libFuzzer-instrumented fuzz reader binary. Requires clang
   with -fsanitize=fuzzer,address available. The output binary accepts
   libFuzzer's -runs, -max_total_time, and corpus-directory arguments."
  []
  (build-libfuzzer-target "fuzz/fuzz_reader.c" "fuzz/fuzz_reader_libfuzzer"))

(defn fuzz-build-libfuzzer-image
  "Build the libFuzzer-instrumented SLAD image loader target. Requires
   clang with -fsanitize=fuzzer,address (the nightly fuzz job installs
   it via apt). The output runs libFuzzer over a corpus directory."
  []
  (build-libfuzzer-target "fuzz/fuzz_image.c" "fuzz/fuzz_image_libfuzzer"))

(defn fuzz-build-libfuzzer-store
  "Build the libFuzzer-instrumented mino.store tx-data parser target."
  []
  (build-libfuzzer-target "fuzz/fuzz_store.c" "fuzz/fuzz_store_libfuzzer"))

(defn fuzz-build-libfuzzer-all
  "Build every libFuzzer-instrumented target: reader, image, store."
  []
  (fuzz-build-libfuzzer)
  (fuzz-build-libfuzzer-image)
  (fuzz-build-libfuzzer-store))

(def ^:private fuzz-targets-c
  "Static stdin-mode fuzz targets under fuzz/. Each is a single .c file
   built against the amalgam TU. Targets must exit 0 on every input --
   crash-free is the contract."
  ["fuzz_reader" "fuzz_image" "fuzz_store"])

(defn fuzz-build-all
  "Build every stdin-mode fuzz target in fuzz/."
  []
  (doseq [target fuzz-targets-c]
    (build-stdin-fuzz-target! target)))

(defn fuzz-build-image
  "Build the SLAD image loader fuzz target."
  []
  (build-stdin-fuzz-target! "fuzz_image"))

(defn fuzz-build-store
  "Build the mino.store tx-data fuzz target."
  []
  (build-stdin-fuzz-target! "fuzz_store"))

(defn fuzz-smoke
  "Replay every corpus seed through the stdin-mode fuzz reader and
   report ok/FAIL per file. Meant for CI: a seed that crashes the
   reader is a regression even if the libFuzzer job is not running."
  []
  (when-not (file-exists? "fuzz/fuzz_reader")
    (fuzz-build))
  (when-not (file-exists? "fuzz/corpus")
    (println "fuzz-smoke: fuzz/corpus directory does not exist")
    (exit 1))
  (let [listing (sh! "ls" "fuzz/corpus")
        seeds   (sort (filterv (fn [s] (and (seq s) (str/ends-with? s ".clj")))
                               (str/split listing "\n")))
        failed  (atom [])]
    (doseq [seed seeds]
      (let [path (str "fuzz/corpus/" seed)
            r    (sh "sh" "-c" (str "./fuzz/fuzz_reader < " path))]
        (if (zero? (:exit r))
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
  (when-not (file-exists? "fuzz/fuzz_image") (fuzz-build-image))
  ;; Generate a fresh valid image as one of the seeds, plus a few
  ;; adversarial shapes that have regressed before (truncated v1,
  ;; bad magic, CRC mismatch, mid-body garbage).
  (let [tmp-dir ".local/fuzz-image-seeds"]
    (when (file-exists? tmp-dir) (rm-rf tmp-dir))
    (mkdir-p tmp-dir)
    (let [valid (str tmp-dir "/valid.img")]
      (sh "sh" "-c"
          (str "./mino/mino -e \"(save-image \\\"" valid "\\\")\""))
      (let [content (slurp valid)
            bad-crc (str/replace content #"CRC32 [0-9a-f]+\n"
                                 "CRC32 deadbeef\n")
            trunc   (subs content 0 (max 20 (quot (count content) 2)))
            seeds   {"valid"       content
                     "truncated"   trunc
                     "bad-crc"     bad-crc
                     "wrong-magic" "WRONG-MAGIC/9\nGARBAGE\n"
                     "empty"       ""
                     "garbage"     "NOT AN IMAGE\n%%%bad\n"}]
        (doseq [[seed-name data] seeds]
          (let [path (str tmp-dir "/" seed-name ".img")]
            (spit path data)
            (let [r (sh "sh" "-c" (str "./fuzz/fuzz_image < " path))]
              (if (zero? (:exit r))
                (println (str "  ok    fuzz-image " seed-name))
                (println (str "  FAIL  fuzz-image " seed-name " (exit " (:exit r) ")"))))))
        (println "fuzz-smoke-image: done")))))

(defn fuzz-smoke-store
  "Smoke the mino.store tx-data fuzz target against a small set of
   inputs covering valid, malformed, and adversarial shapes."
  []
  (when-not (file-exists? "fuzz/fuzz_store") (fuzz-build-store))
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
    (when-not (file-exists? tmp-dir) (mkdir-p tmp-dir))
    (doseq [[seed-name data] seeds]
      ;; Pipe via a per-seed tmp file rather than `echo -n '...'` so
      ;; bytes that collide with shell quoting (`'`, `;`, `$`, etc.)
      ;; pass through verbatim.
      (let [path (str tmp-dir "/" seed-name ".seed")
            _    (spit path data)
            r    (sh "sh" "-c" (str "./fuzz/fuzz_store < " path))]
        (if (zero? (:exit r))
          (println (str "  ok    fuzz-store " seed-name))
          (println (str "  FAIL  fuzz-store " seed-name " (exit " (:exit r) ")")))))
    (println "fuzz-smoke-store: done")))

(defn fuzz-smoke-all
  "Run fuzz-smoke (reader) + fuzz-smoke-image + fuzz-smoke-store.
   Used by nightly CI as the crash-free contract across every
   fuzz target."
  []
  (fuzz-smoke)
  (fuzz-smoke-image)
  (fuzz-smoke-store))

(defn fuzz-summary
  "Run every fuzz smoke target via subprocess, parse the ok/FAIL
   lines, and emit output/fuzz-results.edn. Shape:
   {:targets [{:name 'fuzz-smoke' :seeds N :passed N :failed N} ...]}"
  []
  (let [targets ["fuzz-smoke" "fuzz-smoke-image" "fuzz-smoke-store"]
        results (vec
                  (for [target targets]
                    (let [r (sh "sh" "-c"
                                (str "./mino/mino task " target " 2>&1"))
                          lines (str/split (:out r) "\n")
                          ok-count (count (filter #(str/includes? % "  ok  ") lines))
                          fail-count (count (filter #(str/includes? % "  FAIL") lines))]
                      {:name target :seeds (+ ok-count fail-count)
                       :passed ok-count :failed fail-count})))]
    (sh! "mkdir" "-p" "output")
    (spit "output/fuzz-results.edn" (pr-str {:targets results}))
    (println "fuzz-summary: output/fuzz-results.edn")
    (doseq [t results]
      (println (str "  " (:name t) ": " (:passed t) "/" (:seeds t)
                    " passed" (when (pos? (:failed t)) (str ", " (:failed t) " FAILED")))))))

;;;; Multi-target fuzzing (zig-built persistent-loop runtime)
;;
;; fuzz/targets/<name>.c each implement mino_fuzz_init + mino_fuzz_one
;; (fuzz/targets/fuzz_target.h) against mino.h only; fuzz/rt/loop.c is
;; the shared runtime (replay + mutation-loop modes). Built with the
;; pinned `zig cc` so the QA lane reproduces across machines -- the
;; same toolchain the reproducible sanitizer lanes use. UBSan is on
;; (zig ships its UBSan runtime); ASan is not (zig ships no ASan
;; runtime, matching mino's sanitize-zig boundary), so the libFuzzer
;; build above stays the host-clang coverage-guided + ASan path.

(def ^:private fuzz-targets
  ["reader" "print_roundtrip" "eval" "regex"])

(def ^:private fuzz-cc
  (str/split (or (getenv "FUZZ_CC") "zig cc") " "))

;; Separate CC for libFuzzer targets: always defaults to clang because
;; gcc does not support -fsanitize=fuzzer. Set FUZZ_CC_LIBFUZZER to
;; override (e.g. a versioned clang-18) without affecting CC or FUZZ_CC.
(def ^:private fuzz-cc-libfuzzer
  (or (getenv "FUZZ_CC_LIBFUZZER") "clang"))

(defn- fuzz-target-bin [name] (str "fuzz/bin/mino_fuzz_" name))

(defn- build-fuzz-target [name]
  (sh! "mkdir" "-p" "fuzz/bin")
  (let [flags ["-g" "-O1" "-std=c99" "-fno-omit-frame-pointer"
               "-fsanitize=undefined" "-fno-sanitize-recover=undefined"
               "-funwind-tables" "-Ifuzz/targets" include-flags]
        out   (fuzz-target-bin name)
        args  (into (vec fuzz-cc)
                    (concat flags
                            ["-o" out
                             "fuzz/rt/loop.c"
                             (str "fuzz/targets/" name ".c")
                             amalgam/dist-c]
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
  (amalgam/ensure-dist! cc cflags)
  (doseq [t fuzz-targets] (build-fuzz-target t))
  (println (str "  fuzz-build-targets: " (count fuzz-targets) " targets OK")))

(defn fuzz-smoke-targets
  "Replay every fuzz/corpus seed through every target binary. A crash
   in any target on any seed fails the lane. The reader corpus seeds
   double as seeds for the other targets (the eval / round-trip / regex
   targets all accept arbitrary bytes). CI-friendly: this is the
   regression net even when the time-boxed fuzz lane is not running."
  []
  (amalgam/ensure-dist! cc cflags)
  (doseq [t fuzz-targets]
    (when-not (file-exists? (fuzz-target-bin t))
      (build-fuzz-target t)))
  (when-not (file-exists? "fuzz/corpus")
    (println "fuzz-smoke-targets: fuzz/corpus directory does not exist")
    (exit 1))
  (let [listing (sh! "ls" "fuzz/corpus")
        seeds   (sort (filterv (fn [s] (and (seq s)
                                            (str/ends-with? s ".clj")))
                               (str/split listing "\n")))
        paths   (mapv (fn [s] (str "fuzz/corpus/" s)) seeds)
        failed  (atom [])]
    (doseq [t fuzz-targets]
      (let [r (apply sh (concat [(fuzz-target-bin t) "replay"] paths))]
        (if (zero? (:exit r))
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
  (amalgam/ensure-dist! cc cflags)
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
          (when-not (zero? (:exit r))
            (println (str "  CRASH in " t " -- reproducer at " art "/.current"))
            (swap! failed conj t)))))
    (if (empty? @failed)
      (println (str "fuzz-run: " (count fuzz-targets)
                    " targets survived " secs "s each."))
      (do (println (str "fuzz-run: " (count @failed) " target(s) crashed."))
          (exit 1)))))
