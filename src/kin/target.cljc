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
    :reserved    words this language will not accept as an identifier
    :indent-unit one level of indentation, defaulting to four spaces
    :hoists?     may a function be called before it is defined? default true
    :forward-declaration  (fn [ctx sym] -> String) when it may not
    :dest        where this target's files live -- a root path
    :path        (fn [ns] -> String or nil) namespace -> file, under :dest
    :indent      (fn [ns] -> int) how deep the region sits in its file
                 -- PROVISIONAL, see below

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
  (:require [clojure.string :as str]))

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

(def rust-reserved
  #{"as" "box" "break" "const" "continue" "crate" "dyn" "else" "enum" "extern"
    "false" "fn" "for" "if" "impl" "in" "let" "loop" "match" "mod" "move"
    "mut" "pub" "ref" "return" "self" "static" "struct" "super" "trait"
    "true" "type" "unsafe" "use" "where" "while" "async" "await"})

(def rust
  {:key :rust :ext "rs" :line-comment "//" :doc-comment "///"
   :reserved rust-reserved
   :local-name (namer snake #(str "r#" %) rust-reserved)
   :fn-name (namer snake #(str "r#" %) rust-reserved)})

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
   :fn-name (namer camel nil java-reserved)})

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
   :fn-name (namer pascal #(str "@" %) csharp-reserved)})

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
  "The three kin ships with. A project takes these, extends them, replaces
  them, or supplies its own entirely -- they carry no special status."
  {:rust rust :java java :csharp csharp})
