#!/usr/bin/env bb
;; A PARAMETER named after a target's reserved word.
;;
;;     bb test/reserved.clj
;;
;; `kin.target` has carried the machinery for this since it was written, and
;; the comment above `rust-reserved` names this exact case: "`base` is an
;; ordinary local in Rust and Java and a keyword in C#, so a source that says
;; `base` -- which the hand-written runtime does -- generates two files that
;; compile and one that does not."
;;
;; It did. The escape reached every USE of a parameter in a body and none of
;; the three SIGNATURES, which built their names with `snake`/`camel` directly
;; instead of the target's `:local-name`. `list-from-roots` in the flint tree
;; emitted `rt.R(@base + ...)` in a body whose signature said `int base`, so
;; the C# would not parse -- CS1001, at the parameter, in generated code.
;;
;; Pinned per target because the three answers differ and only one of them is
;; "escape it": Rust has `r#`, C# has `@`, and Java has NO verbatim identifier
;; and must REFUSE.
(require '[kin] '[kin.lang :as core] '[kin.target] '[clojure.string :as str])

(def I32 {:name 'I32 :types {:rust "u32" :java "int" :csharp "int"}})
(def Rt* {:name 'Rt :types {:rust "Rt" :java "Rt" :csharp "Rt"}})

(def vocabulary
  {:namespace 'demo
   :targets #{:rust :java :csharp}
   :tags {'I32 I32 'Rt Rt*}
   :names {}
   :literal-tag (fn [v] (when (integer? v) I32))
   :forms (core/forms {:default-tag I32})})

(def targets {:rust kin.target/rust :java kin.target/java :csharp kin.target/csharp})

(defn render [target forms]
  (let [vocabs {'demo vocabulary}
        scope (kin/require-scope '(ns probe (:require [demo :refer [defn let return I32 Rt]]))
                                 vocabs)
        ctx (assoc (kin/context {} target)
                   :vocabs vocabs :scope-syms scope :vocab-order ['demo]
                   :targets targets
                   :locals (atom {}) :names (atom {})
                   :local-tags (atom {}) :tmp (atom 0))]
    (doseq [f forms] (kin/statement! ctx f))
    (kin/output ctx)))

(def failures (atom 0))
(defn is [label pred actual]
  (if (pred actual)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         actual %s" label (pr-str actual))))))

(println "\nreserved: a parameter named after a target's keyword\n")

(def src '[(defn ^:method ^I32 f [^Rt rt ^I32 base] (return base))])

;; C#: `base` is a keyword, and `@base` is the escape -- in the SIGNATURE as
;; well as the body, which is the whole of this regression.
(let [out (render :csharp src)]
  (is "csharp escapes it in the signature" #(str/includes? % "int @base") out)
  (is "csharp escapes it in the body too"  #(str/includes? % "return @base") out)
  (is "csharp leaves no bare `int base`"   #(not (str/includes? % "int base")) out))

;; Rust: `base` is NOT reserved, so nothing happens to it. The escape must not
;; fire on a word that merely collides somewhere else -- reserved is per target.
(let [out (render :rust src)]
  (is "rust leaves an unreserved name alone" #(str/includes? % "base: u32") out)
  (is "rust does not invent an escape"       #(not (str/includes? % "r#base")) out))

;; Java: `base` is not reserved there either.
(let [out (render :java src)]
  (is "java leaves an unreserved name alone" #(str/includes? % "int base") out))

;; And the negative that makes the design a design: Java has no verbatim
;; identifier, so a parameter it genuinely cannot spell is REFUSED by name
;; rather than mangled into something that compiles and means something else.
(let [thrown (try (render :java '[(defn ^:method ^I32 f [^Rt rt ^I32 static] (return static))])
                  nil
                  (catch Exception e (ex-message e)))]
  (is "java REFUSES a reserved parameter, naming it"
      #(and % (str/includes? % "static") (str/includes? % "reserved"))
      thrown))

(println (if (zero? @failures)
           "\nreserved: a keyword in one language is a keyword in its signature too\n"
           (format "\nreserved: %d FAILURE(S)\n" @failures)))
(System/exit (if (zero? @failures) 0 1))
