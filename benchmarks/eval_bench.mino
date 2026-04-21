(ns benchmarks.eval-bench)
(require '[mino.bench :as bench])

(defn run []
  (bench/run-suite "Eval and function call overhead"
    [["empty fn call" 100000
      (let [f (fn [] nil)]
        (fn [] (f)))]
     ["identity fn call" 100000
      (let [f (fn [x] x)]
        (fn [] (f 42)))]
     ["3-arg fn call" 100000
      (let [f (fn [a b c] (+ a b c))]
        (fn [] (f 1 2 3)))]
     ["let binding (5 bindings)" 10000
      (fn [] (let [a 1 b 2 c 3 d 4 e 5] (+ a b c d e)))]
     ["fibonacci(20) recursive" 100
      (fn [] ((fn fib [n]
                (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))
              20))]
     ["map + filter + reduce pipeline" 1000
      (fn [] (reduce + 0 (filter odd? (map inc (range 100)))))]]))

(run)
