(ns djbuddy.ai.summary
  (:require [cheshire.core :as json]
            [djbuddy.ai.client :as client]))

(def base-prompt
  (str
    "You are analyzing a DJ performance report. "
    "Use only the supplied statistics. "
    "Do not invent information that is not present. "
    "Do not calculate new statistics. "
    "Use the section data to describe the progression of the set."))

(defn describe-set
  [report]

  (client/generate-text
    (str base-prompt
         "\nDescribe the set's pacing, style, era distribution, "
         "artist variety and progression.")

    (json/generate-string report
                          {:pretty true})))

(defn criticize-set
  [report]

  (client/generate-text
    (str base-prompt
         "\nConstructively criticize the set. "
         "Discuss both strengths and weaknesses only when supported "
         "by the supplied statistics.")

    (json/generate-string report
                          {:pretty true})))

(defn summarize-set
  [report]

  (client/generate-text
    (str base-prompt
         "\nSummarize the set in 2-3 sentences.")

    (json/generate-string report
                          {:pretty true})))