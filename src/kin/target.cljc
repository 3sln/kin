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

(def rust
  {:key :rust :local-name snake :fn-name snake :ext "rs"
   :line-comment "//" :doc-comment "///"})

(def java
  {:key :java :local-name camel :fn-name camel :ext "java"
   :line-comment "//" :doc-comment "///"})

(def csharp
  {:key :csharp :local-name camel :fn-name pascal :ext "cs"
   :line-comment "//" :doc-comment "///"})

(def defaults
  "The three kin ships with. A project takes these, extends them, replaces
  them, or supplies its own entirely -- they carry no special status."
  {:rust rust :java java :csharp csharp})
