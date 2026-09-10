# INTRODUCTION

The inspiration for the idea of making a DJ add-on program came through my admiration for music, and more recently, my hobby of being a DJ. I was thinking of ways to help guide new DJs into the world of creating coherent music sets and improve the experience by providing some kind of live feedback, a helping hand if a DJ gets a bit lost in the moment, or to in a way try to quantify the experience, because I firmly believe that the art of DJing is about 70% feeling and 30% technicality. Since machines do not have feelings, I figured I can try to help in that 30% with bringing as much relevant information I can to the user about the music that is being played, which are later being turned into statistics, so that he can access them at any time with just a few commands in the Clojure REPL. That way, the user can have an easy insight on his previously played sets, so that he can spot potential ways to improve, or switch up the set the next time he’s behind the turntables.

But as they say, the path to success is never a straight line, right? The initial idea for this Clojure project was a little different. It was to provide song suggestions in real-time based on the music that the user is currently playing. When I proposed this idea to the university professor, he asked: _“Isn’t this already a feature in VirtualDJ?”_. It is. But I have envisioned a better, more in-depth way of doing it, since I have noticed that the suggestions from VirtualDJ were not always perfect. It did not take into consideration the flow of the whole set, just the current track and it could miss the genre completely sometimes. 

I quickly realized that taking on a feature developed by a huge company with more than two decades of experience proved to be an overly ambitious task for a student. I just couldn’t get my hands on the data that VirtualDJ has. I needed a huge database of music that exposes an API with data points like music key, genre and release year but for each API that I have found I kept getting into a dead end. Either it doesn’t have newer or less popular tracks, doesn’t have the required data points, or it is hidden behind a paywall. High hopes were held in Spotify’s Web API but most of its APIs got deprecated shortly before I started the development of this project, and with that my last hopes for this type of project fell into the water. Then I decided to step back and re-think – I have a very powerful DJ software, how can I use it to my advantage and provide some value to the DJ experience? There, **DJBuddy** was born – **in-depth DJ set statistics with AI analysis**!

# Development process

Luckly, my efforts for the failed “DJ song suggestor” app were not in vain, I have learned a lot about what information is available to me and how to make the most of it. Also I got to keep some functions for integration with VirtualDJ from that draft project.

The development process roughly follows adding one functionality at a time, which are separated in their own packages and files.
The app has two working modes:
1.	**Live mode** – Provides on-command, real-time set stats, gets currently playing track data from locally exposed API by the VirtualDJ Network Control Plugin. If the API is not running or is not reachable, the fallback method for reading tracks is to read from the tracklist.txt file, which takes time to update, making this method slower. Using the Network Control Plugin is recommended.
2.	**History mode** – Analyzes and provides statistics for sets played in the past, data for which is stored in the History folder of VirtualDJ.

### djbuddy.vdj
Integration with VirtualDJ lives in the `djbuddy.vdj` package, with `vdj.clj` handling live mode and `history.clj` handling history mode. This is the core package of the application. 

First, I got to developing the **live mode** in `vdj.clj`, where the communication with the API is being made by the `clj-http.client` dependency. 
The key function for making requests to the VirtualDJ API is `vdj-query` which takes in VirtualDJ **scripts** which are used to get the currently playing track, or send other queries through HTTP query parameters. The function that calls this API is `current-track-from-api`, which after determining which deck is playing, asks the API for the track artist and track title.
```
(vdj-query (str "deck " deck " get_artist"))
(vdj-query (str "deck " deck " get_title"))
```
It creates something like:
```
{:artists ["System Of A Down"]
 :track "Chop Suey!"
 :deck 2
 :source :api}
```
which will be used later as a starting point to enrich the track with various details.

The function that ties everything together is `start-live-watch!` This function **polls** the API each second to check which song is playing and if there were changes in the song being played. It also takes into consideration the scenario if the API is not available, then it will ultimately fall back to the `current-track` function through `current-live-tracks` instead of the previously mentioned `current-track-from-api`. It uses `atoms` to store the tracks between iterations so the program can remember which was detected on the previous polling cycle. To run this asynchronously and not to block the thread, the watcher runs inside a `future`. 
The most challenging part here was to detect when the track should be returned, because even if it is playing on a deck its volume can be turned down so the DJ could listen to it through headphones. That should not count, so helper methods were introduced to check that.

After the live mode came **history mode**, which uses the `clojure-java-io` dependency for reading the history file.
```
(def history-dir
  (io/file
    (System/getProperty "user.home")
    "AppData"
    "Local"
    "VirtualDJ"
    "History"))
```
In order to get a set of tracks, the user has to provide the **date, start time and end time** when the set took place. That concept poses a few problems, first that came to my mind was what if the set crosses midnight (which they often do)? After parsing the time to `LocalTime`, the `time-between?` function solves this problem by checking if the end time is less then the start time, meaning the set crossed to a new day. After parsing the track from the history through `parse-history-line`, the app is working with a `map` like this:
```
{:artists ["System Of A Down"]
 :track "Chop Suey!"
 :remix nil
 :played-time ...
 :played-at ...
 :duration-seconds ...
 :source :vdj-history}
```
of which the full set will consist. The `import-set` function will read all tracks in the provided time period and assign them their order number in the set and calculate the set duration.

### djbuddy.dj
This package is intended to **resolve track information** based on the track that was read using one of the two modes. Because VirtualDJ analyzes tracks as they are being played and stores valuable track details like BPM, key, album, duration, I used that to fetch that data directly from VirtualDJ’s local `database.xml`.

