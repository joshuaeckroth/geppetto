(ns geppetto.fn-test
  (:use [clojure.test])
  (:require [plumbing.graph :as graph])
  (:require [clojure.core.cache :as cache])
  (:use [geppetto.fn]))

(deftest test-fn-params
  (let [g1 (paramfnk [x y] [a [1 2 3] b (range 4 7)] (* x y a b))
        g2 {:result (paramfnk [x y] [a [1 2 3] b [4 5 6]] (* x y a b))}
        g3 {:result g1}
        f1 (compile-graph graph/eager-compile g1)
        f2 (compile-graph graph/eager-compile g2)
        f3 (compile-graph graph/eager-compile g3)]
    (is (= (* 1 2 3 4) (f1 {:x 1 :y 2 :params {:a 3 :b 4}})))
    (is (= (* 1 2 3 4) (:result (f2 {:x 1 :y 2 :params {:a 3 :b 4}}))))
    (is (= (* 1 2 3 4) (:result (f3 {:x 1 :y 2 :params {:a 3 :b 4}}))))
    (is (= [:a :b] (fn-params g1)))
    (is (= [1 2 3] (fn-param-range g1 :a)))
    (is (= [4 5 6] (fn-param-range g1 :b)))
    (is (= [:a :b] (fn-params f1)))
    (is (= [1 2 3] (fn-param-range f1 :a)))
    (is (= [4 5 6] (fn-param-range f1 :b)))
    (is (vector? (fn-param-range f1 :b)))
    (is (= [:a :b] (fn-params f2)))
    (is (= [1 2 3] (fn-param-range f2 :a)))
    (is (= [4 5 6] (fn-param-range f2 :b)))
    (is (= [:a :b] (fn-params f3)))
    (is (= [1 2 3] (fn-param-range f3 :a)))
    (is (= [4 5 6] (fn-param-range f3 :b)))
    (is (= '[x y] (:bindings (meta g1))))))

(deftest test-all-fn-params
  (let [g1 (paramfnk [x y] [a [1 2] b [3 4]] (* x y a b))
        g2 {:x (paramfnk [y] [a [1 2]] (+ y a))
            :z (paramfnk [x y] [b [3 4]] (+ x y b))}
        f1 (compile-graph graph/eager-compile g1)
        f2 (compile-graph graph/eager-compile g2)]
    (is (= {:a [1 2] :b [3 4]} (all-fn-params g1)))
    (is (= [{:a 1 :b 3} {:a 1 :b 4}
            {:a 2 :b 3} {:a 2 :b 4}]
           (all-fn-params-combinations g1)))
    (is (= {:a [1 2] :b [3 4]} (all-fn-params f1)))
    (is (= [{:a 1 :b 3} {:a 1 :b 4}
            {:a 2 :b 3} {:a 2 :b 4}]
           (all-fn-params-combinations f1)))
    (is (= {:a [1 2] :b [3 4]} (all-fn-params g2)))
    (is (= [{:a 1 :b 3} {:a 1 :b 4}
            {:a 2 :b 3} {:a 2 :b 4}]
           (all-fn-params-combinations g2)))
    (is (= {:a [1 2] :b [3 4]} (all-fn-params f2)))
    (is (= [{:a 1 :b 3} {:a 1 :b 4}
            {:a 2 :b 3} {:a 2 :b 4}]
           (all-fn-params-combinations f2)))))

(deftest test-with-params
  (let [g1 (paramfnk [x y] [a [1 2] c [3 4]] (* x y a c))
        g2 {:x (paramfnk [y] [a [1 2]] (+ y a))
            :z (paramfnk [x y] [b [3 4]] (+ x y b))}
        f1 (compile-graph graph/eager-compile g1)
        f2 (compile-graph graph/eager-compile g2)
        f (fn-with-params [x y] (let [foo 1]
                                  (+ (f1 {:x x :y foo :params params})
                                     (:z (f2 {:x x :y y :params params})))))]
    (is (= (+ (* 5 1 2 8) (+ (+ 2 2) 2 3))
           (f {:x 5 :y 2 :params {:a 2 :b 3 :c 8}})))
    (is (= {:a [1 2], :b [3 4] :c [3 4]}
           (all-fn-params f)))
    (is (= '[x y] (:bindings (meta f))))))

(deftest test-fnkc
  (let [f1 (fnkc f1 [x y] (+ x y))
        c (atom (cache/lru-cache-factory {}))]
    (is (= (f1 {:x 1 :y 2 :cache c}) 3))
    (is (= @c {{:fn-name :f1 :args {:x 1 :y 2}} 3}))))

(deftest test-fnkc-2
  (let [g1 {:foo (fnkc foo [x y] (+ x y))
            :baz (fnkc baz [x y] (/ x y))
            :bar (fnkc bar [foo z] (* foo z))}
        f1 (compile-graph graph/eager-compile g1)
        c (atom (cache/lru-cache-factory {}))]
    (let [result (f1 {:x 1 :y 2 :z 3 :cache c})]
      (prn result)
      (is (= (* (+ 1 2) 3) (:bar result)))
      (is (= (/ 1 2) (:baz result))))
    (is (= @c {{:fn-name :foo :args {:y 2 :x 1}} 3
               {:fn-name :baz :args {:y 2 :x 1}} (/ 1 2)
               {:fn-name :bar :args {:foo 3 :z 3}} 9}))))

(deftest test-paramfnkc
  (let [f1 (paramfnkc f1 [x y] [foo [11 12] bar [13 14]] (+ x y foo bar))
        c (atom (cache/lru-cache-factory {}))]
    (is (= (f1 {:x 1 :y 2 :cache c :params {:foo 11 :bar 13}})
           (+ 1 2 11 13)))
    (is (= @c {{:fn-name :f1 :args {:x 1 :y 2 :params {:foo 11 :bar 13}}} (+ 1 2 11 13)}))))
