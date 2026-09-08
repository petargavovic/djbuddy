(ns djbuddy.dj.library-test
  (:require [midje.sweet :refer :all]
            [djbuddy.dj.library :as library]))


(facts "parse-filename-artist-title"

       (fact "parses a normal Artist - Title filename"
             (library/parse-filename-artist-title
               "Linkin Park - Numb.mp3")

             => {:artist "Linkin Park"
                 :track "Numb"})


       (fact "removes VirtualDJ-style numeric prefixes"
             (library/parse-filename-artist-title
               "1_31 - Pierce The Veil - King for a Day_(Instrumental).wav")

             => {:artist "Pierce The Veil"
                 :track "King for a Day (Instrumental)"}))