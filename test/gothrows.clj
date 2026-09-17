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
;; both of which were verified to emit silently wrong code:
;;
;;   * a short-circuit operand -- `(and (> a 0) (f a))` hoisted `f` above the
;;     `if`, so an operand `&&` would have skipped ran unconditionally;
;;   * a loop test -- `(while (f a) ...)` hoisted it above the loop, so the
;;     test ran ONCE and `for t1 {` span forever on a stale answer.
;;
;; Both were REFUSED by name for a while, which was honest and not an answer.
;; Neither is refused now: the form that makes a position conditional hands
;; the sub-expression a private anchor and puts what lands in it where the
;; operand actually runs. A short-circuit becomes a conditional assignment to
;; a boolean temporary; a loop test moves inside an unconditional loop and
;; breaks. Both shapes are pinned below, and so is the case where nothing
;; hoists -- because the whole point is that ordinary code is unchanged.
;;
;; What is pinned:
;;
;;   1. the signature: `(T, error)`, and `error` when there is no value
;;   2. `return v` becomes `return v, nil` -- the slot saying this path did not fail
;;   3. a call hoists, in order, and the expression sees the temporaries
;;   4. the zero comes from the TAG and is refused by name when absent
;;   5. a zero is asked of functions that can FAIL, not of every function with
;;      a return type
;;   6. a hoisting short-circuit operand becomes a guarded assignment
;;   7. a hoisting loop test moves into the loop
;;   8. calling a fallible function from one that is not is refused
;;   9. nothing changes where nothing hoists
;;  10. A HOST-LINKED FORM MAY SAY IT CAN FAIL. `^:throws` on a kin `defn`
;;      reaches every call site through the emitter `defn` registers; a form
;;      backed by a HOST function registers a TEMPLATE, and a template
;;      describes a spelling and cannot describe a return convention. So
;;      `port.ResourceGet(url)` came out assigned to one variable while the Go
;;      function it named returned two. `(call tmpls {:throws true})` is where
;;      the fact rides now -- beside the template rather than in it.
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

(println "\ngothrows: `^:throws` reaches Go, and moves what a hoist would break\n")

