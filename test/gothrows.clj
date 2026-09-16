#!/usr/bin/env bb
;; `^:throws` in Go: what it emits, and what it refuses.
;;
;;     bb test/gothrows.clj
;;
;; Rust turns a fallible return into `Result<T, String>` and appends `?` at
;; the call. `?` is a POSTFIX EXPRESSION OPERATOR, so `(+ (f a) (g b))` stays
;; one expression there. Go has no such thing: the error check is a STATEMENT,
;; so the call has to leave expression position and be hoisted into a
;; temporary, and what the enclosing expression sees is the temporary's name.
;;
;; That hoist is correct in an ordinary expression and WRONG in two places,
;; both of which were verified to emit silently wrong code before the checks
;; below existed:
;;
;;   * a short-circuit operand -- `(and (> a 0) (f a))` hoisted `f` above the
;;     `if`, so an operand `&&` would have skipped ran unconditionally;
;;   * a loop test -- `(while (f a) ...)` hoisted it above the loop, so the
;;     test ran ONCE and `for t1 {` span forever on a stale answer.
;;
;; What is pinned:
;;
;;   1. the signature: `(T, error)`, and `error` when there is no value
;;   2. `return v` becomes `return v, nil` -- the slot saying this path did not fail
;;   3. a call hoists, in order, and the expression sees the temporaries
;;   4. the zero comes from the TAG and is refused by name when absent
;;   5. a zero is asked of functions that can FAIL, not of every function with
;;      a return type
;;   6. hoisting out of a short-circuit operand is refused
;;   7. hoisting out of a loop test is refused
;;   8. calling a fallible function from one that is not is refused
(require '[kin] '[kin.lang :as core] '[kin.target] '[kin.project :as kp]
         '[clojure.string :as str])

(def failures (atom 0))

(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))

(defn has [label needle text]
  (if (str/includes? text needle)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         wanted   %s\n         in\n%s"
                         label (pr-str needle) text)))))

(defn refuses [label f]
  (let [msg (try (f) :kin/no-throw (catch Exception e (ex-message e)))]
    (if (= :kin/no-throw msg)
      (do (swap! failures inc)
          (println (format "  FAIL %s -- it did NOT refuse" label)))
      (println (format "  ok   %-44s %s" label (subs msg 0 (min 42 (count msg))))))))

(println "\ngothrows: `^:throws` reaches Go, and refuses where a hoist would lie\n")

;; A tag WITH a zero and one WITHOUT, because item 4 is about the difference.
(def I32  {:name 'I32  :types {:go "int"} :zero {:go "0"}})
(def Bare {:name 'Bare :types {:go "int"}})

(def subject
  {:namespace 'subj :targets #{:go}
   :tags {'I32 I32 'Bare Bare} :names {}
   :literal-tag (fn [v] (when (integer? v) I32))
   :forms {}})

(def targets
  {:go {:key :go :ext "go" :indent-unit "\t"
        :local-name (fn [_ s] (str s))
        :fn-name (fn [_ s] (str/join (map str/capitalize (str/split (str s) #"-"))))}})

(def project
  (delay (kp/project {:vocabularies (into [subject] (kp/load-vocabulary 'kin.lang))
                      :targets targets :target-order [:go]})))

(defn go [src] (:go (kp/generate @project src "t.kin")))

(def head
  "(ns t (:require [subj :refer [I32 Bare]]
                   [kin.lang :refer [defn return if let while and < + >]]))\n")

;; 1, 2 -- the signature and the success slot
(let [out (go (str head "(defn ^:throws ^I32 f [^I32 n] (return n))"))]
  (has "1. a fallible function returns (T, error)" "func F(n int) (int, error)" out)
  (has "2. and its return fills the success slot" "return n, nil" out))

(let [out (go (str head "(defn ^:throws f [^I32 n] (return))"))]
  (has "1. with no value it returns `error` alone" "func F(n int) error" out)
  (has "2. and a bare return is `return nil`" "return nil" out))

;; 3 -- two fallible calls in ONE expression, hoisted in order
(let [out (go (str head
                   "(defn ^:throws ^I32 f [^I32 n] (return n))\n"
                   "(defn ^:throws ^I32 g [^I32 a ^I32 b] (return (+ (f a) (f b))))"))]
  (has "3. the first call hoists"        "t1, e1 := F(a)" out)
  (has "3. the second too, in order"     "t2, e2 := F(b)" out)
  (has "3. each checks and propagates"   "return 0, e1" out)
  (has "3. and the expression sees the temporaries" "return t1 + t2, nil" out))

;; 4, 5 -- the zero
(refuses "4. a tag with no `:zero` is refused by name"
         #(go (str head "(defn ^:throws ^Bare f [^Bare n] (return n))")))
(has "5. a zero is asked of fallible functions only"
     "func F(n int) int"
     (go (str head "(defn ^Bare f [^Bare n] (return n))")))

;; 6, 7, 8 -- the three refusals
(refuses "6. a hoist out of a short-circuit operand"
         #(go (str head
                   "(defn ^:throws ^I32 f [^I32 n] (return n))\n"
                   "(defn ^:throws ^I32 g [^I32 a] (if (and (> a 0) (f a)) (return 1)) (return 0))")))
(refuses "7. a hoist out of a loop test"
         #(go (str head
                   "(defn ^:throws ^I32 f [^I32 n] (return n))\n"
                   "(defn ^:throws ^I32 g [^I32 a] (while (f a) (return a)) (return 0))")))
(refuses "8. a fallible call from a function that is not"
         #(go (str head
                   "(defn ^:throws ^I32 f [^I32 n] (return n))\n"
                   "(defn ^I32 g [^I32 a] (return (f a)))")))

(println (format "\ngothrows: %s\n"
                 (if (zero? @failures) "ok" (str @failures " failure(s)"))))
(when (pos? @failures) (System/exit 1))
