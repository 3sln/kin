(ns example.lang
  "Go added to `kin.lang`, plus this example's own subject vocabulary.

  This is the whole answer to `how do I add a language kin has never heard
  of` -- and the interesting half is that it is no longer `write your own
  shape vocabulary`.

  ## Two vocabularies, and only one of them is this namespace's

  `vocabulary` below holds a VECTOR, because a namespace is a GROUP: several
  maps may share a `:namespace`, and a reference walks the group for the
  first that speaks its target. So this file declares

      {:namespace 'example.lang :targets #{:go :java}}   the SUBJECT
      {:namespace 'kin.lang    :targets #{:go}}          Go, ADDED to kin.lang

  The second one is not a namespace of its own. It is MORE OF `kin.lang`, and
  the point is what does not have to happen: `kin.lang` is not edited, not
  forked, and not required under a second name. A source says `(:require
  [kin.lang :refer [defn let while]])` and gets Go's `defn` from this file and
  Java's from `kin.lang` itself, because for each target some map in the
  group can answer.

  ## This used to be a two-language shape vocabulary, and that was the edge

  Every form here used to frame its output with a `case` over `:go` and
  `:java` -- a reimplementation of `defn`, `let`, `set`, `while`, `return`,
  `comment` and three operators that `kin.lang` already had, written out
  again because `kin.lang`'s speak `:rust`, `:java` and `:csharp` and a
  vocabulary was one map per namespace. The README called it the sharpest
  remaining edge in kin. It is gone: what is left is the GO ARMS ONLY, and
  the Java arms are `kin.lang`'s.

  That is worth more than the lines it saved. The duplicate `defn` had to
  keep pace with `kin.lang`'s -- `^:mut` on a parameter, the reserved-word
  namer, the tag a parameter registers so an unannotated `let` can infer from
  it -- and forgetting any of them was a bug in one language and not the
  other. It is not a copy any more, so it cannot drift.

  ## What is still this example's own, and why

  The SUBJECT: three tags, a `literal-tag`, and `to-str`. That division is
  the one kin has always drawn -- `kin.lang` carries no tags and no names,
  because what a `Value` IS belongs to the project -- and `to-str` is here
  because reaching for `strconv` in Go and `java.util.Objects` in Java is a
  fact about this example's data, not about either language's shape."
  (:require [kin]
            [kin.lang :as core]
            [clojure.string :as str]))

(def I32 {:name 'I32 :types {:go "int" :java "int"}
          ;; ONLY GO ASKS FOR THIS. `^:throws` makes a Go function return
          ;; `(int, error)`, so the error path has to name a zero -- Rust's
          ;; `?` propagates without one and an exception unwinds. A tag
          ;; without it cannot be the return of a fallible function in Go,
          ;; which makes it incomplete rather than merely unannotated.
          :zero {:go "0"}})
(def Bool {:name 'Bool :types {:go "bool" :java "boolean"}})
(def Str {:name 'Str :types {:go "string" :java "String"}})

(defn- t [ctx] (:target ctx))
(defn- ty [ctx tag] (get-in (kin/tag ctx tag) [:types (t ctx)]))
(defn- fn-name [ctx nm] ((get-in ctx [:targets (t ctx) :fn-name]) ctx nm))

;; ------------------------------------------------- Go arrives from kin.lang
;;
;; THIS FILE USED TO CARRY GO'S SHAPE FORMS -- `defn`, `let`, `set`, `while`,
;; `return`, `comment` and three operators, one arm each, contributed as a
;; second map under `:namespace 'kin.lang`. They are gone, because `kin.lang`
;; now ships its own Go map and a namespace may not have two maps answering
;; for one target: that is two statements of one fact, and kin refuses it by
;; name rather than letting declaration order pick.
;;
;; So the example is back to carrying only what is ITS OWN -- three tags and
;; one form -- which is what a project using kin should have to write. The
;; deletion is the point: every arm removed here is an arm every other kin
;; project no longer writes either.

;; ------------------------------------------------------------- the subject

(defn- to-str
  "`(to-str x)` -- an integer as text, and the reason imports exist here.

  Go reaches for `strconv` and Java for `java.util.Objects`, so BOTH need
  something at the top of the file that the call site knows about and the
  file header cannot. This is the case a spliced region got wrong: the host
  file had its imports right once, and generated code needing one more had
  nowhere to say so.

  TWO ARMS, and here that is right rather than duplication: this form is the
  SUBJECT's, it speaks both targets, and what it needs in scope differs by
  language. The shape forms above have one arm each because they are one
  language's contribution to a namespace that already speaks the others.

  `core/need!` is `kin.lang`'s, and it is the same function the example used
  to define for itself: the form says WHAT it needs as data and the target's
  `:emit` decides how a header is written. kin does not know what an import
  is."
  [ctx form]
  (let [x (core/strip-parens (kin/render ctx (second form)))]
    (kin/tagged! ctx Str)
    (case (t ctx)
      :go (do (core/need! ctx "strconv")
              (kin/emit! ctx "strconv.Itoa(" x ")"))
      :java (do (core/need! ctx "java.util.Objects")
                (kin/emit! ctx "Objects.toString(" x ")")))))

(def vocabulary
  "ONE MAP AGAIN, and that is the news.

  This was a vector of two while the example supplied Go's shape forms
  itself. `kin.lang` ships them now, so what is left is the subject: the
  tags this example's source annotates with, and the one form that needs a
  different import in each language."
  {:namespace 'example.lang
   :targets #{:go :java}
   :tags {'I32 I32 'Bool Bool 'Str Str}
   :names {}
   :literal-tag (fn [v] (when (integer? v) I32))
   :forms {'to-str to-str}})
