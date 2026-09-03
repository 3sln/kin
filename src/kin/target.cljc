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

(def defaults
  "The three kin ships with. A project takes these, extends them, replaces
  them, or supplies its own entirely -- they carry no special status."
  {:rust rust :java java :csharp csharp})
