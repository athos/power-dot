(ns power-dot.core
  (:refer-clojure :exclude [..])
  (:import [clojure.lang Compiler$LocalBinding]
           [clojure.lang Reflector]
           [java.lang.reflect Method Modifier]
           [java.util Arrays]))

(defn- sam-type? [^Class c]
  ;;FIXME: It's not definition of SAM type
  (.isAnnotationPresent c java.lang.FunctionalInterface))

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
                    (and (= a clojure.lang.AFunction)
                         (sam-type? p)))
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
  (if (and (= arg-type clojure.lang.AFunction)
           (sam-type? param-type))
    (let [^Method m (->> (.getMethods param-type)
                         (filter #(Modifier/isAbstract (.getModifiers ^Method %)))
                         first)
          param-syms (repeatedly (.getParameterCount m) (partial gensym 'param))]
      `(reify ~(symbol (.getName param-type))
         (~(symbol (.getName m)) [_# ~@param-syms]
          (~arg ~@param-syms))))
    arg))

(defn- fixup-args [param-types arg-types args]
  (map fixup-arg param-types arg-types args))

(defmacro dot* [target method-name & args]
  (let [target-type (infer-type &env target)
        arg-types (map (partial infer-type &env) args)]
    (if-let [^Method m (get-matching-method target-type (str method-name) arg-types)]
      `(. ~target ~method-name ~@(fixup-args (.getParameterTypes m) arg-types args))
      `(. ~target ~method-name ~@args))))

(defmacro . [target [mname & args]]
  (let [tsym (gensym 'target)
        asyms (map (fn [_] (gensym 'arg)) args)]
    `(let [~tsym ~target
           ~@(mapcat (fn [asym arg] [asym arg]) asyms args)]
       (dot* ~tsym ~mname ~@asyms))))

(defmacro ..
  ([x form] `(power-dot.core/. ~x ~form))
  ([x form & more]
   `(power-dot.core/.. (power-dot.core/. ~x ~form) ~@more)))
