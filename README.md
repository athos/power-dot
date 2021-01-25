# power-dot
[![Clojars Project](https://img.shields.io/clojars/v/power-dot.svg)](https://clojars.org/power-dot)
![build](https://github.com/athos/power-dot/workflows/build/badge.svg)
[![codecov](https://codecov.io/gh/athos/power-dot/branch/main/graph/badge.svg?token=W0F2GH2J2M)](https://codecov.io/gh/athos/power-dot)

Clojure library for enhanced Java interop that helps you make friends with Java's functional interfaces 😍

## Table of Contents

- [Rationale](#rationale)
- [Installation](#installation)
- [Usage](#usage)
  - [`dot/.` & `dot/new`](#dot--dotnew)
  - [`dot/..`](#dot)
  - [`dot/as-fn`](#dotas-fn)
  - [Reader syntax](#reader-syntax)

## Rationale

Java 8 introduced the concept of *functional interfaces*, and Java's lambdas are designed
to work well with them. Java's Stream API, for example, makes heavy use of functional interfaces
and if you use it in conjunction with lambdas, the code becomes very concise and easy to read:

```java
IntStream.range(0, 10)
    .filter(x -> x % 2 == 0)
    .map(x -> x * x)
    .forEach(System.out::println)
```

Unfortunately, Clojure's Java interop is not smart enough to automatically cast a Clojure
function to a functional interface, so you'll have to wrap it in a `reify` form yourself:

```clojure
(.. (IntStream/range 0 10)
    (filter (reify IntPredicate (test [_ x] (even? x))))
    (map (reify IntUnaryOperator (applyAsInt [_ x] (* x x))))
    (forEach (reify IntConsumer (accept [_ x] (println x)))))
```

This library mitigates that kind of hassle and helps you make friends with functional interfaces.
It infers a matching method from the argument types at compile time, and automatically wrap
function arguments with `reify` if necessary. Consequently, you can write concise code as below:

```clojure
(require '[power-dot.core :as dot])

(dot/.. (IntStream/range 0 10)
        (filter even?)
        (map #(* % %))
        (forEach println))
```

## Installation

Add the following to your project `:dependencies`:

[![Clojars Project](https://clojars.org/power-dot/latest-version.svg)](https://clojars.org/power-dot)

## Usage

### `dot/.` & `dot/new`

The most fundamental operators of the library are `power-dot.core/.` and `power-dot.core/new`.

They can be used in much the same way as Clojure's counterparts (i.e. the `.` and `new` special operators),
except that if a function is fed for a parameter where the method or constructor expects
a functional interface, they handle the function as if it were an object implementing
that functional interface.

For example, `IntStream#forEach` expects `IntConsumer` (which is a functional interface)
as its argument, and you can pass a Clojure function to the method via
the `power-dot.core/.` macro:

```clojure
(require '[power-dot.core :as dot])
(import '[java.util.stream IntStream])

(dot/. (IntStream/range 0 10) (forEach (fn [n] (println n))))
```

In this case, the `dot/.` form will be expanded to something like the following, and
the function successfully acts like an `IntConsumer`:

```clojure
(let [f (fn [n] (println n))]
  (. (IntStream/range 0 10) (forEach (reify IntConsumer (accept [_ x] (f x))))))
```

`dot/new` works almost the same as `dot/.`, except that it invokes constructors
instead of ordinary methods.

You can pass a function in any form as long as the Clojure compiler statically knows that
it's a function. So, the following forms are all valid, besides the above one with `fn`:

```clojure
(dot/. (IntStream/range 0 10) (forEach #(println %)))

(dot/. (IntStream/range 0 10) (forEach println))

(let [p println]
  (dot/. (IntStream/range 0 10) (forEach p)))
```

**NOTE:** If `dot/.` tries to resolve the method from the provided arguments
ending up in failure, it will fall back to Clojure's ordinary `.` and may emit
a reflection warning. So, it's highly recommended to do `(set! *warn-on-reflection* true)`
to be able to notice method resolution failure.

If a method resolution failed, due to e.g. the method's being overloaded for more than
one functional interface types, you may need to explicitly specify the desired type. 
To do so, just add a type hint of the target type to the argument:

```clojure
;; To coerce `println` to `IntConsumer`, add a type hint ^IntConsumer to `println`
;; (this example code actually doesn't need it, though).

(dot/. (IntStream/range 0 10) (forEach ^IntConsumer println))
```

### `dot/..`

`power-dot` also has its own version of the `..` macro.

Analogous to the `..` macro defined in `clojure.core`, the `dot/..` form is expanded to
a chain of `dot/.` invocations. It's useful to use in the context of
"fluent interface" (or method chaining) with heavy use of functional interfaces:

```clojure
(dot/.. (ArrayList. [1 2 3 4 5])
        (stream)
        (filter odd?)
        (forEach println))
```

This form will be expanded to:

```clojure
(dot/. (dot/. (dot/. (ArrayList. [1 2 3 4 5])
                     (stream)))
              (filter odd?)
       (forEach println))
```

### `dot/as-fn`

If the Clojure compiler cannot infer the type of an argument statically, you may need to
explicitly tell `power-dot` that the argument is a function.
To do so, use `dot/as-fn` for that argument:

```clojure
;; For example, this doesn't work because the Clojure compiler cannot tell the type of
;; the return value from `(partial println "val:")`

(dot/.. (IntStream/range 0 5)
        (forEach (partial println "val:")))
;; Execution error (ClassCastException) at user/eval332 (REPL:1).
;; class clojure.core$partial$fn__5839 cannot be cast to class java.util.function.IntConsumer


;; But, this one does work!!

(dot/. (IntStream/range 0 5)
       (forEach (dot/as-fn (partial println "val:"))))
;; val: 0
;; val: 1
;; val: 2
;; val: 3
;; val: 4
```

`dot/as-fn` is also useful if you want to use a keyword (or any other types other than functions
that implement `clojure.lang.IFn`) as a function:

```clojure
(dot/.. (ArrayList. [{:name "Rich"} {:name "Stu"} {:name "Alex"}])
        (stream)
        (map (dot/as-fn :name))
        (forEach println))
;; Rich
;; Stu
;; Alex
```

### Reader syntax

For those who prefer Clojure's *sugared* interop syntax, that is,

```clojure
;; instance method invocation
(.method object ...)

;; class method invocation
(Klass/method ...)

;; constructor invocation
(Klass. ...)
```

over the simplest `(. obj-or-class method ...)` and `(new Klass ...)` forms,
`power-dot` provides convenient reader syntax: `#dot/$`.

By prefixing the `#dot/$` reader tag to those sugared interop forms, you can get them to work 
exactly the same as the `dot/.` or `dot/new` form. For example:

```clojure
#dot/$(.method obj ...)
;; expands to (dot/. obj method ...)

#dot/$(Klass/method ...)
;; expands to (dot/. Klass method ...)

#dot/$(Klass. ...)
;; expands to (dot/new Klass ...)
```

## License

Copyright © 2020 Shogo Ohta

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
