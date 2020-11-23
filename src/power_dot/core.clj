(ns power-dot.core
  (:refer-clojure :exclude [..])
  (:import [clojure.lang Compiler$LocalBinding]
           [clojure.lang Reflector]
           [java.lang.reflect Method Modifier]
           [java.util Arrays]))

(defn- sam-type? [^Class c]
  ;;FIXME: It's not definition of SAM type
  (.isAnnotationPresent c java.lang.FunctionalInterface))

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
                    (and (function-type? a) (sam-type? p)))
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

(defn- get-matching-method [target-type method-name args]
  (let [methods (Reflector/getMethods target-type (count args) method-name false)]
    (when (seq methods)
      (let [param-lists (mapv #(.getParameterTypes ^Method %) methods)
            rets (mapv #(.getReturnType ^Method %) methods)
            method-idx (get-matching-params method-name param-lists args rets)]
        (when-let [^Method m (when (>= method-idx 0) (nth methods method-idx))]
          (if (Modifier/isPublic (-> m .getDeclaringClass .getModifiers))
            m
            (Reflector/getAsMethodOfPublicBase (.getDeclaringClass m) m)))))))

(defn- infer-type [&env sym]
  (let [^Compiler$LocalBinding lb (get &env sym)]
    (when (.hasJavaClass lb)
      (.getJavaClass lb))))

(defn- fixup-arg [^Class param-type arg-type arg]
  (if (and (function-type? arg-type)
           (sam-type? param-type)
           ;; if arg type is a descedant of param type, no need to wrap arg
           ;; with anonymous adapter class
           (not (isa? arg-type param-type)))
    (let [^Method m (->> (.getMethods param-type)
                         (filter #(Modifier/isAbstract (.getModifiers ^Method %)))
                         first)
          param-syms (repeatedly (.getParameterCount m) (partial gensym 'param))]
      `(reify ~(symbol (.getName param-type))
         (~(symbol (.getName m)) [_# ~@param-syms]
          (~arg ~@param-syms))))
    arg))

(defmacro dot* [target method-name & args]
  (let [target-type (infer-type &env target)
        arg-types (for [arg args]
                    (if (contains? &env arg)
                      (infer-type &env arg)
                      (when-let [v (resolve arg)]
                        (when (and (var? v) (function-type? (class @v)))
                          (class @v)))))]
    `(. ~target ~method-name
        ~@(if-let [^Method m (some-> target-type
                                     (get-matching-method (str method-name) arg-types))]
            (map fixup-arg (.getParameterTypes m) arg-types args)
            args))))

(defmacro . [target & args]
  (if (and (= (count args) 1) (seq? (first args)))
    `(power-dot.core/. ~target ~@(first args))
    (let [[mname & args] args
          tsym (gensym 'target)
          asyms (for [arg args]
                  (when-not (symbol? arg)
                    (gensym 'arg)))]
      `(let [~tsym ~target
             ~@(mapcat (fn [asym arg]
                         (when asym [asym arg]))
                       asyms args)]
         (dot* ~tsym ~mname
               ~@(map (fn [asym arg] (or asym arg)) asyms args))))))

(defmacro ..
  ([x form] `(power-dot.core/. ~x ~form))
  ([x form & more]
   `(power-dot.core/.. (power-dot.core/. ~x ~form) ~@more)))
