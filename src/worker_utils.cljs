(ns worker-utils
  (:require [profile]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [cljs.pprint :as pprint]
            [camel-snake-kebab.extras :as cske]))

(defn process-data [x]
  "Logs the message received in worker and returns the data as is."
  (js/console.log "Received message in worker:\n"
                  (with-out-str (pprint/pprint x)))
  x)

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