`library.clj`’s job is to read the database file and prepare the track details, like removing filename extensions, removing unnecessary prefixes and converting beat duration provided by VirtualDJ to BPM. `load-library` is the function that loads the entire database and stores each song with all available details in an array.
After that is done, it is required to match the track that has been read with its corresponding database entry. That is the responsibility of `feature-resolver.clj`. After normalizing the track artist and name, the program attempts to match a track by the filepath of the actual file that was played to its database entry in the `exact-filepath-match` function. If the filepath is unavailable (e.g. a track is from streaming services), the fallback method is to match through the `normalized-artist-title-match` function, which uses the normalized artist and track names, which is a little bit less precise but still does the job well. 

But more often than not, VirtualDJ will **not** provide the track genre and release year, which would be a nice addition to the analysis. That is where the **_last.fm_ API** comes in.

### djbuddy.lastfm
An API key is needed to send requests to the _last.fm_ APIs, so that is being stored in `djbuddy/secrets.clj` which is not being committed to Git due to security reasons. Because of that, the _last.fm _enrichment is actually optional to run this app, if the user does not provide the key it just returns nil for the genre and year fields. That is why in `client.clj`, which is used to communicate with _last.fm_, the `djbuddy.secrets` namespace is not required in the namespace declaration, but only in the `get-lastfm-api-key` function.
```
(try
    (require 'djbuddy.secrets)…
```
The client can fetch information for a particular track, album or artist.
Since _last.fm_ does not provide a specific genre field, the tags for a track can contain a lot of unwanted strings. In `genre.clj`, the program is filtering out useful **genre tags**. There is a large set named `allowed-genres`, which contains all genres that the program can support, that way everything that is very niche or that isn’t a genre at all will not be processed. Since these tags are user-generated, they can have minor formatting differences, for example _nu metal_, _nu-metal_ and _numetal_ are the same but written differently. In order to keep one unified way or writing genres, the `genre-aliases` `hash-map` is introduced. It contains many possible combinations, for example:
```
"nu-metal" → "nu metal" 
"numetal" → "nu metal"
```
When the genre information is received, either from _last.fm_ or the VirtualDJ database, it first gets normalized in the `normalize-genre` function by turning the string to lowercase and trimming unnecessary whitespaces. Then, in the `valid-genre` it is being checked if the resulting string is contained within the `allowed-genres` set.
```
(contains? allowed-genres normalized)
```
Since there can be multiple genres for one track, only one is needed for the set statistics so through the `first-valid-genre` function the first one, which is the most popular valid tag, is being taken. If the track does not have any kind of genre, the fallback is to check the genre of the artist, which is being taken care of in the penultimate function of this namespace – `genre-for-track`.

Getting to the **year** information is a bit more complicated. The release year can be found in the information of the album of the track, in various different places. First it is being checked if _last.fm_’s `album.getInfo` response contains the year as part of a `releasedate` **field**. The `year-from-release-date` function uses regex `(re-find #"(18|19|20)\d{2}" release-date)` to attempt to locate the year in the release date. The second fallback is searching the for the year in the **album tags**, which uses a similar regex in the `year-from-tags` function. The third and the last fallback is the `year-from-wiki` function, which searches for a **year close to the word “released” in the wiki text** which is included in the album information. `year-from-album-info` ties all of these together and is used in `year-for-track` where the call for getting the album information is being made.

Back to the `feature-resolver.clj`, these functions need to be connected to the track resolving process. The `enrich-lastfm` function combines both genre and year resolvers, where it checks if the data is already provided by VirtualDJ, in the case it is, the _last.fm_ APIs will not be called. Finally, _last.fm_ enrichments and VirtualDJ database data are combined in `resolve-history-track`, and then in `resolve-history-set` which applies that function to each track from the set for the history mode and in `resolve-live-track` for the live mode which is polling the database every second until the currently playing track is completely analyzed by VirtualDJ. 
The program is able to produce a Clojure `set` for a track like this:
```
{:artist "Linkin Park" 
 :track "Numb"
 :album "Meteora"
 :bpm 109.0
 :key "F#m"
 :genre "nu metal"
 :year "2003"
 :duration 185.0
 :resolved? true
 :feature-source :virtualdj
 ...}
```
### djbuddy.analysis
This is where the set data gets compiled into **statistics** and presented in a neat REPL report. 

One of the calculations that `report.clj` contains is the `bpm-stats` function which includes set statistics related to BPM – **minimum, maximum, average, start and end BPM of the set**. `genre-stats` calculates the frequency of each played genre in the set. Years are converted to decades in `year->decade` for simplicity, while `decade-stats` is providing the frequency of each decade. `artist-stats` provides the number of unique artists played as well as the most played artist. The `set-duration-seconds` function calculates the set duration by subtracting the set start time from the set end time. I wanted to be able to provide some kind of set progression feedback, which is why each set is divided by the `split-into-fourths` function into four equal parts – **opening, early middle, late middle and closing**. Each of the sections has its own stats, created by the `section-stats` function.
```
{:position :opening
 :track-range [1 5]
 :track-count 5
 :bpm {:min 85
       :max 110
       :avg 98.4}
 :dominant-genres ["alternative rock"
                   "nu metal"]
 :dominant-decade "2000s"}
```
The `performance-report` function takes in the full set and combines all previously mentioned functions to return a `hash-map` of all statistics. `print-performance-report` formats this map to be clear and readable for the user **from the REPL**.

### djbuddy.live
This part acts like the application layer for the live mode, it simplifies the way it can be used.

The `session.clj` file orchestrates the `vdj`, `resolver` and `report` components for the **live mode**. The `live-set*` `atom` accumulates the tracks that are being played by calling the `add-track!` in the `start-live!` function. That function connects the `vdj/start-live-watch!` function with the track resolvers. It can also provide the performance report at any point in the set by calling `print-report!` in the REPL. To stop the set recording, the user needs to call the `stop-live!` function. Starting another set stops the previous set and empties the `live-set*` and `watcher*` `atoms`.

