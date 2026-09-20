#!/usr/bin/env bb
;; A WHOLE NUMBER too large for a 32-bit int.
;;
;;     bb test/literals.clj
;;
;; Four targets, four rules about the digits of an integer literal, and only
;; one of them needs anything added:
;;
;;   * Rust infers a literal's type from where it is used.
;;   * Go's untyped constants convert on assignment.
;;   * C# takes the first type in its own list that fits, so a literal past
;;     `int` is simply a `long`.
;;   * JAVA treats every integer literal as an `int` unless it carries an
;;     `L`, and one that does not fit is a compile error AT THE DIGITS --
;;     `integer number too large` -- whatever it was about to be cast to.
;;
;; It turned up on a fixed-point scale of 65536 squared, which three targets
;; compiled and the fourth refused; nome's identity gate was what noticed,
;; because that is the only place all four are built from one source.
;;
;; Pinned per target because the answers differ, and pinned NEGATIVELY for
;; the ones that need nothing: a suffix invented for a target that did not
;; ask for one is a spelling kin made up.
;;
;; GO IS NOT HERE. These tests render a `^:method`, which is a shape Go's
;; target does not take, and building a second scaffold for one assertion
;; would be testing the scaffold. Go's untyped constants convert on
;; assignment, and nome's identity gate compiles this very literal in all
;; four languages, which is where that claim is actually held.
(require '[kin] '[kin.lang :as core] '[kin.target] '[clojure.string :as str])

(def I64 {:name 'I64 :types {:rust "i64" :java "long" :csharp "long"}})
(def Rt* {:name 'Rt :types {:rust "Rt" :java "Rt" :csharp "Rt"}})

(def vocabulary
  {:namespace 'demo
   :targets #{:rust :java :csharp}
   :tags {'I64 I64 'Rt Rt*}
   :names {}
   :literal-tag (fn [v] (when (integer? v) I64))
   :forms (core/forms {:default-tag I64})})

(def targets {:rust kin.target/rust :java kin.target/java
              :csharp kin.target/csharp})

(defn render [target forms]
  (let [vocabs {'demo vocabulary}
        scope (kin/require-scope '(ns probe (:require [demo :refer [defn let return I64 Rt]]))
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

(println "\nliterals: a whole number past what an int holds\n")

(def big '[(defn ^:method ^I64 f [^Rt rt] (return 4294967296))])
(def small '[(defn ^:method ^I64 f [^Rt rt] (return 65536))])
(def negative '[(defn ^:method ^I64 f [^Rt rt] (return -4294967296))])

;; Java, the one that needs it.
(let [out (render :java big)]
  (is "java suffixes a literal past int" #(str/includes? % "4294967296L") out)
  (is "java leaves no bare digits behind"
      #(not (re-find #"4294967296(?!L)" %)) out))
(let [out (render :java negative)]
  (is "java suffixes a negative one too" #(str/includes? % "4294967296L") out))

;; AND NOT ONE THAT FITS. The suffix is for the literals that need it; on a
;; number an int holds it would be noise, and noise in generated code is
;; indistinguishable from meaning.
(let [out (render :java small)]
  (is "java leaves a literal that fits alone"
      #(and (str/includes? % "65536") (not (str/includes? % "65536L"))) out))

;; The three that take the digits as written.
(doseq [[t label] [[:rust "rust"] [:csharp "csharp"]]]
  (let [out (render t big)]
    (is (str label " takes the digits as written")
        #(and (str/includes? % "4294967296")
              (not (str/includes? % "4294967296L"))) out)))

(println (if (zero? @failures)
           "\nliterals: the digits are Java's problem and Java's alone\n"
           (format "\nliterals: %d FAILURE(S)\n" @failures)))
(System/exit (if (zero? @failures) 0 1))
