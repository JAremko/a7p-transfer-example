(ns transform-from-editable-worker
  (:require [worker-utils :as wu]
            [profile]
            [cljs.spec.alpha :as s]))

(defn on-message
  [event]
  (wu/handle-event
   profile/specific-mapping
   identity
   profile/walk-multiply
   (partial s/valid? ::profile/payload)
   profile/reverse-mapping
   event))

(set! (.-onmessage js/self) on-message)
