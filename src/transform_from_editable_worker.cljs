(ns transform-from-editable-worker)

(defn identity-xf [x]
  x)

(defn on-message [event]
  (when event
    (js/console.log "Received message in worker: " (.-data event)) ;; added logging
    (let [data (.-data event)
          result (identity-xf data)]
      (.postMessage js/self result))))

(set! (.-onmessage js/self) on-message)
