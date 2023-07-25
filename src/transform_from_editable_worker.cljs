(ns transform-from-editable-worker
  (:require [profile]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.walk :as walk]))

(defn process-data [x]
   (js/console.log "Received message in worker: " (str x))
  x)

(defn on-message [event]
  (when event
    (let [data (.-data event)
          edn-data (cljs.core/js->clj data :keywordize-keys true) ;; Convert JavaScript object to ClojureScript data structure with keywordized keys
          kebab-case-data (cske/transform-keys csk/->kebab-case-keyword edn-data) ;; Convert keys to kebab-case
          mapped-data (profile/specific-mapping kebab-case-data)] ;; Apply specific mappings
      (if (:err mapped-data)
        (.postMessage js/self (cljs.core/clj->js mapped-data))
        (let [valid? (s/valid? ::profile/payload mapped-data)] ;; Validate before walk-multiply
          (if valid?
            (let [denormalized-data (profile/walk-multiply mapped-data) ;; Apply walk-multiply only on valid data
                  processed-data (process-data denormalized-data) ;; Process the data
                  post-processed-data (profile/reverse-mapping processed-data)] ;; Apply reverse mappings to processed data
              (if (:err post-processed-data)
                (.postMessage js/self (cljs.core/clj->js post-processed-data))
                (let [camelCase-data (cske/transform-keys csk/->camelCaseKeyword post-processed-data) ;; Convert keys back to camelCase
                      result (cljs.core/clj->js camelCase-data)] ;; Convert the processed data back to a JavaScript object
                  (.postMessage js/self result))))
            (let [report (s/explain-data ::profile/payload mapped-data) ;; Generate report on invalid data
                  result (cljs.core/clj->js report)]
              (.postMessage js/self result))))))))

(set! (.-onmessage js/self) on-message)
