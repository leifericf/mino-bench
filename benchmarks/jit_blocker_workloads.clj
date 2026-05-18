(ns benchmarks.jit-blocker-workloads)
(require '[mino.bench :as bench])

;; Hot-loop workloads that exercise the bytecode ops the JIT
;; eligibility classifier rejects today: PUSHCATCH / POPCATCH / THROW
;; for try/catch and PUSHDYN / POPDYN for dynamic binding. The
;; classifier's OK_WITH_DEOPT path (v0.319.0) covers fns whose first
;; unstenciled op sits past PC 0; these benches drive the classifier
;; into that shape so a future cycle has a corpus that ranks above 0%
;; on the "OK_WITH_DEOPT" line.
;;
;; Output is ;edn; machine-readable rows so diff comparison against
;; later runs is cheap.

;; ---- 1. Try/catch in hot loop, body never throws. ----------------

(defn- try-loop-never-throw [n]
  (loop [i 0 acc 0]
    (if (>= i n)
      acc
      (let [v (try (* i 3) (catch _e 0))]
        (recur (+ i 1) (+ acc v))))))

;; ---- 2. Try/catch in hot loop, body sometimes throws. ------------

(defn- compute-or-throw [i]
  (if (zero? (mod i 20))
    (throw (ex-info "skip" {:i i}))
    (* i 3)))

(defn- try-loop-sometimes-throw [n]
  (loop [i 0 acc 0]
    (if (>= i n)
      acc
      (let [v (try (compute-or-throw i) (catch _e 0))]
        (recur (+ i 1) (+ acc v))))))

;; ---- 3. Dyn-binding in hot loop. ---------------------------------

(def ^:dynamic *scale* 1)

(defn- dyn-loop [n]
  (binding [*scale* 2]
    (loop [i 0 acc 0]
      (if (>= i n)
        acc
        (recur (+ i 1) (+ acc (* *scale* i)))))))

;; ---- 4. Dyn-binding outside hot loop (control). ------------------
;; The binding is set ONCE; the inner loop reads `*scale*` but the
;; PUSHDYN/POPDYN sits outside. Classifier should compile the inner
;; loop fn natively.

(defn- dyn-outer [n]
  (binding [*scale* 2]
    (let [s *scale*]
      (loop [i 0 acc 0]
        (if (>= i n)
          acc
          (recur (+ i 1) (+ acc (* s i))))))))

;; ---- Suite ------------------------------------------------------

(bench/run-suite "JIT blocker workloads (try + dyn)"
  [["try-never-throw 1k"   1000 (fn [] (try-loop-never-throw 1000))]
   ["try-never-throw 10k"  100  (fn [] (try-loop-never-throw 10000))]
   ["try-sometimes-throw 1k"  1000 (fn [] (try-loop-sometimes-throw 1000))]
   ["try-sometimes-throw 10k" 100  (fn [] (try-loop-sometimes-throw 10000))]
   ["dyn-loop 1k"          1000 (fn [] (dyn-loop 1000))]
   ["dyn-loop 10k"         100  (fn [] (dyn-loop 10000))]
   ["dyn-outer 1k"         1000 (fn [] (dyn-outer 1000))]
   ["dyn-outer 10k"        100  (fn [] (dyn-outer 10000))]])