### djbuddy.ai
This is the AI layer that provides different textual interpretations based on a given performance report. It is at the end of the pipeline, implemented as a final app feature.

For the implementation of AI insights, I was in a dilemma if I should implement a locally-ran Ollama model or to use a provider through an API. I decided on the latter, mainly because I think that it is easier for the user to just include an API key then to install a whole LLM to their computer. Having a local model would lower the dependency on having an internet connection, but I think it is a fair trade-off. **_Google Gemini_** is a free option to use with smaller models.

The `client.clj` file’s job is to **communicate** with _Google Gemini_. The model that is being used is `gemini-3.6-flash`, which is a small model that does not consume a lot of tokens. After providing the API key in a similar way to the _last.fm_ API key, the `generate-text` function is sending a HTTP POST request to https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent with a JSON of the **system prompt** and the **user prompt**. The system prompt contains instructions about how _Gemini_ should behave, while the user prompt contains the data _Gemini_ should work with, which in case of this app is the performance report. The response is being extracted from the response map with the extract-text function.

In the `summary.clj` is where _Gemini_ is actually applied to DJBuddy. The contents are simple – a base prompt and three variations of AI prompts. The `base-prompt` contains rules that the AI should generally follow for each interpretation type. Along with sending the performance report as a JSON, these are the three prompts that can be appended to the `base-prompt`:
1.	`describe-set` – This is the most comprehensive mode, it should contain all data in a couple of detailed sentences.
2.	`criticize-set` – It is designed to provide constructive criticism, including areas to improve and what was especially good in the set.
3.	`summarize-set` – Provides a very short overview of the set, not specifically focusing on all stats.

### Project architecture
The overall flow of the application can be visualized like this:
```
                 EXTERNAL DATA SOURCES
        ┌──────────────┬─────────────────┐
        │              │                 │
  VDJ History     VDJ Live Decks   database.xml
        │              │                 │
        ↓              ↓                 │
   History Reader   Live Reader          │
        │              │                 │
        │         Live Session           │
        │              │                 │
        └───────┬──────┘                 │
                ↓                        │
          TRACK RESOLVER ←───────────────┘
                │
                ├──────────→ Last.fm
                │            genre/year
                │
                ↓
          ENRICHED DJ SET
                │
                ↓
        PERFORMANCE ANALYSIS
                │
                ↓
      deterministic statistics
                │
        ┌───────┴────────┐
        ↓                ↓
   REPL report      AI summaries
```
### Testing
For unit testing DJBuddy’s functionalities, the **Midje** testing framework is used. With Midje, I was able to set-up an automated test suite which isolates individual functions by providing “fake” predefined data and verifies that the functions are producing the expected results. The tests are created to cover the most error-prone parts of the application: parsing VirtualDJ data, detecting currently playing tracks, matching tracks with `database.xml`, parsing filenames, choosing a _last.fm_ genre and calculating a history set across midnight (to name a few). The project has 5 test files and 33 individual fact tests.

# Usage of AI

Artificial intelligence was used to an extent while developing this Clojure application. The area where it helped the most is **complex functions**, it helped me understand how to combine different parts of code into one, easy-to-use function so that hopefully, a user with very little or no programming experience can use the app. Here is the breakdown by package, file and function or set and why AI was used:

```
src/
└── djbuddy/
    ├── ai/
    │   ├── client.clj
```
- `generate-text` – Translating the API request and response into Clojure and error handling
```
    │   └── summary.clj
    │
    ├── analysis/
    │   └── report.clj
```
- `split-into-forths` – Splitting a number of tracks evenly into exactly four ordered sections required distributing the remainder correctly, which was not a simple task to implement
- `print-performance-report` – Required a lot of mechanical work because the of intense nested destructuring, calculations, optional data handling and presenting all that in a clean-looking, human-readable format
```
    │
    ├── dj/
    │   ├── feature_resolver.clj
```
- `normalized-artist-title-match` – Helped with searching through the library by using some
- `resolve-live-track` – Recursive polling algorithm with database loading and an unresolved fallback method (was hard to wrap my head around this)
```
    │   └── library.clj
```
- `parse-song` – AI helped map the XML structure into a Clojure data model
```
    │
    ├── lastfm/
    │   ├── client.clj
```
> Here AI helped me to understand where can I find what track metadata in _last.fm_
```
    │   ├── genre.clj
```
- `allowed-genres` – Listed many genres that I couldn’t think of
- `genre-aliases` – Suggested aliases for those genres
```
    │   └── year.clj
```
> AI helped me generally to understand regex in Clojure
```
    │
    ├── live/
    │   └── session.clj
```
- `start-live!` – Orchestrating several subsystems and understanding asynchronous workers
```
    │
    ├── vdj/
    │   ├── history.clj
```
- `read-history` – Because some history files are nested in multiple folders, it was a bit tricky to achieve on my own
- `import-set` – A huge function, contains date/time calculations, midnight rollover, multiple files, sorting, filtering, track ordering and more, was hard to keep track of all of that
```
    │   └── vdj.clj
```
- `parse-track-line` – regex again
- `start-live-watch!` - AI helped with the structure of the future-based loop, use of atoms and handling of music playing from two possible decks and from two possible sources (API and tracklist.txt)
```
    │
    └── secrets_example.clj
```

# Conclusion

