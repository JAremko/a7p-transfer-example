(ns transform-from-editable-worker
  (:require [worker-utils :as wu]
            [profile]
            [cljs.spec.alpha :as s]))

(wu/set-handler
  profile/specific-mapping
  identity
  profile/walk-multiply
  (partial s/valid? ::profile/payload)
  profile/reverse-mapping)