;; A tag WITH a zero and one WITHOUT, because item 4 is about the difference.
(def I32  {:name 'I32  :types {:go "int"} :zero {:go "0"}})
(def Bare {:name 'Bare :types {:go "int" :rust "i32"}})

(def I32both {:name 'I32 :types {:go "int" :rust "i32"} :zero {:go "0"}})

(def subject
  {:namespace 'subj :targets #{:go :rust}
   :tags {'I32 I32both 'Bare Bare} :names {}
   :literal-tag (fn [v] (when (integer? v) I32both))
   ;; A HOST-LINKED FORM THAT CAN FAIL, declared the way a target's `:link`
   ;; declares one: a template per target, and `:throws` beside it.
   :forms {'resource-get (core/call {:go "port.ResourceGet({0})"
                                     :rust "crate::port::resource_get({0})"}
                                    {:tag I32both :throws true})
           ;; And one that cannot, so the test can say what DOES NOT change.
           'clock (core/call {:go "port.Clock()" :rust "crate::port::clock()"}
                             {:tag I32both})}})

(def targets
  {:go {:key :go :ext "go" :indent-unit "\t"
        :local-name (fn [_ s] (str s))
        :fn-name (fn [_ s] (str/join (map str/capitalize (str/split (str s) #"-"))))}
   ;; A SECOND TARGET, for test 10 alone. `^:throws` is one decision with two
   ;; spellings -- Go hoists and checks, Rust appends `?` -- and a test that
   ;; saw only one of them would pass while the other did nothing.
   :rust kin.target/rust})

(def project
  (delay (kp/project {:vocabularies (into [subject] (kp/load-vocabulary 'kin.lang))
                      :targets targets :target-order [:go :rust]})))

(defn go [src] (:go (kp/generate @project src "t.kin")))
(defn rust [src] (:rust (kp/generate @project src "t.kin")))

(def head
  "(ns t (:require [subj :refer [I32 Bare resource-get clock]]
                   [kin.lang :refer [defn return if let while and or < + >]]))\n")

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

;; 6 -- a short-circuit operand that hoists
(let [out (go (str head
                   "(defn ^:throws ^I32 f [^I32 n] (return n))\n"
                   "(defn ^:throws ^I32 g [^I32 a] (if (and (> a 0) (f a)) (return 1)) (return 0))"))]
  (has "6. the value becomes a boolean temporary" "b1 := (a > 0)" out)
  (has "6. the later operand runs under a guard"  "if b1 {\n\t\tt1, e1 := F(a)" out)
  (has "6. and assigns the temporary"             "b1 = t1" out)
  (has "6. which is what the expression sees"     "if b1 {\n\t\treturn 1, nil" out))

(let [out (go (str head
                   "(defn ^:throws ^I32 f [^I32 n] (return n))\n"
                   "(defn ^:throws ^I32 g [^I32 a] (if (or (> a 0) (f a)) (return 1)) (return 0))"))]
  (has "6. `or` is the same statement with the sense flipped" "if !b1 {" out))

;; 7 -- a loop test that hoists
(let [out (go (str head
                   "(defn ^:throws ^I32 f [^I32 n] (return n))\n"
                   "(defn ^:throws ^I32 g [^I32 a] (while (f a) (return a)) (return 0))"))]
  (has "7. the loop becomes unconditional"   "for {" out)
  (has "7. the test moves inside it"         "for {\n\t\tt1, e1 := F(a)" out)
  (has "7. and a false test breaks"          "if !(t1) {\n\t\t\tbreak" out))

;; 8 -- the one refusal that is still a refusal
(refuses "8. a fallible call from a function that is not"
         #(go (str head
                   "(defn ^:throws ^I32 f [^I32 n] (return n))\n"
                   "(defn ^I32 g [^I32 a] (return (f a)))")))

;; 9 -- and none of this touches code that does not hoist
(let [out (go (str head "(defn ^I32 g [^I32 a] (if (and (> a 0) (< a 9)) (return 1)) (return 0))"))]
  (has "9. a short-circuit that hoists nothing is one expression"
       "if (a > 0) && (a < 9) {" out)
  (is  "9. and takes no temporary" false (str/includes? out "b1")))
(let [out (go (str head "(defn ^I32 g [^I32 a] (while (> a 0) (return a)) (return 0))"))]
  (has "9. a loop test that hoists nothing keeps its shape" "for a > 0 {" out)
  (is  "9. and does not become unconditional" false (str/includes? out "for {")))

;; 10 -- a HOST-LINKED form that can fail
(let [src (str head "(defn ^:throws ^I32 g [^I32 a] (return (+ (resource-get a) 1)))")]
  (let [out (go src)]
    (has "10. Go hoists a host call that can fail"  "t1, e1 := port.ResourceGet(a)" out)
    (has "10. and checks its error"                 "if e1 != nil {" out)
    (has "10. propagating the enclosing zero"       "return 0, e1" out)
    (has "10. the expression sees the temporary"    "return t1 + 1, nil" out))
  (has "10. Rust appends `?` to the same call"
       "crate::port::resource_get(a)? + 1" (rust src)))

;; and one that cannot fail is untouched, which is the half that says the
;; mechanism is off by default rather than on for every template.
(let [src (str head "(defn ^I32 g [^I32 a] (return (+ (clock) 1)))")]
  (has "10. a host form that cannot fail still reads as an expression"
       "return port.Clock() + 1" (go src))
  (is  "10. and takes no temporary" false (str/includes? (go src) "e1 != nil")))

;; a fallible HOST call from a function that is not fallible is refused, for
;; the same reason a kin one is: Go would drop the error on the floor.
(refuses "10. a fallible host call from a function that is not"
         #(go (str head "(defn ^I32 g [^I32 a] (return (resource-get a)))")))

(println (format "\ngothrows: %s\n"
                 (if (zero? @failures) "ok" (str @failures " failure(s)"))))
(when (pos? @failures) (System/exit 1))
