#?(:clj 1 :cljs 2 :mino 3)
#?(:default :def)
#?@(:clj [1 2 3])
{:a #?(:clj 1 :cljs 2) :b 3}
[#?@(:mino [1 2 3]) 4]
#?(:clj 1 :cljs 2) ; trailing comment
#?(:clj (defn foo []) :mino nil)
