(ns worker-utils
  (:require [profile]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [cljs.spec.alpha :as s]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]))

(defn process-data [x]
   (js/console.log "Received message in worker: " (str x))
  x)

(defn transform-and-validate [pre-validate-fn post-validate-fn validate-fn data]
  (let [pre-validated-data (pre-validate-fn data)
        valid? (validate-fn pre-validated-data)]
    (if valid?
      {:data (post-validate-fn pre-validated-data)}
      {:err (s/explain-data ::profile/payload pre-validated-data)})))

(defn post-process [reverse-mapping-fn data]
  (let [post-processed-data (reverse-mapping-fn data)]
    (if (:err post-processed-data)
      (cljs.core.clj->js post-processed-data)
      (cske/transform-keys csk/->camelCaseKeyword post-processed-data))))

(defn handle-event [specific-mapping-fn pre-validate-fn post-validate-fn validate-fn reverse-mapping-fn event]
  (when event
    (let [data (.-data event)
          edn-data (cljs.core/js->clj data :keywordize-keys true)
          kebab-case-data (cske/transform-keys csk/->kebab-case-keyword edn-data)
          mapped-data (specific-mapping-fn kebab-case-data)]
      (if (:err mapped-data)
        (.postMessage js/self (cljs.core/clj->js mapped-data))
        (let [{:keys [data err]} (transform-and-validate pre-validate-fn post-validate-fn validate-fn mapped-data)]
          (if err
            (.postMessage js/self (cljs.core.clj->js err))
            (let [processed-data (process-data data)
                  result (post-process reverse-mapping-fn processed-data)]
              (.postMessage js/self (cljs.core/clj->js result)))))))))

(defn set-handler [specific-mapping-fn pre-validate-fn post-validate-fn validate-fn reverse-mapping-fn]
  (set! (.-onmessage js/self) (partial handle-event specific-mapping-fn pre-validate-fn post-validate-fn validate-fn reverse-mapping-fn)))
