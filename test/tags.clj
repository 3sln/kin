#!/usr/bin/env bb
;; Does a tag actually REACH the form that asks for it?
;;
;;     bb test/tags.clj
;;
;; Item 5 of `doc/redesign.md` says kin owes four things about tags, and the
;; flint tree cannot check any of them: it re-emits byte-identical whether the
;; tag machinery works or is stubbed out to return nil, because no vocabulary
;; there reads a tag yet. That is exactly the shape of check this project has
;; shipped three times while it tested nothing, so the four are pinned here
;; against a vocabulary written to report what it was handed.
;;
;; The four:
;;
;;   1. a CALL's declared `:tag` reaches an enclosing form
;;   2. a PARAMETER's `^Tag` reaches a form in the body
;;   3. an UNANNOTATED let binding takes its initialiser's tag
;;   4. a LITERAL takes whatever the vocabulary's `:literal-tag` says
;;
;; and one negative, which is the point of the whole correction: kin does not
;; interpret a tag. A form that ignores tags behaves exactly as it did before
;; tags existed.
(require '[kin :as sp] '[kin.lang :as core] '[kin.target] '[clojure.string :as str])

(def I32 {:name 'I32 :types {:rust "i32"}})
(def F64 {:name 'F64 :types {:rust "f64"}})
(def Rt* {:name 'Rt :types {:rust "Rt"}})

(def seen
  "What `report` was handed, in order. The test reads this."
  (atom []))

(defn report-form
  "`(report x y ...)` -- render each argument and RECORD its tag.

  This is a vocabulary reading tags, which is the only place tags are ever
  read. kin carried them here and never looked inside one."
  [ctx form]
  (doseq [a (rest form)]
    (let [{:keys [text tag]} (sp/kin-render-tagged ctx a)]
      (swap! seen conj [text (:name tag)])))
  (sp/kin-emit! ctx (sp/indent-of ctx) "reported;\n"))

(def vocabulary
  {:namespace 'demo
   :targets #{:rust}
   :tags {'I32 I32 'F64 F64 'Rt Rt*}
   :names {}
   ;; kin cannot know what tag `5` has -- tags belong to vocabularies -- so it
   ;; asks. This is the vocabulary answering.
   :literal-tag (fn [v] (cond (integer? v) I32 (float? v) F64))
   :forms (merge (core/forms {:default-tag I32})
                 {'report report-form
                  ;; A call that DECLARES what it produces.
                  'to-f (core/call {:rust "to_f({0})"} {:tag F64})
                  'add (core/call {:rust "({0} + {1})"} {:tag I32})})})

(def targets {:rust kin.target/rust})

(defn render
  "Render `forms` under `ns-form`, and answer what `report` saw."
  [ns-form forms]
  (reset! seen [])
  (let [vocabs {'demo vocabulary}
        scope (sp/require-scope ns-form vocabs)
        ctx (assoc (sp/context {} :rust)
                   :vocabs vocabs :scope-syms scope :vocab-order ['demo]
                   :targets targets
                   :locals (atom {}) :names (atom {})
                   :local-tags (atom {}) :tmp (atom 0))]
    (doseq [f forms] (sp/kin-statement! ctx f))
    {:text (sp/kin-output ctx) :seen @seen}))

(def ns-form
  '(ns probe (:require [demo :refer [defn let return report to-f add
                                     I32 F64 Rt]])))

(def failures (atom 0))

(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %-46s %s" label (pr-str actual)))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))

(println "\ntags: does a declared tag reach the form that asks?\n")

;; 1, 2 and 4 in one source: a parameter's tag, a call's declared tag, and a
;; literal's tag, all read by `report` from inside a function body.
(let [{:keys [seen]}
      (render ns-form
              '[(defn ^:method ^I32 f [^Rt rt ^I32 a]
                  (report a (to-f a) 5))])]
  (is "2. a parameter's ^I32 reaches the body" '["a" I32] (nth seen 0))
  (is "1. a call's declared :tag reaches its caller" '["to_f(a)" F64] (nth seen 1))
  (is "4. a literal takes the vocabulary's literal-tag" '["5" I32] (nth seen 2)))

;; 3. An unannotated binding infers from its initialiser; an annotated one
;; keeps what it was told, because a source that says `^I32` has said
;; something and inference must not argue with it.
(let [{:keys [text seen]}
      (render ns-form
              '[(defn ^:method ^I32 g [^Rt rt ^I32 a]
                  (let [inferred (to-f a)
                        ^I32 declared (to-f a)]
                    (report inferred declared)))])]
  (is "3. an unannotated let binding infers F64" '["inferred" F64] (nth seen 0))
  (is "3. a declared ^I32 binding stays I32" '["declared" I32] (nth seen 1))
  ;; And the inference is VISIBLE in the output, not only in the tag: the
  ;; declaration kin emits uses the inferred type rather than the default.
  (is "3. and the emitted declaration says f64"
      true (str/includes? text "let inferred: f64 = to_f(a);")))

;; THE NEGATIVE. A form that never asks for a tag behaves as it always did.
;; This is what makes the change additive: `add` declares a tag, `report`
;; reads one, and the arithmetic in between neither knows nor cares.
(let [{:keys [text]}
      (render ns-form '[(defn ^:method ^I32 h [^Rt rt ^I32 a] (return (add a 5)))])]
  (is "0. a form that ignores tags is unchanged"
      true (str/includes? text "return a + 5;")))

(println)
(if (zero? @failures)
  (println "tags: all four obligations hold, and ignoring a tag still works\n")
  (do (println (format "tags: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
