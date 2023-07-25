(ns profile
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.edn :as edn]
            [clojure.set :refer [rename-keys]]
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
                                  ::bc-type
                                  ::coef-g1
                                  ::coef-g7
                                  ::coef-custom]))


(s/def ::payload (s/keys :req-un [::profile]))


(def powers-of-ten
  {:distance 2
   :zero-x 3
   :zero-y 3
   :sc-height 0
   :r-twist 2
   :c-muzzle-velocity 1
   :c-zero-temperature 0
   :c-t-coeff 3
   :c-zero-air-temperature 0
   :c-zero-air-pressure 1
   :c-zero-air-humidity 0
   :c-zero-w-pitch 2
   :c-zero-p-temperature 0
   :b-diameter 3
   :b-weight 1
   :b-length 3
   :bc 4
   :mv 1
   :cd 4
   :ma 4
   :distances 2
   :coef-g1 4
   :coef-g7 4
   :coef-custom 4})


(declare adjust-map)


(defn- adjust-vector-of-maps [v op]
  (mapv #(adjust-map % op) v))


(defn- adjust-value [k v op]
  (let [power (powers-of-ten (-> k name clojure.string/lower-case keyword))]
    (cond
      (and power (vector? v) (map? (first v)))
      (adjust-vector-of-maps v op)

      (and power (vector? v))
      (mapv #(op % (Math/pow 10 power)) v)

      power
      (op v (Math/pow 10 power))

      :else v)))


(defn- adjust-map [m op]
  (let [adjusted (reduce-kv (fn [acc k v]
                              (assoc acc k (adjust-value k v op)))
                            {} m)]
    (if (contains? adjusted :caliber)
      (update adjusted :caliber
              (fn [caliber]
                (if (and (string? caliber)
                         (clojure.string/blank? caliber))
                  "???"
                  caliber)))
      adjusted)))


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


(defn- map-bc-type [data]
  (let [bc-type-mapping {"G1" :g1, "G7" :g7, "CUSTOM" :custom}
        bc-type (get bc-type-mapping (get-in data [:profile :bc-type]))]
    (assoc-in data [:profile :bc-type] bc-type)))


(defn- map-switches [data]
  (let [distance-from-mapping {"VALUE" :value, "INDEX" :index}
        switches (mapv
                   (fn [m]
                     (assoc m :distance-from
                            (get distance-from-mapping
                                 (:distance-from m)
                                 (:distance-from m))))
                   (get-in data [:profile :switches]))]
    (assoc-in data [:profile :switches] switches)))


(defn- map-twist-dir [data]
  (let [twist-dir-mapping {"RIGHT" :right, "LEFT" :left}
        twist-dir (get twist-dir-mapping (get-in data [:profile :twist-dir]))]
    (assoc-in data [:profile :twist-dir] twist-dir)))


(defn- reverse-map-bc-type [data]
  (let [bc-type-mapping {:g1 "G1", :g7 "G7", :custom "CUSTOM"}
        bc-type (get bc-type-mapping (get-in data [:profile :bc-type]))]
    (assoc-in data [:profile :bc-type] bc-type)))


(defn- reverse-map-switches [data]
  (let [distance-from-mapping {:value "VALUE", :index "INDEX"}
        switches (mapv
                   (fn [m]
                     (assoc m :distance-from
                            (get distance-from-mapping
                                 (:distance-from m)
                                 (:distance-from m))))
                   (get-in data [:profile :switches]))]
    (assoc-in data [:profile :switches] switches)))


(defn- reverse-map-twist-dir [data]
  (let [twist-dir-mapping {:right "RIGHT", :left "LEFT"}
        twist-dir (get twist-dir-mapping (get-in data [:profile :twist-dir]))]
    (assoc-in data [:profile :twist-dir] twist-dir)))


(defn replace-bc-table-keys [bc-type bc-table]
  (mapv (fn [m]
          (case bc-type
            :g1 (rename-keys m {:first :bc :second :mv})
            :g7 (rename-keys m {:first :bc :second :mv})
            :custom (rename-keys m {:first :cd :second :ma})
            (throw (ex-info "unknown bc type"
                            {:bc-type bc-type :err "BC type?"}))))
        bc-table))

(defn replace-bc-table-keys-reverse [bc-table]
  (mapv (fn [m]
          (cond
            (and (:bc m) (:mv m)) (rename-keys m {:bc :first, :mv :second})
            (and (:cd m) (:ma m)) (rename-keys m {:cd :first, :ma :second})
            :else (throw (ex-info "Can't infer bc type"
                                  {:bc-table bc-table
                                   :bc-row m
                                   :err "BC table has unknown shape"}))))
        bc-table))

(defn specific-mapping [data]
  (try
    (let [data (-> data
                   map-bc-type
                   map-switches
                   map-twist-dir)
          bc-type (get-in data [:profile :bc-type])
          coef-rows (get-in data [:profile :coef-rows])
          renamed-coef-rows (replace-bc-table-keys bc-type coef-rows)]
      (-> data
          (assoc-in [:profile :coef-g1] (if (= bc-type :g1) renamed-coef-rows []))
          (assoc-in [:profile :coef-g7] (if (= bc-type :g7) renamed-coef-rows []))
          (assoc-in [:profile :coef-custom] (if (= bc-type :custom)
                                              renamed-coef-rows
                                              []))
          (dissoc-in [:profile :coef-rows])))
    (catch js/Error e (ex-data e))))

(defn reverse-mapping [data]
  (try
    (let [bc-type (get-in data [:profile :bc-type])
          coef-key (keyword (str "coef-" (name bc-type)))]
      (-> data
          (update :profile (fn [profile]
                             (let [coef-rows (get profile coef-key)
                                   renamed-coef-rows (replace-bc-table-keys-reverse
                                                        coef-rows)]
                               (-> profile
                                   (assoc :coef-rows renamed-coef-rows)
                                   (dissoc :coef-g1 :coef-g7 :coef-custom)))))
          reverse-map-bc-type
          reverse-map-switches
          reverse-map-twist-dir))
    (catch js/Error e (ex-data e))))
