(ns transform-to-editable-worker
  (:require [profile]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.walk :as walk]))

(defn process-data [x]
  #_ (js/console.log "Received message in worker: " (str x))
  x)

(defn on-message [event]
  (when event
    (let [data (.-data event)
          edn-data (cljs.core/js->clj data :keywordize-keys true) ;; Convert JavaScript object to ClojureScript data structure with keywordized keys
          kebab-case-data (cske/transform-keys csk/->kebab-case-keyword edn-data) ;; Convert keys to kebab-case
          mapped-data (profile/specific-mapping kebab-case-data) ;; Apply specific mappings
          normalized-data (profile/walk-divide mapped-data)
          valid? (s/valid? ::profile/payload normalized-data)]
      (if valid?
        (let [processed-data (process-data normalized-data) ;; Process the data
              post-processed-data (profile/reverse-mapping processed-data) ;; Apply reverse mappings to processed data
              camelCase-data (cske/transform-keys csk/->camelCaseKeyword post-processed-data) ;; Convert keys back to camelCase
              result (cljs.core/clj->js camelCase-data)] ;; Convert the processed data back to a JavaScript object
          (.postMessage js/self result))
        (let [report (s/explain-data ::profile/payload normalized-data)
              result (cljs.core/clj->js report)]
          (.postMessage js/self result))))))

(set! (.-onmessage js/self) on-message)
