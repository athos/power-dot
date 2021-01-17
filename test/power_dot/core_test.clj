(ns power-dot.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [power-dot.core :as dot])
  (:import [java.util ArrayList Optional]
           [java.util.stream IntStream]))

(deftest functional-interface?-test
  (is (#'dot/functional-interface? java.util.function.Function))
  (is (#'dot/functional-interface? Iterable))
  (is (not (#'dot/functional-interface? java.util.Iterator)))
  (is (not (#'dot/functional-interface? String))))

(deftest dot-test
  (is (= "FOO" 
         (-> (Optional/of "foo")
             (dot/. map str/upper-case)
             (.get))))
  (is (= 45 (dot/. (IntStream/range 0 10) (reduce 0 +)))))

(deftest dotdot-test
  (is (= (apply + [1 3 5 7 9])
         (dot/.. (IntStream/range 0 10)
                 (filter even?)
                 (map inc)
                 (reduce 0 +)))))

(deftest as-fn-test
  (is (= "foobar"
         (-> (Optional/of "bar")
             (dot/. (map (dot/as-fn (partial str "foo"))))
             (.get))))
  (is (= ["foo" "bar"]
         (-> (ArrayList. [{:val "foo"} {:val "bar"}])
             (.stream)
             (dot/. (map (dot/as-fn :val)))
             (.toArray)
             seq))))