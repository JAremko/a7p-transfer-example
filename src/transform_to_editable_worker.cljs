(ns transform-to-editable-worker)

(defn identity [x]
  x)

(defn on-message [event]
  (js/console.log "Received message in worker: " (.-data event)) ;; added logging
  (let [data (.-data event)
        result (identity data)]
    (self.postMessage result)))

(set! (.-onmessage js/self) on-message)
