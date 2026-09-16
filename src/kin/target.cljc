(ns kin.target
  "A TARGET is a description, not a keyword.

  kin cannot support every language and should not pretend to. What it can do
  is say what it needs to know about one, and let a project supply that for a
  language kin has never heard of. So a target is a map of functions and
  facts, and the three below are DEFAULTS -- shipped because they are the ones
  this was built against, and meant to be merged into, overridden, or ignored
  entirely in favour of a project's own.

  The keys:

    :key         what a vocabulary's per-target maps are keyed by
    :local-name  (fn [ctx sym] -> String) for a local
    :fn-name     (fn [ctx sym] -> String) for a function
    :unit        (fn [ns] -> unit or nil) what a namespace COMPILES TO
    :emit        (fn [ctx forms]) the whole file, this target's way
    :reserved    words this language will not accept as an identifier
    :indent-unit one level of indentation, defaulting to four spaces
    :hoists?     may a function be called before it is defined? default true
    :forward-declaration  (fn [ctx sym] -> String) when it may not
    :dest        where this target's files live -- a root path
    :path        (fn [ns] -> String or nil) namespace -> file, under :dest
    :indent      (fn [ns] -> int) how deep the region sits in its file
                 -- PROVISIONAL, see below
    :link        (fn [link-data vfs file-path]
                   -> {:link-fn ... :arity ... ...}) what a `@kin:link:`
                 annotation in this language's host source MEANS

  `:link` IS WHERE THE PAYLOAD IS INTERPRETED, and kin ships none -- that is
  the point rather than an omission. A host annotation's payload is opaque
  EDN whose format the target invented, so the target is the only thing that
  can read one; kin knows the marker and the name, hands the data over, and
  uses only what comes back. What comes back is a `:link-fn` (or, for a tag,
  a `:type`), an optional `:arity`, and whatever else that target can
  usefully say -- and THAT is what the cross-target and call-site checks are
  built from. See `kin.host`.

  DESTINATION IS COMPUTED, NOT LISTED. `:dest` and `:path` are how a target
  says where a namespace's generated code goes, and the pair is deterministic:
  give it a namespace and it answers a file, with no table anywhere. That
  replaces a per-source sidecar listing target, file and indent -- sixteen of
  them in the one project using kin, each a restatement of a rule nobody had
  written down.

  `:path` MAY ANSWER NIL, and that is not an error. A source can generate for
  a target and be written nowhere: `unsigned.kin` in the flint tree generates
  for three targets and exists to be VERIFIED rather than shipped. `Generates
  for` and `is written somewhere` are different questions and have to stay
  separable.

  `:indent` IS PROVISIONAL. It stands in for `:wrap`, whose semantics were
  never written down: the generated code sits inside `impl Rt { }` in Rust and
  `class Maps { }` on the ports, and that surrounding text is HAND-WRITTEN in
  the host files today with the kin markers nested inside it. Whether `:wrap`
  owns that text (so kin generates the `impl` line and the host files change
  shape) or only the indent it implies (so they do not) is not recoverable
  from the mention that introduced it, so it is asked rather than guessed --
  `doc/decisions.md`, under `Open, and not to be guessed`. When answered,
  `:indent` is what `:wrap` replaces.

  The name mappers are FUNCTIONS OF THE CONTEXT, not of the symbol alone.
  That matters for more than tidiness: a name may need to depend on what
  encloses it -- whether this is a method or a free function, what the target
  is, what the source declared nearby -- and a mapper that only sees a string
  can never answer those. Reserved words are the first case that needed it and
  will not be the last, so the escaping lives INSIDE the mapper rather than in
  the library wrapping it.
    :fn-name     a dashed function name, likewise
    :ext         the file extension of a generated region's host file
    :line-comment  how a comment begins
    :doc-comment   how a doc comment begins

  Nothing here is privileged. `(assoc rust :local-name identity)` is a valid
  target, and so is a map with no relation to any of these three."
  (:require [clojure.string :as str]
            [kin]))

(defn snake [s] (str/replace (str s) "-" "_"))

