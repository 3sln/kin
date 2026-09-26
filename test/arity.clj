#!/usr/bin/env bb
;; AN ARM A CORE FORM WOULD SILENTLY DROP.
;;
;;     bb test/arity.clj
;;
;; `call` already makes this argument about a template: "Too MANY and the
;; extras are never referenced: that one produces CODE THAT COMPILES and does
;; the wrong thing, which is the worse of the two and had nothing looking for
;; it." A SPECIAL FORM is not a template and had the same hole -- `if-body`
;; destructured `[_ test then else]` and whatever came after was gone.
;;
;; It cost a real afternoon in nome. This:
;;
;;     (if (== k (ct-ident))
;;       (comment "an ident is four things")
;;       (comment "and a page size is the one that is a table")
;;       (let [...] ...))
;;
;; renders the two COMMENTS as the two arms and drops the `let`. The function
;; compiled in four languages, ran, and returned its fallback answer -- so a
;; corpus that expected a real answer failed with no hint of where, and the
;; generated code looked like something a careless author had written.
;;
;; `comment` IS A FORM LIKE ANY OTHER, which is the sharp edge: it reads as
;; annotation and counts as an arm.
;;
;; Too FEW is refused in the same breath, because an `if` with no consequent is
;; a test whose answer is discarded and no target language has a use for one.
(require '[kin] '[kin.lang :as core] '[kin.target] '[clojure.string :as str])

(def I32 {:name 'I32 :types {:rust "i32" :java "int" :csharp "int"}})

(def vocabulary
  {:namespace 'demo
   :targets #{:rust :java :csharp}
   :tags {'I32 I32}
   :names {}
   :literal-tag (fn [v] (when (integer? v) I32))
   :forms (core/forms {:default-tag I32})})

(def targets {:rust kin.target/rust :java kin.target/java
              :csharp kin.target/csharp})

;; RUST AND NOT GO, for the reason `literals.clj` gives: Go's target takes a
;; different file shape and building a second scaffold would be testing the
;; scaffold. The check is in `if-body`, which every target goes through.

(defn render [target forms]
  (let [vocabs {'demo vocabulary}
        scope (kin/require-scope
               '(ns probe (:require [demo :refer [defn let return if do comment
                                                  == + I32]]))
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
(defn refuses [label f]
  (let [msg (try (f) :kin/no-throw (catch Exception e (ex-message e)))]
    (if (= :kin/no-throw msg)
      (do (swap! failures inc)
          (println (format "  FAIL %s -- it did NOT refuse" label)))
      (println (format "  ok   %-46s %s" label
                       (subs msg 0 (min 40 (count msg))))))))

(println "\narity: an arm a core form would silently drop\n")

;; 1. The two shapes that are right.
(let [out (render :rust '[(defn ^I32 f [^I32 n]
                          (if (== n 0) (return 1))
                          (return 2))])]
  (is "1. a test and a then is an `if`" #(str/includes? % "if n == 0 {") out))
(let [out (render :rust '[(defn ^I32 f [^I32 n]
                          (if (== n 0) (return 1) (return 2)))])]
  (is "1. a test, a then and an else is an `if ... else`"
      #(str/includes? % "} else {") out))

;; 2. The one that cost the afternoon.
(refuses "2. a fourth form is refused"
         #(render :rust '[(defn ^I32 f [^I32 n]
                          (if (== n 0) (return 1) (return 2) (return 3)))]))
(refuses "2. and a comment counts as one"
         #(render :rust '[(defn ^I32 f [^I32 n]
                          (if (== n 0)
                            (comment "why")
                            (comment "and why")
                            (return 1))
                          (return 2))]))

;; 3. And the refusal says what to do instead, because `do` is the answer.
(let [msg (try (render :rust '[(defn ^I32 f [^I32 n]
                               (if (== n 0) (return 1) (return 2) (return 3)))])
               (catch Exception e (ex-message e)))]
  (is "3. the message names `do`" #(str/includes? % "`do`") msg)
  (is "3. and says how many it was given" #(str/includes? % "4 forms") msg))
(let [out (render :rust '[(defn ^I32 f [^I32 n]
                          (if (== n 0)
                            (do (comment "why") (return 1)))
                          (return 2))])]
  (is "3. and wrapping the arm in `do` is accepted"
      #(str/includes? % "return 1") out))

;; 4. Too few, refused in the same breath.
(refuses "4. a test with no consequent is refused"
         #(render :rust '[(defn ^I32 f [^I32 n] (if (== n 0)) (return 2))]))

;; 5. AND AN `else if` CHAIN IS STILL A CHAIN, which is the shape the check
;;    must not break: `if-body` recurses through the else arm, so every arm of
;;    a five-way dispatch goes through the same count.
(let [out (render :rust '[(defn ^I32 f [^I32 n]
                          (if (== n 0)
                            (return 1)
                            (if (== n 1)
                              (return 2)
                              (if (== n 2) (return 3) (return 4)))))])]
  (is "5. an else-if chain renders as one chain"
      #(= 2 (count (re-seq #"\} else if " %))) out))
(refuses "5. and a fourth form inside the chain is refused too"
         #(render :rust '[(defn ^I32 f [^I32 n]
                          (if (== n 0)
                            (return 1)
                            (if (== n 1) (return 2) (return 3) (return 4))))]))

(println (format "\narity: %s\n"
                 (if (zero? @failures) "ok" (str @failures " failure(s)"))))
(when (pos? @failures) (System/exit 1))
