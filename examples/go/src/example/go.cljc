(ns example.go
  "A vocabulary that speaks Go, and only Go.

  This is the whole answer to `how do I add a language kin has never heard
  of`: describe the target (`example.targets`), then write the forms your
  sources need with a `:go` entry in each template. kin needs no change and
  knows nothing about Go at either step.

  It is deliberately SMALL -- eight forms and two tags -- because the point is
  that a vocabulary is as big as the sources that use it and no bigger. There
  is no base class to inherit and no set of forms you are obliged to provide.

  ## Why this does not just merge `kin.lang`

  `kin.lang` ships `defn`, `let`, `if`, `for`, `case` and the rest, and every
  one of them frames its output with a three-armed `case` over `:rust`,
  `:java` and `:csharp`. It declares `:targets #{:rust :java :csharp}` for
  exactly that reason: it is one vocabulary that ships in the box, not the
  language, and it speaks the three it was written against.

  So a fourth language writes its own shape forms, as this file does. That is
  honest but it is not free, and it is the sharpest remaining edge in kin --
  see the README."
  (:require [kin :as sp]
            ;; `kin.lang`'s FORMS speak three languages that are not Go, but
            ;; its helpers are just functions -- `strip-parens` knows how to
            ;; drop an expression's outer parentheses safely and was got right
            ;; the hard way. Reusing it is not the same as reusing the
            ;; vocabulary.
            [kin.lang :as core]
            [clojure.string :as str]))

(def I32 {:name 'I32 :types {:go "int"}})
(def Bool {:name 'Bool :types {:go "bool"}})

(defn- ty [ctx tag] (get-in (sp/kin-tag ctx tag) [:types :go]))
(defn- fn-name [ctx nm] ((get-in ctx [:targets :go :fn-name]) ctx nm))

(defn- defn-form
  "`(defn ^I32 gcd [^I32 a ^I32 b] ...)` -- a Go function.

  It also DECLARES the name, so a later form in the same file can call it,
  and registers its return tag so a caller knows what it got."
  [ctx form]
  (let [[_ nm params & body] form
        ret (:tag (meta nm))]
    (sp/kin-declare!
     ctx nm
     (fn [c f]
       (sp/kin-tagged! c (sp/kin-tag c ret))
       (let [as (mapv (fn [x] (sp/kin-render c x)) (rest f))]
         (sp/kin-emit! c (fn-name c nm) "(" (str/join ", " as) ")"))))
    (sp/kin-emit! ctx (sp/indent-of ctx) "func " (fn-name ctx nm) "("
                  (str/join ", " (mapv (fn [p] (str (sp/local-name ctx p) " "
                                                    (ty ctx (:tag (meta p)))))
                                       params))
                  ") " (when ret (str (ty ctx ret) " ")) "{\n")
    (sp/kin-scoped ctx {:key :fn :value nm :indent 1}
                   (fn [inner] (doseq [f body] (sp/kin-statement! inner f))))
    (sp/kin-emit! ctx (sp/indent-of ctx) "}\n")))

(defn- return-form [ctx form]
  (sp/kin-emit! ctx (sp/indent-of ctx) "return"
                (if (second form)
                  (str " " (core/strip-parens (sp/kin-render ctx (second form))))
                  "")
                "\n"))

(defn- assign-form
  "`(set x v)`. Go's `:=` declares and `=` assigns, and which one a line wants
  is a fact about whether the name is new -- so the source says it: `let`
  declares, `set` assigns."
  [ctx form]
  (sp/kin-emit! ctx (sp/indent-of ctx) (sp/kin-render ctx (second form))
                " = " (sp/kin-render ctx (nth form 2)) "\n"))

(defn- let-form [ctx form]
  (let [[_ bindings & body] form]
    (doseq [[nm init] (partition 2 bindings)]
      (let [{:keys [text tag]} (sp/kin-render-tagged ctx init)]
        ;; An unannotated binding takes its initialiser's tag, which in Go is
        ;; also what `:=` does -- so the source needs no annotation and the
        ;; output needs no type.
        (sp/kin-declare-tag! ctx nm (or (sp/kin-tag ctx (:tag (meta nm))) tag))
        (sp/kin-emit! ctx (sp/indent-of ctx) (sp/local-name ctx nm) " := "
                      (core/strip-parens text) "\n")))
    (doseq [f body] (sp/kin-statement! ctx f))))

(defn- for-form
  "`(while test body...)` -- Go spells every loop `for`."
  [ctx form]
  ;; `for (y != 0)` is legal Go and not what anyone writes. The test is a
  ;; whole expression with nothing to bind to, which is the safe case to
  ;; strip.
  (sp/kin-emit! ctx (sp/indent-of ctx) "for "
                (core/strip-parens (sp/kin-render ctx (second form))) " {\n")
  (sp/kin-scoped ctx {:key :loop :value true :indent 1}
                 (fn [inner] (doseq [f (drop 2 form)] (sp/kin-statement! inner f))))
  (sp/kin-emit! ctx (sp/indent-of ctx) "}\n"))

(defn- op [sym result]
  (fn [ctx form]
    (let [as (mapv (fn [f] (sp/kin-render ctx f)) (rest form))]
      (sp/kin-tagged! ctx result)
      (sp/kin-emit! ctx (str "(" (str/join (str " " sym " ") as) ")")))))

(defn- comment-form [ctx form]
  (doseq [line (rest form)] (sp/kin-emit! ctx (sp/indent-of ctx) "// " line "\n")))

(def vocabulary
  {:namespace 'example.go
   :targets #{:go}
   :tags {'I32 I32 'Bool Bool}
   :names {}
   :literal-tag (fn [v] (when (integer? v) I32))
   :forms {'defn defn-form
           'return return-form
           'let let-form
           'set assign-form
           'while for-form
           'comment comment-form
           '!= (op "!=" Bool)
           '> (op ">" Bool)
           'rem (op "%" I32)}})
