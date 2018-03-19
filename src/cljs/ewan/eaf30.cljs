(ns ewan.eaf30
  (:require [cljs.spec.alpha :as s]
            [clojure.data.xml :as xml]
            [cljs-time.format :as timefmt]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string])
  (:require-macros [cljs.spec.alpha :as s]))

;; ----------------------------------------------------------------------------
;; Conversion functions
;; ----------------------------------------------------------------------------
;; Internally, we will be using a Hiccup-like representation of EAF as it is
;; losslessly serializable to JSON. Whenever we need to generate EAF for the
;; user, we will use hiccup->xml, and whenever we need to ingest EAF, we will
;; use xml->hiccup. Note that unlike normal Hiccup, non-terminal nodes in this
;; representation MUST have a map present for attributes, even if it is empty,
;; to align with the conventions of clojure.data.xml.

(defn- snake->kebab
  [kwd]
  (-> kwd
      name
      (clojure.string/lower-case)
      (clojure.string/replace #"_" "-")
      keyword))

(defn- kebab->snake
  [kwd]
  (-> kwd
      name
      (clojure.string/upper-case)
      (clojure.string/replace #"-" "_")
      keyword))

(defn- xml->hiccup
  "Take EAF XML generated by clojure.data.xml and generate equivalent hiccup.
   For tags and attribute names, which data.xml turns into keywords, it also
   converts their names from :CAPS_SNAKE_CASE into :kebab-case"
  [node]
  (if-not (xml/element? node)
    node
    (let [tag (snake->kebab (:tag node))
          attrs (into {} (map (fn [[k v]] [(snake->kebab k) v])
                              (:attrs node)))
          content (map xml->hiccup (:content node))]
      (into [tag attrs] content))))

(defn- hiccup->xml
  "Take the Hiccup-like XML representation used internally by ewan and turn it
  back into XML in anticipation of serialization. Reverts :kebab-case tags into
  :CAPS_SNAKE_CASE."
  [hiccup]
  (if-not (vector? hiccup)
    hiccup
    (let [tag (kebab->snake (first hiccup))
          attrs (into {} (map (fn [[k v]] [(kebab->snake k) v])
                              (second hiccup)))
          content (map hiccup->xml (drop 2 hiccup))]
      (apply xml/element (into [tag attrs] content)))))


;; ----------------------------------------------------------------------------
;; EAF 3.0 spec
;; ----------------------------------------------------------------------------
;; This is as near a translation as possible of the EAF 3.0 format as described
;; by its schema:
;;    - http://www.mpi.nl/tools/elan/EAFv3.0.xsd
;;    - http://www.mpi.nl/tools/elan/EAF_Annotation_Format_3.0_and_ELAN.pdf
;; A number will be given before each element which corresponds to the section
;; in the PDF guide that describes it.

;; 2.1 annotation-document
;; --------------------------------------------
(s/def ::author string?)
(s/def ::date #(some? (timefmt/parse %)))
(s/def ::version string?)
(s/def ::format string?)

(s/def ::annotation-document
  (s/cat
   :tag   #(= % :annotation-document)
   :attrs (s/and
           (s/keys :req-un [::author ::date ::version]
                   :opt-un [::format])
           ;; if present, format must match version
           #(if (:format %)
              (= (:format %) (:version %))
              true))
   :license (s/spec (s/* ::license)))) ;; 2.2


;; 2.2 license
;; --------------------------------------------
(s/def ::license (s/cat :tag #(= % :license)
                        :attrs (s/keys :opt-un [::license-url])
                        :contents string?))
(s/def ::license-url string?)

;; 2.3 header
;; --------------------------------------------








(def sample-xml (xml/parse-str
"<ANNOTATION_DOCUMENT AUTHOR=\"jimbob\" DATE=\"2002-05-30T09:30:10.5\" VERSION=\"3.0\" FORMAT=\"3.0\"><LICENSE>GPL</LICENSE></ANNOTATION_DOCUMENT>"))
(def hiccup (xml->hiccup sample-xml))

(= (hiccup->xml hiccup)
   sample-xml)

(s/valid? ::annotation-document hiccup)
(s/explain ::annotation-document hiccup)

