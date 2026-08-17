(ns djbuddy.lastfm.year
  (:require [djbuddy.lastfm.client :as lastfm]
            [clojure.string :as str]))

(defn year-tag? [value]
  (and (string? value)
       (boolean
         (re-matches #"(18|19|20)\d{2}" value))))

(defn year-from-tags [album-info]
  (->> (get-in album-info [:tags :tag])
       (map :name)
       (filter year-tag?)
       first))

(defn year-from-release-date [album-info]
  (when-let [release-date (:releasedate album-info)]
    (re-find #"(18|19|20)\d{2}" release-date)))

(defn year-from-release-text [text]
  (when (seq text)
    (second
      (re-find
        #"(?i)\breleased\b.{0,120}?\b((?:18|19|20)\d{2})\b"
        text))))

(defn year-from-wiki [album-info]
  (or
    (year-from-release-text
      (get-in album-info [:wiki :summary]))

    (year-from-release-text
      (get-in album-info [:wiki :content]))))

(defn year-from-album-info [album-info]
  (or
    (year-from-release-date album-info)
    (year-from-tags album-info)
    (year-from-wiki album-info)))

(defn year-for-track [track]
  (when (and (:artist track)
             (:album track))

    (when-let [album-info
               (lastfm/album-info
                 (:artist track)
                 (:album track))]

      (year-from-album-info album-info))))