#!/usr/bin/env bb
;; Does requiring a kin namespace let you CALL it?
;;
;;     bb test/exports.clj
;;
;; C8. A function generated in one source and called from another used to need
;; re-declaring in a vocabulary with its per-target spelling -- one definition
;; kept in two places, which is the drift this project keeps finding bugs in.
;; `declare!` already makes that argument one scope down, for calls within a
;; file; this is the same argument at namespace scope.
;;
;; What is pinned:
;;
;;   1. a `^:pub` definition is exported and callable from another namespace
;;   2. an UNMARKED one is not -- without a gate every helper leaks and the
;;      module boundary means nothing
;;   3. a namespace is emitted AFTER everything it requires, so a dependent
;;      resolves against finished exports -- one pass, in dependency order
;;   4. the export carries the TARGET's call shape, not one kin invented
;;   5. a require CYCLE is refused, naming the loop: kin follows Clojure and
;;      namespace dependencies form a DAG
;;   6. `declare-form!` allows a forward reference WITHIN a namespace, and
;;      throws if used before it is defined -- clojure.core/declare exactly
(require '[kin] '[kin.lang :as core] '[kin.target]
         '[kin.vfs :as vfs] '[kin.project :as kp] '[clojure.string :as str])

(def I32 {:name 'I32 :types {:rust "i32" :java "int"}})
(def Rt* {:name 'Rt :types {:rust "Rt" :java "Rt"}})

(def vocabulary
  {:namespace 'demo
   :targets #{:rust :java}
   :tags {'I32 I32 'Rt Rt*}
   :names {}
   :forms (core/forms {:default-tag I32})})

;; `a` exports `twice` and keeps `hidden` to itself. `b` requires `a` and
;; calls it. A DAG: `a` is emitted in full first, so `b` resolves against
;; finished exports.
(def sources
  {"a.kin"
   "(ns s.a (:require [demo :refer [defn return I32 Rt]]))
    (defn ^:pub ^:method ^I32 twice [^Rt rt ^I32 x] (return x))
    (defn ^:method ^I32 hidden [^Rt rt ^I32 x] (return x))"

   "b.kin"
   "(ns s.b (:require [demo :refer [defn return I32 Rt]]
                      [s.a :refer [twice]]))
    (defn ^:pub ^:method ^I32 triple [^Rt rt ^I32 x] (return x))
    (defn ^:pub ^:method ^I32 quad [^Rt rt ^I32 x] (return (twice rt x)))
    ;; `triple` is ^:pub AND called right here, in its own file. This is the
    ;; case that catches an exclusive reading of `:scope`.
    (defn ^:pub ^:method ^I32 nine [^Rt rt ^I32 x] (return (triple rt x)))"})

(def prj
  (kp/resolve-exports
   (kp/project {:vocabularies [vocabulary]
                 :targets {:rust kin.target/rust :java kin.target/java}
                 :target-order [:rust :java]
                 :sources {:vfs (vfs/memory-vfs sources) :match "*.kin"}})))

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
      (println (format "  ok   %-50s %s" label (subs msg 0 (min 40 (count msg)))))
      (do (swap! failures inc)
          (println (format "  FAIL %s -- it did NOT refuse" label))))))

(println "\nexports: requiring a kin namespace is enough\n")

;; 1 and 2. What crossed the boundary, and what did not.
(is "1. `^:pub` definitions are exported"
    '[nine quad triple]
    (vec (sort (keys (:forms (get-in prj [:vocabularies 's.b]))))))
(is "2. an unmarked definition is NOT exported -- `hidden` is absent"
    '[twice] (vec (sort (keys (:forms (get-in prj [:vocabularies 's.a]))))))

;; 3 and 4. The cycle generates, and the call carries the target's shape.
(let [out (kp/generate prj (get sources "b.kin") "b.kin")]
  (is "3. b calls a's export -- Rust reaches it through self"
      true (str/includes? (:rust out) "return self.twice(x);"))
  (is "4. and Java gets Java's shape, not one kin invented"
      true (str/includes? (:java out) "return twice(rt, x);")))

(is "3. and the order is a topological one -- a before b"
    ["a.kin" "b.kin"]
    (:order (kp/generate-in-order
             prj (mapv (fn [[l t]] {:label l :text t}) sources))))

;; The gate, from the other side: `hidden` is not referable at all.
(refuses "2. an unexported name cannot even be referred"
         #(kp/generate prj
                       "(ns s.c (:require [demo :refer [defn return I32 Rt]]
                                          [s.a :refer [hidden]]))
                        (defn ^:method ^I32 c [^Rt rt ^I32 x] (return x))"
                       "c.kin"))

;; `:public` MEANS LOCAL AND EXPORTED, not exported instead of local.
;;
;; This is the assertion that catches the exclusive reading, and nothing else
;; here would: a `^:pub` definition that went only to the export registry
;; still generates a perfectly good file for its own namespace and still
;; exports correctly to everyone else. The ONLY thing that breaks is a caller
;; in the same file -- `nine` calling `triple` -- which resolves to nothing
;; and dies as `not in scope`.
(let [out (kp/generate prj (get sources "b.kin") "b.kin")]
  (is "6. a ^:pub definition is still callable from its OWN file"
      true (str/includes? (:rust out) "return self.triple(x);")))

