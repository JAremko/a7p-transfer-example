(ns worker-utils
  (:require [profile]
            [cljs.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]))

(defn validate-and-transform
  "Validates and transforms data. Returns an error report if validation fails."
  [data process-fn]
  (let [valid? (s/valid? ::profile/payload data)]
    (if valid?
      (cljs.core/clj->js (cske/transform-keys csk/->camelCaseKeyword
                     (profile/reverse-mapping (process-fn data))))
      (cljs.core/clj->js (s/explain-data ::profile/payload data)))))

(defn handle-message
  "Handles incoming event messages, applying specific mappings and transformations."
  [event process-fn]
  (when event
    (let [data (-> event .-data (cljs.core/js->clj :keywordize-keys true))]
      (.postMessage js/self
        (if-let [err (:err (profile/specific-mapping data))]
          (cljs.core/clj->js err)
          (validate-and-transform (cske/transform-keys csk/->kebab-case-keyword data)
                                  process-fn))))))
