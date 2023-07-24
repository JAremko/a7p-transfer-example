(ns transform-from-editable-worker
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]))


;; Basic specs
(s/def ::non-empty-string (s/and string? (complement str/blank?)))
(s/def ::g-type #{:g1 :g7 :custom})
(s/def ::twist-dir #{:right :left})

(defn in-range? [start end value]
  (and (>= value start) (<= value end)))

(s/def ::distance (s/and number? #(in-range? 1.0 3000.0 %)))
(s/def ::reticle-idx (s/and int? #(in-range? 0 255 %)))
(s/def ::zoom (s/and int? #(in-range? 0 4 %)))
(s/def ::profile-name (s/and ::non-empty-string #(<= (count %) 50)))
(s/def ::cartridge-name (s/and ::non-empty-string #(<= (count %) 50)))
(s/def ::caliber (s/and ::non-empty-string #(<= (count %) 50)))
(s/def ::device-uuid (s/and string? #(<= (count %) 50)))
(s/def ::bullet-name (s/and ::non-empty-string #(<= (count %) 50)))
(s/def ::short-name-top (s/and ::non-empty-string #(<= (count %) 8)))
(s/def ::short-name-bot (s/and ::non-empty-string #(<= (count %) 8)))
(s/def ::user-note (s/and string? #(<= (count %) 1024)))
(s/def ::zero-x (s/and number? #(in-range? -200.0 200.0 %)))
(s/def ::zero-y (s/and number? #(in-range? -200.0 200.0 %)))
(s/def ::sc-height (s/and number? #(in-range? -5000.0 5000.0 %)))
(s/def ::r-twist (s/and number? #(in-range? 0.0 100.0 %)))
(s/def ::c-muzzle-velocity (s/and number? #(in-range? 10.0 3000.0 %)))
(s/def ::c-zero-temperature (s/and number? #(in-range? -100.0 100.0 %)))
(s/def ::c-t-coeff (s/and number? #(in-range? 0.0 5.0 %)))
(s/def ::c-zero-air-temperature (s/and number? #(in-range? -100.0 100.0 %)))
(s/def ::c-zero-air-pressure (s/and number? #(in-range? 300.0 1500.0 %)))
(s/def ::c-zero-air-humidity (s/and number? #(in-range? 0.0 100.0 %)))
(s/def ::c-zero-w-pitch (s/and number? #(in-range? -90.0 90.0 %)))
(s/def ::c-zero-p-temperature (s/and number? #(in-range? -100.0 100.0 %)))
(s/def ::b-diameter (s/and number? #(in-range? 0.001 50.0 %)))
(s/def ::b-weight (s/and number? #(in-range? 1.0 6553.5 %)))
(s/def ::b-length (s/and number? #(in-range? 0.01 200.0 %)))
(s/def ::bc (s/and number? #(in-range? 0.0 10.0 %)))
(s/def ::mv (s/and number? #(in-range? 0.0 3000.0 %)))
(s/def ::cd (s/and number? #(in-range? 0.0 10.0 %)))
(s/def ::ma (s/and number? #(in-range? 0.0 10.0 %)))

(s/def ::distances (s/coll-of ::distance :kind vector? :min-count 1 :max-count 200))

(s/def ::sw-pos (s/keys :req-un [::reticle-idx ::zoom]))

(s/def ::switches (s/coll-of ::sw-pos :kind vector? :min-count 4))

(s/def ::coef-g1 (s/coll-of (s/keys :req-un [::bc ::mv]) :max-count 5 :kind vector?))

(s/def ::coef-g7 (s/coll-of (s/keys :req-un [::bc ::mv]) :max-count 5 :kind vector?))

(s/def ::coef-custom (s/coll-of (s/keys :req-un [::cd ::ma]) :max-count 200 :kind vector?))

(s/def ::bc-type (s/and keyword? ::g-type))


(s/def ::profile (s/keys :req-un [::profile-name
                                  ::cartridge-name
                                  ::bullet-name
                                  ::caliber
                                  ::device-uuid
                                  ::short-name-top
                                  ::short-name-bot
                                  ::user-note
                                  ::zero-x
                                  ::zero-y
                                  ::distances
                                  ::switches
                                  ::sc-height
                                  ::r-twist
                                  ::twist-dir
                                  ::c-muzzle-velocity
                                  ::c-zero-temperature
                                  ::c-t-coeff
                                  ::c-zero-distance-idx
                                  ::c-zero-air-temperature
                                  ::c-zero-air-pressure
                                  ::c-zero-air-humidity
                                  ::c-zero-w-pitch
                                  ::c-zero-p-temperature
                                  ::b-diameter
                                  ::b-weight
                                  ::b-length
                                  ::coef-g1
                                  ::coef-g7
                                  ::coef-custom
                                  ::bc-type]))


(defn process-data [x]
  ;; This is a placeholder for your data processing function.
  ;; Update this function with your actual data processing logic.
  x)

(defn on-message [event]
  (when event
    (let [data (.-data event)
          edn-str (pr-str data) ;; Convert JavaScript object to EDN string
          edn-data (reader/read-string edn-str) ;; Convert EDN string to ClojureScript data structure
          processed-data (process-data edn-data) ;; Process the data
          result (reader/read-string (pr-str processed-data))] ;; Convert the processed data back to a JavaScript object
      (js/console.log "Received message in worker: " data) ;; added logging
      (.postMessage js/self result))))

(set! (.-onmessage js/self) on-message)
