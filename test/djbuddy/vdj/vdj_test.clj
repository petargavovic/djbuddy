(ns djbuddy.vdj.vdj-test
  (:require [midje.sweet :refer :all]
            [djbuddy.vdj.vdj :as vdj]))


(facts "parse-track-line"

       (fact "parses a normal track line"
             (vdj/parse-track-line
               "something:something: Linkin Park - Numb")
             =>
             {:artists ["Linkin Park"]
              :track "Numb"
              :source :file})


       (fact "splits multiple artists separated by &"
             (vdj/parse-track-line
               "something:something: Linkin Park & Jay-Z - Numb Encore")
             =>
             {:artists ["Linkin Park" "Jay-Z"]
              :track "Numb Encore"
              :source :file})


       (fact "trims whitespace around artists"
             (vdj/parse-track-line
               "something:something: Linkin Park   &   Jay-Z - Numb Encore")
             =>
             {:artists ["Linkin Park" "Jay-Z"]
              :track "Numb Encore"
              :source :file})


       (fact "returns nil for an invalid line"
             (vdj/parse-track-line "this is not a valid track line")
             => nil))


(facts "current-playing-deck"

       (fact "returns deck 1 when deck 1 is audible"
             (vdj/current-playing-deck) => 1

             (provided
               (vdj/deck-audible? 1) => true))


       (fact "returns deck 2 when only deck 2 is audible"
             (vdj/current-playing-deck) => 2

             (provided
               (vdj/deck-audible? 1) => false
               (vdj/deck-audible? 2) => true))


       (fact "returns nil when neither deck is audible"
             (vdj/current-playing-deck) => nil

             (provided
               (vdj/deck-audible? 1) => false
               (vdj/deck-audible? 2) => false)))


(facts "current-track-from-api"

       (fact "returns artist and title for the currently playing deck"
             (vdj/current-track-from-api)
             =>
             {:artists ["System Of A Down"]
              :track "Toxicity"
              :deck 2
              :source :api}

             (provided
               (vdj/current-playing-deck) => 2
               (vdj/vdj-query "deck 2 get_artist") => "System Of A Down"
               (vdj/vdj-query "deck 2 get_title") => "Toxicity"))


       (fact "returns nil when there is no playing deck"
             (vdj/current-track-from-api) => nil

             (provided
               (vdj/current-playing-deck) => nil))


       (fact "returns nil when artist is missing"
             (vdj/current-track-from-api) => nil

             (provided
               (vdj/current-playing-deck) => 1
               (vdj/vdj-query "deck 1 get_artist") => nil
               (vdj/vdj-query "deck 1 get_title") => "Numb"))


       (fact "returns nil when title is missing"
             (vdj/current-track-from-api) => nil

             (provided
               (vdj/current-playing-deck) => 1
               (vdj/vdj-query "deck 1 get_artist") => "Linkin Park"
               (vdj/vdj-query "deck 1 get_title") => nil)))


(facts "current-track-auto"

       (fact "uses API track when API is available"
             (vdj/current-track-auto)
             =>
             {:artists ["Linkin Park"]
              :track "Numb"
              :source :api}

             (provided
               (vdj/vdj-api-running?) => true
               (vdj/current-track-from-api)
               => {:artists ["Linkin Park"]
                   :track "Numb"
                   :source :api}))


       (fact "falls back to tracklist when API returns no track"
             (vdj/current-track-auto)
             =>
             {:artists ["Deftones"]
              :track "Cherry Waves"
              :source :file}

             (provided
               (vdj/vdj-api-running?) => true
               (vdj/current-track-from-api) => nil
               (vdj/current-track)
               => {:artists ["Deftones"]
                   :track "Cherry Waves"
                   :source :file}))


       (fact "uses tracklist directly when API is unavailable"
             (vdj/current-track-auto)
             =>
             {:artists ["Deftones"]
              :track "Cherry Waves"
              :source :file}

             (provided
               (vdj/vdj-api-running?) => false
               (vdj/current-track)
               => {:artists ["Deftones"]
                   :track "Cherry Waves"
                   :source :file})))


(facts "deck-track"

       (fact "returns live track information for an audible deck"
             (vdj/deck-track 1)
             =>
             (contains
               {:artists ["Linkin Park"]
                :track "Numb"
                :deck 1
                :source :vdj-live})

             (provided
               (vdj/deck-audible? 1) => true
               (vdj/vdj-query "deck 1 get_artist") => "Linkin Park"
               (vdj/vdj-query "deck 1 get_title") => "Numb"))


       (fact "includes played-at"
             (:played-at (vdj/deck-track 1))
             => #(instance? java.time.Instant %)

             (provided
               (vdj/deck-audible? 1) => true
               (vdj/vdj-query "deck 1 get_artist") => "Linkin Park"
               (vdj/vdj-query "deck 1 get_title") => "Numb"))


       (fact "does not return a track when deck is not audible"
             (vdj/deck-track 2) => nil

             (provided
               (vdj/deck-audible? 2) => false))


       (fact "does not return a track if artist is missing"
             (vdj/deck-track 1) => nil

             (provided
               (vdj/deck-audible? 1) => true
               (vdj/vdj-query "deck 1 get_artist") => nil
               (vdj/vdj-query "deck 1 get_title") => "Numb")))