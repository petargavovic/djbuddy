(ns djbuddy.vdj.history-test
  (:require [midje.sweet :refer :all]
            [djbuddy.vdj.history :as history]
            [clojure.java.io :as io])
  (:import [java.time Instant LocalDate LocalDateTime LocalTime]))


(fact "parse-history-line extracts the important VirtualDJ fields"
      (history/parse-history-line
        "<time>23:15</time><lastplaytime>1783423101</lastplaytime><artist>Sepultura</artist><title>Roots Bloody Roots</title><remix>2017 Remaster</remix><songlength>212.555</songlength>")
      =>
      {:artists ["Sepultura"]
       :track "Roots Bloody Roots"
       :remix "2017 Remaster"
       :played-time (LocalTime/of 23 15)
       :played-at (Instant/ofEpochSecond 1783423101)
       :duration-seconds 212.555
       :source :vdj-history})

(fact "set-range moves the end date to the following day when the set crosses midnight"
      (history/set-range
        "2026-09-02"
        "23:15"
        "02:30")
      =>
      {:start
       (LocalDateTime/of
         (LocalDate/of 2026 9 2)
         (LocalTime/of 23 15))

       :end
       (LocalDateTime/of
         (LocalDate/of 2026 9 3)
         (LocalTime/of 2 30))

       :end-date
       (LocalDate/of 2026 9 3)})

(fact "tracks spanning midnight are filtered correctly and numbered in order"
      (let [tracks
            [{:track "Before set"
              :played-date-time
              (LocalDateTime/of 2026 9 2 23 0)}

             {:track "First"
              :played-date-time
              (LocalDateTime/of 2026 9 2 23 15)}

             {:track "Second"
              :played-date-time
              (LocalDateTime/of 2026 9 3 0 30)}

             {:track "Third"
              :played-date-time
              (LocalDateTime/of 2026 9 3 2 30)}

             {:track "After set"
              :played-date-time
              (LocalDateTime/of 2026 9 3 2 31)}]

            {:keys [start end]}
            (history/set-range
              "2026-09-02"
              "23:15"
              "02:30")]

        (-> tracks
            (history/tracks-between-datetimes start end)
            history/add-track-order
            (->> (mapv #(select-keys % [:order :track])))))
      =>
      [{:order 1 :track "First"}
       {:order 2 :track "Second"}
       {:order 3 :track "Third"}])

(fact "import-set ends at the next track when it starts before the selected track naturally ends"

      (history/import-set
        "2026-09-02"
        "23:00"
        "00:31")
      =>
      (contains
        {:started-at
         (Instant/parse "2026-09-02T21:30:00Z")

         :ended-at
         (Instant/parse "2026-09-02T22:35:00Z")

         :end-time-source
         :next-track})

      (provided
        (history/history-file-for-date "2026-09-02")
        => (io/file "2026-09-02.m3u")

        (history/history-file-for-date "2026-09-03")
        => (io/file "2026-09-03.m3u")

        (history/read-history
          anything
          "2026-09-02")
        =>
        [{:track "First"
          :played-at
          (Instant/parse "2026-09-02T21:30:00Z")
          :played-date-time
          (LocalDateTime/of 2026 9 2 23 30)
          :duration-seconds 180.0}]

        (history/read-history
          anything
          "2026-09-03")
        =>
        [{:track "Last selected"
          :played-at
          (Instant/parse "2026-09-02T22:30:00Z")
          :played-date-time
          (LocalDateTime/of 2026 9 3 0 30)
          :duration-seconds 600.0}

         {:track "Next track"
          :played-at
          (Instant/parse "2026-09-02T22:35:00Z")
          :played-date-time
          (LocalDateTime/of 2026 9 3 0 35)
          :duration-seconds 200.0}]))

(fact "import-set uses last track duration when it ends before the next track"

      (history/import-set
        "2026-09-02"
        "23:00"
        "00:31")
      =>
      (contains
        {:ended-at
         (Instant/parse "2026-09-02T22:34:00Z")

         :end-time-source
         :last-track-duration})

      (provided
        (history/history-file-for-date "2026-09-02")
        => (io/file "2026-09-02.m3u")

        (history/history-file-for-date "2026-09-03")
        => (io/file "2026-09-03.m3u")

        (history/read-history
          anything
          "2026-09-02")
        =>
        []

        (history/read-history
          anything
          "2026-09-03")
        =>
        [{:track "Last selected"
          :played-at
          (Instant/parse "2026-09-02T22:30:00Z")
          :played-date-time
          (LocalDateTime/of 2026 9 3 0 30)
          :duration-seconds 240.0}

         {:track "Much later track"
          :played-at
          (Instant/parse "2026-09-02T22:50:00Z")
          :played-date-time
          (LocalDateTime/of 2026 9 3 0 50)
          :duration-seconds 200.0}]))