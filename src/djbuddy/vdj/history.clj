(ns djbuddy.vdj.history
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant LocalTime]
           [java.time.format DateTimeFormatterBuilder]
           [java.time LocalDate LocalDateTime LocalTime]
           [java.time.format DateTimeFormatterBuilder]
           [java.time
            LocalDate
            LocalTime
            LocalDateTime
            ZoneId]))

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

(defn read-history [file date]
  (let [played-date (LocalDate/parse date)]

    (with-open [reader (io/reader file)]
      (->> (line-seq reader)

           (keep parse-history-line)

           (map
             (fn [track]
               (assoc track
                 :played-date played-date
                 :played-date-time
                 (LocalDateTime/of
                   played-date
                   (:played-time track)))))

           doall
           vec))))

(defn crosses-midnight? [start-time end-time]
  (.isAfter
    (parse-time start-time)
    (parse-time end-time)))

(defn set-range [date start-time end-time]
  (let [start-date (LocalDate/parse date)

        start
        (LocalDateTime/of
          start-date
          (parse-time start-time))

        end-date
        (if (crosses-midnight? start-time end-time)
          (.plusDays start-date 1)
          start-date)

        end
        (LocalDateTime/of
          end-date
          (parse-time end-time))]

    {:start start
     :end end
     :end-date end-date}))

(defn datetime-between? [datetime start end]
  (and
    (not (.isBefore datetime start))
    (not (.isAfter datetime end))))

(defn tracks-between-datetimes [tracks start end]
  (->> tracks
       (filter
         #(datetime-between?
            (:played-date-time %)
            start
            end))
       vec))

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

(defn set-time-range
  [date-str start-str end-str]
  (let [date       (LocalDate/parse date-str)
        start-time (LocalTime/parse start-str)
        end-time   (LocalTime/parse end-str)

        start-local
        (LocalDateTime/of date start-time)

        end-date
        (if (.isBefore end-time start-time)
          (.plusDays date 1)
          date)

        end-local
        (LocalDateTime/of end-date end-time)

        zone
        (ZoneId/systemDefault)]

    {:started-at
     (.toInstant (.atZone start-local zone))

     :ended-at
     (.toInstant (.atZone end-local zone))}))

(defn add-track-order [tracks]
  (mapv
    (fn [index track]
      (assoc track :order (inc index)))
    (range)
    tracks))

(defn history-file-for-date [date]
  (let [date     (LocalDate/parse date)
        filename (str date ".m3u")
        year     (str (.getYear date))
        month    (format "%02d" (.getMonthValue date))

        direct
        (io/file history-dir filename)

        archived
        (io/file history-dir year month filename)]

    (cond
      (.exists direct)
      direct

      (.exists archived)
      archived

      :else
      nil)))

(defn track-instant
  [track]
  (or
    (:played-at track)

    (when-let [played-date-time
               (:played-date-time track)]

      (.toInstant
        (.atZone
          played-date-time
          (ZoneId/systemDefault))))))

(defn estimated-track-end
  [track]
  (let [started-at
        (track-instant track)

        duration
        (:duration-seconds track)]

    (when (and started-at duration)
      (.plusMillis
        started-at
        (long
          (Math/round
            (* (double duration)
               1000.0)))))))

(defn track-start
  [track]
  (:played-at track))

(defn track-end-from-duration
  [track]
  (when (and (:played-at track)
             (:duration-seconds track))
    (.plusMillis
      ^Instant (:played-at track)
      (long
        (* 1000
           (:duration-seconds track))))))

(defn next-track-after
  [all-tracks last-selected-track]
  (let [last-start (:played-at last-selected-track)]
    (first
      (filter
        #(and (:played-at %)
              last-start
              (.isAfter
                ^Instant (:played-at %)
                ^Instant last-start))
        all-tracks))))

(defn import-set
  [date start-time end-time]
  (let [{:keys [start end end-date]}
        (set-range date start-time end-time)

        start-date
        (LocalDate/parse date)

        dates
        (if (= start-date end-date)
          [start-date]
          [start-date end-date])

        ;; Read all tracks from all relevant history files.
        all-tracks
        (->> dates
             (mapcat
               (fn [current-date]
                 (let [date-string
                       (str current-date)]

                   (if-let [file
                            (history-file-for-date
                              date-string)]

                     (read-history
                       file
                       date-string)

                     (do
                       (println
                         "VirtualDJ history file not found for:"
                         date-string)
                       [])))))
             (sort-by :played-at)
             vec)

        filtered-tracks
        (-> all-tracks
            (tracks-between-datetimes start end)
            add-track-order)

        first-track
        (first filtered-tracks)

        last-track
        (last filtered-tracks)

        next-track
        (when last-track
          (next-track-after
            all-tracks
            last-track))

        started-at
        (when first-track
          (track-start first-track))

        last-track-natural-end
        (when last-track
          (track-end-from-duration last-track))

        next-track-start
        (when next-track
          (track-start next-track))

        ended-at
        (cond
          (and next-track-start
               last-track-natural-end)
          (if (.isBefore
                ^Instant next-track-start
                ^Instant last-track-natural-end)

            next-track-start
            last-track-natural-end)

          ;; Only the next track is known
          next-track-start
          next-track-start

          ;; Only track duration is known
          last-track-natural-end
          last-track-natural-end

          :else
          nil)]

    {:source :vdj-history

     :started-at started-at
     :ended-at ended-at

     :end-time-source
     (cond
       (and next-track-start
            last-track-natural-end
            (.isBefore
              ^Instant next-track-start
              ^Instant last-track-natural-end))
       :next-track

       last-track-natural-end
       :last-track-duration

       next-track-start
       :next-track

       :else
       nil)

     :tracks filtered-tracks}))


(defn track-by-order [tracks order]
  (first
    (filter #(= order (:order %)) tracks)))