(ns djbuddy.dj.library
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def database-path
  (str (System/getProperty "user.home")
       "\\AppData\\Local\\VirtualDJ\\database.xml"))


(defn parse-double-safe [value]
  (when (seq value)
    (try
      (Double/parseDouble value)
      (catch Exception _
        nil))))


(defn parse-long-safe [value]
  (when (seq value)
    (try
      (Long/parseLong value)
      (catch Exception _
        nil))))


(defn child-by-tag [element tag]
  (first
    (filter #(= tag (:tag %))
            (:content element))))

(defn parse-vdj-bpm [value]
  (when-let [beat-duration (parse-double-safe value)]
    (when (pos? beat-duration)
      (let [bpm (/ 60.0 beat-duration)]
        (/ (Math/round (double (* 100.0 bpm)))
           100.0)))))

(defn filename-without-extension [filepath]
  (-> filepath
      io/file
      .getName
      (str/replace #"\.[^.]+$" "")))

(defn remove-track-prefix [filename]
  (str/replace filename
               #"^\d+(?:[_-]\d+)*\s*-\s*"
               ""))

(defn parse-filename-artist-title [filepath]
  (let [filename (-> filepath
                     filename-without-extension
                     remove-track-prefix)

        parts (str/split filename #"\s+-\s+" 2)]

    (when (= 2 (count parts))
      {:artist (str/trim (first parts))
       :track  (-> (second parts)
                   str/trim
                   (str/replace #"_\(" " ("))})))

(defn parse-song [song]
  (let [attrs (:attrs song)
        tags  (child-by-tag song :Tags)
        infos (child-by-tag song :Infos)
        scan  (child-by-tag song :Scan)

        filepath (:FilePath attrs)

        filename-data
        (parse-filename-artist-title filepath)

        artist
        (or (get-in tags [:attrs :Author])
            (:artist filename-data))

        track
        (or (get-in tags [:attrs :Title])
            (:track filename-data))]

    {:filepath filepath

     :file-size
     (parse-long-safe (:FileSize attrs))

     :artist artist

     :track track

     :genre
     (get-in tags [:attrs :Genre])

     :album
     (get-in tags [:attrs :Album])

     :year
     (get-in tags [:attrs :Year])

     :bpm
     (parse-vdj-bpm
       (get-in scan [:attrs :Bpm]))

     :key
     (or
       (get-in scan [:attrs :Key])
       (get-in tags [:attrs :Key]))

     :duration
     (parse-double-safe
       (get-in infos [:attrs :SongLength]))}))


(defn load-library []
  (let [file (io/file database-path)]

    (if-not (.exists file)

      (do
        (println "VirtualDJ database.xml not found:")
        (println database-path)
        [])

      (with-open [input (io/input-stream file)]
        (let [root (xml/parse input)]

          (->> (:content root)
               (filter #(= :Song (:tag %)))
               (mapv parse-song)))))))