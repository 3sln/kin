#!/usr/bin/env bb
;; Can several vocabulary maps share one `:namespace`?
;;
;;     bb test/grouping.clj
;;
;; THE THIRD HALF OF THE COMPLAINT the whole redesign came from
;; (`doc/redesign.md:1444`): "namespaces aren't target-coupled like I'd
;; intended, and it's not clear that they can be overridden. Which leads to
;; the problem that we have all the `lang.kin` forms, but if the user wants to
;; extend them to a new language, there's no clear path for them."
;;
;; `:targets` answered the first half and first-match-wins require order the
;; second. Neither let you EXTEND a namespace: adding a fourth language to an
;; existing three-language source meant writing your own shape vocabulary with
;; four arms, because `kin.lang`'s speak three.
;;
;; Now a namespace is a GROUP. A project collects every map it has and groups
;; them by `:namespace`; a reference walks the group for the first map that
;; BOTH speaks the current target AND holds the symbol.
;;
;; What is pinned:
;;
;;   1. two maps, one namespace: each target resolves to the map that speaks it
;;   2. the `ns` form does not change -- one require, and the extension is
;;      invisible to the source. That is the whole complaint
;;   3. what a namespace SPEAKS is the union, so a source generates for a
;;      target no single map covers
;;   4. a symbol the extension did not cover is an ERROR naming the symbol,
;;      the target and what does hold it -- not `not in scope`, which is false
;;   5. declared ORDER is the tie-break, and a source can still override the
;;      whole namespace by requiring its own first (the old path, unbroken)
;;   6. one symbol claimed for one target by two maps is REFUSED -- extension
;;      is welcome, restatement is the drift the check exists for
;;   7. `literal-tag` comes from the first map that SPEAKS THE TARGET
;;   8. a bare map is a group of one, so every project that predates this
;;      sees no change at all
(require '[kin] '[kin.lang :as core] '[kin.target] '[kin.project :as kp]
         '[clojure.string :as str])

(def failures (atom 0))

(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))

(defn threw [f]
  (try (f) :kin/no-throw (catch Exception e (ex-message e))))

(defn refuses [label f]
  (let [msg (threw f)]
    (if (= :kin/no-throw msg)
      (do (swap! failures inc)
          (println (format "  FAIL %s -- it did NOT refuse" label)))
      (println (format "  ok   %-46s %s" label (subs msg 0 (min 40 (count msg))))))))

(println "\ngrouping: several maps, one namespace\n")

;; ---------------------------------------------------------------------------
;; A namespace `shape` that speaks :rust and :java, and an EXTENSION of it
;; that speaks :go and nothing else. Neither map mentions the other.

