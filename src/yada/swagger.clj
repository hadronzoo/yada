;; Copyright © 2015, JUXT LTD.

(ns yada.swagger
  (:require
   [bidi.bidi :refer (Matched resolve-handler unresolve-handler route-seq succeed unmatch-pair)]
   [bidi.ring :refer (Ring request make-handler)]
   [camel-snake-kebab :as csk]
   [cheshire.core :as json]
   [clj-time.coerce :refer (to-date)]
   [clj-time.core :refer (now)]
   [clojure.pprint :refer (pprint)]
   [clojure.tools.logging :refer :all]
   [hiccup.page :refer (html5)]
   [json-html.core :as jh]
   [ring.swagger.swagger2 :as rs]
   [ring.util.response :refer (redirect)]
   [schema.core :as s]
   [yada.charset :as charset]
   [yada.methods :refer (Get GET)]
   [yada.media-type :as mt]
   [yada.protocols :as p]
   [yada.core :as yada]
   [yada.util :refer (md5-hash)])
  (:import (clojure.lang PersistentVector Keyword)))

(defprotocol SwaggerPath
  (encode [_] "Format route as a swagger path"))

(extend-protocol SwaggerPath
  String (encode [s] s)
  PersistentVector (encode [v] (apply str (map encode v)))
  Keyword (encode [k] (str "{" (name k) "}")))

(defn to-path [route]
  (let [path (->> route :path (map encode) (apply str))
        handler (-> route :handler)
        {:keys [resource options allowed-methods parameters representations]} handler
        swagger (:swagger options)]
    [path
     (merge-with merge
                 (into {}
                       (for [method allowed-methods
                             :let [parameters (get parameters method)
                                   representations (filter (fn [rep] (or (nil? (:method rep))
                                                                        (contains? (:method rep) method))) representations)
                                   produces (when (#{:get} method)
                                              (distinct (map :name (map :media-type representations))))]]
                         ;; Responses must be added in the static swagger section
                         {method (merge (when produces {:produces produces})
                                        {:parameters parameters})}))
                 swagger)]))

(defrecord SwaggerSpec [spec created-at content-type]
  p/Properties
  (properties
    [_]
    {:representations
     (case content-type
       "application/json" [{:media-type #{"application/json"
                                          "application/json;pretty=true"}
                            :charset #{"UTF-8" "UTF-16;q=0.9" "UTF-32;q=0.9"}}]
       "text/html" [{:media-type "text/html"
                     :charset charset/platform-charsets}]
       "application/edn" [{:media-type #{"application/edn"
                                         "application/edn;pretty=true"}
                           :charset #{"UTF-8"}}])})
  (properties [_ ctx]
     {:last-modified created-at
      :version spec})

  Get
  (GET [_ ctx] (rs/swagger-json spec)))

(defrecord Swaggered [spec route spec-handlers]
  Matched
  (resolve-handler [this m]
    (cond (= (:remainder m) (str (or (:base-path spec) "") "/swagger.json"))
          ;; Return this, which satisfies Ring.
          ;; Truncate :remainder to ensure succeed actually succeeds.
          (succeed this (assoc m :remainder "" :type "application/json"))

          (= (:remainder m) (str (or (:base-path spec) "") "/swagger.edn"))
          (succeed this (assoc m :remainder "" :type "application/edn"))

          (= (:remainder m) (str (or (:base-path spec) "") "/swagger.html"))
          (succeed this (assoc m :remainder "" :type "text/html"))

          ;; Redirect to swagger.json
          (= (:remainder m) (str (or (:base-path spec) "") "/"))
          (succeed (reify Ring (request [_ req _] (redirect (str (:uri req) "swagger.json"))))
                   (assoc m :remainder ""))

          ;; Otherwise
          :otherwise (resolve-handler [[(or (:base-path spec) "") [route]]]
                                      (merge m {::spec spec}))))

  (unresolve-handler [this m]
    (if (= this (:handler m))
      (or (:base-path spec) "")
      (unmatch-pair route m)))

  Ring
  (request [_ req match-context]
    (if-let [h (get spec-handlers (:type match-context))]
      (h req)
      (throw (ex-info "Error: unknown type" {:match-context match-context}))))

  ;; So that we can use the Swaggered record as a Ring handler
  clojure.lang.IFn
  (invoke [this req]
    (let [handler (make-handler ["" this])]
      (handler req))))

(defn swaggered [spec route]
  (let [spec (merge spec {:paths (into {} (map to-path (route-seq route)))})
        modified-date (to-date (now))]
    (->Swaggered spec route
                 (into {} (for [ct ["application/edn" "application/json" "text/html"]]
                            [ct (yada/resource (->SwaggerSpec spec modified-date ct))])))))
