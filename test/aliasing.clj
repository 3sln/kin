#!/usr/bin/env bb
;; Does a binding say whether the callee sees the CALLER'S STORAGE?
;;
;;     bb test/aliasing.clj
;;
;; The divergence this exists to make unspellable: a `Vec<i32>` parameter is
;; COPIED in Rust and an `int[]` parameter is SHARED in Java, C# and Go. One
;; source spelling, two meanings, and every target compiles -- so nothing
;; reports it and a callee's writes are visible on three targets out of four.
;; `pike.rs` is the live instance (`DECISIONS.md#the-pike-vm-is-the-last-triplicate`).
;;
;; `^:mut` does NOT answer this, and the similar word is the trap. `^:mut`
;; says a binding is REASSIGNED -- Rust emits `mut`, the others emit nothing.
;; That is a question about the name; this is a question about the storage.
;;
;; Four obligations:
;;
;;   1. an UNMARKED binding renders exactly as it always did
;;   2. `^:shared` renders the tag's own `:shared` entry, per target
;;   3. a tag with NO entry for the mark is an ERROR, not a silent fallback
;;   4. `^:shared` and `^:copied` together is a source error
(require '[kin] '[kin.lang :as core] '[kin.target] '[clojure.string :as str])

;; A tag that HAS answered the question, for every target it speaks.
(def I32Array
  {:name 'I32Array
   :types {:rust "Vec<i32>" :java "int[]" :csharp "int[]"}
   :shared {:rust "&mut Vec<i32>" :java "int[]" :csharp "int[]"}
   :shared-arg {:rust "&mut {0}" :java "{0}" :csharp "{0}"}
   :copied {:rust "Vec<i32>" :java "int[]" :csharp "int[]"}
   :copied-arg {:rust "{0}.clone()" :java "{0}.clone()" :csharp "({0}.Clone() as int[])"}})

