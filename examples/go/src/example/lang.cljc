(ns example.lang
  "A vocabulary that speaks Go and Java, and nothing kin ships.

  This is the whole answer to `how do I add a language kin has never heard
  of`: describe the target (`example.targets`), then write the forms your
  sources need with an entry per target. kin needs no change and knows nothing
  about either language at either step.

  It is deliberately SMALL -- ten forms and three tags -- because the point is
  that a vocabulary is as big as the sources that use it and no bigger. There
  is no base class to inherit and no set of forms you are obliged to provide.

  ## Two targets whose FILES look nothing alike

  Go takes a module file -- `package gcd`, then imports, then functions at the
  top level. Java takes a class file at the package path -- `package example;`,
  then imports, then `public final class Gcd { ... }` with the functions
  inside it as statics.

  kin has no opinion about either. Each target's `:emit` (see
  `example.targets`) writes its own file, and the same source produces both.
  That is the whole of `kin produces modules, the language consumes them`:
  nothing is split across files, something new is created and imported.

  ## Why this does not just merge `kin.lang`

  `kin.lang` ships `defn`, `let`, `if`, `for`, `case` and the rest, and every
  one of them frames its output with a three-armed `case` over `:rust`,
  `:java` and `:csharp`. It declares `:targets #{:rust :java :csharp}` for
  exactly that reason: it is one vocabulary that ships in the box, not the
  language, and it speaks the three it was written against.

  So a fourth language writes its own shape forms, as this file does. That is
  honest but it is not free, and it is the sharpest remaining edge in kin --
  see the README."
  (:require [kin]
            ;; `kin.lang`'s FORMS speak three languages that are not Go, but
            ;; its helpers are just functions -- `strip-parens` knows how to
            ;; drop an expression's outer parentheses safely and was got right
            ;; the hard way. Reusing it is not the same as reusing the
            ;; vocabulary.
            [kin.lang :as core]
            [clojure.string :as str]))

