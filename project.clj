(defproject djbuddy "0.1.0-SNAPSHOT"
  :description "Stats for your DJ sets!"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [clj-http "3.12.3"]
                 [cheshire "5.11.0"]
                 [org.clojure/data.xml "0.2.0-alpha9"]]
  :source-paths ["src"]
  :plugins [[lein-cljsbuild "1.1.1"]]

  :profiles {:dev {:dependencies [[midje "1.10.6"]]
                   :plugins [[lein-midje "3.2.2"]]}})