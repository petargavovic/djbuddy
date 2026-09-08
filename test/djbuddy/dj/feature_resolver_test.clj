(ns djbuddy.dj.feature-resolver-test
  (:require [midje.sweet :refer :all]
            [djbuddy.dj.feature-resolver :as resolver]))

(def vdj-library
  [{:filepath "C:\\Music\\Linkin Park - Numb.mp3"
    :artist "Linkin Park"
    :track "Numb"
    :bpm 109.9
    :key "11A"
    :genre "Nu Metal"
    :duration 187.0}

   {:filepath "C:\\Music\\System Of A Down - Toxicity.mp3"
    :artist "System Of A Down"
    :track "Toxicity"
    :bpm 117.0
    :key "Cm"
    :genre "Alternative Metal"
    :duration 218.0}

   {:filepath "C:\\Other\\Linkin Park - Numb Remix.mp3"
    :artist "Linkin Park"
    :track "Numb"
    :bpm 128.0
    :key "10A"
    :genre "Electronic"
    :duration 200.0}])


(facts "exact-filepath-match"

       (fact "returns a track when the filepath matches exactly"
             (resolver/exact-filepath-match
               {:filepath "C:\\Music\\Linkin Park - Numb.mp3"}
               vdj-library)

             => (contains
                  {:artist "Linkin Park"
                   :track "Numb"
                   :bpm 109.9
                   :key "11A"}))


       (fact "returns nil when the filepath does not exist"
             (resolver/exact-filepath-match
               {:filepath "C:\\Music\\Missing Song.mp3"}
               vdj-library)

             => nil))


(facts "normalized-artist-title-match"

       (fact "matches the same artist and title"
             (resolver/normalized-artist-title-match
               {:artist "System Of A Down"
                :track "Toxicity"}
               vdj-library)

             => (contains
                  {:artist "System Of A Down"
                   :track "Toxicity"
                   :bpm 117.0}))


       (fact "matching is normalized instead of depending on capitalization"
             (resolver/normalized-artist-title-match
               {:artist "linkin park"
                :track "numb"}
               vdj-library)

             => (contains
                  {:artist "Linkin Park"
                   :track "Numb"}))


       (fact "returns nil if artist and title are not in the library"
             (resolver/normalized-artist-title-match
               {:artist "Deftones"
                :track "Cherry Waves"}
               vdj-library)

             => nil))