(def I32 {:name 'I32 :types {:rust "i32" :java "int" :go "int"}})

(def base
  "The original. Three forms, two targets -- `kin.lang` in miniature."
  {:namespace 'shape :targets #{:rust :java}
   :tags {'I32 I32} :names {}
   :literal-tag (fn [v] (when (integer? v) I32))
   :forms {'wrap (core/call {:rust "rust_wrap({0})" :java "javaWrap({0})"})
           'only-base (core/call {:rust "rb({0})" :java "jb({0})"})}})

(def go-arm
  "GO, ADDED TO `shape`. Same `:namespace`, one target, one arm each.

  Note what is NOT here: no `:rust` or `:java` entry anywhere, and no mention
  of the other two targets. A map that declares `:targets #{:go}` is never
  asked about another one."
  {:namespace 'shape :targets #{:go}
   :tags {'I32 I32} :names {}
   :forms {'wrap (core/call {:go "GoWrap({0})"})}})

(def targets
  {:rust kin.target/rust :java kin.target/java
   :go (merge kin.target/rust {:key :go})})

(def prj
  (kp/project {:vocabularies [base go-arm]
               :targets targets
               :target-order [:rust :java :go]}))

;; 2. THE `ns` FORM DOES NOT CHANGE. One require, naming `shape` once, with
;; nothing in it that knows an extension exists. This is the complaint.
(def src
  "(ns s.t (:require [shape :refer [wrap]]))
   (wrap 1)")

;; 1 and 3. Each target resolves to the map that speaks it, and the source
;; generates for all three -- a target no single map covers.
(let [out (kp/generate prj src "t.kin")]
  ;; `str/includes?` rather than equality: a target's default `:emit` writes
  ;; a banner of its own, and what is being pinned here is which arm answered.
  (is "1. each target resolves to the map that speaks it"
      [true true true]
      [(str/includes? (:rust out) "rust_wrap(1)")
       (str/includes? (:java out) "javaWrap(1)")
       (str/includes? (:go out) "GoWrap(1)")])
  (is "3. and the source generates for all three, which no one map covers"
      [:go :java :rust] (vec (sort-by str (keys out)))))

(is "3. what a namespace SPEAKS is the union of its maps"
    #{:go :java :rust} (kin/group-targets (:vocabularies prj) 'shape))

(is "3. and no single map speaks that union"
    [#{:rust :java} #{:go}]
    (mapv :targets (kin/vocab-group (:vocabularies prj) 'shape)))

;; A name only the BASE has is still referable and still resolves, for the
;; targets the base speaks. Extension adds; it does not narrow.
(is "1. a symbol only the base holds still resolves for the base's targets"
    true
    ;; `:kin/only` because `shape` now SPEAKS Go -- the union -- so without
    ;; it this source would generate for Go too and find no `only-base`
    ;; there. That is assertion 4 below, and it is the one real obligation
    ;; grouping adds: extending a namespace widens what every source
    ;; requiring it generates for, so a partial extension is a thing to
    ;; finish or to exclude.
    (str/includes? (:rust (kp/generate prj "(ns s.u {:kin/only #{:rust}}
                                        (:require [shape :refer [only-base]]))
                                        (only-base 1)" "u.kin"))
                   "rb(1)"))

;; 4. THE HALF-EXTENDED NAMESPACE. `only-base` is referable -- the require
;; scope is the union -- and Go has no map for it. Saying `not in scope` would
;; be false, and it is what the two deleted dispatchers each said differently.
(is "4. a symbol the extension did not cover names the symbol and the target"
    (str "kin: shape/only-base is declared for java rust and not for :go."
         " 2 maps declare shape and none of the ones holding `only-base`"
         " speaks :go.")
    (threw #(kp/generate prj "(ns s.v (:require [shape :refer [only-base]]))
                              (only-base 1)" "v.kin")))

;; 7. `literal-tag` comes from a map that SPEAKS THE TARGET. `base` carries
;; one and `go-arm` does not, so Go gets no literal tag from this namespace --
;; which is the decision, not an accident: a map that cannot speak a target
;; cannot be asked what its types are, because `check-vocabulary` only
;; guarantees a tag has a type for the targets ITS OWN map claims.
(let [ctx-for (fn [t] (assoc (kin/context {} t)
                             :vocabs (:vocabularies prj)
                             :vocab-order ['shape]))]
  (is "7. literal-tag answers for a target its map speaks"
      ['I32 nil]
      [(:name (kin/literal-tag (ctx-for :rust) 5))
       (:name (kin/literal-tag (ctx-for :go) 5))]))

;; ---------------------------------------------------------------------------
;; 5. ORDER IS THE TIE-BREAK, and require order still overrides the lot.

(def louder
  "A second map speaking :rust, holding `wrap` -- listed BEFORE `base`."
  {:namespace 'shape :targets #{:rust}
   :tags {'I32 I32} :names {}
   :forms {'wrap (core/call {:rust "LOUD({0})"})}})

(is "5. within a group, the map listed first wins"
    true
    (let [p (kp/project {:vocabularies [louder
                                        (update base :forms dissoc 'wrap)
                                        go-arm]
                         :targets targets :target-order [:rust]})]
      (str/includes? (:rust (kp/generate p src "t.kin")) "LOUD(1)")))

;; AND THE OLD OVERRIDE PATH IS UNTOUCHED: a source requiring its OWN
;; vocabulary first shadows the whole namespace, group and all. That is the
;; path the redesign already shipped, and grouping is deliberately not a
;; second way to do the same thing -- this one is chosen by the SOURCE.
(is "5. a source's own require still shadows the group entirely"
    true
    (let [mine {:namespace 'mine :targets #{:rust}
                :tags {} :names {}
                :forms {'wrap (core/call {:rust "MINE({0})"})}}
          p (kp/project {:vocabularies [base go-arm mine]
                         :targets targets :target-order [:rust]})]
      (str/includes?
       (:rust (kp/generate p "(ns s.w {:kin/only #{:rust}}
                               (:require [mine :refer [wrap]]
                                         [shape :refer [wrap]]))
                              (wrap 1)" "w.kin"))
       "MINE(1)")))

;; ---------------------------------------------------------------------------
;; 6. RESTATEMENT IS REFUSED, extension is not.

(refuses "6. one symbol, one target, two maps"
         #(kp/project {:vocabularies [base (assoc go-arm :targets #{:rust})]
                       :targets targets :target-order [:rust]}))

(is "6. and the refusal names the symbol and the target"
    true
    (let [m (threw #(kp/project {:vocabularies
                                 [base (assoc go-arm :targets #{:rust})]
                                 :targets targets :target-order [:rust]}))]
      (boolean (and (str/includes? m "`wrap`") (str/includes? m ":rust")))))

;; THE SAME SYMBOL FOR A DIFFERENT TARGET IS FINE, which is the whole point:
;; `wrap` is in both maps above and the project builds, because one speaks
;; :rust and :java and the other :go.
(is "6. the same symbol for a DIFFERENT target is extension, not drift"
    2 (count (kin/vocab-group (:vocabularies prj) 'shape)))

;; AND THE IDENTICAL ENTRY TWICE IS NOT DRIFT EITHER, which follows
;; `require-scope`: its `put` treats an entry equal to the one already held
;; as no shadowing at all. An extension restating a tag it needs for its own
;; target is one fact written twice. Two FORMS never collide this way -- a
;; closure is not `=` to another -- so two implementations for one target
;; stay ambiguous, which they are.
(is "6. but the identical tag in two maps is one fact written twice"
    #{:rust :java}
    (let [same {:namespace 'shape :targets #{:rust :java}
                :tags {'I32 I32} :names {} :forms {}}]
      (kin/group-targets
       (:vocabularies (kp/project {:vocabularies [base same]
                                   :targets targets :target-order [:rust]}))
       'shape)))

;; ---------------------------------------------------------------------------
;; 8. BACKWARD COMPATIBILITY, stated as an assertion rather than assumed.
;;
;; Every project that predates this has one map per namespace. `vocab-group`
;; accepts a bare map as a group of one, so a context built by hand -- which
;; is what most of these test files do -- keeps working untouched.

(is "8. a bare map is a group of one"
    [{:namespace 'solo}] (kin/vocab-group {'solo {:namespace 'solo}} 'solo))

(is "8. and a missing namespace is an empty group, not an error"
    nil (kin/vocab-group {} 'absent))

;; `group-answer` is what `source-origins` reports with, and with one map per
;; namespace it always answers index 0 -- so `:split` is empty for every
;; existing project, which is the compatibility claim in one number.
(let [o (kp/source-origins prj src "t.kin")]
  (is "8. a symbol answered by different maps per target is reported as SPLIT"
      {:rust {:index 0 :targets #{:rust :java}}
       :java {:index 0 :targets #{:rust :java}}
       :go {:index 1 :targets #{:go}}}
      (into {} (get (:split o) 'wrap)))
  (is "8. and the attribution still names the namespace, as it always did"
      true (contains? (get (:by-vocab o) '[shape :form]) 'wrap)))

(println)
(if (zero? @failures)
  (println "grouping: a namespace is a group, and the ns form never learned\n")
  (do (println (format "grouping: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
