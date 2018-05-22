(ns ewan.spec.eaf30
  (:require [cljs.spec.alpha :as s]
            [clojure.data.xml :as xml]
            [cljs-time.format :as timefmt]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.zip :as z])
  (:require-macros [cljs.spec.alpha :as s]
                   [ewan.spec.eaf30 :refer [defzipfn
                                            defzipfn-]]))

;; ----------------------------------------------------------------------------
;; Conversion functions
;; ----------------------------------------------------------------------------
;; Internally, we will be using a Hiccup-like representation of EAF as it is
;; losslessly serializable to JSON. Whenever we need to generate EAF for the
;; user, we will use hiccup->eaf-str, and whenever we need to ingest EAF, we
;; will use eaf-str->hiccup. Note that unlike normal Hiccup, non-terminal nodes
;; in this representation MUST have a map present for attributes, even if it
;; is empty, to align with the conventions of clojure.data.xml.
;;
;; NOTE 1: the XSD schema has many types that are more specific than what
;; we will specify here. E.g., URL's and numbers in the original XSD schema
;; are treated here as strings. Ideally we'd be more specific, but it's not
;; worth my time at the moment since it's unlikely these  would hold corrupt
;; values anyway in most use-cases. (But of course, it'd be good to have)

(defn- snake->kebab
  [kwd]
  (-> kwd
      name
      (string/lower-case)
      (string/replace #"_" "-")
      keyword))

(defn- kebab->snake
  [kwd]
  (-> kwd
      name
      (string/upper-case)
      (string/replace #"-" "_")
      keyword))

(defn- xml->hiccup
  "Take EAF XML generated by clojure.data.xml and generate equivalent hiccup.
   For tags and attribute names, which data.xml turns into keywords, it also
   converts their names from :CAPS_SNAKE_CASE into :kebab-case"
  [node]
  (if-not (xml/element? node)
    node
    (let [tag (snake->kebab (:tag node))
          attrs (->> (:attrs node)
                     ;; filter out this attr--see note below under EAF 3.0 spec
                     (filter (fn [[k _]] (not= (name k)
                                              "noNamespaceSchemaLocation")))
                     (map (fn [[k v]] [(snake->kebab k) v]))
                     (into {}))
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
          attrs (->> (second hiccup)
                     (map (fn [[k v]] [(kebab->snake k) v]))
                     (into {}))
          content (map hiccup->xml (drop 2 hiccup))]
      (apply xml/element (into [tag attrs] content)))))

;; NOTE: this is NOT a correct solution. For one thing, XML is a context-free
;; language while we're attempting to parse it using a regular expression.
;; One possible error this could introduce:  it gets rid of the content of any
;; inner content that might match /\s+/, such as `<p>     </p>`.
;; Ideally this would be handled using an XSLT transform, but I'm not sure how
;; to do that conveniently.
;; Cf: https://stackoverflow.com/questions/10549290/what-would-be-the-regular-expression-to-remove-whitespaces-between-tags-only-in?noredirect=1&lq=1
(defn- unsafe-remove-whitespace
  [str]
  (clojure.string/replace str #">\s+<" "><"))

(defn- add-annotation-document-attrs
  "A hack necessary to restore attributes on the XML string since
  clojure.data.xml was too inconvenient to work with. Takes something like

      <?xml version=\"1.0\" encoding=\"UTF-8\"?>
      <ANNOTATION_DOCUMENT [...]>

  and turns it into

      <?xml version=\"1.0\" encoding=\"UTF-8\"?>
      <ANNOTATION_DOCUMENT [...]
          xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
          xsi:noNamespaceSchemaLocation=\"http://www.mpi.nl/tools/elan/EAFv2.8.xsd\">"
  [xml-str]
  (string/replace-first
   xml-str
   "<ANNOTATION_DOCUMENT "
   "<ANNOTATION_DOCUMENT xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"http://www.mpi.nl/tools/elan/EAFv2.8.xsd\" "))


;; ----------------------------------------------------------------------------
;; EAF 3.0 spec
;; ----------------------------------------------------------------------------
;; This is as near a translation as possible of the EAF 3.0 format as described
;; by its schema:
;;    - http://www.mpi.nl/tools/elan/EAFv3.0.xsd
;;    - http://www.mpi.nl/tools/elan/EAF_Annotation_Format_3.0_and_ELAN.pdf
;; A number will be given before each element which corresponds to the section
;; in the PDF guide that describes it.
;;
;; One major difference is that the ANNOTATION_TAG element does NOT have the
;; `xmlns:xsi` and `xsi:noNamespaceSchemaLocation` tags because the CLJS
;; implementation of `clojure.data.xml` was being too helpful. Instead,
;; we will just make sure to strip these attrs out when we encounter them
;; and make sure to write them back when we write back to an XML string.
;;
;; Note that we've had to give the definitions in a different order because of
;; how spec expects all specs used in a dependent spec to be defined before
;; reference.
;;

;; 2.2 license
;; --------------------------------------------
(s/def ::license-url string?)
(s/def ::license (s/cat :tag #(= % :license)
                        :attrs (s/keys :opt-un [::license-url])
                        :contents string?))

;; 2.3 header
;; --------------------------------------------
;; 2.3.1 media descriptor
(s/def ::media-url string?)
(s/def ::mime-type string?)
(s/def ::relative-media-url string?)
(s/def ::time-origin string?) ;; this should be a number, see Note 1 at top
(s/def ::extracted-from string?)
(s/def ::media-descriptor
  (s/cat :tag #(= % :media-descriptor)
         :attrs (s/keys :req-un [::media-url
                                 ::mime-type]
                        :opt-un [::relative-media-url
                                 ::time-origin
                                 ::extracted-from])))

;; 2.3.2 linked file descriptor
;; mime-type and time-origin defined in 2.3.1
(s/def ::link-url string?)
(s/def ::relative-link-url string?)
(s/def ::associated-with string?)
(s/def ::linked-file-descriptor
  (s/cat :tag #(= % :linked-file-descriptor)
         :attrs (s/keys :req-un [::link-url
                                 ::mime-type]
                        :opt-un [::relative-link-url
                                 ::time-origin
                                 ::associated-with])))

;; 2.3.3 properties
(s/def ::name string?)
(s/def ::property
  (s/cat :tag #(= % :property)
         :attrs (s/keys :opt-un [::name])
         :content string?))

(s/def ::media-file string?)
(s/def ::time-units #{"milliseconds" "PAL-frames" "NTSC-frames"})
(s/def ::header
  (s/cat :tag #(= % :header)
         :attrs (s/keys :opt-un [::media-file ::time-units])
         :media-descriptors (s/* (s/spec ::media-descriptor))
         :linked-file-descriptors (s/* (s/spec ::linked-file-descriptor))
         :properties (s/* (s/spec ::property))))


;; 2.4 time order
;; --------------------------------------------
;; 2.4.1 time slots
(s/def ::time-slot-id string?)
;; this should actually ensure that TIME_VALUE holds a non-negative
;; integer. See Note 1 at top
(s/def ::time-value string?)
(s/def ::time-slot
  (s/cat :tag #(= % :time-slot)
         :attrs (s/keys :req-un [::time-slot-id] :opt-un [::time-value])))

(s/def ::time-order
  (s/cat :tag #(= % :time-order)
         :attrs map?
         :time-slots (s/* (s/spec ::time-slot))))



;; 2.5 tier
;; --------------------------------------------
;; 2.5.2 alignable annotation
(s/def ::annotation-id string?) ;; next 4 from 2.5.5--also used in 2.5.3
(s/def ::ext-ref string?)
(s/def ::lang-ref string?)
(s/def ::cve-ref string?)
(s/def ::time-slot-ref1 string?)
(s/def ::time-slot-ref2 string?)
(s/def ::svg-ref string?)

;; from 2.5.4--also used in 2.5.3
(s/def ::annotation-value
  (s/cat :tag #(= % :annotation-value)
         :attrs map?
         :contents string?))

(s/def ::alignable-annotation
  (s/cat :tag #(= % :alignable-annotation)
         :attrs (s/keys :req-un [::annotation-id
                                 ::time-slot-ref1
                                 ::time-slot-ref2]
                        :opt-un [::svg-ref
                                 ::ext-ref
                                 ::lang-ref
                                 ::cve-ref])
         :annotation-value (s/spec ::annotation-value)))

;; 2.5.3 ref annotation
(s/def ::annotation-ref string?)
(s/def ::previous-annotation string?)
(s/def ::ref-annotation
  (s/cat :tag #(= % :ref-annotation)
         :attrs (s/keys :req-un [::annotation-id
                                 ::annotation-ref]
                        :opt-un [::previous-annotation
                                 ::ext-ref
                                 ::lang-ref
                                 ::cve-ref])
         :annotation-value (s/spec ::annotation-value)))

;; 2.5.1 annotation
(s/def ::annotation
  (s/cat :tag #(= % :annotation)
         :attrs map?
         :child (s/alt :alignable-annotation
                       (s/spec ::alignable-annotation)
                       :ref-annotation
                       (s/spec ::ref-annotation))))

(s/def ::tier-id string?)
(s/def ::participant string?)
(s/def ::annotator string?)
(s/def ::linguistic-type-ref string?)
(s/def ::default-locale string?)
(s/def ::parent-ref string?)
(s/def ::ext-ref string?)
(s/def ::tier
  (s/cat :tag #(= % :tier)
         :attrs (s/keys :req-un [::tier-id
                                 ::linguistic-type-ref]
                        :opt-un [::participant
                                 ::annotator
                                 ::default-locale
                                 ::parent-ref
                                 ::ext-ref
                                 ::lang-ref]) ;; already defined in 2.5.2
         :annotations (s/* (s/spec ::annotation))))

;; 2.6 linguistic type
;; --------------------------------------------
(s/def ::linguistic-type-id string?)
(s/def ::time-alignable #{"true" "false"})
(s/def ::constraints string?)
(s/def ::graphic-references #{"true" "false"})
(s/def ::controlled-vocabulary-ref string?)
(s/def ::lexicon-ref string?)
(s/def ::linguistic-type
  (s/cat :tag #(= % :linguistic-type)
         :attrs (s/keys :req-un [::linguistic-type-id]
                        :opt-un [::time-alignable
                                 ::constraints
                                 ::graphic-references
                                 ::controlled-vocabulary-ref
                                 ::ext-ref
                                 ::lexicon-ref])))
;; ext-ref is defined above in 2.5.1. There is a slight semantic difference
;; here since 2.5.1's ext ref allows multiple refs, but since we're only
;; checking if it's a string in this spec, it doesn't matter.

;; 2.7 constraint
;; --------------------------------------------
(s/def ::stereotype #{"Time_Subdivision" "Symbolic_Subdivision"
                      "Symbolic_Association" "Included_In"})
(s/def ::description string?)
(s/def ::constraint
  (s/cat :tag #(= % :constraint)
         :attrs (s/keys :req-un [::stereotype]
                        :opt-un [::description])))


;; 2.9 controlled vocabulary
;; --------------------------------------------

;; 2.9.2 cve value
(s/def ::cve-value
  (s/cat :tag #(= % :cve-value)
         :attrs (s/keys :req-un [::lang-ref] ;; already defined in 2.5.2
                        :opt-un [::description]) ;; 2.7
         :contents string?))

;; 2.9.1 cv entry ml
(s/def ::cve-id string?)
(s/def ::cv-entry-ml
  (s/cat :tag #(= % :cv-entry-ml)
         :attrs (s/keys :req-un [::cve-id]
                        :opt-un [::ext-ref]) ;; defined in 2.5.1
         :values (s/+ (s/spec ::cve-value))))

;; 2.9.3 description
;; tag is actually called DESCRIPTION, but there is a collision with 2.7
(s/def ::cv-description
  (s/cat :tag #(= % :description)
         :attrs (s/keys :req-un [::lang-ref]) ;; already defined in 2.5.2
         :contents string?))

(s/def ::cv-id string?)
(s/def ::controlled-vocabulary
  (s/cat :tag  #(= % :controlled-vocabulary)
         :attrs (s/keys :req-un [::cv-id]
                        :opt-un [::ext-ref]) ;; defined in 2.5.1
         :descriptions (s/* (s/spec ::cv-description))
         :cv-entry-ml (s/* (s/spec ::cv-entry-ml))
         ))

;; 2.10 external ref
;; --------------------------------------------
(s/def ::ext-ref-id string?)
(s/def ::type #{"iso12620" "ecv" "cve_id" "lexen_id" "resource_url"})
(s/def ::value string?)
(s/def ::external-ref
  (s/cat :tag #(= % :external-ref)
         :attrs (s/keys ::req-un [::ext-ref-id
                                  ::type
                                  ::value])))

;; 2.11 locale
;; --------------------------------------------
(s/def ::language-code string?)
(s/def ::country-code string?)
(s/def ::variant string?)
(s/def ::locale
  (s/cat :tag #(= % :locale)
         :attrs (s/keys ::req-un [::language-code]
                        ::opt-un [::country-code
                                  ::variant])))

;; 2.12 language
;; --------------------------------------------
(s/def ::lang-id string?)
(s/def ::lang-def string?)
(s/def ::lang-label string?)
(s/def ::language
  (s/cat :tag #(= % :language)
         :attrs (s/keys ::req-un [::lang-id]
                        ::opt-un [::lang-def
                                  ::lang-label])))

;; 2.13 lexicon ref
;; --------------------------------------------
;; some of these have previous collisions, so just namespace them all
(s/def :ewan.eaf30.lexicon-ref/lex-ref-id string?)
(s/def :ewan.eaf30.lexicon-ref/name string?)
(s/def :ewan.eaf30.lexicon-ref/type string?)
(s/def :ewan.eaf30.lexicon-ref/url string?)
(s/def :ewan.eaf30.lexicon-ref/lexicon-id string?)
(s/def :ewan.eaf30.lexicon-ref/lexicon-name string?)
(s/def :ewan.eaf30.lexicon-ref/datcat-id string?)
(s/def :ewan.eaf30.lexicon-ref/datcat-name string?)
(s/def ::lexicon-ref
  (s/cat :tag #(= % :lexicon-ref)
         :attrs (s/keys ::req-un [:ewan.eaf30.lexicon-ref/lex-ref-id
                                  :ewan.eaf30.lexicon-ref/name
                                  :ewan.eaf30.lexicon-ref/type
                                  :ewan.eaf30.lexicon-ref/url
                                  :ewan.eaf30.lexicon-ref/lexicon-id
                                  :ewan.eaf30.lexicon-ref/lexicon-name]
                        ::opt-un [:ewan.eaf30.lexicon-ref/datcat-id
                                  :ewan.eaf30.lexicon-ref/datcat-name])))

;; 2.14 ref link set
;; --------------------------------------------
;; 2.14.3 refLinkAttribute
(s/def ::ref-link-id string?)
(s/def ::ref-link-name string?)
;; ext-ref, lang-ref, cve-ref already defined in 2.5
(s/def ::ref-type string?)

;; 2.14.1 cross ref link
(s/def ::ref1 string?)
(s/def ::ref2 string?)
(s/def ::directionality #{"undirected" "unidirectional" "bidirectional"})
(s/def ::cross-ref-link
  (s/cat :tag #(= % :cross-ref-link)
         :attrs (s/keys ::req-un [::ref1
                                  ::ref2
                                  ::ref-link-id]
                        ::opt-un [::directionality
                                  ::ref-link-name
                                  ::ext-ref
                                  ::lang-ref
                                  ::cve-ref
                                  ::ref-type])))

;; 2.14.2 group ref link
(s/def ::refs string?)
(s/def ::group-ref-link
  (s/cat :tag #(= % :group-ref-link)
         :attrs (s/keys ::req-un [::refs
                                  ::ref-link-id]
                        ::opt-un [::ref-link-name
                                  ::ext-ref
                                  ::lang-ref
                                  ::cve-ref
                                  ::ref-type])))

(s/def ::link-set-id string?)
(s/def ::link-set-name string?)
(s/def ::cv-ref string?)
(s/def ::ref-link-set
  (s/cat :tag #(= % :ref-link-set)
         :attrs (s/keys ::req-un [::link-set-id]
                        ::opt-un [::link-set-name
                                  ::ext-ref       ;; from 2.5
                                  ::lang-ref      ;; from 2.5
                                  ::cv-ref])
         :links (s/* (s/alt :cross-ref-link
                            (s/spec ::cross-ref-link)
                            :group-ref-link
                            (s/spec ::group-ref-link)))))


;; 2.1 annotation document
;; --------------------------------------------
(s/def ::author string?)
(s/def ::date #(some? (timefmt/parse %)))
(s/def ::version string?)
(s/def ::format string?)
;; Note that the order of the seq in the XSD is NOT the same as that of
;; the section numbers in the explanatory document
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
   :license (s/* (s/spec ::license)) ;; 2.2
   :header (s/spec ::header) ;; 2.3
   :time-order (s/spec ::time-order) ;; 2.4
   :tiers (s/* (s/spec ::tier)) ;; 2.5
   :linguistic-type (s/* (s/spec ::linguistic-type)) ;; 2.6
   :locale (s/* (s/spec ::locale)) ;; 2.11
   :language (s/* (s/spec ::language)) ;; 2.12
   :constraints (s/* (s/spec ::constraint)) ;; 2.7
   :controlled-vocabulary (s/* (s/spec ::controlled-vocabulary)) ;; 2.9
   :lexicon-ref (s/* (s/spec ::lexicon-ref)) ;; 2.13
   :ref-link-set (s/* (s/spec ::ref-link-set)) ;; 2.14
   :external-ref (s/* (s/spec ::external-ref)) ;; 2.10
   ))

;; ----------------------------------------------------------------------------
;; Public API
;; ----------------------------------------------------------------------------
;; (Refer to the conversion functions at the beginning of this file.)

(defn eaf-str->hiccup
  "Takes the raw text of an EAF file, parses it into XML, and gives the hiccup
  analog of that XML."
  [str]
  (-> str
      unsafe-remove-whitespace
      xml/parse-str
      xml->hiccup))

(defn hiccup->eaf-str
  "Takes hiccup representation of an EAF file, turns it into XML, and gives
  the EAF file string, without indentation."
  [hiccup]
  (-> hiccup
      hiccup->xml
      xml/emit-str
      add-annotation-document-attrs))

(defn eaf?
  "Tests whether the hiccup supplied conforms to the EAF 3.0 spec"
  [hiccup]
  (s/valid? ::annotation-document hiccup))

(defn create-eaf
  "Creates a new, minimal set of EAF 3.0 hiccup, roughly mimicking what
  ELAN 5.1 does when it creates a fresh project."
  [{:keys [:author :date :media-descriptors]}]
  [:annotation-document {:author author
                         :date date
                         :format "3.0"
                         :version "3.0"}
   (-> [:header {:media-file ""
                 :time-units "milliseconds"}]
       ;; append media descriptors
       (into (for [{:keys [:media-url :mime-type]} media-descriptors]
               [:media-descriptor {:media-url media-url
                                   :mime-type mime-type}]))
       (conj [:property {:name "lastUsedAnnotationId"} "0"]))
   [:time-order {}]
   [:tier {:linguistic-type-ref "default-lt"
           :tier-id "default"}]
   [:linguistic-type {:graphic-references "false"
                      :linguistic-type-id "default-lt"
                      :time-alignable "true"}]
   [:constraint {:description "Time subdivision of parent annotation's time interval, no time gaps allowed within this interval"
                 :stereotype "Time_Subdivision"}]
   [:constraint {:description "Symbolic subdivision of a parent annotation. Annotations refering to the same parent are ordered"
                 :stereotype "Symbolic_Subdivision"}]
   [:constraint {:description "1-1 association with a parent annotation"
                 :stereotype "Symbolic_Association"}]
   [:constraint {:description "Time alignable annotations within the parent annotation's time interval, gaps are allowed"
                 :stereotype "Included_In"}]])

;; helper funcs
;; ----------------------------------------------------------------------------
;; Internally, we will use clojure.zip, as it's probably the most ergonomic way
;; of manipulating this rather large hiccup structure. The public API for this
;; module, however, will never expose a zipper to consumers.

;; for hiccup
(defn- tag-name
  [hiccup]
  (first hiccup))

(defn- attrs
  [hiccup]
  (and (map? (second hiccup))
       (second hiccup)))

(defn- children
  [hiccup]
  (if-not (map? (second hiccup))
    (js/Error. "EAF hiccup must have an attrs map, even if it is empty.")
    (drop 2 hiccup)))

(defn- hiccup-zipper
  "Returns a zipper for Hiccup forms, given a root form."
  [root]
  (let [children-pos #(if (map? (second %)) 2 1)]
    (z/zipper
     vector?
     #(drop (children-pos %) %) ; get children
     #(into [] (concat (take (children-pos %1) %1) %2)) ; make new node
     root)))

(defn- right-while
  "Call z/right while (pred (z/node zipper)) is true"
  [loc pred]
  (when loc
    (when-let [node (z/node loc)]
      (if (pred node)
        (recur (z/right loc) pred)
        loc))))

(defn- left-while
  "Call z/left while (pred (z/node loc)) is true"
  [loc pred]
  (when loc
    (when-let [node (z/node loc)]
      (if (pred node)
        (recur (z/left loc) pred)
        loc))))

(defn- take-right-while
  "Returns a seq of contiguous nodes beginning from the current node and going
  right such that (pred node) is satisfied for all in the sequence"
  [loc pred]
  (when loc
    (when-let [node (z/node loc)]
      (when (pred node)
        (cons node (take-right-while (z/right loc) pred))))))

(defn- update-right-while
  "Like right-while, but also updates each node that tests true with
  the value of (func (z/node loc))"
  [loc pred func]
  (when loc
    (when-let [node (z/node loc)]
      (if (pred node)
        (recur (z/right (z/replace loc (func node))) pred func)
        loc))))

(defn- right-to-first
  [loc kwd]
  (right-while loc #(not= (tag-name %) kwd)))

(defn- take-right-to-last
  [loc kwd]
  (take-right-while loc #(= (tag-name %) kwd)))

;; Trivial getters and setters
;; ----------------------------------------------------------------------------
;; defzipfn is a macro that generates something like this:
;; (defn <name> [hiccup] (-> hiccup hiccup-zipper <arg1> <arg2> ...))
;; Functions prefixed with `go-to` return a `zip` location, or `nil` if no
;; appropriate element could be found
(defzipfn- go-to-annotation-document) ;; do nothing
(defzipfn- go-to-licenses z/down (right-to-first :license))
(defzipfn- go-to-header z/down (right-to-first :header))
(defzipfn- go-to-time-order z/down (right-to-first :time-order))
(defzipfn- go-to-tiers z/down (right-to-first :tier))
(defzipfn- go-to-linguistic-types z/down (right-to-first :linguistic-type))
(defzipfn- go-to-locales z/down (right-to-first :locale))
(defzipfn- go-to-languages z/down (right-to-first :language))
(defzipfn- go-to-constraints z/down (right-to-first :constraint))
(defzipfn- go-to-controlled-vocabularies z/down (right-to-first :controlled-vocabulary))
(defzipfn- go-to-lexicon-refs z/down (right-to-first :lexicon-ref))
(defzipfn- go-to-external-refs z/down (right-to-first :external-ref))

(defzipfn get-date z/node attrs :date)
(defzipfn get-author z/node attrs :author)
(defzipfn get-version z/node attrs :version)

(defn get-licenses
  [hiccup]
  (-> hiccup
      go-to-licenses
      (take-right-to-last :license)))

(defn get-media-descriptors
  [hiccup]
  (-> hiccup
      go-to-header
      z/down
      (take-right-to-last :media-descriptor)))

(defn get-properties
  [hiccup]
  (-> hiccup
      go-to-header
      z/down
      (right-to-first :property)
      (take-right-to-last :property)))

(defn get-tiers
  [hiccup]
  (-> hiccup
      go-to-tiers
      (take-right-to-last :tier)))

;; NYI: get-* for linguistic types, locales, languages, etc.



;; derived data structures and cache
;; ----------------------------------------------------------------------------
;; Non-trivial getters and setters rely on data that is most efficiently
;; obtained from data structures that are derived from the XML.
;; Most of the time, we don't care about an old hiccup
;; structure after we've encountered a new one, so we keep track of the
;; latest one we've seen in `:latest-doc` and cache derived structures
;; with the other keys in `*cache*`.
;;
;; Functions that rely on derived structures call functions which are
;; prefixed with `build-`. These functions check to see if the hiccup
;; they're given matches `:latest-doc` and then either just return
;; the cached derived structure if it's the same, or build a new version
;; if it's different and set the appropriate var.
;;
;; From an external perspective, this approach is still functionally
;; pure and preserves referential transparency. It just gets us a
;; performance win a lot of the time.

(def ^:private *cache* {:latest-doc nil
                        :annotation-map nil
                        :tier-parent-map nil})

(defn get-time-slot-val [hiccup time-slot-id]
  "Determine the millisecond value of a time slot ID."
  (when-let [loc (-> hiccup
                     go-to-time-order
                     z/down
                     (right-while
                      #(not= time-slot-id (:time-slot-id (attrs %)))))]
    ;; TODO: find out why :time-value is sometimes allowed to be null
    ;; for now, just interpolate between the two neighboring time slots
    ;; with time-value values
    (or (-> loc z/node attrs :time-value)
        (let [left-neighbor-val
              (or (-> loc
                      (left-while #(not (some? (:time-value (attrs %)))))
                      z/node
                      attrs
                      :time-value)
                  0)
              right-neighbor-val
              (-> loc
                  (right-while #(not (some? (:time-value (attrs %)))))
                  z/node
                  attrs
                  :time-value)]
          (str (/ (+ (int left-neighbor-val)
                     (int right-neighbor-val))
                  2))))))

(defn- build-annotation-map [hiccup]
  "Returns a seq of elements that each corresponds to an annotation. A map
   is returned with the keys:
    :ref            if the annotation is a reference annotation
    :time1, :time2  if the annotation is an alignable annotation"
  (into
   {}
   (for [tier (get-tiers hiccup)
         ann (children tier)]
     (let [inner-ann (first (children ann))
           type (tag-name inner-ann)
           {:keys [:annotation-id
                   :annotation-ref
                   :time-slot-ref1
                   :time-slot-ref2]} (attrs inner-ann)]
       [annotation-id
        (if annotation-ref
          {:ref annotation-ref}
          {:time1 (get-time-slot-val hiccup time-slot-ref1)
           :time2 (get-time-slot-val hiccup time-slot-ref2)})]))))

(defn- build-tier-parent-map
  "map from tier-id's to parent-refs, e.g.:
    [:tier {:tier-id \"Foo\" :parent-ref \"Bar\"}]
    ->
    {..., \"Foo\" \"Bar\", ...}"
  [hiccup]
  (into {}
        (for [a (map attrs (get-tiers hiccup))]
          (when (:parent-ref a)
            [(:tier-id a) (:parent-ref a)]))))

(defn- update-cache!
  [hiccup]
  (set! *cache* {:latest-doc hiccup
                 :annotation-map (build-annotation-map hiccup)
                 :tier-parent-map (build-tier-parent-map hiccup)}))

(defn- build-annotation-map-cached [hiccup]
  (when-not (= (:latest-doc *cache*) hiccup)
    (update-cache! hiccup))
  (:annotation-map *cache*))

(defn- build-tier-parent-map-cached [hiccup]
  (when-not (= (:latest-doc *cache*) hiccup)
    (update-cache! hiccup))
  (:tier-parent-map *cache*))

;; More involved getters and setters
;; ----------------------------------------------------------------------------
(defn get-annotation-times
  "Returns a map with keys :time1 :time2 representing the millisecond time
   for a given annotation. If the annotation is a reference annotation, its
   times are recursively resolved."
  [hiccup ann-id]
  (let [{:keys [ref] :as m}
        (get (build-annotation-map-cached hiccup) ann-id)]
    (if ref
      (recur hiccup ref)
      m)))

(defn get-parent-tiers
  "Given a tier ID, return a seq of parent tiers"
  [hiccup tier-id]
  (let [parents (build-tier-parent-map-cached hiccup)
        inner (fn inner [id]
                (let [parent (get parents id)]
                  (if parent
                    (cons parent (inner parent))
                    nil)))]
    (inner tier-id)))

(defn is-parent-tier
  "Given a tier ID, return true if there are other tiers
   that refer to it with :parent-ref; nil otherwise"
  [hiccup tier-id]
  (some (fn [[child parent]]
          (= parent tier-id))
        (build-tier-parent-map-cached hiccup)))



;(def *eaf (:eaf (:project/current-project re-frame.db.app-db.state)))



;(build-tier-inheritance-map *eaf)