;; THE REFERENCE SHAPE IS DECIDED BY WHERE THE CALL IS, not by kin.
;;
;; A target whose `:emit` pushes a `:kin/unit` frame gets cross-unit calls
;; qualified; one that pushes nothing gets nil on both sides and self-
;; references always -- which is why a project generating regions sees no
;; change at all. Both are the same code path asking the same question.
(let [unit-of (fn [ns-name] (str/capitalize (last (str/split (name ns-name) #"\."))))
      unit-java (merge kin.target/java
                       {:unit unit-of
                        :emit (fn [ctx forms]
                                (let [ns-name (second (first (filter #(and (seq? %) (= 'ns (first %))) forms)))
                                      cls (unit-of ns-name)
                                      needs (atom #{})]
                                  (kin/scoped
                                   ctx {:key :kin/unit :value cls}
                                   (fn [i1]
                                     (kin/scoped
                                      i1 {:key :needs :value needs}
                                      (fn [inner]
                                        (doseq [f (remove #(and (seq? %) (= 'ns (first %))) forms)]
                                          (kin/statement! inner f))))))
                                  (kin/emit! ctx (str "// needs: "
                                                      (str/join "," (sort @needs)) "\n"))))})
      up (kp/resolve-exports
          (kp/project {:vocabularies [vocabulary]
                        :targets {:java unit-java}
                        :target-order [:java]
                        :sources {:vfs (vfs/memory-vfs sources) :match "*.kin"}}))
      out (:java (kp/generate up (get sources "b.kin") "b.kin"))]
  ;; `twice` lives in unit `A`; `quad` is being emitted inside unit `B`.
  (is "7. a call into ANOTHER unit is qualified" true
      (str/includes? out "return A.twice(rt, x);"))
  ;; `triple` lives in `B` too, and is called from `B`.
  (is "7. a call within the SAME unit is not" true
      (str/includes? out "return triple(rt, x);"))
  ;; And the form contributed what it needs, as data, to the header.
  (is "7. and the cross-unit call registered its import" true
      (str/includes? out "// needs: A")))

;; 5. A REQUIRE CYCLE IS REFUSED, naming the loop. kin follows Clojure:
;; namespace dependencies form a DAG. Two namespaces that call each other are
;; two halves of one thing and belong in one namespace.
(refuses "5. a require cycle is refused, naming the loop"
  #(kp/resolve-exports
     (kp/project
      {:vocabularies [vocabulary]
       :targets {:rust kin.target/rust}
       :target-order [:rust]
       :sources {:vfs (vfs/memory-vfs
                       {"p.kin" "(ns s.p (:require [demo :refer [defn return I32 Rt]]
                                                   [s.q :refer [qq]]))
                                 (defn ^:pub ^:method ^I32 pp [^Rt rt ^I32 x] (return (qq rt x)))"
                        "q.kin" "(ns s.q (:require [demo :refer [defn return I32 Rt]]
                                                   [s.p :refer [pp]]))
                                 (defn ^:pub ^:method ^I32 qq [^Rt rt ^I32 x] (return (pp rt x)))"})
                 :match "*.kin"}})))


;; ---------------------------------------------------------------------------
;; `declare-form!` -- a name reserved before its definition arrives.
;;
;; The placeholder INDIRECTS: the name resolves, and asking it to do work
;; before the definition lands throws saying so. That is
;; `clojure.core/declare`'s failure mode -- an unbound var you may refer to
;; and may not call.
;;
;; SEE `doc/decisions.md`: what "needs its content" means differs between
;; Clojure and kin, because kin has no runtime. Emitting a call IS asking for
;; the content, so under this reading a forward CALL throws rather than
;; emitting. That is deliberate and it is raised rather than settled.

(let [ctx (assoc (kin/context {} :rust)
                 :targets {:rust kin.target/rust}
                 :locals (atom {}) :names (atom {})
                 :local-tags (atom {}) :tmp (atom 0))]
  (kin/declare-form! ctx {:scope :private} 'ghost)
  (is "6. a declared name RESOLVES -- it is in the local registry"
      true (some? (clojure.core/get @(:locals ctx) 'ghost)))
  (refuses "6. and asking it to do work before definition throws"
           #((clojure.core/get @(:locals ctx) 'ghost) ctx '(ghost)))
  ;; The definition FILLS the placeholder, so a reference already handed out
  ;; starts working rather than pointing at a stale stand-in.
  (let [held (clojure.core/get @(:locals ctx) 'ghost)]
    (kin/define-form! ctx {:scope :private} 'ghost
                      (fn [c _] (kin/emit! c "filled")))
    (is "6. and the definition fills the SAME placeholder"
        "filled" (let [sub (assoc ctx :out (atom []))]
                   (held sub '(ghost))
                   (kin/output sub)))))

(println)
(if (zero? @failures)
  (println "exports: a kin namespace is a vocabulary by the time anything looks\n")
  (do (println (format "exports: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
