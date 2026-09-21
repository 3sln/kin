#!/usr/bin/env bb
;; Does LINK resolve positionally, and does GENERATE emit what it decided?
;;
;;     bb test/link.clj
;;
;; "Locals win once declared" is not a precedence rule a static lookup can
;; answer -- it is a question about a POINT in the file. So the declare phase
;; becomes a LINK phase: an ordered walk, bodies included, that resolves every
;; reference and records how each should be emitted. Generate emits what link
;; decided rather than deciding again.
;;
;; What is pinned:
;;
;;   1. link walks BODIES, not just heads -- a reference lives inside one
;;   2. resolution is POSITIONAL: the same name resolves differently before
;;      and after the definition that declares it
;;   3. the store is keyed by form IDENTITY, so two `=` forms in different
;;      functions keep separate resolutions
;;   4. generate consults the record rather than resolving again
(require '[kin] '[kin.lang :as core] '[kin.target] '[kin.project :as kp]
         '[clojure.string :as str])

(def I32 {:name 'I32 :types {:rust "i32"}})
(def Rt* {:name 'Rt :types {:rust "Rt"}})

;; `thing` exists in the VOCABULARY. A source may also define its own.
(def vocabulary
  {:namespace 'demo :targets #{:rust}
   :tags {'I32 I32 'Rt Rt*} :names {}
   :forms (merge (core/forms {:default-tag I32})
                 ;; BOTH ARGUMENTS, because the local `thing` below takes
                 ;; both and this fixture is about ONE NAME resolving two
                 ;; ways. A name that resolves two ways has to take the same
                 ;; arguments both ways, or the two readings of `(thing rt x)`
                 ;; are not the same call -- and the arity check in
                 ;; `kin.lang/call` now says so.
                 {'thing (core/call {:rust "VOCAB_thing({0}, {1})"})})})

(def prj (kp/project {:vocabularies [vocabulary]
                      :targets {:rust kin.target/rust}
                      :target-order [:rust]}))

(defn link-of
  "Link `text` and answer the resolution store."
  [text]
  (let [a (kp/analyse prj text "t.kin")
        g (assoc (kin/context {} :rust)
                 :vocabs (:vocabularies prj) :scope-syms (:scope a)
                 :targets (:targets prj) :kin/ns (:ns-name a)
                 :locals (atom {}) :names (atom {})
                 :local-tags (atom {}) :tmp (atom 0))
        l (kin/map->LinkContext
           (assoc (into {} g) :scope {} :indent 0
                  :resolutions (kin/-resolutions g)
                  :locals (atom {}) :local-tags (atom {}) :names (atom {})))]
    (kin/link! l (cons 'do (:forms a)))
    (kin/-resolutions g)))

(def failures (atom 0))

(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))

(println "\nlink: resolve in order, then emit what was resolved\n")

;; 1 and 2. THE POSITIONAL RULE. `thing` is called twice -- once before the
;; local definition and once after -- and must resolve differently.
(def positional
  "(ns t (:require [demo :refer [defn return thing I32 Rt]]))
   (defn ^:method ^I32 before [^Rt rt ^I32 x] (return (thing rt x)))
   (defn ^:method ^I32 thing  [^Rt rt ^I32 x] (return x))
   (defn ^:method ^I32 after  [^Rt rt ^I32 x] (return (thing rt x)))")

(let [store (link-of positional)
      sites (for [[f r] store :when (and r (= 'thing (first f)))] (:kind r))]
  (is "1. link walked into the bodies and found both call sites"
      2 (count sites))
  ;; Sorted so the assertion does not depend on store iteration order.
  (is "2. the SAME name resolves two ways -- vocabulary before, local after"
      [:local :vocabulary] (vec (sort-by name sites))))

;; And it shows in the OUTPUT, which is the part that matters.
(let [out (:rust (kp/generate prj positional "t.kin"))]
  (is "4. the call before the definition emits the vocabulary's shape"
      true (str/includes? out "return VOCAB_thing(self, x);"))
  (is "4. and the call after it emits the local one"
      true (str/includes? out "return self.thing(x);")))

;; 3. THE IDENTITY TRAP. Two `(thing rt x)` forms are `=`. A value-keyed store
;; would merge them and hand one function's resolution to the other --
;; silently, and producing code that compiles.
(let [a (list 'thing 'rt 'x)
      b (list 'thing 'rt 'x)
      store (kin/resolution-store)
      ctx (kin/map->LinkContext {:driver {} :target :rust :scope {} :indent 0
                                 :resolutions store})]
  (is "3. the two forms are equal as values" true (= a b))
  (kin/record-resolution! ctx a {:kind :vocabulary})
  (kin/record-resolution! ctx b {:kind :local})
  (is "3. and keep SEPARATE resolutions, because the store keys by identity"
      [{:kind :vocabulary} {:kind :local}]
      [(kin/resolution ctx a) (kin/resolution ctx b)]))

(println)
(if (zero? @failures)
  (println "link: positional resolution, recorded per form, emitted as decided\n")
  (do (println (format "link: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