(def I32 {:name 'I32 :types {:go "int" :java "int"}})
(def Bool {:name 'Bool :types {:go "bool" :java "boolean"}})
(def Str {:name 'Str :types {:go "string" :java "String"}})

(defn- t [ctx] (:target ctx))
(defn- ty [ctx tag] (get-in (kin/tag ctx tag) [:types (t ctx)]))
(defn- fn-name [ctx nm] ((get-in ctx [:targets (t ctx) :fn-name]) ctx nm))

(defn- defn-form
  "`(defn ^I32 gcd [^I32 a ^I32 b] ...)` -- a function, framed per target.

  Go puts the type after the name and Java before it; Go has no visibility
  keyword and Java wants `static`. That is the whole difference, and it is
  the ordinary shape of one in a vocabulary.

  It also DECLARES the name, so a later form in the same file can call it,
  and registers its return tag so a caller knows what it got."
  [ctx form]
  (let [[_ nm params & body] form
        ret (:tag (meta nm))]
    (kin/define-form!
     ctx nm
     (fn [c f]
       (kin/tagged! c (kin/tag c ret))
       (let [as (mapv (fn [x] (kin/render c x)) (rest f))]
         (kin/emit! c (fn-name c nm) "(" (str/join ", " as) ")"))))
    (kin/emit!
     ctx (kin/indent-of ctx)
     (case (t ctx)
       :go (str "func " (fn-name ctx nm) "("
                (str/join ", " (mapv (fn [p] (str (kin/local-name ctx p) " "
                                                  (ty ctx (:tag (meta p)))))
                                     params))
                ") " (when ret (str (ty ctx ret) " ")) "{\n")
       ;; PUBLIC, because a module exists to be consumed. Go exports by
       ;; capitalising -- which the `:fn-name` namer already does -- and Java
       ;; needs the word. Package-private compiles perfectly and then cannot
       ;; be called from the hand-written code that imports it, which is a
       ;; failure that only shows up at the call site, in another file.
       :java (str "public static " (if ret (ty ctx ret) "void") " "
                  (fn-name ctx nm) "("
                  (str/join ", " (mapv (fn [p] (str (ty ctx (:tag (meta p))) " "
                                                    (kin/local-name ctx p)))
                                       params))
                  ") {\n")))
    ;; A PARAMETER'S TAG HAS TO BE REGISTERED, or an unannotated `let` in the
    ;; body has nothing to infer from. kin.lang's `defn` does this; a
    ;; vocabulary that writes its own owes it too, and forgetting it is
    ;; invisible in Go -- `x := a` needs no type -- and emits ` x = a;` in
    ;; Java. One target hid the bug and the other showed it, which is the
    ;; argument for having two in the example at all.
    (kin/scoped
     ctx {:key :fn :value nm :indent 1}
     (fn [inner]
       (doseq [p params]
         (kin/define-tag! inner {:scope :private} p (kin/tag inner (:tag (meta p)))))
       (doseq [f body] (kin/statement! inner f))))
    (kin/emit! ctx (kin/indent-of ctx) "}\n")))

(defn- semi [ctx] (if (= :go (t ctx)) "" ";"))

(defn- return-form [ctx form]
  (kin/emit! ctx (kin/indent-of ctx) "return"
                (if (second form)
                  (str " " (core/strip-parens (kin/render ctx (second form))))
                  "")
                (semi ctx) "\n"))

(defn- assign-form
  "`(set x v)`. Go's `:=` declares and `=` assigns, and which one a line wants
  is a fact about whether the name is new -- so the source says it: `let`
  declares, `set` assigns."
  [ctx form]
  (kin/emit! ctx (kin/indent-of ctx) (kin/render ctx (second form))
                " = " (core/strip-parens (kin/render ctx (nth form 2)))
                (semi ctx) "\n"))

(defn- let-form [ctx form]
  (let [[_ bindings & body] form]
    (doseq [[nm init] (partition 2 bindings)]
      (let [{:keys [text tag]} (kin/render-tagged ctx init)]
        ;; An unannotated binding takes its initialiser's tag, which in Go is
        ;; also what `:=` does -- so the source needs no annotation and the
        ;; output needs no type.
        (kin/define-tag! ctx {:scope :private} nm (or (kin/tag ctx (:tag (meta nm))) tag))
        ;; Go's `:=` declares AND infers; Java needs the type written out, and
        ;; the tag is what supplies it. Same source, and the inference kin
        ;; already does is what makes the Java possible without an annotation.
        (kin/emit! ctx (kin/indent-of ctx)
                      (case (t ctx)
                        :go (str (kin/local-name ctx nm) " := "
                                 (core/strip-parens text))
                        :java (str (get-in (or (kin/tag ctx (:tag (meta nm))) tag)
                                           [:types :java])
                                   " " (kin/local-name ctx nm) " = "
                                   (core/strip-parens text)))
                      (semi ctx) "\n")))
    (doseq [f body] (kin/statement! ctx f))))

(defn- for-form
  "`(while test body...)` -- Go spells every loop `for`."
  [ctx form]
  ;; `for (y != 0)` is legal Go and not what anyone writes. The test is a
  ;; whole expression with nothing to bind to, which is the safe case to
  ;; strip.
  (kin/emit! ctx (kin/indent-of ctx)
                (let [c (core/strip-parens (kin/render ctx (second form)))]
                  (case (t ctx)
                    ;; Go spells every loop `for`.
                    :go (str "for " c " {\n")
                    :java (str "while (" c ") {\n"))))
  (kin/scoped ctx {:key :loop :value true :indent 1}
                 (fn [inner] (doseq [f (drop 2 form)] (kin/statement! inner f))))
  (kin/emit! ctx (kin/indent-of ctx) "}\n"))

(defn- op [sym result]
  (fn [ctx form]
    (let [as (mapv (fn [f] (kin/render ctx f)) (rest form))]
      (kin/tagged! ctx result)
      (kin/emit! ctx (str "(" (str/join (str " " sym " ") as) ")")))))

(defn- comment-form [ctx form]
  (doseq [line (rest form)] (kin/emit! ctx (kin/indent-of ctx) "// " line "\n")))

(defn need!
  "Say that this file needs `what` in scope. DATA, not text.

  The form describes what it needs and knows nothing about how a header is
  written; `:emit` reads the collected set afterwards, dedupes, SORTS and
  formats it. That division is the same one as everywhere else in kin -- the
  form says WHAT, the target says HOW -- and it is why the same `to-str`
  below serves a language that writes `import \"strconv\"` and one that writes
  `import java.util.Objects;`.

  kin's part is only carrying the atom: `:emit` put it in scope with
  `scoped`, and a form reaches it from arbitrarily deep with `get`.
  kin does not know what an import IS."
  [ctx what]
  (when-let [needs (kin/get ctx :needs)]
    (swap! needs conj what)))

(defn- to-str-form
  "`(to-str x)` -- an integer as text, and the reason imports exist here.

  Go reaches for `strconv` and Java for `java.util.Objects`, so BOTH need
  something at the top of the file that the call site knows about and the
  file header cannot. This is the case that a spliced region got wrong: the
  host file had its imports right once, and generated code needing one more
  had nowhere to say so."
  [ctx form]
  (let [x (core/strip-parens (kin/render ctx (second form)))]
    (kin/tagged! ctx Str)
    (case (t ctx)
      :go (do (need! ctx "strconv")
              (kin/emit! ctx "strconv.Itoa(" x ")"))
      :java (do (need! ctx "java.util.Objects")
                (kin/emit! ctx "Objects.toString(" x ")")))))

(def vocabulary
  {:namespace 'example.lang
   :targets #{:go :java}
   :tags {'I32 I32 'Bool Bool 'Str Str}
   :names {}
   :literal-tag (fn [v] (when (integer? v) I32))
   :forms {'defn defn-form
           'return return-form
           'let let-form
           'set assign-form
           'while for-form
           'comment comment-form
           'to-str to-str-form
           '!= (op "!=" Bool)
           '> (op ">" Bool)
           'rem (op "%" I32)}})
