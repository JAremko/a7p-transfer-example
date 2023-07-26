(ns worker-utils
  (:require [profile]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [cljs.pprint :as pprint]
            [camel-snake-kebab.extras :as cske]))


(defn validate-coef-g1-g7 [{:keys [coef-g1 coef-g7]}]
  (when (some #(or (not (map? %)) (not (:bc %)) (not (:mv %))) (concat coef-g1 coef-g7))
    "Every row in :coef-g1 or :coef-g7 should be a map with :bc and :mv keys"))

(defn validate-coef-custom [{:keys [coef-custom]}]
  (when (seq coef-custom)
    (when (some #(or (not (map? %)) (not (:cd %)) (not (:ma %))) coef-custom)
      "Every row in :coef-custom should be a map with :cd and :ma keys")))

(defn validate-active-coef-collection [{:keys [bc-type coef-g1 coef-g7 coef-custom]}]
  (let [active-coef (case bc-type
                      :g1 coef-g1
                      :g7 coef-g7
                      :custom coef-custom
                      nil)]
    (when (or (nil? active-coef) (empty? active-coef))
      "The active :coef-* collection must have at least one item")))

(defn validate-bc-not-zero [{:keys [coef-g1 coef-g7]}]
  (when (some #(and (not (zero? (:mv %))) (= 0 (:bc %))) (concat coef-g1 coef-g7))
    "The :bc in :coef-g1 or :coef-g7 can't be 0 unless both :bc and :mv are 0"))

(defn validate-unique-mv-ma [data]
  (let [coef-g1-g7 (concat (:coef-g1 data) (:coef-g7 data))
        coef-custom (:coef-custom data)
        coef-g1-g7-valid (filter #(not (and (or (= 0 (:bc %)) (nil? (:bc %)))
                                             (or (= 0 (:mv %)) (nil? (:mv %))))) coef-g1-g7)
        coef-custom-valid (filter #(not (and (or (= 0 (:cd %)) (nil? (:cd %)))
                                             (or (= 0 (:ma %)) (nil? (:ma %))))) coef-custom)
        mv-ma-values (concat (map :mv coef-g1-g7-valid) (map :ma coef-custom-valid))
        grouped (group-by identity mv-ma-values)]
    (when (some #(> (count (second %)) 1) grouped)
      ":mv for :coef-g1 and :coef-g7, :ma for :coef-custom can't repeat unless 0")))

(defn validate-at-least-one-active-row [{:keys [coef-g1 coef-g7 coef-custom]}]
  (let [all-coef (concat coef-g1 coef-g7 coef-custom)]
    (when (every? #(and (or (= 0 (:bc %)) (nil? (:bc %)))
                        (or (= 0 (:mv %)) (nil? (:mv %)))
                        (or (= 0 (:cd %)) (nil? (:cd %)))
                        (or (= 0 (:ma %)) (nil? (:ma %)))) all-coef)
      "At least one row in :coef-* should have at least one non-zero value")))

(def invariants
  [validate-active-coef-collection
   validate-coef-g1-g7
   validate-coef-custom
   validate-bc-not-zero
   validate-unique-mv-ma
   validate-at-least-one-active-row
   ;; Add more validators here as needed
  ])

(defn process-data [{:keys [profile] :as x}]
  "Logs the received profile data in the worker, checks its invariants, and returns the data as is if all checks pass.
   If any invariant checks fail, returns a map with :err key and a vector of error messages."
  (js/console.log "Received message in worker:\n" (with-out-str (pprint/pprint profile)))
  (let [errors (filterv some? (map #(% profile) invariants))]
    (if (not-empty errors)
      (do
        (js/console.log "Errors:\n" (str errors) "\n")
        {:err errors})
      x)))


(defn transform-and-validate [pre-validate-fn post-validate-fn validate-fn data]
  "Applies transformation before validation and post transformation if valid,
   returns data with :data key or spec validation error with :err key."
  (let [pre-validated-data (pre-validate-fn data)
        valid? (validate-fn pre-validated-data)]
    (if valid?
      {:data (post-validate-fn pre-validated-data)}
      {:err (s/explain-data ::profile/payload pre-validated-data)})))

(defn post-process [reverse-mapping-fn data]
  "Applies reverse mapping and returns processed data, converts keys to camelCase."
  (let [post-processed-data (reverse-mapping-fn data)]
    (if (:err post-processed-data)
      (cljs.core.clj->js post-processed-data)
      (cske/transform-keys csk/->camelCaseKeyword post-processed-data))))

(defn handle-message [mapped-data transform-and-validate-fn process-data-fn
                     post-process-fn]
  "Handles message with given mapped data. Applies transformation,
   validation and post processing, returns JS object to post back."
  (let [{:keys [data err]} (transform-and-validate-fn mapped-data)]
    (if err
      (cljs.core.clj->js err)
      (let [processed-data (process-data-fn data)
            result (post-process-fn processed-data)]
        (cljs.core.clj->js result)))))

(defn handle-event [specific-mapping-fn pre-validate-fn post-validate-fn
                   validate-fn reverse-mapping-fn event]
  "Handles event, applies specific mapping and calls handle-message for
   processing the mapped data."
  (when event
    (let [data (.-data event)
          edn-data (cljs.core/js->clj data :keywordize-keys true)
          kebab-case-data (cske/transform-keys csk/->kebab-case-keyword edn-data)
          mapped-data (specific-mapping-fn kebab-case-data)]
      (if (:err mapped-data)
        (.postMessage js/self (cljs.core/clj->js mapped-data))
        (.postMessage js/self
                      (handle-message
                        mapped-data
                        (partial transform-and-validate
                                 pre-validate-fn post-validate-fn validate-fn)
                        process-data
                        (partial post-process reverse-mapping-fn)))))))
