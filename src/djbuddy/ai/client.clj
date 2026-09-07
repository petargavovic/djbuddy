(ns djbuddy.ai.client
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]))

(def model
  "gemini-3.6-flash")

(def gemini-url
  (str
    "https://generativelanguage.googleapis.com/v1beta/models/"
    model
    ":generateContent"))


(defn get-gemini-api-key
  []
  (try
    (require 'djbuddy.secrets)

    (when-let [key-var
               (ns-resolve 'djbuddy.secrets
                           'gemini-api-key)]
      (some-> (var-get key-var)
              str
              str/trim
              not-empty))

    (catch Exception _
      nil)))


(defn- extract-text
  [body]
  (->> (get-in body
               [:candidates 0 :content :parts])
       (keep :text)
       (remove str/blank?)
       (str/join "\n")))


(defn generate-text
  [system-prompt user-prompt]

  (if-let [api-key
           (get-gemini-api-key)]

    (let [response
          (http/post
            gemini-url

            {:headers
             {"x-goog-api-key"
              api-key

              "Content-Type"
              "application/json"}

             :body
             (json/generate-string
               {:system_instruction
                {:parts
                 [{:text system-prompt}]}

                :contents
                [{:role "user"
                  :parts
                  [{:text user-prompt}]}]})

             :as :json
             :coerce :always
             :throw-exceptions false})

          status
          (:status response)

          body
          (:body response)]

      (if (<= 200 status 299)

        (let [text
              (extract-text body)]

          (if (str/blank? text)
            (throw
              (ex-info
                "Gemini returned no text."
                {:response body}))

            text))

        (throw
          (ex-info
            "Gemini API request failed."
            {:status status
             :response body}))))

    (throw
      (ex-info
        "Gemini API key is not configured."
        {:reason :missing-gemini-api-key}))))