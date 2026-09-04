(ns djbuddy.analysis.report
  (:require [clojure.string :as str])
  (:import [java.time Duration Instant]))

(defn average
  [values]
  (when (seq values)
    (/ (reduce + values)
       (count values))))

(defn round-2
  [n]
  (when (some? n)
    (/ (Math/round (* (double n) 100.0))
       100.0)))

(defn percentage
  [part total]
  (if (pos? total)
    (round-2 (* 100.0 (/ part total)))
    0.0))

(defn missing-count
  [total distribution]
  (- total (reduce + (vals distribution))))

(defn bpm-stats
  [tracks]
  (let [ordered (sort-by :order tracks)
        bpms    (vec (keep :bpm ordered))]
    (when (seq bpms)
      {:min   (round-2 (apply min bpms))
       :max   (round-2 (apply max bpms))
       :avg   (round-2 (average bpms))
       :start (round-2 (first bpms))
       :end   (round-2 (last bpms))})))

(defn genre-stats
  [tracks]
  (->> tracks
       (keep :genre)
       frequencies
       (sort-by val >)
       (into (array-map))))

(defn parse-year
  [year]
  (cond
    (number? year)
    (long year)

    (string? year)
    (try
      (Long/parseLong year)
      (catch Exception _
        nil))

    :else
    nil))

(defn year->decade
  [year]
  (when-let [year (parse-year year)]
    (str (* 10 (quot year 10)) "s")))

(defn decade-stats
  [tracks]
  (->> tracks
       (keep :year)
       (keep year->decade)
       frequencies
       (sort-by key)
       (into (array-map))))

(defn dominant-decade
  [tracks]
  (->> tracks
       (keep :year)
       (keep year->decade)
       frequencies
       (sort-by val >)
       ffirst))

(defn track-artists
  [track]
  (cond
    (seq (:artists track))
    (:artists track)

    (not (str/blank? (:artist track)))
    [(:artist track)]

    :else
    []))

(defn artist-stats
  [tracks]
  (let [artists (mapcat track-artists tracks)
        counts  (frequencies artists)]
    {:unique (count counts)
     :most-played
     (when (seq counts)
       (apply max-key val counts))}))

(defn set-duration-seconds
  [{:keys [source started-at ended-at]}]
  (when started-at
    (let [effective-end
          (cond
            ended-at
            ended-at

            ;; Live report is still running
            (= source :vdj-live)
            (Instant/now)

            :else
            nil)]

      (when effective-end
        (.getSeconds
          (Duration/between
            started-at
            effective-end))))))

(defn format-duration
  [seconds]
  (if (nil? seconds)
    "Unknown"

    (let [hours
          (quot seconds 3600)
          minutes
          (quot (mod seconds 3600) 60)
          seconds-left
          (mod seconds 60)]

      (cond
        (pos? hours)
        (format "%dh %02dmin"
                hours
                minutes)

        (pos? minutes)
        (format "%dmin %02ds"
                minutes
                seconds-left)

        :else
        (format "%ds"
                seconds-left)))))

(defn resolved-track?
  [track]
  (not= false (:resolved? track)))

(defn resolution-stats
  [tracks]
  (let [resolved (count (filter resolved-track? tracks))]
    {:resolved resolved
     :unresolved (- (count tracks) resolved)}))

(def section-names
  [:opening
   :early-middle
   :late-middle
   :closing])

(def section-labels
  {:opening      "OPENING"
   :early-middle "EARLY MIDDLE"
   :late-middle  "LATE MIDDLE"
   :closing      "CLOSING"})

(defn split-into-fourths
  [tracks]
  (let [tracks     (vec (sort-by :order tracks))
        n          (count tracks)
        base       (quot n 4)
        remainder  (mod n 4)
        sizes      (mapv #(if (< % remainder)
                            (inc base)
                            base)
                         (range 4))]
    (loop [remaining tracks
           remaining-sizes sizes
           result []]
      (if-let [size (first remaining-sizes)]
        (recur
          (vec (drop size remaining))
          (rest remaining-sizes)
          (conj result
                (vec (take size remaining))))
        result))))

