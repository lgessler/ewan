(ns ewan.eaf30
  (:require [cljs.spec.alpha :as s]
            [clojure.data.xml :as xml]
            [cljs.pprint :refer [pprint]])
  (:require-macros [cljs.spec.alpha :as s]))

(defn- xml->hiccup
  "Take XML generated by clojure.data.xml and generate equivalent hiccup.
    This function ought to be the inverse of xml/sexp-as-element"
  [node]
  (if (xml/element? node)
    (into (vector (:tag node)
                  (:attrs node))
          (map xml->hiccup (:content node)))
    node))

(defn- hiccup->xml
  [hiccup]
  (if (vector? hiccup)
    (apply xml/element (into [(first hiccup)
                              (second hiccup)]
                             (map hiccup->xml (drop 2 hiccup))))
    hiccup))

(def sxml (xml/parse-str "<ANNOTATION_DOCUMENT head=\"yes\"><ELT>Foo</ELT><ELT>Bar</ELT><EMPTY></EMPTY></ANNOTATION_DOCUMENT>"))

;;(js/console.log "PARSED:")
;;(pprint sxml)
;;(js/console.log "-------")
;;
;;
;;(js/console.log "FULL CIRCLE:")
;;(pprint (hiccup->xml (xml->hiccup sxml)))
;;(js/console.log (= (hiccup->xml (xml->hiccup sxml))
;;                   sxml))
;;(js/console.log "-------")
;;
;;
;;(js/console.log "HICCUPED:")
;;(pprint (xml->hiccup sxml))