(defn camel [s]
  (let [[h & r] (str/split (str s) #"-")] (str h (str/join (mapv str/capitalize r)))))

(defn pascal [s]
  (str/join (mapv str/capitalize (str/split (str s) #"-"))))

(defn namer
  "Build a `(fn [ctx sym] -> String)` from a spelling and an escape.

  `escape` is `nil` for a language with no verbatim identifier, and then a
  reserved word is REFUSED by name rather than mangled: the source, the target
  and the word all appear in the message, which is a better place to learn it
  than a build error in generated code."
  [spell escape reserved]
  (fn [ctx sym]
    (let [s (spell (str sym))]
      (if-not (contains? reserved s)
        s
        (if escape
          (escape s)
          (throw (ex-info (str "kin: `" s "` is a reserved word in "
                               (name (:target ctx)) ", which has no escape -- "
                               "rename it in the source")
                          {:name s :target (:target ctx)})))))))

;; RESERVED WORDS are a per-target fact like any other, and one that only
;; shows up when a source happens to name something badly. `base` is an
;; ordinary local in Rust and Java and a keyword in C#, so a source that says
;; `base` -- which the hand-written runtime does -- generates two files that
;; compile and one that does not.
;;
;; Rust and C# can escape: `r#base` and `@base` are legal identifiers. Java
;; cannot, so a Java-reserved word has to be renamed at the source. `:escape`
;; being nil says so, and is better than a silent mangling.

;; --------------------------------------------------------------- the unit
;;
;; WHAT A NAMESPACE COMPILES TO, and why it is one derivation rather than two.
;;
;; The linker asks two questions that look like one and are not:
;;
;;   WHERE AM I?      answerable only of the file being written, so it comes
;;                    out of a scope frame -- `:kin/unit`.
;;   WHERE IS THAT?   asked at a reference, about a namespace whose `:emit`
;;                    is not running and has no frame anywhere. No scope can
;;                    answer it, so it is a FUNCTION of the namespace --
;;                    `:unit`.
;;
;; Both were already here and nothing connected them, so a project had to
;; write the connection by hand -- and every project that skipped half of it
;; got the half-broken configuration silently: a call that crosses a unit
;; qualifies itself (`home` comes off the frame) while a NAME does not
;; (`home` comes off `:unit`, which nobody set). The default `:emit` below
;; computes the frame FROM `:unit`, which is what makes the two sides agree
;; by construction rather than by discipline.
;;
;; A UNIT IS A MAP, not a string, because one string cannot answer both
;; halves of a cross-unit reference: `Champ.bnDatamap` wants the SIMPLE name
;; and `import flint.rt.Champ;` wants the qualified one. A string is still
;; accepted everywhere -- `unit-label` and `unit-import` both take one -- so
;; a project that already returns one keeps working.

(def ^:private unit-separator
  "How this language spells the join between a package and what is in it."
  {:rust "::"})

(def ^:private unit-capitalises
  "Languages whose unit is a CLASS, and so is named like one."
  #{:java :csharp})

(def ^:private unit-prefix
  "What every intra-project path begins with, where the language has one.
  Rust does: a sibling module is reached as `crate::...`, and there is no
  spelling of it that omits the crate root."
  {:rust "crate"})

(defn module-unit
  "What `ns-sym` compiles to for `target`, for a target that mirrors a
  namespace onto a package or module path.

      flint.rt.champ  ->  {:ns flint.rt.champ
                           :name \"Champ\"
                           :parent \"flint.rt\"
                           :qualified \"flint.rt.Champ\"}          :java, :csharp

                          {:ns flint.rt.champ
                           :name \"champ\"
                           :parent \"crate::flint::rt\"
                           :qualified \"crate::flint::rt::champ\"} :rust

  `:ns` IS CARRIED, and it is what a unit is compared BY. `:name` and
  `:qualified` are spellings, and a project changes both the day it adds a
  root or a casing rule; the namespace does not move. A comparison on the
  spelling would also quietly say YES for two different namespaces whose last
  segments agree, which is `flint.rt.eq` against `flint.gc.eq` -- a real
  shape, and one that would silently drop an import.

  THREE OPTIONS, and they are the whole of what a project has to say: `kin`
  cannot guess that a Java tree is reverse-DNS, that a C# one PascalCases its
  namespace segments, or that a leading `flint.` is already said by the root.
  `root` is prepended, `drop` is how many leading segments of the NAMESPACE
  go first, and `pascal-parents?` capitalises the segments the namespace
  contributed -- not the root, which is written as the project wants it.
  Supplying them is a one-line `:unit` override rather than a whole `:emit`,
  which is the point --

      :unit (fn [ns] (target/module-unit
                      :java ns {:root [\"com\" \"acme\" \"kgen\"] :drop 1}))

  The shape mirrors `module-path` deliberately: the path a namespace is
  WRITTEN to and the name it is IMPORTED by are two spellings of one fact,
  and a target whose two answers disagree emits files that cannot see each
  other."
  ([target ns-sym] (module-unit target ns-sym nil))
  ([target ns-sym {:keys [root drop pascal-parents?]}]
   (let [sep (get unit-separator target ".")
         own (clojure.core/drop (or drop 0) (str/split (str ns-sym) #"\."))
         segs (concat (when-let [p (get unit-prefix target)] [p])
                      root
                      (if pascal-parents?
                        (concat (mapv pascal (butlast own)) [(last own)])
                        own))
         parent (butlast segs)
         nm (cond-> (str (last segs))
              (contains? unit-capitalises target) pascal)]
     {:ns ns-sym
      :name nm
      :parent (when (seq parent) (str/join sep parent))
      :qualified (str/join sep (concat parent [nm]))})))

(defn unit-of
  "What `ns-sym` compiles to for the target being emitted, or nil.

  A target that names no `:unit` has no units and gets nil, which is right:
  a project splicing regions into somebody else's file has no boundary to
  cross. Read from the target rather than the scope because the namespace
  asked about is usually NOT the one being emitted."
  [ctx ns-sym]
  (when-let [f (get-in ctx [:targets (:target ctx) :unit])] (f ns-sym)))

(defn unit-label
  "How a reference SPELLS a unit -- the simple name."
  [u]
  (if (map? u) (:name u) (str u)))

(defn unit-import
  "How a header NAMES a unit -- the qualified name."
  [u]
  (if (map? u) (:qualified u) (str u)))

;; ------------------------------------------------------------ the default
;;
;; A WHOLE FILE, for a target that has not said otherwise.
;;
;; `examples/go` is the reference and this is that pattern, generalised: drop
;; an anchor where the header goes, put an atom beside it, run the forms --
;; each of which `need!`s a DESCRIPTION of what it reached for -- then read
;; the atom, dedupe, SORT, format and emit against the anchor. The anchor is
;; resolved when the buffer is joined, so the last step writes into a place
;; the output went past several hundred lines ago.
;;
;; THE SORT IS NOT TIDINESS. A set is unordered. An unsorted header would
;; come out in a different order on two runs of the same input, and a drift
;; gate -- whose whole job is comparing committed output against a fresh
;; generation -- would report a difference that is not there. A gate that
;; fails at random gets re-run until it passes and then gets ignored.

(defn ns-form-of [forms]
  (first (filter #(and (seq? %) (= 'ns (first %))) forms)))

(defn body-of [forms]
  (remove #(and (seq? %) (= 'ns (first %))) forms))

(defn emit-body
  "Every form of the body, in source order."
  [ctx forms]
  (doseq [f forms] (kin/statement! ctx f)))

(defn with-unit
  "Emit `f` inside `unit`, with a header built from what it asked for.

  THE THREE STEPS, and they are the whole pattern:

  1. drop an anchor where the header goes, and open a `:needs` frame beside
     it -- an atom, so a form buried anywhere inside can reach it;
  2. run the forms inside the `:kin/unit` frame, so every reference can tell
     whether it is leaving this unit, and `need!` when it is;
  3. read the atom, dedupe, SORT, format and emit against the anchor.

  `render-header` is handed the needs, already sorted, and answers the text.
  A target that wants no header passes one that answers `\"\"`; a target that
  opens no `:needs` frame at all -- by not calling this -- gets nothing,
  which is what `need!` already promises.

  PUBLIC, so that a project writing its own `:emit` -- a different wrapper, a
  different header, a prelude of its own -- gets the pattern rather than a
  fourth transcription of it. Such a project supplies its own `:unit` too:
  the frame this opens says where a reference IS, and only `:unit` can say
  where the namespace it reaches for went."
  [ctx unit render-header f]
  (let [at (kin/emit-anchor! ctx)
        needs (atom #{})]
    (kin/scoped ctx {:key :kin/unit :value unit}
                (fn [i1] (kin/scoped i1 {:key :needs :value needs} f)))
    (when (seq @needs)
      (kin/emit! at (render-header (sort-by unit-import @needs)) "\n"))
    nil))

(defn- banner!
  "Who wrote this file and from what."
  [ctx comment-prefix ns-name]
  (kin/emit! ctx comment-prefix " Generated by kin. Do not edit.\n")
  (kin/emit! ctx comment-prefix " source: " (str ns-name) "\n\n"))

(defn module-emitter
  "An `:emit` that carries the unit derivation it was written against.

  IT INSTALLS `unit-fn` ONLY WHERE THE TARGET NAMED NONE, and that is the
  whole of the backward-compatibility argument. The derivation and the
  placement have to agree -- a frame naming one thing and a `:unit` naming
  another is worse than neither -- so they ship together and a target that
  supplies its own `:unit` keeps it. A project that supplies its own `:emit`
  never reaches this at all, and so does not inherit a derivation it did not
  ask for: replacing the placement must not silently change how a name in
  another namespace is spelled.

  Installing it into the CONTEXT rather than the target map means the
  derivation is visible exactly where it is asked -- `unit-of` reads it off
  the context at the reference site -- and nowhere else."
  [unit-fn emit]
  (fn [ctx forms]
    (emit (if (get-in ctx [:targets (:target ctx) :unit])
            ctx
            (assoc-in ctx [:targets (:target ctx) :unit] unit-fn))
          forms)))

(defn receiver-tag
  "The TAG of a form's receiver, if it declares one, else nil.

  ASKED OF THE SOURCE, not of a table and not of a list of form heads. What
  it looks for is the metadata kin itself defines -- `^:method` and
  `^:instance`, which `kin.lang`'s `defn` reads to decide there is a receiver
  at all -- so a declaration form named something other than `defn` is
  covered the day it is written, and a head list that has to be maintained is
  not introduced here to be forgotten later."
  [form]
  (when (and (seq? form) (>= (count form) 3))
    (let [m (meta (second form))
          params (nth form 2)]
      (when (and (or (:method m) (:instance m))
                 (sequential? params) (seq params))
        (:tag (meta (first params)))))))

(defn- rust-file
  "A Rust MODULE: banner, `use` lines, then the items -- with the METHODS,
  and only the methods, inside an `impl`.

  THE `impl` IS DERIVED FROM THE SOURCE, which is the difference from a
  sidecar saying `this file wraps`. A method's receiver carries a tag, the
  tag names a type, and `impl T { }` in another module of the same crate is
  ordinary Rust -- an inherent impl may live anywhere in the defining crate.

  IT WRAPS THE METHODS AND NOT THE FILE, and the difference is not cosmetic:
  a `defdata` emits a module-level `static`, and Rust has no associated
  `static` in an inherent impl, so a file holding both a table and a method
  would not compile if one `impl` were wrapped round the lot. Runs of methods
  and runs of items alternate in SOURCE ORDER, each run of methods in its own
  `impl` -- Rust allows as many as it likes -- and each run asking its own
  first receiver for the type, so a file with two receivers is not silently
  filed under one.

  NO PER-REFERENCE IMPORT FOR A METHOD, and that is not a special case here
  -- it falls out. A method call is spelled `recv.name(..)`, so it never asks
  the qualifier for a spelling and never fires `need!`. What does fire is a
  free function or a module-level constant reached from another module, and a
  glob `use` is exactly what Rust wants for those."
  [ctx forms]
  (let [ns-name (second (ns-form-of forms))
        body (body-of forms)]
    (banner! ctx "//" ns-name)
    (with-unit
      ctx (unit-of ctx ns-name)
      (fn [needs]
        (str/join (map (fn [n] (str "use " (unit-import n) "::*;\n")) needs)))
      (fn [inner]
        (doseq [run (partition-by (comp some? receiver-tag) body)]
          (if-let [ty (when-let [recv (receiver-tag (first run))]
                        (get-in (kin/tag inner recv) [:types :rust]))]
            (do (kin/emit! inner "impl " ty " {\n")
                (kin/scoped inner {:key :impl :value ty :indent 1}
                            (fn [in2] (emit-body in2 run)))
                (kin/emit! inner "}\n"))
            (emit-body inner run)))))))

(defn- class-file
  "A CLASS FILE: banner, the package or namespace line, imports, wrapper.

  The wrapper is not a workaround. Java and C# have no top-level function, so
  a place to put one is what a class IS here -- the same job a Rust module
  does. The unit and the class are therefore the same thing, which is why the
  `:kin/unit` frame and the `:class` frame are opened over the same value.

  A SIBLING IN THE SAME PACKAGE IS ALREADY IN SCOPE, in both languages, so a
  need whose parent is this file's parent produces no line. It would be legal
  and it would be noise -- and under the shipped `module-unit`, where every
  namespace of a project shares one parent, it would be noise in every file.
  A unit with NO parent at all is in the default package, where there is
  nothing to import and, in Java, no legal way to say it."
  [ctx forms {:keys [package-line class-line import-line]}]
  (let [ns-name (second (ns-form-of forms))
        unit (unit-of ctx ns-name)
        cls (unit-label unit)
        here (and (map? unit) (:parent unit))
        already-here? (fn [n] (and (map? n) (or (nil? (:parent n))
                                                (= here (:parent n)))))]
    (banner! ctx "//" ns-name)
    (when here (kin/emit! ctx (package-line here)))
    (with-unit
      ctx unit
      ;; DEDUPED AFTER RENDERING, not before. Two siblings in one package are
      ;; two needs and one `using` in C#, and a file that says the same
      ;; directive twice does not compile there.
      (fn [needs]
        (str/join (distinct (keep #(when-not (already-here? %) (import-line %))
                                  needs))))
      (fn [inner]
        (kin/emit! inner (class-line cls))
        (kin/scoped inner {:key :class :value cls :indent 1}
                    (fn [in2] (emit-body in2 (body-of forms))))
        (kin/emit! inner "}\n")))))

(defn- java-file
  "Java IMPORTS THE TYPE: `Champ.bnDatamap(..)` needs `Champ` in scope, which
  is `import flint.rt.Champ;` -- the unit's qualified name exactly."
  [ctx forms]
  (class-file ctx forms
              {:package-line (fn [p] (str "package " p ";\n\n"))
               :import-line (fn [n] (str "import " (unit-import n) ";\n"))
               :class-line (fn [c] (str "public final class " c " {\n"))}))

(defn- csharp-file
  "C# IMPORTS THE NAMESPACE, which is the one place the two class languages
  differ. `using static X.Champ;` brings Champ's MEMBERS into scope and not
  the name `Champ`, so a `Champ.LIMIT` written by the default cross-unit rule
  would not resolve; `using X;` is what puts the type there.

  The file-scoped `namespace X;` comes first and the `using` lines after it,
  where they are directives inside that namespace -- legal, and what lets one
  anchor serve both languages."
  [ctx forms]
  (class-file ctx forms
              {:package-line (fn [p] (str "namespace " p ";\n\n"))
               ;; A UNIT NAMES ITS PARENT; ANYTHING ELSE IS WRITTEN AS GIVEN.
               ;; A vocabulary may `need!` a plain string -- `System.Numerics`
               ;; -- and the default has no business guessing at it or, worse,
               ;; dropping it silently.
               :import-line (fn [n] (str "using " (if (map? n)
                                                    (:parent n)
                                                    (unit-import n)) ";\n"))
               :class-line (fn [c] (str "public static class " c " {\n"))}))

(def rust-reserved
  #{"as" "box" "break" "const" "continue" "crate" "dyn" "else" "enum" "extern"
    "false" "fn" "for" "if" "impl" "in" "let" "loop" "match" "mod" "move"
    "mut" "pub" "ref" "return" "self" "static" "struct" "super" "trait"
    "true" "type" "unsafe" "use" "where" "while" "async" "await"})

(def rust
  {:key :rust :ext "rs" :line-comment "//" :doc-comment "///"
   :reserved rust-reserved
   :local-name (namer snake #(str "r#" %) rust-reserved)
   :fn-name (namer snake #(str "r#" %) rust-reserved)
   :emit (module-emitter #(module-unit :rust %) rust-file)})

(def java-reserved
  #{"abstract" "assert" "boolean" "break" "byte" "case" "catch" "char" "class"
    "const" "continue" "default" "do" "double" "else" "enum" "extends" "final"
    "finally" "float" "for" "goto" "if" "implements" "import" "instanceof"
    "int" "interface" "long" "native" "new" "package" "private" "protected"
    "public" "return" "short" "static" "strictfp" "super" "switch"
    "synchronized" "this" "throw" "throws" "transient" "try" "void"
    "volatile" "while"})

(def java
  {:key :java :ext "java" :line-comment "//" :doc-comment "///"
   :reserved java-reserved
   ;; No verbatim identifier: a collision is refused, not escaped.
   :local-name (namer camel nil java-reserved)
   :fn-name (namer camel nil java-reserved)
   :emit (module-emitter #(module-unit :java %) java-file)})

(def csharp-reserved
  #{"abstract" "as" "base" "bool" "break" "byte" "case" "catch" "char"
    "checked" "class" "const" "continue" "decimal" "default" "delegate" "do"
    "double" "else" "enum" "event" "explicit" "extern" "false" "finally"
    "fixed" "float" "for" "foreach" "goto" "if" "implicit" "in" "int"
    "interface" "internal" "is" "lock" "long" "namespace" "new" "null"
    "object" "operator" "out" "override" "params" "private" "protected"
    "public" "readonly" "ref" "return" "sbyte" "sealed" "short" "sizeof"
    "stackalloc" "static" "string" "struct" "switch" "this" "throw" "true"
    "try" "typeof" "uint" "ulong" "unchecked" "unsafe" "ushort" "using"
    "virtual" "void" "volatile" "while"})

(def csharp
  {:key :csharp :ext "cs" :line-comment "//" :doc-comment "///"
   :reserved csharp-reserved
   :local-name (namer camel #(str "@" %) csharp-reserved)
   :fn-name (namer pascal #(str "@" %) csharp-reserved)
   :emit (module-emitter #(module-unit :csharp %) csharp-file)})

(def go-reserved
  "Go's twenty-five keywords, and the predeclared identifiers a generated
  name must not collide with either. `len`, `cap`, `new` and `make` are not
  keywords -- they can be shadowed, legally -- but a generated `func Len` that
  shadows the builtin inside its own package is the kind of code the not-worse
  rule exists to refuse."
  #{"break" "case" "chan" "const" "continue" "default" "defer" "else"
    "fallthrough" "for" "func" "go" "goto" "if" "import" "interface" "map"
    "package" "range" "return" "select" "struct" "switch" "type" "var"
    "append" "cap" "close" "complex" "copy" "delete" "imag" "len" "make"
    "new" "panic" "print" "println" "real" "recover"})

(defn go-namer
  "Go spells VISIBILITY IN THE NAME, so `^:pub` decides the first letter.

  This is the first of kin's namers that reads the symbol's METADATA, and it
  is why `namer` could not simply be reused: Rust has `pub`, Java has
  `public` and C# has `internal`, so for those three the spelling and the
  visibility are independent and a namer needs only the symbol. Go has no
  keyword at all -- an identifier is exported exactly when it begins with an
  upper-case letter -- so the two questions are one question here.

  `locals?` is the other half. A local variable and a parameter are never
  exported, so they are always lower camel; only a top-level name asks about
  `^:pub`. Passing the wrong one gives a capitalised local, which compiles
  and is wrong in the way `golint` will tell you about."
  [locals?]
  (fn [ctx sym]
    (let [pascal-str (pascal (str sym))
          lower (str (str/lower-case (subs pascal-str 0 1)) (subs pascal-str 1))
          s (if (and (not locals?) (:pub (meta sym))) pascal-str lower)]
      (if-not (contains? go-reserved s)
        s
        ;; NO ESCAPE. Go has no raw-identifier syntax -- Rust's `r#type`, C#'s
        ;; `@class` -- so a collision is refused by name, as it is for Java.
        (throw (ex-info (str "kin: `" s "` is a keyword or a predeclared"
                             " identifier in Go, which has no escape for"
                             " either -- rename it in the source")
                        {:name s :target :go}))))))

(defn- go-file
  "A Go MODULE: banner, `package`, the import block, then the body.

  Nearer `rust-file` than `java-file` -- there is no wrapper type, because Go
  has top-level functions and a method carries its receiver on the `func`
  itself, which `kin.lang`'s Go `defn` already emits. So no `impl` runs to
  partition and no class to open.

  THE IMPORT BLOCK IS WRITTEN AGAINST AN ANCHOR, like every other target's
  header, so an import discovered deep in a function body still appears at the
  top. Go's parenthesised form is used even for one import, because `gofmt`
  leaves both alone and a block that grows by a line reads better in a diff
  than one that changes shape."
  [ctx forms]
  (let [ns-name (second (ns-form-of forms))
        body (body-of forms)]
    (banner! ctx "//" ns-name)
    (kin/emit! ctx "package " (last (str/split (str ns-name) #"\.")) "\n\n")
    (with-unit
      ctx (unit-of ctx ns-name)
      (fn [needs]
        (str "import (\n"
             (str/join (map (fn [n] (str "\t\"" (unit-import n) "\"\n")) needs))
             ")\n"))
      ;; A BLANK LINE BETWEEN TOP-LEVEL FORMS, which Go alone asks for.
      ;; `gofmt` leaves two adjacent `func`s alone, so kin's house style of
      ;; no separator held until a `defdata` put a multi-line `var` next to
      ;; one -- gofmt inserts a blank line there and rewrites the file. Being
      ;; the thing that separates forms is why `:emit` drives the loop rather
      ;; than handing kin a prefix and a suffix.
      (fn [inner]
        (doseq [[i f] (map-indexed vector body)]
          (when (pos? i) (kin/emit! inner "\n"))
          (kin/statement! inner f))))))

(def go
  "Go, as a target. `:vfs` and `:path` are the project's, as for the other
  three -- and so, for now, is `:unit`: Go needs TWO spellings where the
  others need one, an import path absolute from the module root for the
  header and a bare package name for a reference, and the module root is
  something kin cannot know. See nome's ROADMAP P0.4c."
  {:key :go :ext "go" :line-comment "//" :doc-comment "//"
   :indent-unit "\t"
   :reserved go-reserved
   :local-name (go-namer true)
   :fn-name (go-namer false)
   ;; GO HOISTS AT PACKAGE LEVEL -- a function may be called above its
   ;; definition -- so `declare*` emits nothing, as it does for the other
   ;; three, and no `:forward-declaration` is needed.
   :hoists? true
   :emit go-file})

(defn- rust-forward
  "Rust has no forward declaration for an inherent method, and does not need
  one: items in a `impl` block are visible to each other regardless of order.
  So `:hoists?` is true and this is unused -- kept as the worked shape for a
  target that genuinely cannot, C being the obvious one, where a `(declare
  foo)` has to become `int foo(int);` above the first use."
  [_ sym]
  (str "// forward: " sym))

(defn module-path
  "A namespace's file, under a generated ROOT, in the host convention.

      flint.rt.maps  ->  kingen/flint/rt/maps.rs      (:rust)
                         kingen/flint/rt/Maps.java    (:java)
                         kingen/flint/rt/Maps.cs      (:csharp)

  THE SHAPE IS THE SAME FOR ALL THREE, which is the part worth recording
  because it was not obvious: Rust mirrors the module path onto directories
  exactly as Java mirrors a package and C# a namespace. Only the FILENAME
  differs -- Rust keeps the tail as written, the other two capitalise it into
  a class name -- because only Rust has a module that is not a class.

  `root` is the generated subtree. Generated code lives parallel to the human
  source rather than inside it, which is also what settles the naming
  collision: `flint.rt.maps` writes to the generated tree and never contends
  with the hand-written `Maps`."
  [root ns-name {:keys [ext capitalise?]}]
  (let [segs (str/split (str ns-name) #"\.")
        dirs (butlast segs)
        tail (last segs)]
    (str/join "/" (concat (when (seq root) [root]) dirs
                          [(str (if capitalise? (pascal tail) tail) "." ext)]))))

(def defaults
  "The four kin ships with. A project takes these, extends them, replaces
  them, or supplies its own entirely -- they carry no special status."
  {:rust rust :go go :java java :csharp csharp})
