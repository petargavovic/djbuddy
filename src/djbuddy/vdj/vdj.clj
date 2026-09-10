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

(defn current-live-tracks []
  (if (vdj-api-running?)

    ;; Network Control available
    {:source :api
     :tracks (vec (audible-tracks))}

    ;; Network Control unavailable -> tracklist.txt
    {:source :file
     :tracks (if-let [track (current-track)]
               [track]
               [])}))

(defn start-live-watch! [on-track]
  (let [running?     (atom true)
        last-by-slot (atom {})
        track-order  (atom 0)
        last-source  (atom nil)]

    (let [worker
          (future
            (while @running?

              (let [{:keys [source tracks]}
                    (current-live-tracks)]

                (when (not= source @last-source)
                  (reset! last-source source)

                  (println
                    (case source
                      :api
                      "Music tracking using VirtualDJ API."

                      :file
                      "VirtualDJ API unavailable. Using tracklist.txt.")))

                (doseq [track tracks]
                  (let [;; API tracks use their deck.
                        ;; File tracks have no deck.
                        slot (or (:deck track) :file)

                        key [(mapv str/lower-case
                                   (:artists track))
                             (some-> (:track track)
                                     str/lower-case
                                     str/trim)]]

                    (when (not= key
                                (get @last-by-slot slot))

                      (swap! last-by-slot assoc slot key)

                      (let [numbered-track
                            (assoc track
                              :order
                              (swap! track-order inc))]

                        (try
                          (on-track numbered-track)

                          (catch Exception e
                            (println
                              "Track processing failed:"
                              (.getMessage e)))))))))

              (Thread/sleep 1000)))]

      {:future worker

       :stop!
       (fn []
         (reset! running? false)
         (future-cancel worker))})))