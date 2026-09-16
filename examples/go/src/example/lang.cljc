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

(def I32 {:name 'I32 :types {:go "int" :java "int"}})
(def Bool {:name 'Bool :types {:go "bool" :java "boolean"}})
(def Str {:name 'Str :types {:go "string" :java "String"}})

(defn- t [ctx] (:target ctx))
(defn- ty [ctx tag] (get-in (kin/tag ctx tag) [:types (t ctx)]))
(defn- fn-name [ctx nm] ((get-in ctx [:targets (t ctx) :fn-name]) ctx nm))

;; ---------------------------------------------------------------- Go's arms
;;
;; ONE ARM EACH, not two. Every function below is what Go needs and nothing
;; else -- no `case`, no second branch, and no mention of Java anywhere. A
;; map that declares `:targets #{:go}` is never asked about another target,
;; which is what makes a single-language extension honest rather than a
;; three-armed form with two arms left empty.

(defn- go-defn
  "`(defn ^I32 gcd [^I32 a ^I32 b] ...)` -- Go puts the type after the name.

  It also DECLARES the name, so a later form in the same file can call it,
  and registers its return tag so a caller knows what it got."
  [ctx form]
  (let [[_ nm params & body] form
        ret (:tag (meta nm))]
    ;; `:public` means LOCAL AND EXPORTED: a `defn` here is callable from the
    ;; rest of its own file and from any file that requires it.
    (kin/define-form!
     ctx {:scope :public} nm
     (fn [c f]
       (kin/tagged! c (kin/tag c ret))
       (let [as (mapv (fn [x] (kin/render c x)) (rest f))]
         (kin/emit! c (fn-name c nm) "(" (str/join ", " as) ")"))))
    ;; PUBLIC, because a module exists to be consumed -- and in Go that is
    ;; spelled by capitalising, which the target's `:fn-name` namer does.
    (kin/emit!
     ctx (kin/indent-of ctx)
     "func " (fn-name ctx nm) "("
     (str/join ", " (mapv (fn [p] (str (kin/local-name ctx p) " "
                                       (ty ctx (:tag (meta p)))))
                          params))
     ") " (when ret (str (ty ctx ret) " ")) "{\n")
    ;; A PARAMETER'S TAG HAS TO BE REGISTERED, or an unannotated `let` in the
    ;; body has nothing to infer from. `kin.lang`'s `defn` does this, and a
    ;; vocabulary that writes its own owes it too -- forgetting it is
    ;; invisible in Go, where `x := a` needs no type, and was invisible here
    ;; for exactly as long as this file also wrote Java's `defn`.
    (kin/scoped
     ctx {:key :fn :value nm :indent 1}
     (fn [inner]
       (doseq [p params]
         (kin/define-tag! inner {:scope :private} p (kin/tag inner (:tag (meta p)))))
       (doseq [f body] (kin/statement! inner f))))
    (kin/emit! ctx (kin/indent-of ctx) "}\n")))

(defn- go-return [ctx form]
  (kin/emit! ctx (kin/indent-of ctx) "return"
             (if (second form)
               (str " " (core/strip-parens (kin/render ctx (second form))))
               "")
             "\n"))

(defn- go-set
  "`(set x v)`. Go's `:=` declares and `=` assigns, and which one a line wants
  is a fact about whether the name is new -- so the source says it: `let`
  declares, `set` assigns."
  [ctx form]
  (kin/emit! ctx (kin/indent-of ctx) (kin/render ctx (second form))
             " = " (core/strip-parens (kin/render ctx (nth form 2)))
             "\n"))

(defn- go-let
  "`:=` declares AND infers, so the output needs no type -- but the TAG is
  still registered, because a later form asking what `x` is has to get an
  answer whether or not Go wrote one down."
  [ctx form]
  (let [[_ bindings & body] form]
    (doseq [[nm init] (partition 2 bindings)]
      (let [{:keys [text tag]} (kin/render-tagged ctx init)]
        (kin/define-tag! ctx {:scope :private} nm
                         (or (kin/tag ctx (:tag (meta nm))) tag))
        (kin/emit! ctx (kin/indent-of ctx)
                   (kin/local-name ctx nm) " := " (core/strip-parens text) "\n")))
    (doseq [f body] (kin/statement! ctx f))))

(defn- go-while
  "Go spells every loop `for`, and `for (y != 0)` is legal Go that nobody
  writes -- the test is a whole expression with nothing to bind to, which is
  the safe case to strip."
  [ctx form]
  (kin/emit! ctx (kin/indent-of ctx)
             "for " (core/strip-parens (kin/render ctx (second form))) " {\n")
  (kin/scoped ctx {:key :loop :value true :indent 1}
              (fn [inner] (doseq [f (drop 2 form)] (kin/statement! inner f))))
  (kin/emit! ctx (kin/indent-of ctx) "}\n"))

(defn- go-comment [ctx form]
  (doseq [line (rest form)] (kin/emit! ctx (kin/indent-of ctx) "// " line "\n")))

(defn- go-op [sym result]
  (fn [ctx form]
    (let [as (mapv (fn [f] (kin/render ctx f)) (rest form))]
      (kin/tagged! ctx result)
      (kin/emit! ctx (str "(" (str/join (str " " sym " ") as) ")")))))

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
  "TWO MAPS: this example's subject, and Go added to `kin.lang`.

  A vector rather than a map, which `load-vocabulary` splices. The order is
  the group's tie-break where two maps both speak a target and both hold a
  symbol -- these two share no symbol at all, so it is only determinism."
  [{:namespace 'example.lang
    :targets #{:go :java}
    :tags {'I32 I32 'Bool Bool 'Str Str}
    :names {}
    :literal-tag (fn [v] (when (integer? v) I32))
    :forms {'to-str to-str}}

   {:namespace 'kin.lang
    ;; GO ONLY. `kin.lang`'s own map speaks `:rust`, `:java` and `:csharp`,
    ;; so the namespace speaks four languages out of two maps and no single
    ;; map speaks all four -- which is exactly the thing that could not be
    ;; said before.
    :targets #{:go}
    :tags {} :names {}
    :forms {'defn go-defn
            'return go-return
            'let go-let
            'set go-set
            'while go-while
            'comment go-comment
            '!= (go-op "!=" Bool)
            '> (go-op ">" Bool)
            'rem (go-op "%" I32)}}])
