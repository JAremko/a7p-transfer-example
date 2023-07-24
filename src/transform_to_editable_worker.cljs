(ns transform-to-editable-worker
  (:require [cljs.reader :as reader]))

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
