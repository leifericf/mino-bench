(ns benchmarks.real-workloads)
(require '[mino.bench :as bench])
(require '[clojure.string :as str])

;; Real-workload-shaped benchmarks. Each one is designed to be
;; dispatch-heavy (so the JIT can move it) and reach into a
;; subsystem the cycle's JIT-2 work touched: protocol dispatch,
;; binding cascades, callback-heavy pipeline kernels, parsing.
;;
;; These are NOT GC-bound the way realistic_bench's int-map rows
;; are; the JIT-on / JIT-off ratio should clear the noise envelope
;; on rows the cycle targets.

;; ---- 1. CSV-like parser over a 10 MB-ish synthetic string. ----------

(defn- gen-csv [rows cols]
  ;; Build the string once outside the timed body.
  (apply str
         (for [r (range rows)]
           (str (apply str (interpose "," (for [c (range cols)]
                                            (str "r" r "c" c))))
                "\n"))))

(def ^:private csv-blob (gen-csv 1000 10))   ;; ~70 KB; iters scale

(defn- parse-csv [s]
  (let [lines (str/split s "\n")]
    (mapv (fn [line] (str/split line ",")) lines)))

;; ---- 2. Transducer-pipeline-style over chars + ints -----------------

(defn- pipeline-kernel [coll]
  (->> coll
       (map inc)
       (filter (fn [n] (zero? (mod n 3))))
       (map (fn [n] (* n n)))
       (reduce +)))

;; ---- 3. Protocol-heavy state machine. -------------------------------

(defprotocol IStep
  (step [s in]))

(defrecord IdleState [])
(defrecord BusyState [n])
(defrecord DoneState [n])

(extend-protocol IStep
  IdleState
  (step [_ in]
    (if (= in :start) (->BusyState 0) (->IdleState)))
  BusyState
  (step [s in]
    (cond
      (= in :stop) (->DoneState (:n s))
      (= in :tick) (->BusyState (inc (:n s)))
      :else        s))
  DoneState
  (step [s _] s))

(defn- run-state-machine [n]
  (loop [s (->IdleState)
         events (concat [:start] (repeat n :tick) [:stop])]
    (if (empty? events)
      s
      (recur (step s (first events)) (rest events)))))

;; ---- 4. Binding-based logger. ---------------------------------------

(def ^:dynamic *verbose* false)
(def ^:dynamic *prefix* "")

(defn- log-noise [n]
  (loop [i 0 emitted 0]
    (if (>= i n)
      emitted
      (let [out (if *verbose*
                  (str *prefix* "i=" i)
                  nil)]
        (recur (+ i 1) (if (nil? out) emitted (+ emitted 1)))))))

(defn- nested-binding-log []
  (let [a (binding [*verbose* false] (log-noise 10000))
        b (binding [*verbose* true *prefix* "[A] "] (log-noise 10000))
        c (binding [*verbose* false]
            (binding [*verbose* true *prefix* "[B] "] (log-noise 10000)))]
    (+ a b c)))

(defn run []
  (bench/run-suite "Real-shaped workloads"
    [["csv parse 1k rows x 10 cols" 10
      (fn [] (count (parse-csv csv-blob)))]

     ["pipeline 50k ints (transducer-shaped)" 20
      (fn [] (pipeline-kernel (range 50000)))]

     ["protocol state machine 5k ticks" 30
      (fn [] (run-state-machine 5000))]

     ["nested binding logger" 30
      (fn [] (nested-binding-log))]]))

(run)
