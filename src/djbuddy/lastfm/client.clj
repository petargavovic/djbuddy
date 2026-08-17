(ns djbuddy.lastfm.client
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def lastfm-url
  "https://ws.audioscrobbler.com/2.0/")

(defn get-lastfm-api-key []
  (try
    (require 'djbuddy.secrets)

    (when-let [key-var
               (ns-resolve 'djbuddy.secrets
                           'lastfm-api-key)]
      (some-> (var-get key-var)
              str
              str/trim
              not-empty))

    (catch Exception _
      nil)))

(defn artist-tags [artist]
  (when-let [api-key (get-lastfm-api-key)]
    (try
      (let [response
            (http/get
              lastfm-url
              {:query-params
               {"method" "artist.getTopTags"
                "artist" artist
                "api_key" api-key
                "autocorrect" "1"
                "format" "json"}

               :as :text
               :throw-exceptions false})

            body
            (json/parse-string
              (:body response)
              true)]

        (get-in body [:toptags :tag]))

      (catch Exception e
        (println "Last.fm artist lookup failed:"
                 (.getMessage e))
        nil))))

(defn album-info [artist album]
  (when-let [api-key (get-lastfm-api-key)]
    (try
      (let [response
            (http/get
              lastfm-url
              {:query-params
               {"method" "album.getInfo"
                "artist" artist
                "album" album
                "api_key" api-key
                "autocorrect" "1"
                "format" "json"}

               :as :text
               :throw-exceptions false})

            body
            (json/parse-string
              (:body response)
              true)]

        (:album body))

      (catch Exception e
        (println "Last.fm album lookup failed:"
                 (.getMessage e))
        nil))))

(defn track-tags [artist track]
  (when-let [api-key (get-lastfm-api-key)]
    (try
      (let [response
            (http/get
              lastfm-url
              {:query-params
               {"method" "track.getInfo"
                "artist" artist
                "track" track
                "api_key" api-key
                "autocorrect" "1"
                "format" "json"}

               :as :text
               :throw-exceptions false})

            body
            (json/parse-string
              (:body response)
              true)]

        (get-in body [:track :toptags :tag]))

      (catch Exception e
        (println "Last.fm track lookup failed:"
                 (.getMessage e))
        nil))))