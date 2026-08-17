(ns djbuddy.lastfm.genre
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [djbuddy.lastfm.client :as client]))

(def ignored-tags
  #{"seen live"
    "favorites"
    "favourite"
    "favorite"
    "awesome"
    "80s"
    "70s"
    "90s"
    "00s"
    "male vocalists"
    "female vocalists"
    "british"
    "american"})

(def allowed-genres
  #{
    ;; Rock
    "rock"
    "alternative rock"
    "indie rock"
    "hard rock"
    "classic rock"
    "garage rock"
    "psychedelic rock"
    "progressive rock"
    "post-rock"
    "grunge"
    "shoegaze"
    "emo"
    "post-hardcore"

    ;; Punk
    "punk"
    "punk rock"
    "pop punk"
    "hardcore punk"
    "skate punk"
    "post-punk"

    ;; Metal
    "metal"
    "heavy metal"
    "alternative metal"
    "nu metal"
    "metalcore"
    "deathcore"
    "death metal"
    "melodic death metal"
    "black metal"
    "thrash metal"
    "doom metal"
    "power metal"
    "progressive metal"
    "industrial metal"
    "symphonic metal"
    "groove metal"
    "folk metal"

    ;; Pop
    "pop"
    "indie pop"
    "electropop"
    "synthpop"
    "dance pop"
    "hyperpop"
    "dream pop"
    "art pop"
    "europop"

    ;; Hip hop / rap
    "hip hop"
    "rap"
    "trap"
    "drill"
    "boom bap"
    "cloud rap"
    "emo rap"
    "conscious hip hop"

    ;; Electronic / club
    "electronic"
    "electronica"
    "edm"
    "house"
    "deep house"
    "tech house"
    "progressive house"
    "future house"
    "electro house"
    "acid house"
    "disco house"
    "french house"
    "techno"
    "minimal techno"
    "trance"
    "progressive trance"
    "psytrance"
    "hardstyle"
    "hardcore"
    "happy hardcore"
    "gabber"
    "breakbeat"
    "breaks"
    "drum and bass"
    "jungle"
    "dubstep"
    "future bass"
    "uk garage"
    "garage"
    "2-step"
    "grime"
    "jersey club"
    "baltimore club"
    "footwork"
    "juke"
    "ghetto house"
    "electro"
    "idm"
    "ambient"
    "downtempo"
    "eurodance"
    "eurobeat"
    "euro house"
    "italo dance"
    "italo disco"
    "synthwave"
    "new wave"
    "hi-nrg"

    ;; Funk / soul / R&B
    "funk"
    "soul"
    "r&b"
    "contemporary r&b"
    "neo soul"
    "disco"
    "boogie"

    ;; Jazz / blues
    "jazz"
    "fusion"
    "jazz fusion"
    "blues"
    "blues rock"

    ;; Reggae
    "reggae"
    "dub"
    "dancehall"
    "ska"

    ;; Country / folk
    "country"
    "folk"
    "folk rock"
    "americana"
    "bluegrass"

    ;; Latin / world
    "latin"
    "reggaeton"
    "salsa"
    "bachata"
    "cumbia"
    "afrobeat"
    "afrobeats"

    ;; Industrial / goth
    "industrial"
    "industrial rock"
    "gothic rock"
    "darkwave"
    "coldwave"

    ;; Misc
    "experimental"
    "noise"
    "soundtrack"
    "classical"
    "orchestral"
    })

(def genre-aliases
  {
   ;; Rock
   "alt rock" "alternative rock"
   "alternative" "alternative rock"
   "indie" "indie rock"

   ;; Metal
   "alt metal" "alternative metal"
   "alternative-metal" "alternative metal"

   "nu-metal" "nu metal"
   "numetal" "nu metal"

   "metal core" "metalcore"
   "death core" "deathcore"

   "melodeath" "melodic death metal"
   "melodic death" "melodic death metal"

   "prog metal" "progressive metal"
   "progressive-metal" "progressive metal"

   ;; Punk
   "pop-punk" "pop punk"
   "punkrock" "punk rock"
   "hardcore punk rock" "hardcore punk"

   ;; Hip hop
   "hip-hop" "hip hop"
   "hiphop" "hip hop"
   "hip hop/rap" "hip hop"

   "trap music" "trap"

   ;; Electronic
   "electronica" "electronic"
   "electronic music" "electronic"

   "dnb" "drum and bass"
   "drum & bass" "drum and bass"
   "drum n bass" "drum and bass"
   "drum'n'bass" "drum and bass"

   "dub step" "dubstep"

   "ukg" "uk garage"
   "uk garage music" "uk garage"

   "2 step" "2-step"
   "two step" "2-step"

   "jersey" "jersey club"
   "jersey-club" "jersey club"
   "jerseyclub" "jersey club"

   "bmore club" "baltimore club"
   "baltimore-club" "baltimore club"

   "deep-house" "deep house"
   "tech-house" "tech house"

   "psy trance" "psytrance"
   "psy-trance" "psytrance"

   "euro dance" "eurodance"
   "euro-dance" "eurodance"
   "90s eurodance" "eurodance"

   "euro-house" "euro house"
   "eurohouse" "euro house"

   "euro beat" "eurobeat"
   "euro-beat" "eurobeat"

   "italodance" "italo dance"
   "italo-dance" "italo dance"

   "italodisco" "italo disco"
   "italo-disco" "italo disco"

   "hi nrg" "hi-nrg"
   "high energy" "hi-nrg"

   "happy-hardcore" "happy hardcore"

   "disco-house" "disco house"
   "french-house" "french house"

   "new-wave" "new wave"

   "synth-wave" "synthwave"
   "synth wave" "synthwave"

   ;; R&B
   "rnb" "r&b"
   "r and b" "r&b"
   "rhythm and blues" "r&b"

   "contemporary rnb" "contemporary r&b"
   "contemporary r&b" "contemporary r&b"

   "neo-soul" "neo soul"

   ;; Reggae
   "reggae music" "reggae"

   ;; Industrial / goth
   "industrial-rock" "industrial rock"
   "goth rock" "gothic rock"

   ;; Misc
   "synth pop" "synthpop"
   "synth-pop" "synthpop"

   "electro pop" "electropop"
   "electro-pop" "electropop"

   "dance-pop" "dance pop"
   "dream-pop" "dream pop"

   "euro pop" "europop"
   "euro-pop" "europop"
   })


(defn normalize-genre [genre]
  (when genre
    (let [normalized
          (-> genre
              str/lower-case
              str/trim
              (str/replace #"\s+" " "))]

      (get genre-aliases
           normalized
           normalized))))

(defn valid-genre [value]
  (when value
    (let [normalized (normalize-genre value)]
      (when (contains? allowed-genres normalized)
        normalized))))

(defn first-valid-genre [tags]
  (->> tags
       (map :name)
       (map normalize-genre)
       (filter allowed-genres)
       first))

(defn genre-for-track [artist track]
  (or
    ;; Prefer track-specific genre
    (first-valid-genre
      (client/track-tags artist track))

    ;; If track tags are garbage/missing,
    ;; use artist-level genre
    (first-valid-genre
      (client/artist-tags artist))))

