(ns djbuddy.vdj.history
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant LocalTime]
           [java.time.format DateTimeFormatterBuilder]))

(def history-dir
  (io/file
    (System/getProperty "user.home")
    "AppData"
    "Local"
    "VirtualDJ"
    "History"))

(def time-formatter
  (-> (DateTimeFormatterBuilder.)
      (.appendPattern "H:mm")
      (.optionalStart)
      (.appendPattern ":ss")
      (.optionalEnd)
      (.toFormatter)))

(defn parse-time [time]
  (LocalTime/parse time time-formatter))

(defn tag-value [line tag]
  (second
    (re-find
      (re-pattern
        (str "<" tag ">(.*?)</" tag ">"))
      line)))

(defn parse-long-safe [value]
  (when value
    (try
      (Long/parseLong value)
      (catch Exception _
        nil))))

(defn parse-double-safe [value]
  (when value
    (try
      (Double/parseDouble value)
      (catch Exception _
        nil))))

(defn parse-history-line [line]
  (let [time          (tag-value line "time")
        last-playtime (parse-long-safe (tag-value line "lastplaytime"))
        artist        (tag-value line "artist")
        title         (tag-value line "title")
        remix         (tag-value line "remix")
        song-length   (parse-double-safe (tag-value line "songlength"))]

    (when (and time artist title)
      {:artists         [artist]
       :track           title
       :remix           remix
       :played-time     (parse-time time)
       :played-at       (when last-playtime
                          (Instant/ofEpochSecond last-playtime))
       :duration-seconds song-length
       :source          :vdj-history})))

(defn read-history [file]
  (with-open [reader (io/reader file)]
    (->> (line-seq reader)
         (keep parse-history-line)
         doall)))

(defn time-between? [time start end]
  (if (<= (compare start end) 0)

    ;; Normal:
    ;; 20:00 -> 23:00
    (and (>= (compare time start) 0)
         (<= (compare time end) 0))

    ;; Crosses midnight:
    ;; 23:00 -> 03:00
    (or (>= (compare time start) 0)
        (<= (compare time end) 0))))

(defn tracks-between [tracks start-time end-time]
  (let [start (parse-time start-time)
        end   (parse-time end-time)]
    (filterv
      #(time-between?
         (:played-time %)
         start
         end)
      tracks)))

(defn add-track-order [tracks]
  (mapv
    (fn [index track]
      (assoc track :order (inc index)))
    (range)
    tracks))

(defn import-set [history-file start-time end-time]
  (-> (read-history history-file)
      (tracks-between start-time end-time)
      (add-track-order)))

(defn track-by-order [tracks order]
  (first
    (filter #(= order (:order %)) tracks)))