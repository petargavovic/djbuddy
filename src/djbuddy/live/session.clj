(ns djbuddy.live.session
  (:require [djbuddy.analysis.report :as report]
            [clojure.pprint :refer [pprint]]
            [djbuddy.vdj.vdj :as vdj]
            [djbuddy.dj.feature-resolver :as resolver]))

(def live-set*
  (atom
    {:source :vdj-live
     :started-at nil
     :ended-at nil
     :tracks []}))

(def watcher* (atom nil))

(defn add-track!
  [track]
  (swap!
    live-set*
    (fn [set-data]
      (-> set-data

          (cond->
            (nil? (:started-at set-data))
            (assoc
              :started-at
              (or (:played-at track)
                  (java.time.Instant/now))))

          ;; Store the track
          (update :tracks conj track)))))

(defn current-set
  []
  @live-set*)

(defn reset-set!
  []
  (reset!
    live-set*
    {:source :vdj-live
     :started-at nil
     :ended-at nil
     :tracks []}))

(defn print-report!
  []
  (report/print-performance-report
    (report/performance-report @live-set*)))

(defn stop-live!
  []
  (when-let [watcher @watcher*]

    ((:stop! watcher))

    (reset! watcher* nil)

    ;; Freeze the final set duration
    (when (:started-at @live-set*)
      (swap!
        live-set*
        assoc
        :ended-at
        (java.time.Instant/now)))

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