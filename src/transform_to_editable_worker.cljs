(ns transform-to-editable-worker
  (:require [worker-utils :as wu]
            [profile]
            [cljs.spec.alpha :as s]))

(wu/set-handler
  profile/specific-mapping
  profile/walk-divide
  identity
  (partial s/valid? ::profile/payload)
  profile/reverse-mapping)
