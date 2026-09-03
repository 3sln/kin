#!/usr/bin/env bb
;; A form has TWO concerns, so it has three slots and two context types.
;;
;;     bb test/contexts.clj
;;
;; How do I USE the thing you produce, and how should it be PRODUCED? They run
;; in different passes with different powers, so they are handed different
;; CONTEXT TYPES rather than the same map with a rule attached.
;;
;;     Scoped      both passes -- ask what encloses you
;;     Declaring   the LINK pass only
;;     Emitting    the GENERATE pass only
;;
;; What is pinned:
;;
;;   1. a bare function means `:generate`, which is the common case
;;   2. a map of {:wrap :declare :generate} is accepted, and anything else is
;;      refused by name rather than failing later as a call to a non-function
;;   3. `:wrap` runs AROUND the slot, in whichever pass is running
;;   4. the two context types are distinguishable by capability, which is what
;;      makes "a generate function cannot declare" true by construction rather
;;      than by convention
(require '[kin] '[kin.target])

(def failures (atom 0))

(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))

(defn refuses [label f]
  (let [msg (try (f) nil (catch Exception e (ex-message e)))]
    (if msg
      (println (format "  ok   %-46s %s" label (subs msg 0 (min 40 (count msg)))))
      (do (swap! failures inc)
          (println (format "  FAIL %s -- it did NOT refuse" label))))))

(println "\ncontexts: two types, three slots\n")

;; 1 and 2. Normalisation.
(is "1. a bare function is a :generate slot"
    [:generate] (vec (keys (kin/form-slots (fn [_ _] nil)))))
(is "2. a map passes through as written"
    [:declare :generate]
    (vec (sort (keys (kin/form-slots {:generate (fn [_ _])
                                      :declare (fn [_ _])})))))
(refuses "2. anything else is refused by name"
         #(kin/form-slots "not a form"))

;; 3. `:wrap` runs around the slot. The order matters: wrap opens, the slot
;; runs inside it, wrap closes.
(let [trace (atom [])
      ctx (assoc (kin/context {} :rust) :targets {:rust kin.target/rust})
      slots {:wrap (fn [c f]
                     (swap! trace conj :wrap-in)
                     (kin/scoped c {:key :class :value "Outer"}
                                 (fn [inner] (f inner)))
                     (swap! trace conj :wrap-out))
             :generate (fn [c _]
                         (swap! trace conj [:generate (kin/get c :class)]))}]
  (#'kin/run-slot slots :generate ctx '(x))
  (is "3. :wrap runs around the slot, and its scope is visible inside"
      [:wrap-in [:generate "Outer"] :wrap-out] @trace))

;; A slot that is absent is simply not run -- `:wrap` still runs, because it
;; may be establishing scope for a pass that has nothing else to do.
(let [trace (atom [])
      ctx (kin/context {} :rust)
      slots {:wrap (fn [c f] (swap! trace conj :wrap) (f c))}]
  (#'kin/run-slot slots :declare ctx '(x))
  (is "3. an absent slot runs nothing but the wrap" [:wrap] @trace))

;; 4. THE CAPABILITY SPLIT. This is what makes the separation structural: a
;; generate context and a link context are told apart by what they implement,
;; not by a flag anyone can set.
(let [gen (kin/context {} :rust)
      link (kin/->LinkContext {} :rust {} 0)]
  (is "4. the generate context can emit"
      [true false] [(satisfies? kin/Emitting gen) (satisfies? kin/Declaring gen)])
  (is "4. the link context can declare, and cannot emit"
      [false true] [(satisfies? kin/Emitting link) (satisfies? kin/Declaring link)])
  ;; And BOTH carry the scope stack, which is why `:wrap` is polymorphic
  ;; across the passes instead of needing two copies that can disagree.
  (is "4. and both carry the scope stack"
      [true true] [(satisfies? kin/Scoped gen) (satisfies? kin/Scoped link)]))


;; ---------------------------------------------------------------------------
;; LAYOUT: `kingen/*` then the host convention, ONE SHAPE for all three.
;;
;; Rust mirrors a module path onto directories exactly as Java mirrors a
;; package and C# a namespace. Only the filename differs, because only Rust
;; has a module that is not a class.
(is "5. one namespace, three files, the same directory shape"
    ["kingen/flint/rt/maps.rs"
     "kingen/flint/rt/Maps.java"
     "kingen/flint/rt/Maps.cs"]
    [(kin.target/module-path "kingen" 'flint.rt.maps {:ext "rs"})
     (kin.target/module-path "kingen" 'flint.rt.maps {:ext "java" :capitalise? true})
     (kin.target/module-path "kingen" 'flint.rt.maps {:ext "cs" :capitalise? true})])

(is "5. a single-segment namespace needs no directories"
    "kingen/gcd.go"
    (kin.target/module-path "kingen" 'gcd {:ext "go"}))

(println)
(if (zero? @failures)
  (println "contexts: the slots dispatch and the capabilities are distinct\n")
  (do (println (format "contexts: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