(defn section-stats
  [position tracks]
  (let [bpms   (vec (keep :bpm tracks))
        orders (vec (keep :order tracks))]
    {:position position

     :track-range
     (when (seq orders)
       [(first orders)
        (last orders)])

     :track-count
     (count tracks)

     :bpm
     (when (seq bpms)
       {:min (round-2 (apply min bpms))
        :max (round-2 (apply max bpms))
        :avg (round-2 (average bpms))})

     :dominant-genres
     (->> tracks
          (keep :genre)
          frequencies
          (sort-by val >)
          (take 2)
          (mapv key))

     :dominant-decade
     (dominant-decade tracks)}))

(defn section-analysis
  [tracks]
  (mapv section-stats
        section-names
        (split-into-fourths tracks)))


(defn performance-report
  [set-data]
  (let [tracks
        (vec
          (sort-by
            :order
            (:tracks set-data)))
        duration-seconds
        (set-duration-seconds set-data)]
    {:track-count      (count tracks)
     :duration-seconds duration-seconds
     :duration-minutes (when duration-seconds
                         (round-2
                           (/ duration-seconds 60.0)))
     :bpm              (bpm-stats tracks)
     :genres           (genre-stats tracks)
     :decades          (decade-stats tracks)
     :artists          (artist-stats tracks)
     :sections         (section-analysis tracks)}))

(defn print-performance-report
  [report]
  (println)
  (println "========================================")
  (println "          DJ PERFORMANCE REPORT")
  (println "========================================")

  (println)
  (println "OVERVIEW")
  (println "----------------------------------------")
  (println "Tracks:    " (:track-count report))
  (println "Duration:  " (format-duration
                           (:duration-seconds report)))

  (let [{:keys [min max avg start end]} (:bpm report)]
    (println)
    (println "BPM")
    (println "----------------------------------------")
    (println "Average:   " avg)
    (println "Range:     " min "-" max)
    (println "Start:     " start)
    (println "End:       " end))

  (println)
  (println "GENRES")
  (println "----------------------------------------")
  (doseq [[genre count] (:genres report)]
    (println
      (format "%-18s %3d   %6.2f%%"
              genre
              count
              (percentage count (:track-count report)))))
  (let [missing (missing-count (:track-count report)
                               (:genres report))]
    (when (pos? missing)
      (println
        (format "%-18s %3d   %6.2f%%"
                "Unknown"
                missing
                (percentage missing (:track-count report))))))

  (println)
  (println "DECADES")
  (println "----------------------------------------")
  (doseq [[decade count] (:decades report)]
    (println
      (format "%-18s %3d   %6.2f%%"
              decade
              count
              (percentage count (:track-count report)))))
  (let [missing (missing-count (:track-count report)
                               (:decades report))]
    (when (pos? missing)
      (println
        (format "%-18s %3d   %6.2f%%"
                "Unknown"
                missing
                (percentage missing (:track-count report))))))

  (let [{:keys [unique most-played]} (:artists report)]
    (println)
    (println "ARTISTS")
    (println "----------------------------------------")
    (println "Unique artists:" unique)
    (when most-played
      (println "Most played:   "
               (first most-played)
               "(" (second most-played) "tracks )")))

  (println)
  (println "SET SECTIONS")
  (println "========================================")

  (doseq [section (:sections report)]
    (let [{:keys [position track-range track-count bpm dominant-genres]}
          section]

      (println)
      (println (get section-labels position))
      (println "----------------------------------------")

      (println "Tracks:     "
               (first track-range)
               "-"
               (second track-range)
               "(" track-count "tracks )")

      (when bpm
        (println "BPM avg:    " (:avg bpm))
        (println "BPM range:  " (:min bpm) "-" (:max bpm)))

      (println "Genres:     "
               (clojure.string/join ", " dominant-genres))

      (println "Dominant decade:     "
               (or (:dominant-decade section)
                   "Unknown"))))

  (println)
  (println "========================================")
  nil)