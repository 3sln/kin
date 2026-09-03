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
    :local-name  a dashed local, spelled the way this language spells one
    :reserved    words this language will not accept as an identifier
    :escape      how to make a reserved word usable, or nil if it cannot be
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

;; RESERVED WORDS are a per-target fact like any other, and one that only
;; shows up when a source happens to name something badly. `base` is an
;; ordinary local in Rust and Java and a keyword in C#, so a source that says
;; `base` -- which the hand-written runtime does -- generates two files that
;; compile and one that does not.
;;
;; Rust and C# can escape: `r#base` and `@base` are legal identifiers. Java
;; cannot, so a Java-reserved word has to be renamed at the source. `:escape`
;; being nil says so, and is better than a silent mangling.

(def rust
  {:key :rust :local-name snake :fn-name snake :ext "rs"
   :line-comment "//" :doc-comment "///"
   :reserved #{"as" "box" "break" "const" "continue" "crate" "dyn" "else"
               "enum" "extern" "false" "fn" "for" "if" "impl" "in" "let"
               "loop" "match" "mod" "move" "mut" "pub" "ref" "return" "self"
               "static" "struct" "super" "trait" "true" "type" "unsafe"
               "use" "where" "while" "async" "await" "-"}
   :escape #(str "r#" %)})

(def java
  {:key :java :local-name camel :fn-name camel :ext "java"
   :line-comment "//" :doc-comment "///"
   :reserved #{"abstract" "assert" "boolean" "break" "byte" "case" "catch"
               "char" "class" "const" "continue" "default" "do" "double"
               "else" "enum" "extends" "final" "finally" "float" "for" "goto"
               "if" "implements" "import" "instanceof" "int" "interface"
               "long" "native" "new" "package" "private" "protected" "public"
               "return" "short" "static" "strictfp" "super" "switch"
               "synchronized" "this" "throw" "throws" "transient" "try"
               "void" "volatile" "while"}
   ;; Java has no verbatim identifier. A collision must be renamed at source.
   :escape nil})

(def csharp
  {:key :csharp :local-name camel :fn-name pascal :ext "cs"
   :line-comment "//" :doc-comment "///"
   :reserved #{"abstract" "as" "base" "bool" "break" "byte" "case" "catch"
               "char" "checked" "class" "const" "continue" "decimal"
               "default" "delegate" "do" "double" "else" "enum" "event"
               "explicit" "extern" "false" "finally" "fixed" "float" "for"
               "foreach" "goto" "if" "implicit" "in" "int" "interface"
               "internal" "is" "lock" "long" "namespace" "new" "null"
               "object" "operator" "out" "override" "params" "private"
               "protected" "public" "readonly" "ref" "return" "sbyte"
               "sealed" "short" "sizeof" "stackalloc" "static" "string"
               "struct" "switch" "this" "throw" "true" "try" "typeof" "uint"
               "ulong" "unchecked" "unsafe" "ushort" "using" "virtual" "void"
               "volatile" "while"}
   :escape #(str "@" %)})

(def defaults
  "The three kin ships with. A project takes these, extends them, replaces
  them, or supplies its own entirely -- they carry no special status."
  {:rust rust :java java :csharp csharp})
