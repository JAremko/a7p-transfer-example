(ns transform-to-editable-worker
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.walk :as walk]))


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


(s/def :transform-to-editable-worker.sw/distance
  (s/or :distance ::distance :unused zero?))


(s/def ::c-idx (s/or :index (s/int-in 0 201) :unsuded #{255}))


(s/def ::distance-from #{:index :value})


(s/def ::sw-pos (s/keys :req-un [::c-idx
                                 :transform-to-editable-worker.sw/distance
                                 ::distance-from
                                 ::reticle-idx
                                 ::zoom]))

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

(s/def ::payload (s/keys :req-un [::profile]))

(def powers-of-ten
  {::distance 2
   ::zero-x 3
   ::zero-y 3
   ::sc-height 0
   ::r-twist 2
   ::c-muzzle-velocity 1
   ::c-zero-temperature 0
   ::c-t-coeff 3
   ::c-zero-air-temperature 0
   ::c-zero-air-pressure 1
   ::c-zero-air-humidity 0
   ::c-zero-w-pitch 2
   ::c-zero-p-temperature 0
   ::b-diameter 3
   ::b-weight 1
   ::b-length 3
   ::bc 4
   ::mv 1
   ::cd 4
   ::ma 4
   :distances 2})

(defn- adjust-value [k v op]
  (let [power (powers-of-ten k)]
    (cond
      (and power (vector? v)) (mapv #(op % (Math/pow 10 power)) v)
      power (op v (Math/pow 10 power))
      :else v)))

(defn- adjust-map [m op]
  (reduce-kv (fn [acc k v] (assoc acc k (adjust-value k v op))) {} m))

(defn walk-multiply [m]
  (walk/postwalk
    (fn [x] (if (map? x) (adjust-map x *) x))
    m))

(defn walk-divide [m]
  (walk/postwalk
    (fn [x] (if (map? x) (adjust-map x /) x))
    m))

(defn dissoc-in [m [k & ks]]
  (if ks
    (if-let [submap (get m k)]
      (assoc m k (dissoc-in submap ks))
      m)
    (dissoc m k)))

(defn process-data [x]
  ;; This is a placeholder for your data processing function.
  ;; Update this function with your actual data processing logic.
  (js/console.log "Received message in worker: " (str x)) ;; added logging
  x)

(defn specific-mapping [data]
  (let [bc-type-mapping {"G1" :g1, "G7" :g7, "CUSTOM" :custom}
        distance-from-mapping {"VALUE" :value, "INDEX" :index}
        twist-dir-mapping {"RIGHT" :right, "LEFT" :left}
        bc-type (get bc-type-mapping (get-in data [:profile :bc-type]))
        switches (mapv
                   (fn [m]
                     (assoc m :distance-from
                            (get distance-from-mapping
                                 (:distance-from m)
                                 (:distance-from m))))
                   (get-in data [:profile :switches]))
        twist-dir (get twist-dir-mapping (get-in data [:profile :twist-dir]))
        coef-rows (mapv
                    (fn [m]
                      (case bc-type
                        :g1 {:bc (m :first) :mv (m :second)}
                        :g7 {:bc (m :first) :mv (m :second)}
                        :custom {:cd (m :first) :ma (m :second)}))
                    (get-in data [:profile :coef-rows]))]
    (-> data
        (assoc-in [:profile :bc-type] bc-type)
        (assoc-in [:profile :switches] switches)
        (assoc-in [:profile :twist-dir] twist-dir)
        (assoc-in [:profile (keyword (str "coef-" (name bc-type)))] coef-rows)
        (dissoc-in [:profile :coef-rows]))))

(defn reverse-mapping [data]
  (let [bc-type-mapping {:g1 "G1", :g7 "G7", :custom "CUSTOM"}
        distance-from-mapping {:value "VALUE", :index "INDEX"}
        twist-dir-mapping {:right "RIGHT", :left "LEFT"}
        bc-type (get bc-type-mapping (get-in data [:profile :bc-type]))
        switches (mapv
                   (fn [m]
                     (assoc m :distance-from
                            (get distance-from-mapping
                                 (:distance-from m)
                                 (:distance-from m))))
                   (get-in data [:profile :switches]))
        twist-dir (get twist-dir-mapping (get-in data [:profile :twist-dir]))
        coef-rows (mapv
                    (fn [m]
                      (case bc-type
                        :g1 {:first (m :bc) :second (m :mv)}
                        :g7 {:first (m :bc) :second (m :mv)}
                        :custom {:first (m :cd) :second (m :ma)}))
                    (get-in data [:profile (keyword (str "coef-" (name bc-type)))]))]
    (-> data
        (assoc-in [:profile :bc-type] bc-type)
        (assoc-in [:profile :switches] switches)
        (assoc-in [:profile :twist-dir] twist-dir)
        (assoc-in [:profile :coef-rows] coef-rows)
        (dissoc-in [:profile (keyword (str "coef-" (name bc-type)))]))))

(defn on-message [event]
  (when event
    (let [data (.-data event)
          edn-data (cljs.core/js->clj data :keywordize-keys true) ;; Convert JavaScript object to ClojureScript data structure with keywordized keys
          kebab-case-data (cske/transform-keys csk/->kebab-case-keyword edn-data) ;; Convert keys to kebab-case
          mapped-data (specific-mapping kebab-case-data) ;; Apply specific mappings
          normalized-data (walk-divide mapped-data)
          valid? (s/valid? ::payload normalized-data)]
      (if valid?
        (let [processed-data (process-data normalized-data) ;; Process the data
              post-processed-data (reverse-mapping processed-data) ;; Apply reverse mappings to processed data
              camelCase-data (cske/transform-keys csk/->camelCaseKeyword post-processed-data) ;; Convert keys back to camelCase
              result (cljs.core/clj->js camelCase-data)] ;; Convert the processed data back to a JavaScript object
          (.postMessage js/self result))
        (let [report (s/explain-data ::payload normalized-data)
              result (cljs.core/clj->js report)]
          (.postMessage js/self result))))))

(set! (.-onmessage js/self) on-message)
