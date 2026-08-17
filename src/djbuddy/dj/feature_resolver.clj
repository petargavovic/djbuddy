(ns djbuddy.dj.feature-resolver
  (:require [clojure.string :as str]
            [djbuddy.dj.library :as library]
            [djbuddy.lastfm.genre :as genre]
            [djbuddy.lastfm.year :as year]))


;; --------------------------------------------------
;; Normalization
;; --------------------------------------------------

(defn normalize-text [value]
  (some-> value
          str
          str/trim
          str/lower-case
          (str/replace #"\s+" " ")))


(defn track-artist [track]
  (or (:artist track)

      ;; Support the format used by the live VDJ reader:
      ;; {:artists ["Linkin Park"]}
      (when-let [artists (seq (:artists track))]
        (str/join " & " artists))))


(defn track-title [track]
  (:track track))


(defn value-present? [value]
  (and (some? value)
       (not (and (string? value)
                 (str/blank? value)))))


(defn primary-artist [track]
  (some-> (track-artist track)
          (str/split #"\s*(?:&|,)\s*" 2)
          first
          str/trim))


;; --------------------------------------------------
;; VirtualDJ database matching
;; --------------------------------------------------

(defn exact-filepath-match
  [track library-tracks]

  (when-let [filepath (:filepath track)]

    (some
      #(when (= filepath (:filepath %))
         %)
      library-tracks)))


(defn normalized-artist-title-match
  [track library-tracks]

  (let [artist (normalize-text (track-artist track))
        title  (normalize-text (track-title track))]

    (when (and artist title)

      (some
        (fn [library-track]

          (when
            (and
              (= artist
                 (normalize-text
                   (track-artist library-track)))

              (= title
                 (normalize-text
                   (track-title library-track))))

            library-track))

        library-tracks))))


(defn find-vdj-track
  [track library-tracks]

  ;; Resolution priority:
  ;;
  ;; 1. filepath
  ;; 2. artist/title

  (if-let [match
           (exact-filepath-match
             track
             library-tracks)]

    {:track match
     :method :filepath}

    (when-let [match
               (normalized-artist-title-match
                 track
                 library-tracks)]

      {:track match
       :method :artist-title})))


;; --------------------------------------------------
;; Enrichment
;; --------------------------------------------------

(defn enrich-track
  [original-track resolved-track feature-source resolution-method]

  ;; resolved-track is merged first so information from
  ;; the actual played event remains authoritative.
  ;;
  ;; For example :order, :played-at, :deck and :source
  ;; must not be overwritten.

  (-> (merge resolved-track
             original-track)

      (assoc
        :resolved? true
        :feature-source feature-source
        :resolution-method resolution-method)))


(defn unresolved-track
  [track reason]

  (assoc track
    :resolved? false
    :feature-source :unresolved
    :resolution-method nil
    :unresolved-reason reason))

(defn enrich-genre [track]
  (if-let [existing-genre
           (genre/valid-genre (:genre track))]

    (assoc track
      :genre existing-genre
      :genre-source :virtualdj)

    (if-let [resolved-genre
             (genre/genre-for-track
               (primary-artist track)
               (:track track))]

      (assoc track
        :genre resolved-genre
        :genre-source :lastfm)

      (assoc track
        :genre nil))))

(defn enrich-year [track]
  (if (value-present? (:year track))

    track

    (let [lookup-track
          (assoc track
            :artist (primary-artist track))]

      (if-let [resolved-year
               (year/year-for-track lookup-track)]

        (assoc track
          :year resolved-year
          :year-source :lastfm)

        track))))

(defn enrich-lastfm [track]
  (-> track
      enrich-genre
      enrich-year))


;; --------------------------------------------------
;; VirtualDJ resolver
;; --------------------------------------------------

(defn resolve-from-vdj
  [track library-tracks]

  (when-let [{resolved-track :track
              method         :method}

             (find-vdj-track
               track
               library-tracks)]

    (enrich-track
      track
      resolved-track
      :virtualdj
      method)))


;; --------------------------------------------------
;; ReccoBeats fallback
;; --------------------------------------------------

(defn resolve-reccobeats-by-id
  [track {:keys [by-spotify-id
                 by-isrc]}]

  (cond

    (and (:spotify-id track)
         by-spotify-id)

    (when-let [resolved
               (by-spotify-id
                 (:spotify-id track))]

      (enrich-track
        track
        resolved
        :reccobeats
        :spotify-id))


    (and (:isrc track)
         by-isrc)

    (when-let [resolved
               (by-isrc
                 (:isrc track))]

      (enrich-track
        track
        resolved
        :reccobeats
        :isrc))


    :else
    nil))


(defn resolve-reccobeats-by-catalog
  [track {:keys [by-artist-title]}]

  (when by-artist-title

    (when-let [resolved
               (by-artist-title
                 (track-artist track)
                 (track-title track))]

      (enrich-track
        track
        resolved
        :reccobeats
        :artist-title))))


;; --------------------------------------------------
;; HISTORY MODE
;; --------------------------------------------------


(defn resolve-history-track
  [track library-tracks reccobeats]

  (let [resolved
        (or
          (resolve-from-vdj track library-tracks)

          (resolve-reccobeats-by-id
            track
            reccobeats)

          (resolve-reccobeats-by-catalog
            track
            reccobeats)

          (unresolved-track
            track
            :not-found))]

    (enrich-lastfm resolved)))

(defn resolve-history-set [tracks]
  (let [library-tracks (library/load-library)]
    (mapv
      #(resolve-history-track % library-tracks {})
      tracks)))

;; --------------------------------------------------
;; LIVE MODE
;; --------------------------------------------------

(defn resolve-live-track
  ([track]
   (resolve-live-track
     track
     {:poll-ms 1000
      :timeout-ms 30000}))

  ([track {:keys [poll-ms timeout-ms]
           :or   {poll-ms 1000
                  timeout-ms 30000}}]

   (let [deadline
         (+ (System/currentTimeMillis)
            timeout-ms)]

     (loop []

       ;; Reload database.xml because VirtualDJ may have
       ;; written the newly analysed track since the
       ;; previous attempt.
       (let [library-tracks
             (library/load-library)]

         (if-let [resolved
                  (resolve-from-vdj
                    track
                    library-tracks)]

           (enrich-lastfm resolved)

           (if (< (System/currentTimeMillis)
                  deadline)

             (do
               (Thread/sleep poll-ms)
               (recur))

             (-> (unresolved-track
                   track
                   :not-written-to-vdj-database)
                 enrich-lastfm))))))))