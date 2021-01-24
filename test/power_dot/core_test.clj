(ns power-dot.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [power-dot.core :as dot])
  (:import [java.util ArrayList Optional]
           [java.util.concurrent ForkJoinPool]
           [java.util.stream Collectors IntStream]))

(deftest functional-interface?-test
  (is (#'dot/functional-interface? java.util.function.Function))
  (is (#'dot/functional-interface? Iterable))
  (is (not (#'dot/functional-interface? nil)))
  (is (not (#'dot/functional-interface? String)))
  (is (not (#'dot/functional-interface? java.util.Iterator))))

(defmacro infer [sym]
  (#'dot/infer-type &env sym))

(def ^String s "foo")
(def ^ints arr (int-array 0))
(def ^"[J" arr' (long-array 0))

(deftest infer-type-test
  (is (= (class +) (infer +)))
  (is (= String (infer s)))
  (is (= CharSequence (infer ^CharSequence s)))
  (is (= String (infer java.io.File/separator)))
  (is (= CharSequence (infer ^CharSequence java.io.File/separator)))
  (is (= (Class/forName "[I") (infer arr)))
  (is (= (Class/forName "[J") (infer arr')))
  (let [d 12.3]
    (is (= Double/TYPE (infer d))))
  (is (= Class (infer Optional)))
  (is (= Class (infer java.util.UUID))))

(deftest get-matching-method
  (is (= (.getMethod ForkJoinPool "submit" (into-array [Runnable]))
         (#'dot/get-matching-method false ForkJoinPool "submit" [Runnable])))
  (is (thrown? Throwable
               (#'dot/get-matching-method false ForkJoinPool "submit"
                                          [(class compare)]))))

(deftest dot-test
  (is (= "FOO"
         (-> (Optional/of "foo")
             (dot/. map str/upper-case)
             (.get))))
  (is (= 45 (dot/. (IntStream/range 0 10) (reduce 0 +))))
  (is (= 25
         (-> (IntStream/range 3 5)
             (dot/. reduce 0 #(+ %1 (* %2 %2))))))
  (is (= 20
         (-> (IntStream/range 0 5)
             (dot/. map ^java.util.function.Function (partial * 2))
             (.sum))))
  (is (= 15
         (-> (ArrayList. [1 2 3 4 5])
             (.stream)
             (.collect (dot/. Collectors reducing 0 +)))))
  (let [xs (ArrayList. [3 1 4 1 5])]
    (dot/. java.util.Collections (sort xs compare))
    (is (= [1 1 3 4 5] xs))))

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