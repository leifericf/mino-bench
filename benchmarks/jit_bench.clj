(ns benchmarks.jit-bench)
(require '[mino.bench :as bench])

;; Micro-bench for the copy-and-patch JIT.
;;
;; The harness compares `(fn ...)` shapes whose bodies match the
;; runtime's current stencil set against the interpreter baseline.
;; Each top-level fn is warmed past the JIT hot threshold (100 calls)
;; before benchmarking; this lets the bench measure the steady-state
;; native dispatch cost rather than the first-call-through-interpreter
;; cost.
;;
;; Each suite covers shapes whose stencils land in successive cycle
;; releases. Numbers move as the stencil set widens; the cycle close
;; rolls them into a comparison table.

;; Trivial pass-through and constant-returning -- JIT-eligible since
;; v0.195.0 (OP_MOVE / OP_LOAD_K / OP_RETURN_IMM + fused LOAD_K_RETURN).
(def id-fn      (fn [x]   x))
(def const42-fn (fn []    42))
(def pick-a-fn  (fn [a b] a))

;; Arith II shapes -- JIT-eligible since v0.197.0 (ADD/SUB/MUL_II)
;; and v0.198.0 (LT/LE/GT/GE/EQ_II).
(def add-fn  (fn [a b] (+ a b)))
(def sub-fn  (fn [a b] (- a b)))
(def mul-fn  (fn [a b] (* a b)))
(def lt-fn   (fn [a b] (< a b)))
(def eq-fn   (fn [a b] (= a b)))

;; Unary shapes -- JIT-eligible since v0.199.0 (INC_I / DEC_I / ZERO_INT_P).
(def inc-fn  (fn [x] (inc x)))
(def dec-fn  (fn [x] (dec x)))
(def zero-fn (fn [x] (zero? x)))

;; Immediate-arg shapes -- JIT-eligible since v0.199.0
;; (ADD_IK / SUB_IK / LT_IK / LE_IK / EQ_IK).
(def add-3-fn  (fn [x] (+ x 3)))
(def lt-100-fn (fn [x] (< x 100)))

;; Control-flow shape -- JIT-eligible since v0.200.0 (OP_JMP / OP_JMPIFNOT
;; + chain-ABI fix).
(def abs-fn (fn [x] (if (< x 0) (- 0 x) x)))

;; Counted-loop body shapes.
;;
;;  sum-to       compiles to unfused INC_I + LT_II + JMPIFNOT + JMP --
;;               JIT-eligible since v0.200.0.
;;  count-to     compiles to fused OP_LOOP_INT_LT -- intentionally NOT
;;               in the stencil table after v0.202.0 (a stencil was
;;               written and benched but regressed vs the interpreter
;;               so v0.202.0 dropped it from g_stencils).
;;  countdown    compiles to fused OP_LOOP_INT_DEC -- JIT-eligible since
;;               v0.201.0.
;;  lockstep     compiles to fused OP_LOOP_INT_LT_INC -- JIT-eligible
;;               since v0.201.0.
(def sum-to-fn   (fn [n]
                   (loop [i 0 acc 0]
                     (if (< i n)
                       (recur (inc i) (+ acc i))
                       acc))))
(def count-to-fn (fn [n]
                   (loop [i 0]
                     (if (< i n) (recur (inc i)) i))))
(def countdown-fn (fn [n]
                    (loop [i n]
                      (if (zero? i) i (recur (dec i))))))
(def lockstep-fn (fn [n]
                   (loop [i 0 k 0]
                     (if (< i n) (recur (inc i) (inc k)) k))))

;; Pre-warm every fn under test past the 100-call hot threshold.
(dotimes [_ 300]
  (id-fn 1) (const42-fn) (pick-a-fn 1 2)
  (add-fn 1 2) (sub-fn 3 2) (mul-fn 2 3)
  (lt-fn 1 2) (eq-fn 1 1)
  (inc-fn 1) (dec-fn 1) (zero-fn 0)
  (add-3-fn 1) (lt-100-fn 1)
  (abs-fn -5) (abs-fn 5)
  (sum-to-fn 10) (count-to-fn 10)
  (countdown-fn 10) (lockstep-fn 10))

(defn run []
  (bench/run-suite "Trivial shapes (v0.195.0 stencil set)"
    [["(id 7)              x 1M" 1000000 (fn [] (id-fn 7))]
     ["(const42)           x 1M" 1000000 (fn [] (const42-fn))]
     ["(pick-a 1 2)        x 1M" 1000000 (fn [] (pick-a-fn 1 2))]])
  (bench/run-suite "Arith II shapes (v0.197.0 / v0.198.0 stencils)"
    [["(add 1 2)           x 1M" 1000000 (fn [] (add-fn 1 2))]
     ["(sub 3 2)           x 1M" 1000000 (fn [] (sub-fn 3 2))]
     ["(mul 2 3)           x 1M" 1000000 (fn [] (mul-fn 2 3))]
     ["(lt? 1 2)           x 1M" 1000000 (fn [] (lt-fn 1 2))]
     ["(= 1 1)             x 1M" 1000000 (fn [] (eq-fn 1 1))]])
  (bench/run-suite "Unary / immediate-arg shapes (v0.199.0 stencils)"
    [["(inc 1)             x 1M" 1000000 (fn [] (inc-fn 1))]
     ["(dec 1)             x 1M" 1000000 (fn [] (dec-fn 1))]
     ["(zero? 0)           x 1M" 1000000 (fn [] (zero-fn 0))]
     ["(+ x 3)             x 1M" 1000000 (fn [] (add-3-fn 5))]
     ["(< x 100)           x 1M" 1000000 (fn [] (lt-100-fn 5))]])
  (bench/run-suite "Control flow (v0.200.0 OP_JMP / OP_JMPIFNOT)"
    [["(abs -5)            x 1M" 1000000 (fn [] (abs-fn -5))]
     ["(abs 5)             x 1M" 1000000 (fn [] (abs-fn 5))]])
  (bench/run-suite "Counted-loop bodies (v0.200.0 unfused + v0.201.0 fused)"
    [["(sum-to 100)        x  10K"  10000 (fn [] (sum-to-fn 100))]
     ["(sum-to 1000)       x   1K"   1000 (fn [] (sum-to-fn 1000))]
     ["(count-to 1000)     x   1K"   1000 (fn [] (count-to-fn 1000))]
     ["(countdown 1000)    x   1K"   1000 (fn [] (countdown-fn 1000))]
     ["(lockstep 1000)     x   1K"   1000 (fn [] (lockstep-fn 1000))]]))

(run)
