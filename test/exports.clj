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
;;   7. a reference into another UNIT is qualified and registers its import,
;;      and one within the unit is bare -- the same code path both times
;;   8. a NAME does all of that too. It could not: a name was a per-target
;;      string, so a `defconst` read from another module came out bare with no
;;      import and compiled only where something else derived the import by
;;      scanning the sources. A plain string still means `no linking`, which
;;      is what a vocabulary's names are and what they stay.
(require '[kin] '[kin.lang :as core] '[kin.target]
         '[kin.vfs :as vfs] '[kin.project :as kp] '[clojure.string :as str])

(def I32 {:name 'I32 :types {:rust "i32" :java "int"}})
(def Rt* {:name 'Rt :types {:rust "Rt" :java "Rt"}})

(def vocabulary
  {:namespace 'demo
   :targets #{:rust :java}
   :tags {'I32 I32 'Rt Rt*}
   ;; A PER-TARGET STRING, which is what a vocabulary's names are and what
   ;; the tree consuming kin has a hundred and forty-eight of. It means
   ;; `spell it this way, there is nothing to link` -- the truth for a name
   ;; every target declares in its own header -- and section 8 pins that
   ;; teaching names to link did not change it.
   :names {'TY_STR {:rust "TY_STR" :java "TY_STR"}
           ;; AND ONE THAT LINKS, written by hand. The door a `defconst`
           ;; goes through is open to a vocabulary too -- a constant that
           ;; lives in a hand-written header the generated file has to
           ;; import is the same shape as one another module declares.
           ;; `check-vocabulary` demands a spelling per target and cannot
           ;; ask a function for one, so it exempts a function: it answers
           ;; for every target by construction, at the reference.
           'HOSTED (fn [ctx] (core/need! ctx "Header") "HOSTED")}
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
(defn unit-of
  "Which UNIT a namespace is emitted into: `s.a` -> `A`."
  [ns-name]
  (str/capitalize (last (str/split (name ns-name) #"\."))))

(def unit-java
  "A target that opens a UNIT and collects what its forms need.

  The three things a linking target does, and nothing else: it says how a
  namespace becomes a unit (`:unit`), it pushes the unit it is opening
  (`:kin/unit`), and it puts an atom where references can say what they need
  (`:needs`). What lands in that atom is what a header would be written from,
  so printing it is enough to see whether a reference registered anything."
  (merge kin.target/java
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
                                        (str/join "," (sort @needs)) "\n"))))}))

(let [up (kp/resolve-exports
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

;; ---------------------------------------------------------------------------
;; 8. A NAME LINKS TOO -- and until now it could not.
;;
;; A form reference went through `declared-call`: it knew which module
;; declared the thing, asked the target how a cross-unit reference is spelled,
;; and fired `need!` so the header could carry the import. A NAME reference
;; was a lookup in a map of per-target strings, so it knew none of that and
;; could register nothing. A `defconst` in another module came out as a bare
;; word with no import -- which does not resolve, and compiled only where the
;; project ALSO derived its imports by scanning every source for the names it
;; defines. The reference itself contributed nothing.
;;
;; So a name registers a FUNCTION now, asked at the reference. It is the same
;; qualifier `declared-call` asks, out of the same place: bare within the
;; unit, qualified outside it, `need!` either way.

(def const-sources
  {"k.kin"
   ;; The constant, and a use of it in its OWN unit -- which must stay bare.
   "(ns s.k (:require [demo :refer [defn defconst return I32 Rt]]))
    (defconst ^:pub ^I32 LIMIT 200)
    (defn ^:pub ^:method ^I32 own [^Rt rt] (return LIMIT))"

   "m.kin"
   ;; The reference from ANOTHER unit -- the flint shape exactly: `casemap`
   ;; refers `CASE_UPPER_LEN` from `casetable`, and the emitted Java said a
   ;; bare `CASE_UPPER_LEN` with no import.
   "(ns s.m (:require [demo :refer [defn return I32 Rt TY_STR]]
                      [s.k :refer [LIMIT]]))
    (defn ^:pub ^:method ^I32 cap [^Rt rt ^I32 x] (return LIMIT))"

   "w.kin"
   ;; ONLY a vocabulary name, in its own file, so that what its reference
   ;; registers can be read off the header with nothing else in it. Asking
   ;; the same question inside `m.kin` cannot answer it: the constant's
   ;; import is in that header too, so the line is non-empty either way.
   "(ns s.w (:require [demo :refer [defn return I32 Rt TY_STR HOSTED]]))
    (defn ^:pub ^:method ^I32 vocab [^Rt rt] (return TY_STR))"

   "h.kin"
   "(ns s.h (:require [demo :refer [defn return I32 Rt HOSTED]]))
    (defn ^:pub ^:method ^I32 hosted [^Rt rt] (return HOSTED))"})

(let [up (kp/resolve-exports
          (kp/project {:vocabularies [vocabulary]
                       :targets {:java unit-java}
                       :target-order [:java]
                       :sources {:vfs (vfs/memory-vfs const-sources)
                                 :match "*.kin"}}))
      out (:java (kp/generate up (get const-sources "m.kin") "m.kin"))
      own (:java (kp/generate up (get const-sources "k.kin") "k.kin"))
      voc (:java (kp/generate up (get const-sources "w.kin") "w.kin"))
      hos (:java (kp/generate up (get const-sources "h.kin") "h.kin"))]
  (is "8. a constant read from ANOTHER unit is qualified" true
      (str/includes? out "return K.LIMIT;"))
  ;; THE ASSERTION THIS WAS BUILT FOR. The spelling above could be argued
  ;; about -- a project using static imports wants the name bare -- but the
  ;; import cannot: whatever it is spelled, the reference leaves the unit and
  ;; something has to say so. Before this, nothing did.
  (is "8. and it registered the import the reference needs" true
      (str/includes? out "// needs: K"))
  ;; BACKWARD COMPATIBILITY, stated as a test rather than as an intention. A
  ;; vocabulary's name is a per-target string, it is spelled verbatim, and it
  ;; registers nothing -- there is no module it belongs to.
  (is "8. a vocabulary's plain per-target name is unchanged" true
      (str/includes? voc "return TY_STR;"))
  (is "8. and needs NOTHING -- a plain string is not a link" true
      (str/includes? voc "// needs: \n"))
  ;; The constant's import is the ONLY thing in this header: a reference
  ;; registers what it needs and nothing more.
  (is "8. and the linking reference registered exactly one thing" true
      (str/includes? out "// needs: K\n"))
  ;; A HAND-WRITTEN VOCABULARY MAY CARRY A LINKING NAME too. This one spells
  ;; itself and asks for a header, which a per-target string could not do --
  ;; and `check-vocabulary`, which demands a spelling per target, let it
  ;; through rather than refusing what it cannot read.
  (is "8. a vocabulary's name may LINK as well, and is not refused for it"
      [true true]
      [(str/includes? hos "return HOSTED;")
       (str/includes? hos "// needs: Header\n")])
  ;; And within its own unit the same constant is bare, with nothing needed.
  (is "8. the declaring unit's own use of it is bare" true
      (and (str/includes? own "return LIMIT;")
           (not (str/includes? own "K.LIMIT"))))
  (is "8. and its own file imports nothing for it" true
      (str/includes? own "// needs: \n")))

;; ONE EXPORTED VALUE, EVERY TARGET. A name is recorded per target as it is
;; generated and read back as ONE value -- `export-vocabulary` keeps the first
;; and drops the rest, because a name was data and the data was the same
;; whichever target was being emitted when it was recorded. A function has to
;; survive that, so it closes over the NAMESPACE and asks the target for the
;; unit at the reference rather than baking one in.
;;
;; The two targets are given DIFFERENT unit conventions on purpose: `K` and
;; `k`. A value that had captured one target's answer would register the other
;; target's import here, and both spellings below would still look plausible.
(let [rust-unit (fn [ns-name] (str/lower-case (last (str/split (name ns-name) #"\."))))
      unit-rust (merge kin.target/rust
                       {:unit rust-unit
                        :emit (fn [ctx forms]
                                (let [ns-name (second (first (filter #(and (seq? %) (= 'ns (first %))) forms)))
                                      needs (atom #{})]
                                  (kin/scoped
                                   ctx {:key :kin/unit :value (rust-unit ns-name)}
                                   (fn [i1]
                                     (kin/scoped
                                      i1 {:key :needs :value needs}
                                      (fn [inner]
                                        (doseq [f (remove #(and (seq? %) (= 'ns (first %))) forms)]
                                          (kin/statement! inner f))))))
                                  (kin/emit! ctx (str "// needs: "
                                                      (str/join "," (sort @needs)) "\n"))))})
      both (kp/resolve-exports
            (kp/project {:vocabularies [vocabulary]
                         :targets {:java unit-java :rust unit-rust}
                         :target-order [:rust :java]
                         :sources {:vfs (vfs/memory-vfs const-sources)
                                   :match "*.kin"}}))
      out (kp/generate both (get const-sources "m.kin") "m.kin")]
  (is "8. one exported name still answers each target its own way"
      [true true]
      [(str/includes? (:java out) "return K.LIMIT;")
       ;; Rust qualifies nothing by default -- a crate reaches a sibling
       ;; through a `use`, which is what the import IS here.
       (str/includes? (:rust out) "return LIMIT;")])
  (is "8. and each registers ITS OWN target's unit, not the other's"
      [true true]
      [(str/includes? (:java out) "// needs: K\n")
       (str/includes? (:rust out) "// needs: k\n")]))

;; A NAME REACHED FROM A NAMESPACE WITH NO UNITS AT ALL is bare and registers
;; nothing -- the same answer a call gets, and the reason a project that
;; splices regions sees no change from any of this.
(let [plain (kp/resolve-exports
             (kp/project {:vocabularies [vocabulary]
                          :targets {:java kin.target/java}
                          :target-order [:java]
                          :sources {:vfs (vfs/memory-vfs const-sources)
                                    :match "*.kin"}}))
      out (:java (kp/generate plain (get const-sources "m.kin") "m.kin"))]
  (is "8. a target that names no `:unit` gets the bare spelling" true
      (str/includes? out "return LIMIT;")))

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
