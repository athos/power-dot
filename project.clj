(defproject power-dot "0.1.0"
  :description "Clojure library for enhanced Java interop that helps you make friends with Java's functional interfaces"
  :url "https://github.com/athos/power-dot"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[type-infer "0.1.1"]]
  :profiles {:provided
             {:dependencies [[org.clojure/clojure "1.10.2"]]}}
  :repl-options {:init-ns user})
