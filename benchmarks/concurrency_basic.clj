(ns benchmarks.concurrency-basic)
(require '[mino.bench :as bench])
(require "core/async")

;; Concurrency micro-benchmarks for the Performance page.
;;
;; Standalone mino grants cpu_count workers right after install_all,
;; so future/promise/thread and blocking <!!/>!! resolve to real OS
;; threads. Each suite below isolates one concurrency primitive so
;; the per-op cost can be reported on the doc page.
;;
;;   1. Future spawn + deref round-trip (per-future cost end-to-end)
;;   2. Atom CAS contention scaling (1/2/4/8 workers, all incrementing)
;;   3. Blocking channel cross-thread ping-pong throughput
;;
;; The C-side pool comparison ships separately; this file is pure mino
;; so the same shape can run under any embedder that grants threads.

(defn run-future-roundtrip []
  (bench/run-suite "Future spawn + deref round-trip"
    [;; Spawn a future that does almost nothing, deref it. The body
     ;; cost is ~1 prim eval, so the rest is spawn + park + wake.
     ["future + deref (trivial body)" 10000
      (fn [] @(future 42))]
     ;; Same shape, larger body.
     ["future + deref ((+ 1 2 3))" 10000
      (fn [] @(future (+ 1 2 3)))]]))

(defn- atom-cas-bench
  "Run threads workers, each performing iters increments on a shared
   atom via swap!. Each call spawns and joins workers fresh so the bench
   measures per-iteration steady-state cost, not pool warmup."
  [threads iters-per-thread]
  (let [a (atom 0)
        fs (doall (mapv (fn [_]
                          (future
                            (dotimes [_ iters-per-thread]
                              (swap! a inc))))
                        (range threads)))]
    (mapv deref fs)
    @a))

(defn run-atom-cas []
  (let [iters 25000]
    (bench/run-suite "Atom CAS contention scaling"
      [;; 1 worker (no contention)
       [(str "swap! inc, 1 worker, " iters " iters") 10
        (fn [] (atom-cas-bench 1 iters))]
       ;; 2 workers
       [(str "swap! inc, 2 workers, " iters " iters/each") 10
        (fn [] (atom-cas-bench 2 iters))]
       ;; 4 workers
       [(str "swap! inc, 4 workers, " iters " iters/each") 10
        (fn [] (atom-cas-bench 4 iters))]
       ;; 8 workers
       [(str "swap! inc, 8 workers, " iters " iters/each") 10
        (fn [] (atom-cas-bench 8 iters))]])))

(defn- channel-pingpong-bench
  "Producer puts iters values, consumer takes iters values, both run
   on dedicated workers via the blocking <!!/>!! API. Returns when
   both workers terminate."
  [iters]
  (let [ch (chan)
        producer (future
                   (dotimes [i iters]
                     (>!! ch i))
                   (close! ch))
        consumer (future
                   (loop [n 0]
                     (if-let [v (<!! ch)]
                       (recur (inc n))
                       n)))]
    @producer
    @consumer))

(defn run-channel-pingpong []
  (bench/run-suite "Blocking channel cross-thread ping-pong"
    [;; Unbuffered channel: every put rendezvous-parks until matched.
     ["unbuffered chan, 1000 messages" 20
      (fn [] (channel-pingpong-bench 1000))]
     ["unbuffered chan, 5000 messages" 5
      (fn [] (channel-pingpong-bench 5000))]]))

(defn- spawn-batch
  "Spawn n trivial futures, await all. n must stay under the thread
   limit -- the spawn API caps live workers at mino_set_thread_limit
   so n simultaneous futures with n > limit raises a runtime error."
  [n]
  (let [fs (doall (mapv (fn [_] (future 0)) (range n)))]
    (mapv deref fs)))

(defn run-spawn-batch []
  ;; Fan out under the cpu_count cap so the suite measures spawn cost
  ;; without colliding with the runtime limit. The standalone grant on
  ;; an M3 Pro is 12, so 8 stays well clear.
  (bench/run-suite "Spawn-per-future fan-out"
    [["spawn 8 futures, await all" 1000
      (fn [] (spawn-batch 8))]]))

(defn run-all []
  (run-future-roundtrip)
  (run-atom-cas)
  (run-channel-pingpong)
  (run-spawn-batch))

(run-all)
