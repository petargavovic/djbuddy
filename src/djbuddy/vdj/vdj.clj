(ns djbuddy.vdj.vdj
  (:require [clj-http.client :as http])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def vdj-url "http://localhost:80")

(def vdj-history-path
  (str (System/getProperty "user.home") "\\AppData\\Local\\VirtualDJ\\History"))

(def tracklist-file (str vdj-history-path "\\tracklist.txt"))


(defn parse-track-line [line]
  (when-let [[_ artists-str track]
             (re-matches #".*?:.*?:\s*(.+?)\s+-\s+(.+)" line)]
    {:artists (mapv str/trim (str/split artists-str #"&"))
     :track   (str/trim track)
     :source  :file}))

(defn current-track []
  (when (.exists (io/file tracklist-file))
    (when-let [line (last (line-seq (io/reader tracklist-file)))]
      (parse-track-line line))))



(defn vdj-api-running? []
  (try
    (= 200 (:status (http/get vdj-url {:throw-exceptions false})))
    (catch Exception _
      false)))

(defn vdj-query [script]
  (try
    (let [response (http/get (str vdj-url "/query")
                             {:query-params {"script" script}
                              :as :text
                              :throw-exceptions false})]
      (when (= 200 (:status response))
        (some-> (:body response) str/trim)))
    (catch Exception e
      (println "VDJ query failed:" (.getMessage e))
      nil)))

(defn truthy-vdj? [value]
  (contains? #{"true" "1" "yes" "on"} (str/lower-case (str/trim (str value)))))

(defn deck-audible? [deck]
  (truthy-vdj? (vdj-query (str "deck " deck " is_audible"))))

(defn current-playing-deck []
  (cond
    (deck-audible? 1) 1
    (deck-audible? 2) 2
    :else nil))

(defn current-track-from-api []
  (when-let [deck (current-playing-deck)]
    (let [artist (vdj-query (str "deck " deck " get_artist"))
          title  (vdj-query (str "deck " deck " get_title"))]
      (when (and (seq artist) (seq title))
        {:artists [artist]
         :track title
         :deck deck
         :source :api}))))


(defn current-track-auto []
  (if (vdj-api-running?)
    (or (current-track-from-api)
        (current-track))
    (current-track)))

(defn deck-track [deck]
  (when (deck-audible? deck)
    (let [artist (vdj-query (str "deck " deck " get_artist"))
          title  (vdj-query (str "deck " deck " get_title"))]

      (when (and (seq artist)
                 (seq title))
        {:artists [artist]
         :track title
         :deck deck
         :played-at (java.time.Instant/now)
         :source :vdj-live}))))

(defn audible-tracks []
  (keep deck-track [1 2]))

(defn start-live-watch! [on-track]
  (let [running?     (atom true)
        last-by-deck (atom {})
        track-order  (atom 0)]

    (let [worker
          (future
            (while @running?

              (doseq [track (audible-tracks)]
                (let [deck (:deck track)
                      key  [(:artists track)
                            (:track track)]]

                  (when (not= key
                              (get @last-by-deck deck))

                    (swap! last-by-deck assoc deck key)

                    ;; array-map to arrange order of parameters
                    (let [numbered-track
                          (array-map
                            :order   (swap! track-order inc)
                            :artists (:artists track)
                            :track   (:track track)
                            :deck    (:deck track)
                            :played-at (:played-at track)
                            :source  (:source track))]

                      (try
                        (on-track numbered-track)

                        (catch Exception e
                          (println
                            "Track processing failed:"
                            (.getMessage e))))))))

              (Thread/sleep 1000)))]

      {:future worker

       :stop!
       (fn []
         (reset! running? false)
         (future-cancel worker))})))