The moment when I first saw the performance report in its full glory in the REPL was when I realized that I have finally created something tangible and useful in this app. That was probably the highlight of the development cycle and it pushed me to create more. I figured that AI was a great addition to the report because it can provide a perspective to the DJ that he might not have thought about, without actually needing to play in front of a real person.

There are many ways that this app could be improved in the future to enrich the experience. These are some of my favorite ideas:

-	**BPM progression** – graphically display the set energy progression, the user could identify sudden or gradual BPM rises, long flat sections, energy cooldowns and similar

-	**Transition analyzer** – the user can check if two tracks are harmonically compatible, have a similar BPM and what their genre is, and based on these parameters a transition score value could be introduced

-	**Streaming service playlist analysis** – it would be a nice addition if the user could analyze a set before even playing it – just paste a Spotify or Tidal playlist link and the set would be analyzed just like in the history mode
  
-	**Report exporting** - ability to export a performance report into a more tangible format (_.csv_, _.html_ or _.json_)

Developing an app about something that I enjoy in Clojure was a challenging but also intriguing task. I got to work in a very unusual programming language compared to what I am used to (_Java_, _C#_). But as I was learning more about it, I was understanding that the possibilities with it are endless. It is a fast, clean and stable programming language that is worth giving a try.

# User manual

Prerequisites:

•	Java JDK

•	Leiningen

•	VirtualDJ 2023 or later


1.	Clone the repository
```bash
# choose a folder on your machine
cd /your-chosen-folder
# clone the repo
git clone https://github.com/petargavovic/djbuddy
```
For maximum data availability and functionality, the user should include the _last.fm_ and _Google Gemini_ API keys in a `secrets.clj` file, as described in `secrets_example.clj`.
```
(ns djbuddy.secrets-example)

(def lastfm-api-key
  "YOUR_LASTFM_API_KEY")

(def gemini-api-key
  "YOUR_GEMINI_API_KEY")
```
2.	Start the REPL by running `lein repl` in `cmd` at the project path or in your IDE of choice
3.	Load the namespaces:
   ```
(require '[djbuddy.vdj.history :as history]
         '[djbuddy.dj.feature-resolver :as resolver]
         '[djbuddy.analysis.report :as report]
         '[djbuddy.live.session :as session]
         '[djbuddy.ai.summary :as ai]
         '[djbuddy.vdj.vdj :as vdj]
         '[clojure.pprint :refer [pprint]])
```

## LIVE MODE
4.	Install the Network Control extension in VirtualDJ (Infinity license required):
    <img width="949" height="695" alt="vdj1" src="https://github.com/user-attachments/assets/8f716c33-c182-42fd-bac9-fdbaa3e9118c" />
5.	Enable the plugin

  	5.1. Switch to the PRO layout

  	5.2. In the center panel select the MASTER tab:

  	<img width="323" height="339" alt="vdj2" src="https://github.com/user-attachments/assets/9f813972-47a3-4ae0-a4c0-ceed6d9b6a25" />

  	5.3. Click on the 'down arrow' inside MASTER EFFECT and select the Network Control effect:

  	<img width="267" height="793" alt="vdj3" src="https://github.com/user-attachments/assets/ed4ecb43-e3aa-4bd0-91c7-47167279f542" />
6.	Start the live mode:
   ```
(session/start-live!)
```
<img width="287" height="94" alt="live1" src="https://github.com/user-attachments/assets/a34f9963-22b7-406b-bfab-0522d6f77c1a" />


7. Play tracks in VirtualDJ
<img width="476" height="708" alt="image" src="https://github.com/user-attachments/assets/f4bd3e53-669b-4663-8142-ffd3ed0f8ba4" />


The current set of tracks can be checked at any time:
```
(pprint
  (session/current-set))
```
```
{:source :vdj-live,
 :started-at
 #object[java.time.Instant 0x16b2fe12 "2026-09-10T16:43:47.128964600Z"],
 :ended-at nil,
 :tracks
 [{:genre "nu metal",
   :feature-source :virtualdj,
   :filepath "netsearch://td392539",
   :key "Cm",
   :deck 1,
   :duration 299.537415,
   :file-size 12035895,
   :source :vdj-live,
   :artist "Deftones",
   :played-at
   #object[java.time.Instant 0x16b2fe12 "2026-09-10T16:43:47.128964600Z"],
   :year "2000",
   :resolution-method :artist-title,
   :order 1,
   :artists ["Deftones"],
   :album "White Pony",
   :bpm 137.33,
   :resolved? true,
   :genre-source :lastfm,
   :track "Change"}
  {:genre "rock",
   :feature-source :virtualdj,
   :filepath "netsearch://td2125690",
   :key "Em",
   :deck 2,
   :duration 196.138957,
   :file-size 7882080,
   :source :vdj-live,
   :artist "Linkin Park",
   :played-at
   #object[java.time.Instant 0x79498539 "2026-09-10T16:45:45.122108100Z"],
   :year "2003",
   :resolution-method :artist-title,
   :order 2,
   :artists ["Linkin Park"],
   :album "Meteora (Bonus Edition)",
   :bpm 100.03,
   :resolved? true,
   :genre-source :lastfm,
   :track "Breaking the Habit"}
  {:genre "alternative metal",
   :feature-source :virtualdj,
   :filepath "netsearch://td33753604",
   :key "Fm",
   :deck 1,
   :year-source :lastfm,
   :duration 153.78576,
   :file-size 6180624,
   :source :vdj-live,
   :artist "System Of A Down",
   :played-at
   #object[java.time.Instant 0x72cde987 "2026-09-10T16:47:33.256565400Z"],
   :year "2002",
   :resolution-method :artist-title,
   :order 3,
   :artists ["System of A Down"],
   :album "Steal This Album!",
   :bpm 120.0,
   :resolved? true,
   :genre-source :lastfm,
   :track "Innervision"}
  ....
```
8.	Print the live report:
```
(session/print-report!)
```
```
========================================
          DJ PERFORMANCE REPORT
========================================

OVERVIEW
----------------------------------------
Tracks:     10
Duration:   15min 03s

BPM
----------------------------------------
Average:    132.05
Range:      100.03 - 193.57
Start:      137.33
End:        131.38

GENRES
----------------------------------------
nu metal             3    30.00%
metal                3    30.00%
rock                 2    20.00%
alternative metal    2    20.00%

DECADES
----------------------------------------
1990s                2    20.00%
2000s                5    50.00%
2010s                2    20.00%
2020s                1    10.00%

ARTISTS
----------------------------------------
Unique artists: 8
Most played:    System of A Down ( 2 tracks )

SET SECTIONS
========================================

OPENING
----------------------------------------
Tracks:      1 - 3 ( 3 tracks )
BPM avg:     119.12
BPM range:   100.03 - 137.33
Genres:      nu metal, rock
Dominant decade:      2000s

EARLY MIDDLE
----------------------------------------
Tracks:      4 - 6 ( 3 tracks )
BPM avg:     151.23
BPM range:   125.1 - 193.57
Genres:      metal, nu metal
Dominant decade:      2000s

LATE MIDDLE
----------------------------------------
Tracks:      7 - 8 ( 2 tracks )
BPM avg:     129.44
BPM range:   128.0 - 130.88
Genres:      nu metal, rock
Dominant decade:      2020s

CLOSING
----------------------------------------
Tracks:      9 - 10 ( 2 tracks )
BPM avg:     125.29
BPM range:   119.19 - 131.38
Genres:      metal, alternative metal
Dominant decade:      1990s

========================================
```
9.	Store the live report:
```
(def live-report
  (report/performance-report
    (session/current-set)))
```

10.	Send the set for the AI interpretation for any of the three modes:
```
(ai/describe-set live-report)
```
```
=>
"Based on the provided statistics, here is an analysis of the 10-track, 15.4-minute (924-second) DJ performance set:
 
 ### **Pacing**
 * **Overall Tempo:** The set starts at a BPM of 137.33 and finishes at 131.38, maintaining an overall average BPM of 132.05. 
 * **BPM Range:** The tempo spans from a minimum of 100.03 BPM to a maximum peak of 193.57 BPM.
 
 ### **Style**
 The performance is centered around heavy rock genres. Across all 10 tracks, the genre breakdown consists of:
 * **Nu metal:** 3 tracks
 * **Metal:** 3 tracks
 * **Rock:** 2 tracks
 * **Alternative metal:** 2 tracks
 
 ### **Era Distribution**
 The set primarily relies on music from the turn of the millennium, with representation spanning four decades:
 * **2000s:** 5 tracks (majority)
 * **1990s:** 2 tracks
 * **2010s:** 2 tracks
 * **2020s:** 1 track
 
 ### **Artist Variety**
 The set features a high degree of variety with **8 unique artists** across 10 tracks. The most-played artist is **System of A Down**, with 2 tracks included in the set.
 
 ---
 
 ### **Set Progression**
 
 1. **Opening (Tracks 1–3):** 
    * **BPM:** Average of 119.12 (Min: 100.03, Max: 137.33)
    * **Dominant Genres:** Nu metal, rock
    * **Dominant Era:** 2000s
    * *Description:* The set opens with a moderate average tempo, establishing a 2000s nu metal and rock foundation.
 
 2. **Early-Middle (Tracks 4–6):** 
    * **BPM:** Average of 151.23 (Min: 125.1, Max: 193.57)
    * **Dominant Genres:** Metal, nu metal
    * **Dominant Era:** 2000s
    * *Description:* The set escalates significantly in tempo, reaching its highest average and maximum BPM while shifting into metal and nu metal from the 2000s.
 
 3. **Late-Middle (Tracks 7–8):** 
    * **BPM:** Average of 129.44 (Min: 128.0, Max: 130.88)
    * **Dominant Genres:** Nu metal, rock
    * **Dominant Era:** 2020s
    * *Description:* The tempo cools down to a tight BPM range (128.0–130.88), transitioning stylistically back to nu metal and rock, but moving to contemporary 2020s material.
 
 4. **Closing (Tracks 9–10):** 
    * **BPM:** Average of 125.29 (Min: 119.19, Max: 131.38)
    * **Dominant Genres:** Metal, alternative metal
    * **Dominant Era:** 1990s
    * *Description:* The set concludes with a slightly lower average tempo, shifting focus to 1990s metal and alternative metal."
```
```
(ai/criticize-set live-report)
```
```
=>
"Here is a performance analysis and constructive criticism based strictly on the provided statistics.
 
 ---
 
 ### **Set Progression Analysis**
 
 *   **Opening (Tracks 1–3):** 
     The set opens at a starting BPM of 137.33, with the section overall spanning from 100.03 to 137.33 BPM (average of 119.12 BPM). This section focuses primarily on **nu metal** and **rock**, anchored in the **2000s** decade.
 *   **Early-Middle (Tracks 4–6):** 
     The performance experiences a steep rise in tempo, reaching an average BPM of 151.23 and hitting the overall set peak at 193.57 BPM (minimum 125.1 BPM). The dominant genres adjust slightly to **metal** and **nu metal**, while maintaining the **2000s** as the dominant decade.
 *   **Late-Middle (Tracks 7–8):** 
     The tempo stabilizes into a much narrower and lower range between 128.0 and 130.88 BPM (average of 129.44 BPM). The style returns to **nu metal** and **rock**, but shifts eras to the **2020s**.
 *   **Closing (Tracks 9–10):** 
     The set concludes with tracks ranging from 119.19 to 131.38 BPM (average of 125.29 BPM), officially ending at 131.38 BPM. The final tracks transition into **metal** and **alternative metal**, dominated by the **1990s** decade.
 
 ---
 
 ### **Strengths**
 
 1.  **Artist & Focus Diversity:** 
     With 8 unique artists across 10 tracks, the selection avoids over-relying on a single act (only \"System of A Down\" is featured twice). 
 2.  **Genre Cohesion:** 
     The overall set maintains a tight thematic direction centered around metal and rock subgenres—specifically nu metal (3 tracks), metal (3 tracks), rock (2 tracks), and alternative metal (2 tracks).
 3.  **Data Quality:** 
     The set possesses complete metric tracking across all 10 tracks, with 10/10 scores for BPM, genre, year, and key identification.
 
 ---
 
 ### **Weaknesses & Areas for Improvement**
 
 1.  **Extremely Wide BPM Volatility:** 
     The set displays drastic tempo shifts across a brief timeframe. The BPM shifts from an opening section average of 119.12 to an early-middle peak reach of 193.57 (average 151.23 BPM), before dropping rapidly down to a 129.44 BPM average in the late-middle section. Managing transitions across a minimum of 100.03 BPM and a maximum of 193.57 BPM creates aggressive energy swings.
 2.  **Brief Duration per Track:** 
     Totaling 15.4 minutes (924 seconds) across 10 tracks, the set moves through transitions quickly. Combined with the broad decade jumps (moving from 2000s to 2020s to 1990s in consecutive sections), the rapid pacing leaves very little time to establish a continuous groove at any single tempo or era."
```
```
(ai/summarize-set live-report)
```
```
=>
"The 15.4-minute DJ set opens with 2000s nu metal and rock tracks averaging 119.12 BPM, before transitioning into an early-middle section of 2000s metal and nu metal averaging 151.23 BPM. The performance then moves into a late-middle section dominated by 2020s nu metal and rock at an average of 129.44 BPM. Finally, the set concludes with a closing section featuring 1990s metal and alternative metal averaging 125.29 BPM."
```
11.	Stop the live session
```
(session/stop-live!)
```
```
Live session stopped.
```
## HISTORY MODE
4.	Import a previous DJ set by specifying the date and the time range of the set (crossing midnight is acceptable):
```
(def raw-set
  (history/import-set
    "2026-07-29"
    "23:00"
    "04:00"))
```
The set currently looks something like this:
```
(pprint raw-set)
```
```
{:source :vdj-history,
 :started-at
 #object[java.time.Instant 0x73801bb0 "2026-07-29T21:03:11Z"],
 :ended-at
 #object[java.time.Instant 0x77fa3a82 "2026-07-30T01:31:54.183Z"],
 :end-time-source :last-track-duration,
 :tracks
 [{:duration-seconds 177.006,
   :played-time #object[java.time.LocalTime 0x641a32e3 "23:03"],
   :source :vdj-history,
   :played-at
   #object[java.time.Instant 0x73801bb0 "2026-07-29T21:03:11Z"],
   :remix nil,
   :played-date #object[java.time.LocalDate 0x47996422 "2026-07-29"],
   :order 1,
   :artists ["PinkPantheress, Zara Larsson"],
   :played-date-time
   #object[java.time.LocalDateTime 0x40772b24 "2026-07-29T23:03:11"],
   :track "Stateside + Zara Larsson"}
  {:duration-seconds 213.242,
   :played-time #object[java.time.LocalTime 0x21d73c81 "23:05"],
   :source :vdj-history,
   :played-at
   #object[java.time.Instant 0x5076f679 "2026-07-29T21:05:57Z"],
   :remix nil,
   :played-date #object[java.time.LocalDate 0x28064691 "2026-07-29"],
   :order 2,
   :artists ["Tyla, Zara Larsson"],
   :played-date-time
   #object[java.time.LocalDateTime 0x19eb2daa "2026-07-29T23:05:57"],
   :track "SHE DID IT AGAIN"}
  {:duration-seconds 142.55,
   :played-time #object[java.time.LocalTime 0x42357484 "23:07"],
   :source :vdj-history,
   :played-at
   #object[java.time.Instant 0x753d4ffd "2026-07-29T21:07:55Z"],
   :remix "From F1® The Movie",
   :played-date #object[java.time.LocalDate 0x385fe2e0 "2026-07-29"],
   :order 3,
   :artists ["Tate McRae"],
   :played-date-time
   #object[java.time.LocalDateTime 0x533c09b6 "2026-07-29T23:07:55"],
   :track "Just Keep Watching (From F1® The Movie)"}
   ....
```
5.	Resolve and enrich the imported set:
```
(def history-set
  (resolver/resolve-history-set raw-set))
```
The set now looks something like this:
```
{:source :vdj-history,
 :started-at
 #object[java.time.Instant 0x73801bb0 "2026-07-29T21:03:11Z"],
 :ended-at
 #object[java.time.Instant 0x77fa3a82 "2026-07-30T01:31:54.183Z"],
 :end-time-source :last-track-duration,
 :tracks
 [{:genre "pop",
   :duration-seconds 177.006,
   :feature-source :virtualdj,
   :filepath
   "C:\\Users\\gavov\\Downloads\\Telegram Desktop\\Wet party\\90 - PinkPantheress, Zara Larsson - Stateside + Zara Larsson.mp3",
   :key "Eb",
   :played-time #object[java.time.LocalTime 0x641a32e3 "23:03"],
   :duration 177.005714,
   :file-size 7310427,
   :source :vdj-history,
   :artist "PinkPantheress, Zara Larsson",
   :played-at
   #object[java.time.Instant 0x73801bb0 "2026-07-29T21:03:11Z"],
   :remix nil,
   :year "2025",
   :played-date #object[java.time.LocalDate 0x47996422 "2026-07-29"],
   :resolution-method :artist-title,
   :order 1,
   :artists ["PinkPantheress, Zara Larsson"],
   :album "Fancy Some More?",
   :bpm 123.42,
   :resolved? true,
   :played-date-time
   #object[java.time.LocalDateTime 0x40772b24 "2026-07-29T23:03:11"],
   :genre-source :virtualdj,
   :track "Stateside + Zara Larsson"}
  {:genre "pop",
   :duration-seconds 213.242,
   :feature-source :virtualdj,
   :filepath
   "C:\\Users\\gavov\\Downloads\\Telegram Desktop\\Wet party\\93 - Tyla, Zara Larsson - SHE DID IT AGAIN.flac",
   :key "Bm",
   :played-time #object[java.time.LocalTime 0x21d73c81 "23:05"],
   :duration 213.241995,
   :file-size 25511180,
   :source :vdj-history,
   :artist "Tyla, Zara Larsson",
   :played-at
   #object[java.time.Instant 0x5076f679 "2026-07-29T21:05:57Z"],
   :remix nil,
   :year "2026",
   :played-date #object[java.time.LocalDate 0x28064691 "2026-07-29"],
   :resolution-method :artist-title,
   :order 2,
   :artists ["Tyla, Zara Larsson"],
   :album "A*POP",
   :bpm 97.0,
   :resolved? true,
   :played-date-time
   #object[java.time.LocalDateTime 0x19eb2daa "2026-07-29T23:05:57"],
   :genre-source :virtualdj,
   :track "SHE DID IT AGAIN"}
  {:genre "pop",
   :duration-seconds 142.55,
   :feature-source :virtualdj,
   :filepath
   "C:\\Users\\gavov\\Downloads\\Telegram Desktop\\Wet party\\92 - Tate McRae - Just Keep Watching (From F1® The Movie).flac",
   :key "Bm",
   :played-time #object[java.time.LocalTime 0x42357484 "23:07"],
   :duration 142.550499,
   :file-size 17329623,
   :source :vdj-history,
   :artist "Tate McRae",
   :played-at
   #object[java.time.Instant 0x753d4ffd "2026-07-29T21:07:55Z"],
   :remix "From F1® The Movie",
   :year "2025",
   :played-date #object[java.time.LocalDate 0x385fe2e0 "2026-07-29"],
   :resolution-method :artist-title,
   :order 3,
   :artists ["Tate McRae"],
   :album "Just Keep Watching (From F1® The Movie)",
   :bpm 130.0,
   :resolved? true,
   :played-date-time
   #object[java.time.LocalDateTime 0x533c09b6 "2026-07-29T23:07:55"],
   :genre-source :virtualdj,
   :track "Just Keep Watching (From F1® The Movie)"}
   ....
```
6.	Generate the performance report:
```
(def history-report
  (report/performance-report history-set))
```
7.	Print the report:
```
(report/print-performance-report history-report)
```
```
========================================
          DJ PERFORMANCE REPORT
========================================

OVERVIEW
----------------------------------------
Tracks:     65
Duration:   4h 28min

BPM
----------------------------------------
Average:    131.28
Range:      97.0 - 160.0
Start:      123.42
End:        129.0

GENRES
----------------------------------------
funk                 1     1.54%
pop                 35    53.85%
electropop           1     1.54%
hip hop              2     3.08%
trance               1     1.54%
disco                1     1.54%
alternative rock     1     1.54%
electronic           5     7.69%
rap                  5     7.69%
techno               1     1.54%
latin                1     1.54%
soundtrack           1     1.54%
electro house        1     1.54%
r&b                  2     3.08%
house                5     7.69%
electro              1     1.54%
Unknown              1     1.54%

DECADES
----------------------------------------
2000s               12    18.46%
2010s               18    27.69%
2020s               19    29.23%
Unknown             16    24.62%

ARTISTS
----------------------------------------
Unique artists: 52
Most played:    Lady Gaga ( 3 tracks )

SET SECTIONS
========================================

OPENING
----------------------------------------
Tracks:      1 - 17 ( 17 tracks )
BPM avg:     126.49
BPM range:   97.0 - 133.99
Genres:      pop, r&b
Dominant decade:      2020s

EARLY MIDDLE
----------------------------------------
Tracks:      18 - 33 ( 16 tracks )
BPM avg:     143.44
BPM range:   119.6 - 160.0
Genres:      pop, rap
Dominant decade:      2020s

LATE MIDDLE
----------------------------------------
Tracks:      34 - 49 ( 16 tracks )
BPM avg:     127.63
BPM range:   119.0 - 136.06
Genres:      pop, house
Dominant decade:      2000s

CLOSING
----------------------------------------
Tracks:      50 - 65 ( 16 tracks )
BPM avg:     127.46
BPM range:   119.0 - 143.0
Genres:      pop, rap
Dominant decade:      2010s

========================================
```
8.	Send the set for the AI interpretation for any of the three modes:
```
(ai/describe-set history-report)
```
```
=>
"Based on the provided DJ performance report, here is an analysis of the set:
 
 ### Set Overview & Pacing
 * **Duration & Volume:** The performance lasts 268.72 minutes (16,123 seconds) across a total of 65 tracks. 
 * **BPM Range:** The overall set ranges from a minimum tempo of 97.0 BPM to a maximum of 160.0 BPM, with an overall average of 131.28 BPM. 
 * **Trajectory:** The set starts at 123.42 BPM and ends at 129.0 BPM.
 
 ### Style
 Pop is the primary genre of the set, accounting for 35 tracks. The remaining known genres consist of:
 * **Electronic / House / Dance:** Electronic (5), House (5), Electro (1), Electro House (1), Electropop (1), Techno (1), and Trance (1)
 * **Hip Hop & Urban:** Rap (5), Hip Hop (2), and R&B (2)
 * **Other Genres:** Alternative Rock (1), Disco (1), Funk (1), Latin (1), and Soundtrack (1)
 
 *(Genre data is known for 64 tracks).*
 
 ### Era Distribution
 The track list spans three decades:
 * **2020s:** 19 tracks
 * **2010s:** 18 tracks
 * **2000s:** 12 tracks
 
 *(Year data is known for 49 tracks).*
 
 ### Artist Variety
 The set exhibits broad artist variety, featuring 52 unique artists across 65 tracks. The most-played artist in the set is Lady Gaga, with 3 tracks.
 
 ---
 
 ### Progression
 
 1. **Opening (Tracks 1–17)**
    * **BPM:** Average of 126.49 BPM (Min: 97.0, Max: 133.99).
    * **Dominant Genres:** Pop, R&B.
    * **Dominant Decade:** 2020s.
 
 2. **Early-Middle (Tracks 18–33)**
    * **BPM:** Reaches the highest tempo of the set, with an average of 143.44 BPM (Min: 119.6, Max: 160.0).
    * **Dominant Genres:** Pop, Rap.
    * **Dominant Decade:** 2020s.
 
 3. **Late-Middle (Tracks 34–49)**
    * **BPM:** Drops back down to an average of 127.63 BPM (Min: 119.0, Max: 136.06).
    * **Dominant Genres:** Pop, House.
    * **Dominant Decade:** Shifts to the 2000s.
 
 4. **Closing (Tracks 50–65)**
    * **BPM:** Maintains a similar tempo to the late-middle section, averaging 127.46 BPM (Min: 119.0, Max: 143.0).
    * **Dominant Genres:** Pop, Rap.
    * **Dominant Decade:** 2010s."
```
```
(ai/criticize-set history-report)
```
```
=>
"### Progression of the Set
 
 The performance spanned 65 tracks over 268.72 minutes (16,123 seconds), starting at a BPM of 123.42 and ending at 129.0 BPM, with an overall average BPM of 131.28. The set progressed through four distinct sections:
 
 *   **Opening (Tracks 1–17):** The set began in the 2020s decade, featuring pop and r&b as the dominant genres. BPM in this section ranged from a minimum of 97.0 to a maximum of 133.99, averaging 126.49 BPM across 17 tracks.
 *   **Early-Middle (Tracks 18–33):** The performance shifted to higher energy while remaining in the 2020s era, with pop and rap taking over as dominant genres. The tempo peaked in this 16-track section, ranging from 119.6 BPM to a set maximum of 160.0 BPM, with the highest section average of 143.44 BPM.
 *   **Late-Middle (Tracks 34–49):** Energy brought a noticeable drop in tempo as the dominant decade moved back to the 2000s. Dominant genres transitioned to pop and house over these 16 tracks. BPM ranged from 119.0 to 136.06, averaging 127.63 BPM.
 *   **Closing (Tracks 50–65):** The final 16 tracks centered on the 2010s decade, returning to pop and rap as dominant genres. The section held a range of 119.0 to 143.0 BPM, finishing with an average section BPM of 127.46.
 
 ---
 
 ### Constructive Criticism
 
 #### Strengths
 *   **High Artist Variety:** Across 65 tracks, the performance featured 52 unique artists, ensuring diverse artist selection without over-relying on single creators. The most-played artist was Lady Gaga with only 3 track plays.
 *   **Decade and Genre Trajectory:** The set intentionally moved through distinct decade focus points (2020s in the first half, 2000s in the late-middle, and 2010s to close) and varied genre combinations per section (Pop/R&B $\\rightarrow$ Pop/Rap $\\rightarrow$ Pop/House $\\rightarrow$ Pop/Rap).
 *   **Data Completeness for Genres:** Genre identification was high across the dataset, with 64 out of 65 tracks having known genre data.
 
 #### Weaknesses
 *   **Abrupt Tempo Spikes:** The early-middle section contained a significant tempo surge, averaging 143.44 BPM with a peak of 160.0 BPM, flanked by adjacent sections averaging 126.49 BPM (opening) and 127.63 BPM (late-middle). This creates a sharp energy spike rather than a smooth gradual curve.
 *   **Genre Concentration:** Despite 16 total genres being listed across the set, pop heavily dominated the track selection with 35 of the total tracks, leaving several listed genres (such as funk, electropop, trance, disco, alternative rock, techno, latin, soundtrack, electro house, and electro) with only 1 track each.
 *   **Gaps in Track Metadata:** Several performance metrics lacked full data coverage. Out of 65 tracks, only 56 had known BPM and key data, and only 49 had known release year data."
```
```
(ai/summarize-set history-report)
```
```
=>
"The 65-track performance begins with an opening section of 2020s pop and r&b averaging 126.49 BPM, before transitioning into an early-middle section of 2020s pop and rap with a higher average BPM of 143.44. In the late-middle section, the set shifts to 2000s pop and house tracks with a lower average BPM of 127.63. Finally, the performance finishes with a closing section dominated by 2010s pop and rap, maintaining an average BPM of 127.46."
```


## License

Copyright © 2026 FIXME

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
https://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
