(ns benchmarks.side-exit-micro)
(require '[mino.bench :as bench])

;; Side-exit / deopt micro bench. Probes the JIT's compile-with-deopt
;; path landed in mino v0.319.0: fns whose first unstenciled op sits
;; past PC 0 compile to a native prefix plus a deopt stencil that
;; bails the rest of the body into the interpreter via
;; mino_bc_run_resume. The interesting measurement is the round-trip
;; cost per deopt -- if it sits below the cost of running the prefix
;; through the interpreter, side-exit is a net win.
;;
;; Each fn here is shaped so the JIT can cover its prefix (OP_LOAD_K /
;; OP_INC_I / OP_MOVE / OP_RETURN-class ops are all stenciled) and so
;; its tail uses OP_PUSHDYN (via binding), which is unstenciled today
;; and forces the side-exit. The pure-interp control has its dyn op
;; at PC 0, so the classifier rejects it outright (UNKNOWN_OP, no
;; prefix) and the interpreter takes the whole body.

;; ---- 1. Pure-interp control: dyn op at PC 0, classifier rejects. ----

(defn dyn-at-pc0 [x]
  (binding [*ns* *ns*]
    (+ x 1)))

;; ---- 2. Tiny prefix (~3 stenciled ops) before the dyn split. --------

(defn deopt-tiny [x]
  (let [a (inc x)]
    (binding [*ns* *ns*]
      (+ a 1))))

;; ---- 3. Medium prefix (~30 stenciled ops). --------------------------

(defn deopt-medium [x]
  (let [a (inc x)
        b (inc a)
        c (inc b)
        d (inc c)
        e (inc d)
        f (inc e)
        g (inc f)
        h (inc g)
        i (inc h)
        j (inc i)
        k (inc j)
        l (inc k)
        m (inc l)
        n (inc m)
        o (inc n)
        p (inc o)
        q (inc p)
        r (inc q)
        s (inc r)
        t (inc s)]
    (binding [*ns* *ns*]
      (+ t 1))))

;; ---- 4. Pure stenciled fn (no deopt) -- baseline cost reference. ----

(defn pure-prefix [x]
  (let [a (inc x)
        b (inc a)
        c (inc b)]
    (+ c 1)))

;; Warm everything past the JIT hot threshold.
(dotimes [_ 500]
  (dyn-at-pc0 1)
  (deopt-tiny 1)
  (deopt-medium 1)
  (pure-prefix 1))

(defn run []
  (bench/run-suite "Side-exit micro (v0.320)"
    [["pure-prefix (no deopt, baseline) x 1M"
      1000000 (fn [] (pure-prefix 7))]

     ["dyn-at-pc0 (classifier rejects)  x 1M"
      1000000 (fn [] (dyn-at-pc0 7))]

     ["deopt-tiny (~3-op native prefix) x 1M"
      1000000 (fn [] (deopt-tiny 7))]

     ["deopt-medium (~30-op prefix)     x 1M"
      1000000 (fn [] (deopt-medium 7))]]))

(run)
