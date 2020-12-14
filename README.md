# power-dot
[![Clojars Project](https://img.shields.io/clojars/v/power-dot.svg)](https://clojars.org/power-dot)

A PoC library for Clojure that helps you make friends with Java's functional interfaces 😍

## Rationale

Java 8 introduced the concept of *functional interfaces*, and Java's lambdas are designed
to work well with them. Java's Stream API, for example, makes heavy use of functional interfaces
and if you use lambdas in conjunction with it, the code becomes very concise and easy to read:

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
as its argument, and you can pass a function to the method via the `power-dot.core/.` macro:

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

If a method resolution failed, due to eg. the method's being overloaded for more than
one functional interface types, you may need to explicitly specify the desired type. 
To do so, just add a type hint of the target type to the argument:

```clojure
;; To coerce `println` to `IntConsumer`, add a type hint ^IntConsumer to `println`
;; (though, this example code doesn't need it, actually).

(dot/. (IntStream/range 0 10) (forEach ^IntConsumer println))
```

### `dot/..`

`power-dot` also has its own version of the `..` macro.

Analogous to the `..` macro in `clojure.core`, the `power-dot.core/..` form is expanded to
a chain of `power-dot.core/.` invocations. It's useful to use in the context of
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
