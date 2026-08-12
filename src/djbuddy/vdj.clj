(ns djbuddy.vdj
  (:require [clj-http.client :as http])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])

  (:import [java.nio.file Paths Path StandardWatchEventKinds WatchService FileSystems]))

(def vdj-url "http://localhost:80") ;; adjust port if needed

(def vdj-history-path
  (str (System/getProperty "user.home") "\\AppData\\Local\\VirtualDJ\\History"))

(def tracklist-file (str vdj-history-path "\\tracklist.txt"))


(defn parse-track-line [line]
  (when-let [[_ artists-str track]
             (re-matches #".*?:.*?:\s*(.+?)\s*-\s*(.+)" line)]
    {:artists (map str/trim (str/split artists-str #"&"))
     :track track
     :source :file}))

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

(defn track-key [track]
  {:artists (mapv str/lower-case (:artists track))
   :track   (some-> (:track track) str/lower-case str/trim)
   :source  (:source track)
   :deck    (:deck track)})

(defn choose-track-source []
  (if (vdj-api-running?)
    :api
    :file))

(defn current-track-from-source [source]
  (case source
    :api  (current-track-from-api)
    :file (current-track)))


(defn watch-tracklist []
  (let [source (choose-track-source)
        last-track-key (atom nil)]

    (println
      (case source
        :api  "Music tracking using VirtualDJ API."
        :file "Music tracking using tracklist.txt."))

    (future
      (loop []
        (when-let [track (current-track-from-source source)]
          (let [current-key (track-key track)]
            (when (not= current-key @last-track-key)
              (reset! last-track-key current-key)
              (println "\nNow playing:")
              (println "Source:" (:source track))
              (when (:deck track)
                (println "Deck:" (:deck track)))
              (println "Artists:" (str/join ", " (:artists track)))
              (println "Track:" (:track track)))))

        (Thread/sleep 1000)
        (recur)))))