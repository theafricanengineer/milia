(ns milia.api.io
  (:import [goog.net.EventType]
           [goog.net XhrIo]
           [goog.net IframeIo])
  (:require [chimera.seq :refer [in?]]
            [chimera.string :refer [is-not-null?]]
            [cljs.core.async :refer [<! put! chan]]
            [cljs-hash.md5  :refer [md5]]
            [cljs-http.client :as http]
            [cljs-http.core :as http-core]
            [clojure.set :refer [rename-keys]]
            [clojure.string :refer [join split blank?]]
            [goog.net.cookies :as cookies]
            [goog.events :as gev]
            [milia.utils.remote :refer [*credentials* hosts bad-token-msgs]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn build-http-options
  "Build http-options based on arguments."
  [http-options method no-cache?]
  (let [stateful-method? (in? [:post :put :patch] method)
        ;; With credentials always false
        http-options (assoc http-options :with-credentials? false)]
    ;; if JSON Params and stateful do not alter
    (if (and (:json-params http-options) stateful-method?)
      http-options
      (let [param-key (if stateful-method? :form-params :query-params)
            options+xhr (assoc-in http-options [param-key :xhr] true)]
        (if (and no-cache? (not stateful-method?))
          (assoc-in options+xhr [param-key :t] (-> js/Date .now str md5))
          options+xhr)))))

(def raw-request
  "An almost 'batteries-included' request, similar to cljs-http.client/request.
   Contains everything except response decoding."
  (-> http-core/request
      http/wrap-accept
      http/wrap-form-params
      http/wrap-content-type
      http/wrap-json-params
      http/wrap-edn-params
      http/wrap-query-params
      http/wrap-basic-auth
      http/wrap-oauth
      http/wrap-method
      http/wrap-url))

(defn token->headers
  "Builds request headers for the HTTP request by adding
  Authorization, X-CSRFToken and Cache-control headers where necessary"
  [& {:keys [get-crsftoken? must-revalidate? accept-header auth-token]}]
  (let [temp-token (:temp-token *credentials*)]
    (into {} [(if auth-token
                ["Authorization" (str "Token " auth-token)]
                (when (and (not-empty temp-token) (is-not-null?
                                                   temp-token))
                  ["Authorization" (str "TempToken " temp-token)]))
              (when must-revalidate?
                ["Cache-control" "must-revalidate"])
              (when-let [crsf-token (and get-crsftoken?
                                         (cookies/get "csrftoken"))]
                ["X-CSRFToken" crsf-token]
                ["X-CSRF-Token" crsf-token])
              ["Accept" (or accept-header "application/json")]])))

(defn get-xhr-io-response
  [io-obj & [{:keys [require-json?]}]]
  (try
    (.getResponseJson io-obj)
    (catch js/Error _
      (let [text-response (.getResponseText io-obj)]
        (if require-json? {:error text-response} text-response)))))

(defn upload-via-iframe
  [form form-api event-chan]
  (let [io-obj (IframeIo.)]
    (gev/listen io-obj
                (.-SUCCESS goog.net.EventType)
                #(put! event-chan {:data (.getResponseText io-obj)}))
    (gev/listen io-obj
                (.-ERROR goog.net.EventType)
                #(put! event-chan {:data (.getResponseText io-obj)}))
    (.sendFromForm io-obj form
                   (str form-api "?legacy=true"))))

(defn upload-file
  "Use goog.net.XhrIo to upload file. Receives an HTML form object,
  a core.async channel where result message will be put
  and (optionally) an id to include in the result message. Returns the
  XhrIo object that can be used to abort request. More XhrIo API
  docs at: https://goo.gl/B0fm2a"
  [form chan & {:keys [headers id require-json?] :or {:require-json true}}]
  (let [io-obj (XhrIo.)
        data   (when id {:id id})
        url    (.-action form)]
    (.setProgressEventsEnabled io-obj true)
    ;; event handlers
    (gev/listen io-obj goog.net.EventType.SUCCESS
                #(put! chan (assoc data
                                   :data (get-xhr-io-response io-obj
                                                              require-json?)
                                   :success? true)))
    (gev/listen io-obj goog.net.EventType.ERROR
                #(put! chan (assoc data
                                   :data (get-xhr-io-response io-obj
                                                              require-json?)
                                   :success? false)))
    (gev/listen io-obj goog.net.EventType.PROGRESS
                #(put! chan (assoc data :progress
                                   {:length-computable (.-lengthComputable %)
                                    :loaded            (.-loaded %)
                                    :total             (.-total %)})))
    ;; make the requests
    (.send io-obj url "POST" form headers)
    io-obj))

(defn http-request
  "Wraps cljs-http.client/request and redirects if status is 401"
  [request-fn & args]
  (let [response-channel (chan)]
    (go
      (let [original-response-channel (apply request-fn args)
            {:keys [status] :as response} (<! original-response-channel)]
        (if (= status 401)
          (set! js/window.location js/window.location)
          (put! response-channel response))))
    response-channel))
