(ns djbuddy.live.session
  (:require [djbuddy.analysis.report :as report]
            [clojure.pprint :refer [pprint]]
            [djbuddy.vdj.vdj :as vdj]
            [djbuddy.dj.feature-resolver :as resolver]))

(def live-set* (atom []))
(def watcher* (atom nil))

(defn add-track!
  [track]
  (swap! live-set* conj track))

(defn current-set
  []
  @live-set*)

(defn reset-set!
  []
  (reset! live-set* []))

(defn print-report!
  []
  (report/print-performance-report
    (report/performance-report @live-set*)))

(defn stop-live!
  []
  (when-let [watcher @watcher*]
    ((:stop! watcher))
    (reset! watcher* nil)
    (println "Live session stopped.")))

(defn start-live!
  []
  (stop-live!)
  (reset-set!)

  (reset!
    watcher*
    (vdj/start-live-watch!
      (fn [track]
        (let [resolved-track
              (resolver/resolve-live-track track)]

          (add-track! resolved-track)

          (println)
          (println "Resolved live track:")
          (pprint resolved-track)))))

  (println "Live session started."))