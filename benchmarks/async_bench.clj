(ns benchmarks.async-bench)
(require '[mino.bench :as bench])
(require "core/async")

;; Five suites chosen to isolate distinct cost buckets in the current
;; C-backed async implementation:
;;
;;   1. Buffered channel throughput (no scheduler, no parking)
;;      -- pure buffer + offer/poll data-structure cost
;;   2. put!/drain! + poll! on buffered channel
;;      -- buffer + callback queue + drain loop
;;   3. Go-block park/unpark (unbuffered, hand-shake)
;;      -- IOC state machine + channel waitlists + scheduler
;;   4. alts! arbitration across several arities
;;      -- alts handler record allocation + arbitration flag
;;   5. Timer-chan / timeout
;;      -- timer priority queue + host clock + drain
;;   6. Realistic pipeline + fan-out
;;      -- proxy for embed workloads; user code + async together
;;
;; Iteration counts calibrated so each entry runs long enough for GC
;; share to be visible above clock noise.

(defn run []
  ;; --- Suite 1: buffer + offer/poll (no scheduler) ---
  (bench/run-suite "async: buffered channel (offer!/poll!, no scheduler)"
    [["offer!/poll! on (chan 1024)" 100000
      (let [ch (chan 1024)]
        (fn []
          (offer! ch 1)
          (poll! ch)))]
     ["offer!/poll! on (chan 1)" 100000
      (let [ch (chan 1)]
        (fn []
          (offer! ch 1)
          (poll! ch)))]
     ["offer! full returns false" 100000
      (let [ch (chan 1)]
        (offer! ch :x)
        (fn [] (offer! ch :y)))]
     ["poll! empty returns nil" 100000
      (let [ch (chan 1)]
        (fn [] (poll! ch)))]])

  ;; --- Suite 2: put!/take! + drain! on buffered channel ---
  (bench/run-suite "async: buffered put!/take! + drain!"
    [["put!/take! on (chan 1) + drain!" 10000
      (let [ch (chan 1)
            r  (atom nil)]
        (fn []
          (put! ch 1)
          (take! ch (fn [v] (reset! r v)))
          (drain!)))]
     ["put!/take! on (chan 1024) + drain!" 10000
      (let [ch (chan 1024)
            r  (atom nil)]
        (fn []
          (put! ch 1)
          (take! ch (fn [v] (reset! r v)))
          (drain!)))]
     ["put! with callback + drain!" 10000
      (let [ch (chan 1)
            r  (atom nil)]
        (fn []
          (put! ch 1 (fn [ok] (reset! r ok)))
          (poll! ch)
          (drain!)))]])

  ;; --- Suite 3: go-block park/unpark (unbuffered hand-shake) ---
  (bench/run-suite "async: go-block park/unpark"
    [["go (<! ch) with pending put + drain!" 1000
      (fn []
        (let [ch (chan)
              r  (atom nil)]
          (put! ch :v)
          (let [out (go (<! ch))]
            (take! out (fn [v] (reset! r v)))
            (drain!))))]
     ["go (>! ch v) paired with take! + drain!" 1000
      (fn []
        (let [ch (chan)
              r  (atom nil)]
          (go (>! ch :v) :done)
          (take! ch (fn [v] (reset! r v)))
          (drain!)))]
     ["go hand-shake: producer/consumer pair" 500
      (fn []
        (let [ch (chan)
              r  (atom nil)]
          (go (>! ch :ping) :done)
          (let [out (go (<! ch))]
            (take! out (fn [v] (reset! r v)))
            (drain!))))]
     ["go with 3 sequential <!" 500
      (fn []
        (let [a (chan 1) b (chan 1) c (chan 1)
              r (atom nil)]
          (put! a 1) (put! b 2) (put! c 3)
          (drain!)
          (let [out (go (let [x (<! a) y (<! b) z (<! c)] (+ x y z)))]
            (take! out (fn [v] (reset! r v)))
            (drain!))))]])

  ;; --- Suite 4: alts! arbitration ---
  (bench/run-suite "async: alts! arbitration"
    [["alts! over 1 ready channel" 10000
      (let [ch (chan 1024)]
        (fn []
          (offer! ch :v)
          (alts! [ch])))]
     ["alts! over 8 channels, last ready" 5000
      (let [chs (vec (repeatedly 8 (fn [] (chan 1024))))
            hot (last chs)]
        (fn []
          (offer! hot :v)
          (alts! chs {:priority true})))]
     ["alts! with :default (nothing ready)" 10000
      (let [ch (chan)]
        (fn [] (alts! [ch] {:default :nope})))]
     ["alts! put op ready (buffered)" 10000
      (let [ch (chan 1024)]
        (fn []
          (alts! [[ch :v]])
          (poll! ch)))]])

  ;; --- Suite 5: timer / timeout ---
  (bench/run-suite "async: timer / timeout"
    [["(timeout 0) + take! + drain-loop!" 1000
      (fn []
        (let [t (timeout 0)
              r (atom :pending)]
          (take! t (fn [v] (reset! r v)))
          (drain-loop! (fn [] (not (= :pending @r))))))]
     ["alts! over (timeout 0) + dead chan" 1000
      (fn []
        (let [ch (chan)
              t  (timeout 0)
              r  (atom nil)]
          (drain!)
          (alts! [ch t] {:priority true})))]
     ["create 10 overlapping timeouts" 100
      (fn []
        (dotimes [_ 10] (timeout 1)))]])

  ;; --- Suite 6: realistic pipeline ---
  (bench/run-suite "async: realistic pipeline"
    [["producer->xform->consumer, 100 vals" 50
      (fn []
        (let [src  (chan 16 (map inc))
              dst  (atom [])
              done (atom false)]
          (go
            (loop [i 0]
              (if (>= i 100)
                (close! src)
                (do (>! src i) (recur (+ i 1))))))
          (go
            (loop []
              (let [v (<! src)]
                (if (nil? v)
                  (reset! done true)
                  (do (swap! dst conj v) (recur))))))
          (drain-loop! (fn [] @done))))]
     ["fan-in 4->1 via alts! over 40 vals" 50
      (fn []
        (let [ch1 (chan 8) ch2 (chan 8) ch3 (chan 8) ch4 (chan 8)
              got (atom 0)
              done (atom false)
              per 10]
          (go (loop [i 0]
                (if (>= i per) (close! ch1)
                  (do (>! ch1 i) (recur (+ i 1))))))
          (go (loop [i 0]
                (if (>= i per) (close! ch2)
                  (do (>! ch2 i) (recur (+ i 1))))))
          (go (loop [i 0]
                (if (>= i per) (close! ch3)
                  (do (>! ch3 i) (recur (+ i 1))))))
          (go (loop [i 0]
                (if (>= i per) (close! ch4)
                  (do (>! ch4 i) (recur (+ i 1))))))
          (go (loop [closed 0]
                (if (>= closed 4)
                  (reset! done true)
                  (let [[v _] (alts! [ch1 ch2 ch3 ch4])]
                    (if (nil? v)
                      (recur (+ closed 1))
                      (do (swap! got (fn [n] (+ n 1))) (recur closed)))))))
          (drain-loop! (fn [] @done))))]]))

(run)
