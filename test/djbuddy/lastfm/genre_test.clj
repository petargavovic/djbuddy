(ns djbuddy.lastfm.genre-test
  (:require [midje.sweet :refer :all]
            [djbuddy.lastfm.genre :as genre]
            [djbuddy.lastfm.client :as client]))


(facts "genre-for-track"

       (fact "returns a valid genre from track tags"
             (with-redefs [client/track-tags
                           (fn [_artist _track]
                             [{:name "seen live"}
                              {:name "nu metal"}
                              {:name "favorites"}])

                           client/artist-tags
                           (fn [_artist]
                             (throw
                               (Exception.
                                 "Artist fallback should not be called")))]

               (genre/genre-for-track "Linkin Park" "Numb"))

             => "nu metal")


       (fact "ignores non-genre tags and falls back to artist tags"
             (with-redefs [client/track-tags
                           (fn [_artist _track]
                             [{:name "seen live"}
                              {:name "favorites"}
                              {:name "male vocalists"}])

                           client/artist-tags
                           (fn [_artist]
                             [{:name "house"}
                              {:name "electronic"}])]

               (genre/genre-for-track "David Guetta" "Memories"))

             => "house")


       (fact "returns nil when neither track nor artist has a usable genre"
             (with-redefs [client/track-tags
                           (fn [_artist _track]
                             [{:name "favorites"}
                              {:name "seen live"}])

                           client/artist-tags
                           (fn [_artist]
                             [{:name "favorites"}])]

               (genre/genre-for-track "Unknown Artist" "Unknown Track"))

             => nil))