;; A tag that has NOT. Marking a binding with it must fail.
(def Blob {:name 'Blob :types {:rust "Blob" :java "Blob" :csharp "Blob"}})

;; A tag that says how a shared parameter is DECLARED and not how an argument
;; to one is WRITTEN. Half an answer, which must be refused like none at all.
(def HalfSaid
  {:name 'HalfSaid
   :types {:rust "Half" :java "Half" :csharp "Half"}
   :shared {:rust "&mut Half" :java "Half" :csharp "Half"}})
(def Rt* {:name 'Rt :types {:rust "Rt" :java "Rt" :csharp "Rt"}})
(def I32 {:name 'I32 :types {:rust "i32" :java "int" :csharp "int"}})

(def vocabulary
  {:namespace 'demo
   :targets #{:rust :java :csharp}
   :tags {'I32Array I32Array 'Blob Blob 'HalfSaid HalfSaid 'Rt Rt* 'I32 I32}
   :names {}
   :forms (core/forms {:default-tag I32})})

(def targets {:rust kin.target/rust :java kin.target/java :csharp kin.target/csharp})

(defn render [target forms]
  (let [vocabs {'demo vocabulary}
        ns-form '(ns probe (:require [demo :refer [defn return I32Array Blob HalfSaid Rt I32]]))
        scope (kin/require-scope ns-form vocabs)
        ctx (assoc (kin/context {} target)
                   :vocabs vocabs :scope-syms scope :vocab-order ['demo]
                   :targets targets
                   :locals (atom {}) :names (atom {})
                   :local-tags (atom {}) :tmp (atom 0))]
    (doseq [f forms] (kin/statement! ctx f))
    (kin/output ctx)))

(defn render-err [target forms]
  (try (render target forms) nil
       (catch Exception e (ex-message e))))

(def failures (atom 0))
(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %-52s %s" label (pr-str actual)))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))
(defn has [label needle actual]
  (if (and actual (str/includes? actual needle))
    (println (format "  ok   %-52s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         wanted to contain %s\n         actual   %s"
                         label (pr-str needle) (pr-str actual))))))

(println "\naliasing: does a binding say who owns the storage?\n")

;; 1. NOTHING ALREADY WRITTEN CHANGES. An unmarked parameter takes `:types`,
;;    which is the path every existing source is on.
(let [src '[(defn ^:method f [^Rt rt ^I32Array xs] (return))]]
  (has "1. unmarked renders `:types` on rust" "xs: Vec<i32>" (render :rust src))
  (has "1. unmarked renders `:types` on java" "int[] xs" (render :java src)))

;; 2. THE MARK READS THE TAG, and the tag is what differs per target. Rust
;;    spends a word here; Java and C# spend nothing, because an array is
;;    already shared there. Same source, and now the same MEANING.
(let [src '[(defn ^:method f [^Rt rt ^:shared ^I32Array xs] (return))]]
  (has "2. ^:shared reads the tag's :shared on rust" "xs: &mut Vec<i32>" (render :rust src))
  (has "2. ... and java spends nothing for it" "int[] xs" (render :java src))
  (has "2. ... nor does c#" "int[] xs" (render :csharp src)))

;; 3. THE WHITELIST, and it is absence of data rather than a rule. A tag that
;;    never said what sharing means for it cannot be shared -- and the refusal
;;    names the tag AND the target, because that is what the author must go
;;    and write down.
(let [msg (render-err :rust '[(defn ^:method f [^Rt rt ^:shared ^Blob b] (return))])]
  (has "3. a tag with no :shared entry is refused" "has no :shared rendering" msg)
  (has "3. and the refusal names the target" "rust" msg))

;; 4. BOTH MARKS IS A SOURCE ERROR. They are the two answers to one question,
;;    and a binding that claims both has not answered it.
(has "4. ^:shared and ^:copied together is refused"
     "both ^:shared and ^:copied"
     (render-err :rust '[(defn ^:method f [^Rt rt ^:shared ^:copied ^I32Array xs] (return))]))

;; 5. THE CALL SITE HONOURS THE CALLEE'S MARK. A call cannot read the
;;    callee's source, so the marks travel with the registered call -- and
;;    Rust writes `&mut xs` where the others write `xs`. Without this the
;;    parameter half is useless: the signature would want a reference and
;;    every call would hand it a value.
(let [src '[(defn ^:method sink [^Rt rt ^:shared ^I32Array xs] (return))
            (defn ^:method go [^Rt rt ^:shared ^I32Array ys] (return (sink rt ys)))]]
  (has "5. rust writes `&mut` at the call site" "sink(&mut ys)" (render :rust src))
  (has "5. ... and java writes the bare name" "sink(rt, ys)" (render :java src)))

;; 6. A COPY IS WORK ON THE OTHER SIDE, which is the case that shows this is
;;    not a Rust-shaped concept: Rust hands the value over and Java must
;;    clone, so the burden lands where the default was already sharing.
(let [src '[(defn ^:method take [^Rt rt ^:copied ^I32Array xs] (return))
            (defn ^:method go [^Rt rt ^:shared ^I32Array ys] (return (take rt ys)))]]
  (has "6. java clones for a ^:copied parameter" "take(rt, ys.clone())" (render :java src)))

;; 7. HALF AN ANSWER IS REFUSED. A tag that says how the parameter is
;;    declared and not how the argument is written would compile on the
;;    targets that need no word and fail on the one that does.
(has "7. a tag that says the type but not the argument is refused"
     "must also say"
     (render-err :rust '[(defn ^:method sink [^Rt rt ^:shared ^HalfSaid h] (return))
                         (defn ^:method go [^Rt rt ^:shared ^HalfSaid g] (return (sink rt g)))]))

(println)
(if (zero? @failures)
  (println "aliasing: a binding can say who owns the storage, and a tag that cannot say, says so\n")
  (do (println (format "aliasing: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
