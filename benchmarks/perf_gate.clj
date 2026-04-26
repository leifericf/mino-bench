;; Perf regression gate. Runs a small, stable set of benches R times,
;; takes the minimum mean-ns + minimum bytes-per-op for each, compares
;; to a pinned baseline, and exits non-zero if any bench regressed
;; beyond a configured threshold.
;;
;; Usage:
;;   ./mino/mino benchmarks/perf_gate.clj                 [run + check]
;;   MINO_PERF_GATE_RECORD=1 ./mino/mino .../perf_gate.clj [rewrite baseline]
;;
;; Baseline lives in baselines/perf_baseline.edn. When you intentionally
;; change the eval floor (perf optimization or correctness fix with a
;; known cost), re-record the baseline in the same commit so the gate
;; tracks reality.
;;
;; CI relaxation: GitHub Actions and other CI hosts set CI=true. When
;; that is detected the timing regression threshold widens to absorb
;; shared-runner noise; local runs stay strict. The allocation gate
;; never relaxes — allocation counts are deterministic.

(ns benchmarks.perf-gate)
(require '[mino.bench :as bench])

(def ^:private baseline-path "baselines/perf_baseline.edn")

;; Tenths of a percent. Local: +15% regression / -30% speedup. CI runners
;; (CI=true) widen regression to +50% to absorb runner-to-runner variance
;; on shared GitHub-hosted hardware; the baseline is itself recorded on
;; that hardware via the workflow_dispatch record path, so the +50% band
;; is for noise on top of a same-class baseline, not for absorbing a
;; different-hardware mismatch. Speedup gate stays symmetric so an
;; unintended -30% drop still forces a baseline refresh.
(def ^:private local-regress-tenths 150)
(def ^:private ci-regress-tenths    500)
(def ^:private speedup-tenths       300)

;; Allocation gate. Allocation counts are deterministic, so we hold this
;; tight. Zero-baseline benches use exact equality (any growth fails).
;; Non-zero baselines use a small tenths-of-a-percent band.
(def ^:private alloc-regress-tenths 100)

;; Runs per bench. Gate reports the min.
(def ^:private runs 3)

(defn- ci-runner? []
  (let [v (getenv "CI")]
    (and v (not= v "") (not= v "0") (not= v "false"))))

(defn- regress-tenths []
  (if (ci-runner?) ci-regress-tenths local-regress-tenths))

;; Bench registry. Each entry: [id label iter body-fn]. The 15 benches
;; cover reader, eval-special, allocation, host-call, and regex paths so
;; a regression in any of them surfaces here. Adding or removing a bench
;; requires re-recording the baseline.
(def ^:private benches
  [;; eval-special
   ["fn-call-identity" "identity fn call"           100000
    (let [f (fn [x] x)] (fn [] (f 42)))]
   ["let-local-lookup" "let binding + local lookup" 100000
    (fn [] (let [x 42] x))]
   ["if-branch"        "if true 1 0"                1000000
    (fn [] (if true 1 0))]
   ["do-block"         "do with three forms"        1000000
    (fn [] (do nil nil 1))]
   ["loop-recur-5"     "loop+recur 5 iters"         100000
    (fn [] (loop [i 0] (if (>= i 5) i (recur (inc i)))))]

   ;; host-call (primitive dispatch)
   ["arith-inc"   "inc on small int"           1000000
    (fn [] (inc 41))]
   ["arith-add"   "+ on two small ints"        1000000
    (fn [] (+ 1 2))]
   ["count-vec3"  "count of pre-built [1 2 3]" 1000000
    (let [v [1 2 3]] (fn [] (count v)))]
   ["assoc-small" "assoc into 1-entry map"     100000
    (let [m {:a 1}] (fn [] (assoc m :b 2)))]

   ;; allocation
   ["cons-create" "cons cell creation"     100000
    (fn [] (cons 1 nil))]
   ["vec3-create" "small vector [1 2 3]"   100000
    (fn [] [1 2 3])]
   ["small-map"   "small map {:a 1 :b 2}"  100000
    (fn [] {:a 1 :b 2})]

   ;; reader
   ["read-int"  "read-string \"42\""        10000
    (fn [] (read-string "42"))]
   ["read-list" "read-string \"(+ 1 2 3)\"" 10000
    (fn [] (read-string "(+ 1 2 3)"))]

   ;; regex
   ["re-find-simple" "re-find on short string" 10000
    (fn [] (re-find "[0-9]+" "abc123def"))]])

(defn- run-once [[id _ iter body-fn]]
  (let [r (bench/bench id iter body-fn)]
    [(:mean-ns r) (:alloc-bytes-per-op r)]))

(defn- min-of-runs [entry]
  (let [results (repeatedly runs #(run-once entry))]
    [(apply min (mapv first results))
     (apply min (mapv second results))]))

(defn- measure-all []
  (into {}
        (mapv (fn [entry]
                (let [[ns bytes] (min-of-runs entry)]
                  [(first entry) {:ns ns :bytes bytes}]))
              benches)))

(defn- load-baseline []
  (when (file-exists? baseline-path)
    (read-string (slurp baseline-path))))

(defn- format-ns [n]
  (cond
    (>= n 1000000) (str (quot n 1000000) "ms")
    (>= n 1000)    (str (quot n 1000) "us")
    :else          (str n "ns")))

(defn- percent-tenths [baseline current]
  (if (and baseline (pos? baseline))
    (quot (* (- current baseline) 1000) baseline)
    0))

(defn- fmt-pct-tenths [t]
  (let [sign (if (neg? t) "-" "+")
        a    (if (neg? t) (- t) t)]
    (str sign (quot a 10) "." (mod a 10) "%")))

(defn- ns-status [b c]
  (cond
    (nil? b)                              :no-baseline
    (nil? c)                              :no-measurement
    :else
    (let [d (percent-tenths b c)]
      (cond
        (> d (regress-tenths)) :regressed
        (< d (- speedup-tenths)) :speedup
        :else :ok))))

(defn- alloc-status [b c]
  (cond
    (nil? b) :no-baseline
    (nil? c) :no-measurement
    (zero? b)
    (cond
      (zero? c) :ok
      (pos? c)  :regressed
      :else     :speedup)
    :else
    (let [d (percent-tenths b c)]
      (cond
        (> d alloc-regress-tenths)    :regressed
        (< d (- alloc-regress-tenths)) :speedup
        :else :ok))))

(defn- check [current baseline]
  (mapv (fn [[id label _ _]]
          (let [b   (get baseline id)
                c   (get current id)
                bn  (when b (:ns b))
                bb  (when b (:bytes b))
                cn  (when c (:ns c))
                cb  (when c (:bytes c))
                ns-d (if (and bn cn) (percent-tenths bn cn) 0)]
            {:id           id
             :label        label
             :baseline-ns  bn
             :current-ns   cn
             :ns-delta     ns-d
             :ns-status    (ns-status bn cn)
             :baseline-b   bb
             :current-b    cb
             :alloc-status (alloc-status bb cb)}))
        benches))

(defn- mark-for-status [s]
  (case s
    :ok             "OK    "
    :regressed      "FAIL  "
    :speedup        "FASTER"
    :no-baseline    "?BASE "
    :no-measurement "?MEAS "))

(defn- worst-status [a b]
  (let [order {:ok 0 :speedup 1 :no-baseline 2 :no-measurement 2 :regressed 3}]
    (if (>= (or (get order a) 0) (or (get order b) 0)) a b)))

(defn- print-report [rows]
  (println)
  (println (str "perf-gate: bench vs baseline"
                " (timing +" (quot (regress-tenths) 10) "."
                (mod (regress-tenths) 10) "% regression fails;"
                " allocation +" (quot alloc-regress-tenths 10) "."
                (mod alloc-regress-tenths 10) "% or any growth from 0 fails)"))
  (doseq [r rows]
    (let [combined (worst-status (:ns-status r) (:alloc-status r))
          mark     (mark-for-status combined)
          bn       (if (:baseline-ns r) (format-ns (:baseline-ns r)) "-")
          cn       (if (:current-ns r)  (format-ns (:current-ns r))  "-")
          dn       (if (and (:baseline-ns r) (:current-ns r))
                     (fmt-pct-tenths (:ns-delta r)) "-")
          bb       (if (nil? (:baseline-b r)) "-" (str (:baseline-b r) "B"))
          cb       (if (nil? (:current-b r))  "-" (str (:current-b r) "B"))]
      (println (str "  " mark "  " (:label r)
                    " (" (:id r) ")"
                    ": ns=" cn " (base=" bn " " dn ")"
                    " alloc=" cb "/op (base=" bb ")"))))
  (println))

(defn- record-baseline [current]
  (let [sorted (sort-by first (seq current))
        body   (apply str
                      (mapv (fn [[k v]]
                              (str "  " (pr-str k)
                                   " {:ns " (:ns v)
                                   " :bytes " (:bytes v) "}\n"))
                            sorted))]
    (spit baseline-path
          (str ";; Perf gate baseline (min of " runs " runs).\n"
               ";; :ns is mean-ns/op; :bytes is allocation bytes/op.\n"
               ";; Rewrite this file in the same commit as an intentional\n"
               ";; eval-floor or allocation-shape change.\n"
               "{\n" body "}\n"))
    (println (str "perf-gate: baseline written to " baseline-path))))

(defn- record-mode? []
  (let [v (getenv "MINO_PERF_GATE_RECORD")]
    (and v (not= v "") (not= v "0"))))

(defn- print-hw-note []
  (println (str "perf-gate: benches=" (count benches)
                " runs-per-bench=" runs
                (if (ci-runner?) " ci=yes" " ci=no")
                " (CPU-sensitive; expect noise on shared runners)")))

(defn run []
  (print-hw-note)
  (let [current (measure-all)]
    (if (record-mode?)
      (do (record-baseline current)
          (print-report (check current current))
          (exit 0))
      (let [baseline (load-baseline)]
        (when (nil? baseline)
          (println "perf-gate: no baseline present; set MINO_PERF_GATE_RECORD=1 to create one.")
          (exit 2))
        (let [rows   (check current baseline)
              failed (filterv (fn [r]
                                (or (contains? #{:regressed :speedup :no-baseline :no-measurement}
                                               (:ns-status r))
                                    (contains? #{:regressed :no-baseline :no-measurement}
                                               (:alloc-status r))))
                              rows)]
          (print-report rows)
          (if (empty? failed)
            (do (println "perf-gate: PASS") (exit 0))
            (do (println (str "perf-gate: FAIL (" (count failed) " bench(es) outside threshold)"))
                (exit 1))))))))

(run)
