# power-dot

A PoC library for Clojure that helps you make friends with Java's functional interfaces

## Example

```clojure
(require '[power-dot.core :as dot])
(import '[java.util.stream IntStream])

(dot/. (IntStream/range 0 10) (forEach (fn [x] (println x))))
;; 0
;; 1
;; 2
;; 3
;; 4
;; 5
;; 6
;; 7
;; 8
;; 9
;=> nil

(dot/.. (IntStream/range 0 10)
        (filter #(= (mod % 3) 0))
        (forEach println))
;; 0
;; 3
;; 6
;; 9
;=> nil

(letfn [(fib [n]
          (if (<= n 1)
            n
            (+ (fib (- n 1)) (fib (- n 2)))))]
  (dot/.. (IntStream/range 0 10)
          (map fib)
          (forEach println)))
;; 0
;; 1
;; 1
;; 2
;; 3
;; 5
;; 8
;; 13
;; 21
;; 34
;=> nil
```

## Usage

FIXME

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
