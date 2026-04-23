(ns benchmarks.actor-bench)
(require '[mino.bench :as bench])
(require "core/actor")

;; Five suites chosen to isolate distinct cost buckets in the current
;; C-backed actor implementation (clone.c):
;;
;;   1. Spawn lifecycle -- mino_state_new + core install + body eval
;;      + GC of isolated state. Dominates for short-lived actors.
;;   2. Self-send + receive inside body -- mutex + value clone + FIFO
;;      enqueue/dequeue. Isolates mailbox data-structure cost.
;;   3. External send!-only throughput -- spawn empty actor, fill its
;;      mailbox. Measures cross-state clone + enqueue without receive.
;;   4. Payload size scaling -- send! with small/medium/large payloads
;;      to expose deep-clone cost.
;;   5. Mass spawn fan-out + realistic workload -- N small actors
;;      spawned with a handler that does bounded work in-body.
;;
;; Note on method: the actor body runs once synchronously during spawn
;; with no scheduler/tick primitive exposed. Cross-actor send+process
;; cannot be driven from pure mino after spawn returns, so "receive"
;; cost is only measurable inside the same spawn body as the sends.

(defn run []
  ;; --- Suite 1: spawn lifecycle ---
  (bench/run-suite "actor: spawn lifecycle"
    [["spawn empty body" 500
      (fn [] (spawn nil))]
     ["spawn body with local def" 500
      (fn [] (spawn (def h (fn [m] m))))]
     ["spawn body with 10 defs" 200
      (fn []
        (spawn
          (def a 1) (def b 2) (def c 3) (def d 4) (def e 5)
          (def f 6) (def g 7) (def h 8) (def i 9) (def j 10)))]
     ["spawn + immediate empty receive" 500
      (fn [] (spawn (receive)))]])

  ;; --- Suite 2: self-send + receive inside body ---
  (bench/run-suite "actor: self-send + receive inside body"
    [["self-send + receive, 100 int msgs" 100
      (fn []
        (spawn
          (dotimes [_ 100] (send! *self* 1))
          (dotimes [_ 100] (receive))))]
     ["self-send + receive, 1000 int msgs" 20
      (fn []
        (spawn
          (dotimes [_ 1000] (send! *self* 1))
          (dotimes [_ 1000] (receive))))]
     ["100 empty receives (cold mailbox)" 500
      (fn []
        (spawn
          (dotimes [_ 100] (receive))))]])

  ;; --- Suite 3: external send!-only throughput ---
  (bench/run-suite "actor: external send! throughput (no receive)"
    [["send! 100 int msgs to external actor" 100
      (fn []
        (let [a (spawn nil)]
          (dotimes [_ 100] (send! a 1))))]
     ["send! 1000 int msgs to external actor" 20
      (fn []
        (let [a (spawn nil)]
          (dotimes [_ 1000] (send! a 1))))]
     ["send! 100 keyword msgs" 100
      (fn []
        (let [a (spawn nil)]
          (dotimes [_ 100] (send! a :hello))))]])

  ;; --- Suite 4: payload size scaling (clone cost) ---
  (bench/run-suite "actor: send! payload size scaling"
    [["send! small map {3 keys}" 200
      (let [payload {:a 1 :b 2 :c 3}]
        (fn []
          (let [a (spawn nil)]
            (dotimes [_ 100] (send! a payload)))))]
     ["send! medium vector [100 ints]" 200
      (let [payload (vec (range 100))]
        (fn []
          (let [a (spawn nil)]
            (dotimes [_ 50] (send! a payload)))))]
     ["send! large map {100 keys}" 100
      (let [payload (zipmap (map (fn [i] (keyword (str "k" i))) (range 100))
                            (range 100))]
        (fn []
          (let [a (spawn nil)]
            (dotimes [_ 50] (send! a payload)))))]
     ["send! nested [10 x [10 kv map]]" 100
      (let [payload (vec (repeat 10 {:a 1 :b 2 :c 3 :d 4 :e 5
                                     :f 6 :g 7 :h 8 :i 9 :j 10}))]
        (fn []
          (let [a (spawn nil)]
            (dotimes [_ 50] (send! a payload)))))]])

  ;; --- Suite 5: mass spawn + realistic workload ---
  (bench/run-suite "actor: mass spawn + realistic workload"
    [["spawn 100 empty actors" 20
      (fn []
        (dotimes [_ 100] (spawn nil)))]
     ["spawn 100 actors each doing 10 self-sends" 20
      (fn []
        (dotimes [_ 100]
          (spawn
            (dotimes [_ 10] (send! *self* :x))
            (dotimes [_ 10] (receive)))))]
     ["realistic: spawn worker + send 100 tasks (no process)" 50
      (let [task [:compute 42]]
        (fn []
          (let [w (spawn nil)]
            (dotimes [_ 100] (send! w task)))))]
     ["realistic: 10 workers, 10 tasks each, send-only" 50
      (let [task [:compute 42]]
        (fn []
          (let [workers (vec (repeatedly 10 (fn [] (spawn nil))))]
            (doseq [w workers]
              (dotimes [_ 10] (send! w task))))))]]))

(run)
