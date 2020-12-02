(ns power-dot.core
  (:refer-clojure :exclude [..])
  (:require [clojure.string :as str])
  (:import [clojure.lang Compiler$LocalBinding]
           [clojure.lang Reflector]
           [java.lang.reflect Constructor Method Modifier]
           [java.util Arrays]))

(defn- functional-interface? [^Class c]
  (and (some? c)
       (or (.isAnnotationPresent c java.lang.FunctionalInterface)
           (and (.isInterface c)
                (->> (.getMethods c)
                     (reduce (fn [n ^Method m]
                               (if (Modifier/isAbstract (.getModifiers m))
                                 (let [n' (inc n)]
                                   (if (> n' 1) (reduced n') n'))
                                 n))
                             0)
                     (= 1))))))

(defn- function-type? [c]
  (isa? c clojure.lang.AFn))

(defn- params-match? [params args]
  (loop [params params
         args args
         exact 0]
    (if (seq params)
      (let [p (first params)
            a (or (first args) Object)]
        (if (= p a)
          (recur (next params) (next args) (inc exact))
          (when (or (Reflector/paramArgTypeMatch p a)
                    (and (function-type? a) (functional-interface? p)))
            (recur (next params) (next args) exact))))
      exact)))

(defn- get-matching-params [method-name param-lists args rets]
  (loop [i 0, match-idx -1, tied false, found-exact? false]
    (if (>= i (count param-lists))
      (if tied
        (throw (IllegalArgumentException.
                (str "More than one matching method found: " method-name)))
        match-idx)
      (let [match (params-match? (nth param-lists i) args)]
        (cond (and match (= match (count args)))
              (recur (inc i)
                     (if (or (not found-exact?)
                             (= match-idx -1)
                             (.isAssignableFrom ^Class (nth rets match-idx) (nth rets i)))
                       i
                       match-idx)
                     false
                     true)
              (and match (not found-exact?))
              (cond (= match-idx -1)
                    (recur (inc i) i tied found-exact?)

                    (Compiler/subsumes (nth param-lists i) (nth param-lists match-idx))
                    (recur (inc i) i false found-exact?)

                    (Arrays/equals ^objects (nth param-lists match-idx)
                                   ^objects (nth param-lists i))
                    (recur (inc i)
                           (if (.isAssignableFrom ^Class (nth rets match-idx) (nth rets i))
                             i
                             match-idx)
                           tied
                           found-exact?)

                    (not (Compiler/subsumes (nth param-lists match-idx) (nth param-lists i)))
                    (recur (inc i) match-idx true found-exact?)

                    :else (recur (inc i) match-idx tied found-exact?))
              :else (recur (inc i) match-idx tied found-exact?))))))

(defn- get-matching-method [static? target-type method-name args]
  (let [methods (Reflector/getMethods target-type (count args) method-name static?)]
    (case (count methods)
      0 nil
      1 (first methods)
      (let [param-lists (mapv #(.getParameterTypes ^Method %) methods)
            rets (mapv #(.getReturnType ^Method %) methods)
            method-idx (get-matching-params method-name param-lists args rets)]
        (when-let [^Method m (when (>= method-idx 0) (nth methods method-idx))]
          (if (Modifier/isPublic (-> m .getDeclaringClass .getModifiers))
            m
            (Reflector/getAsMethodOfPublicBase (.getDeclaringClass m) m)))))))

(defn- get-matching-ctor [^Class target-type args]
  (let [ctors (->> (.getConstructors target-type)
                   (filterv (fn [^Constructor ctor]
                              (= (.getParameterCount ctor) (count args)))))]
    (case (count ctors)
      0 nil
      1 (first ctors)
      (let [param-lists (mapv #(.getParameterTypes ^Constructor %) ctors)
            rets (into [] (map (constantly target-type)) ctors)
            ctor-idx (get-matching-params (.getName target-type) param-lists args rets)]
        (when (>= ctor-idx 0) (nth ctors ctor-idx))))))

(defn- infer-type [&env sym]
  (let [^Compiler$LocalBinding lb (get &env sym)]
    (when (.hasJavaClass lb)
      (.getJavaClass lb))))

(defn- infer-arg-type [&env sym]
  (if (contains? &env sym)
    (infer-type &env sym)
    (when-let [v (resolve sym)]
      (when (and (var? v) (function-type? (class @v)))
        (class @v)))))

(defn- hinted-arg-type [arg]
  (some-> arg meta :tag resolve))

(defn- fixup-arg [^Class param-type arg-type hinted-type arg]
  (if (and (not= param-type arg-type)
           (or (and (function-type? arg-type)
                    (functional-interface? param-type))
               (functional-interface? hinted-type))
           ;; if arg type is a descedant of param type, no need to wrap arg
           ;; with anonymous adapter class
           (not (isa? arg-type param-type)))
    (let [^Method m (->> (.getMethods param-type)
                         (filter #(Modifier/isAbstract (.getModifiers ^Method %)))
                         first)
          param-syms (repeatedly (.getParameterCount m) (partial gensym 'param))]
      `(reify ~(symbol (.getName param-type))
         (~(symbol (.getName m)) [_# ~@param-syms]
          (~(with-meta arg nil) ~@param-syms))))
    arg))

(defn- strip-tag [x]
  (if (instance? clojure.lang.IObj x)
    (vary-meta x dissoc :tag)
    x))

(defn- inherit-tag [x y]
  (if-let [t (:tag (meta y))]
    (with-meta x {:tag t})
    x))

(defmacro dot* [static? target method-name & args]
  (let [target-type (if static? (resolve target) (infer-type &env target))
        arg-types (map (partial infer-arg-type &env) args)
        coerced-arg-types (map #(or (hinted-arg-type %1) %2) args arg-types)]
    `(. ~target ~method-name
        ~@(if-let [^Method m (when target-type
                               (get-matching-method static? target-type
                                                    (str method-name)
                                                    coerced-arg-types))]
            (map fixup-arg (.getParameterTypes m) arg-types coerced-arg-types args)
            args))))

(defmacro . [target & args]
  (if (and (= (count args) 1) (seq? (first args)))
    `(power-dot.core/. ~target ~@(first args))
    (let [[mname & args] args
          static? (and (symbol? target)
                       (class? (resolve target)))
          tsym (gensym 'target)
          asyms (for [arg args]
                  (when-not (symbol? arg)
                    (gensym 'arg)))]
      `(let [~@(when-not static?
                 [tsym target])
             ~@(mapcat (fn [asym arg]
                         (when asym [asym (strip-tag arg)]))
                       asyms args)]
         (dot* ~static? ~(if static? target tsym) ~mname
               ~@(map (fn [asym arg]
                        (or (some-> asym (inherit-tag arg))
                            arg))
                      asyms args))))))

(defmacro new* [c & args]
  (let [target-type (resolve c)
        arg-types (map (partial infer-arg-type &env) args)
        coerced-arg-types (map #(or (hinted-arg-type %1) %2) args arg-types)]
    `(new ~c
          ~@(if-let [^Constructor ctor (some-> target-type
                                               (get-matching-ctor coerced-arg-types))]
              (map fixup-arg (.getParameterTypes ctor) arg-types coerced-arg-types args)
              args))))

(defmacro new [c & args]
  (let [asyms (for [arg args]
                (when-not (symbol? arg)
                  (gensym 'arg)))]
    `(let [~@(mapcat (fn [asym arg]
                       (when asym [asym (strip-tag arg)]))
                     asyms args)]
       (new* ~c ~@(map (fn [asym arg]
                         (or (some-> asym (inherit-tag arg))
                             arg))
                       asyms args)))))

(defmacro ..
  ([x form] `(power-dot.core/. ~x ~form))
  ([x form & more]
   `(power-dot.core/.. (power-dot.core/. ~x ~form) ~@more)))

(defmacro as-fn [x]
  `(let [^clojure.lang.AFunction x# ~x] x#))

(defn expand-ordinary-form [form]
  (or (when (and (seq? form)
                 (>= (count form) 2)
                 (symbol? (first form)))
        (let [op (first form)
              op-name (str op)]
          (some->
           (cond (= op '.)
                 `(power-dot.core/. ~@(rest form))

                 (str/starts-with? op-name ".")
                 `(power-dot.core/. ~(second form)
                                    ~(symbol (subs op-name 1))
                                    ~@(nnext form))

                 (str/ends-with? op-name ".")
                 `(power-dot.core/new ~(symbol (subs op-name 0 (dec (count op-name))))
                                      ~@(rest form))

                 (some-> (namespace op) symbol resolve class?)
                 `(power-dot.core/. ~(symbol (namespace op))
                                    ~(symbol (name op))
                                    ~@(rest form)))
           (with-meta (meta form)))))
      form))

(defn expand-thread-first-form [form]
  (or (when (and (seq? form)
                 (symbol? (first form)))
        (let [op (first form)
              op-name (str op)]
          (some->
           (cond (= op '.)
                 `(power-dot.core/. ~@(rest form))

                 (str/starts-with? op-name ".")
                 `(power-dot.core/. ~(symbol (subs op-name 1))
                                    ~@(rest form))

                 (str/ends-with? op-name ".")
                 `((fn [x#]
                     (power-dot.core/new ~(symbol (subs op-name 0 (dec (count op-name))))
                                         x#
                                         ~@(rest form))))

                 (some-> (namespace op) symbol resolve class?)
                 `((fn [x#]
                     (power-dot.core/. ~(symbol (namespace op))
                                       ~(symbol (name op))
                                       x#
                                       ~@(rest form)))))
           (with-meta (meta form)))))
      form))
