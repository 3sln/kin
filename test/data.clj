#!/usr/bin/env bb
;; Does `defdata` generate a table -- and its accessors -- into every target?
;;
;;     bb test/data.clj
;;
;; A data table used to be a bespoke script per table: hardcoded per-language
;; string templates, hardcoded destination paths, and a hand-written accessor
;; in every runtime. `defdata` replaces all of that, and the four things that
;; make it more than a code-shaped `defconst` are what is pinned here.
;;
;;   1. PROVENANCE IS ERASED. `:data` inline and `:path` read through the
;;      target's vfs produce BYTE-IDENTICAL output -- the emitter cannot tell
;;      which it was, so a table that moves into a file cannot change what is
;;      generated from it. And kin opens no file of its own: a vfs is the only
;;      door, so a target with none is refused by name.
;;   2. THE EMITTER IS NAMED, NEVER DEFAULTED, and it is looked up in the
;;      TARGET MAP -- the same door `:indent-unit` and `:local-name` come
;;      through. No new mechanism.
;;   3. AN ACCESSOR IS AN ORDINARY BINDING. It is `defn`'s own call machinery,
;;      so `:refer` across namespaces, a sibling module's qualified call and
;;      the import that call needs all work without being reimplemented. AND
;;      SO IS THE TABLE'S OWN NAME, which is the half that could not link: it
;;      was a per-target string, so a source naming `CASE_UPPER` directly got
;;      a bare word and no import.
;;   4. THE DECLARED SIGNATURE IS KIN'S CONTRACT. An emitter satisfies the
;;      declared accessors and arity; it cannot add one, drop one, or read an
;;      argument that does not exist.
;;
;; And `:type`/`:expr` are OPTIONAL: an emitter that only wants accessors
;; emits no binding at all, which is the case a packed or computed table is.
(require '[kin] '[kin.lang :as core] '[kin.target]
         '[kin.vfs :as vfs] '[kin.project :as kp] '[clojure.string :as str]
         '[clojure.edn :as edn])

(def I32 {:name 'I32 :types {:rust "i32" :java "int"}})
(def Rt* {:name 'Rt  :types {:rust "Rt"  :java "Rt"}})
(def Cmp {:name 'Cmp :types {:rust "u32" :java "int"}})

(def vocabulary
  {:namespace 'demo
   :targets #{:rust :java}
   :tags {'I32 I32 'Rt Rt* 'Cmp Cmp}
   :names {}
   :forms (core/forms {:default-tag I32})})

;; ---------------------------------------------------------------- emitters
;;
;; Both are looked up in `:data-emitters` on the target, keyed by the symbol
;; the source writes. kin ships `flat-array`; `only-accessors` is this test's
;; own, and exists to pin that a binding is optional.

(defn only-accessors
  "An emitter that answers NO `:type` and NO `:expr` -- accessors only.

  The count is a literal and the sum is a literal, so nothing has to exist in
  the module for either to work. A packed blob, a computed table or one that
  lives in a hand-written file elsewhere all look like this."
  [_ctx {:keys [data accessors]}]
  {:accessors (into {} (for [{:keys [name arity]} accessors]
                         [name (str (if (= 1 arity) (count data) (reduce + data)))]))})

(defn liar
  "An emitter that answers an accessor nobody declared. Pinned as REFUSED:
  the declaration is the contract."
  [_ctx {:keys [accessors]}]
  {:accessors (into {'invented "0"}
                    (for [{:keys [name]} accessors] [name "0"]))})

(defn overreacher
  "An emitter whose template reads an argument the accessor does not take."
  [_ctx {:keys [accessors]}]
  {:accessors (into {} (for [{:keys [name]} accessors] [name "TBL[{7}]"]))})

(defn helpful
  "An emitter whose accessor cannot be a one-line expansion, so it contributes
  a HELPER BODY to the module and has its template call it.

  `{unit}` on the helper's own name, exactly as on a table's: the helper
  lives in the module that declared the data, so a call from elsewhere has to
  reach it the same way a call to the table does."
  [_ctx {bound :binding :keys [data accessors]}]
  {:accessors (into {} (for [{:keys [name]} accessors]
                         [name (str "{unit}lookup" bound "({1})")]))
   :helpers [(str "static int lookup" bound "(int i) {")
             (str "    return " (vec data) "[i] * 2;")
             "}"]})

(def emitters
  {'kin.lang/flat-array core/flat-array
   'only-accessors only-accessors
   'helpful helpful
   'liar liar
   'overreacher overreacher})

;; -------------------------------------------------------------- the sources

(def table-edn
  ;; A file holding SEVERAL tables, which is why `:key` exists.
  (pr-str {:upper [[65 90 32 0] [192 214 32 0]]
           :lower [[97 122 -32 0]]}))

(def inline-src
  "(ns s.inline (:require [demo :refer [defn defdata return I32 Rt Cmp]]))
   (defdata case-upper
     \"Uppercase ranges: [start end delta stride].\"
     :data [[65 90 32 0] [192 214 32 0]]
     :emitter kin.lang/flat-array
     :stride 4
     :accessors {^I32 case-upper-n  [^Rt rt]
                 ^Cmp case-upper-at [^Rt rt ^I32 i ^I32 f]})
   (defn ^:method ^Cmp probe [^Rt rt ^I32 i] (return (case-upper-at rt i 2)))")

(def path-src
  "(ns s.path (:require [demo :refer [defn defdata return I32 Rt Cmp]]))
   (defdata case-upper
     \"Uppercase ranges: [start end delta stride].\"
     :path \"data/casetable.edn\"
     :key :upper
     :emitter kin.lang/flat-array
     :stride 4
     :accessors {^I32 case-upper-n  [^Rt rt]
                 ^Cmp case-upper-at [^Rt rt ^I32 i ^I32 f]})
   (defn ^:method ^Cmp probe [^Rt rt ^I32 i] (return (case-upper-at rt i 2)))")

(defn target-with
  "`base`, plus the emitter table and a vfs holding the data file.

  The emitter lookup is `(get-in ctx [:targets (:target ctx) :data-emitters])`
  -- an ordinary target key. That is the whole of the mechanism."
  [base]
  (merge base {:data-emitters emitters
               :vfs (vfs/memory-vfs {"data/casetable.edn" table-edn})}))

(def prj
  (kp/project {:vocabularies [vocabulary]
               :targets {:rust (target-with kin.target/rust)
                         :java (target-with kin.target/java)}
               :target-order [:rust :java]}))

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
      (println (format "  ok   %-52s %s" label (subs msg 0 (min 46 (count msg)))))
      (do (swap! failures inc)
          (println (format "  FAIL %s -- it did NOT refuse" label))))))

(println "\ndefdata: one table, every target, accessors and all\n")

;; ---------------------------------------------------------------------------
;; 1. INLINE DATA. The binding, the doc comment, and the two accessors.

(def inline-out (kp/generate prj inline-src "inline.kin"))

(is "1. rust gets a static, typed and sized by the emitter" true
    (str/includes? (:rust inline-out)
                   "pub(crate) static CASE_UPPER: [u32; 8] = ["))
(is "1. java gets the same table, spelled Java's way" true
    (str/includes? (:java inline-out)
                   "public static final int[] CASE_UPPER = {"))
(is "1. the rows survive the flattening, one record to a line" true
    (str/includes? (:rust inline-out) "    65, 90, 32, 0,\n    192, 214, 32, 0"))
(is "1. the docstring reaches the OUTPUT, as a doc comment" true
    (str/includes? (:rust inline-out)
                   "/// Uppercase ranges: [start end delta stride]."))

;; THE ACCESSOR IS A CALL, resolved like any other and emitted from the
;; emitter's template. `{1}` and `{2}` are the two indices; `{0}` is the
;; receiver, which a module-level table has no use for.
(is "1. an index accessor expands the emitter's template -- rust casts" true
    (str/includes? (:rust inline-out) "return CASE_UPPER[(i * 4 + 2) as usize];"))
(is "1. and java does not" true
    (str/includes? (:java inline-out) "return CASE_UPPER[i * 4 + 2];"))

;; The COUNT accessor is a literal: two records of stride 4 out of eight
;; numbers. It costs nothing at run time and needs no binding to exist.
(let [out (:rust (kp/generate
                  prj
                  (str/replace inline-src "(case-upper-at rt i 2)" "(case-upper-n rt)")
                  "n.kin"))]
  (is "1. the count accessor is a literal, and counts RECORDS" true
      (str/includes? out "return 2;")))

;; ---------------------------------------------------------------------------
;; 2. PROVENANCE IS ERASED. `:path` read through the target's vfs, `:key`
;; selecting one table out of the file -- and the output is the same bytes.

(def path-out (kp/generate prj path-src "path.kin"))

(is "2. `:path` through the vfs gives the SAME output as `:data` inline"
    (str/replace (:rust inline-out) "s.inline" "s.path")
    (str/replace (:rust path-out) "s.path" "s.path"))
(is "2. `:key` selected `:upper` and not the whole file" true
    (and (str/includes? (:rust path-out) "192, 214, 32, 0")
         (not (str/includes? (:rust path-out) "-32"))))

;; kin OPENS NO FILE OF ITS OWN. A target with no vfs is refused by name
;; rather than reaching for the disk behind the project's back.
(refuses "2. a `:path` with no vfs on the target is refused"
         #(kp/generate (kp/project
                        {:vocabularies [vocabulary]
                         :targets {:rust (merge kin.target/rust
                                                {:data-emitters emitters})}
                         :target-order [:rust]})
                       path-src "novfs.kin"))
(refuses "2. and a `:path` that is not in the vfs names the file"
         #(kp/generate prj (str/replace path-src "casetable.edn" "absent.edn")
                       "absent.kin"))
(refuses "2. `:data` AND `:path` together is refused -- they say one thing"
         #(kp/generate prj (str/replace path-src ":path" ":data [1] :path")
                       "both.kin"))

;; ---------------------------------------------------------------------------
;; 3. AN ACCESSOR IS AN ORDINARY BINDING: referred across namespaces, and
;; qualified when the call crosses a unit boundary, because it goes through
;; `defn`'s own call machinery rather than a second copy of it.

(def sources
  {"a.kin"
   "(ns s.a (:require [demo :refer [defdata I32 Rt Cmp]]))
    (defdata ^:pub case-upper
      :data [[65 90 32 0] [192 214 32 0]]
      :emitter kin.lang/flat-array
      :stride 4
      :accessors {^I32 case-upper-n  [^Rt rt]
                  ^Cmp case-upper-at [^Rt rt ^I32 i ^I32 f]})"

   "b.kin"
   "(ns s.b (:require [demo :refer [defn return I32 Rt Cmp]]
                      [s.a :refer [case-upper-at case-upper-n]]))
    (defn ^:pub ^:method ^Cmp start [^Rt rt ^I32 i] (return (case-upper-at rt i 0)))
    (defn ^:pub ^:method ^I32 how-many [^Rt rt] (return (case-upper-n rt)))"})

(def linked
  (kp/resolve-exports
   (kp/project {:vocabularies [vocabulary]
                :targets {:rust (target-with kin.target/rust)
                          :java (target-with kin.target/java)}
                :target-order [:rust :java]
                :sources {:vfs (vfs/memory-vfs sources) :match "*.kin"}})))

(is "3. `^:pub` accessors leave the namespace as ordinary forms"
    '[case-upper-at case-upper-n]
    (vec (sort (keys (:forms (get-in linked [:vocabularies 's.a]))))))

(let [out (kp/generate linked (get sources "b.kin") "b.kin")]
  (is "3. another namespace refers one and calls it" true
      (str/includes? (:java out) "return CASE_UPPER[i * 4 + 0];"))
  (is "3. and the count accessor crosses too" true
      (str/includes? (:java out) "return 2;")))

;; THE UNIT BOUNDARY. A target whose `:emit` pushes a `:kin/unit` frame gets
;; a cross-unit reference QUALIFIED, and the import registered -- which is
;; `defn`'s rule, applied to the emitter's `{unit}` placeholder. A target that
;; pushes no frame gets nil on both sides and bare references always.
(defn unit-of
  "Which UNIT a namespace is emitted into: `s.a` -> `A`."
  [ns-name]
  (str/capitalize (last (str/split (name ns-name) #"\."))))

(def unit-java
  "A target that opens a UNIT and collects what its forms need, so that what
  a reference registered can be read straight off the header."
  (merge (target-with kin.target/java)
         {:unit unit-of
          :emit (fn [ctx forms]
                  (let [ns-name (second (first (filter #(and (seq? %) (= 'ns (first %))) forms)))
                        needs (atom #{})]
                    (kin/scoped
                     ctx {:key :kin/unit :value (unit-of ns-name)}
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
  (is "3. a call into ANOTHER unit qualifies the table" true
      (str/includes? out "return A.CASE_UPPER[i * 4 + 0];"))
  (is "3. and registers the import, as data, for the header" true
      (str/includes? out "// needs: A"))
  ;; And within its own unit the same template is bare -- one rule, both
  ;; sides, so an emitter never learns where it is called from.
  (let [own (:java (kp/generate up (get sources "a.kin") "a.kin"))]
    (is "3. the declaring unit's own file has no qualifier to add" true
        (and (str/includes? own "public static final int[] CASE_UPPER = {")
             (not (str/includes? own "A.CASE_UPPER"))))))

;; AND THE TABLE'S OWN NAME, which is the half that could not link.
;;
;; An accessor was an ordinary binding already -- `declared-call` -- but the
;; BINDING it reads was registered as a map of per-target strings and looked
;; up as one. So a source naming `CASE_UPPER` directly, which is the whole
;; point of emitting a binding rather than only accessors, got a bare word
;; and no import: the same defect a `defconst` had, in the same declaration.
;; It registers the same linking value now, so both halves of one `defdata`
;; answer the same way.
;;
;; Its OWN file is the source of the table and the only other file is this
;; one, so the header here holds exactly what this reference asked for.
(def naming-sources
  {"a.kin" (get sources "a.kin")
   "c.kin"
   "(ns s.c (:require [demo :refer [defn return I32 Rt]]
                      [s.a :refer [case-upper]]))
    (defn ^:pub ^:method ^I32 whole [^Rt rt] (return case-upper))"})

(let [up (kp/resolve-exports
          (kp/project {:vocabularies [vocabulary]
                       :targets {:java unit-java}
                       :target-order [:java]
                       :sources {:vfs (vfs/memory-vfs naming-sources)
                                 :match "*.kin"}}))
      out (:java (kp/generate up (get naming-sources "c.kin") "c.kin"))]
  (is "3. naming the TABLE from another unit qualifies it" true
      (str/includes? out "return A.CASE_UPPER;"))
  (is "3. and that reference alone registered the import" true
      (str/includes? out "// needs: A\n")))

;; A MODULE WITH NO `defn` IN IT AT ALL. This is the natural shape -- a data
;; table is its own namespace and the code that uses it lives elsewhere -- and
;; it is the case every other source in this file MASKS, because each of them
;; pairs its `defdata` with a `defn` that supplies the targets by a side door.
;;
;; What a namespace SPEAKS used to be reconstructed from the targets that some
;; exported form or tag happened to have been registered under, and `:names`
;; was not in that union at all. So a namespace of `^:pub defconst`s and
;; `defdata` tables with private accessors exported perfectly good referable
;; names and reported that it could speak NO TARGET -- and every dependent
;; then died with `generates for NO target`, naming the empty intersection
;; rather than the namespace that emptied it. It speaks what it GENERATED for.
;; TWO table modules, because the defect and the use are different shapes and
;; one module cannot be both. A module a dependent calls an ACCESSOR of has to
;; export a form, and an exported form is exactly what used to supply the
;; targets by the side door -- so the module that pins the defect is the one
;; whose exports are ALL NAMES, and it has to be its own file.
(def table-only
  {"t.kin"
   ;; NAME-ONLY EXPORTS: a `^:pub` constant, and a table whose accessors are
   ;; private. Nothing here reaches `:forms` or `:tags`, which is precisely
   ;; the shape that reported `#{}`.
   "(ns s.t (:require [demo :refer [defdata defconst I32 Rt Cmp]]))
    (defconst ^:pub ^I32 CASE_UPPER_LEN 8)
    (defconst ^I32 CASE_LOWER_LEN 4)
    (defdata case-lower
      :data [[97 122 -32 0]]
      :emitter kin.lang/flat-array :stride 4
      :accessors {^Cmp case-lower-at [^Rt rt ^I32 i ^I32 f]})"

   "v.kin"
   ;; A `^:pub` table -- still with NO `defn` anywhere in the file.
   "(ns s.v (:require [demo :refer [defdata I32 Rt Cmp]]))
    (defdata ^:pub case-upper
      :data [[65 90 32 0] [192 214 32 0]]
      :emitter kin.lang/flat-array :stride 4
      :accessors {^I32 case-upper-n  [^Rt rt]
                  ^Cmp case-upper-at [^Rt rt ^I32 i ^I32 f]})"

   "u.kin"
   "(ns s.u (:require [demo :refer [defn return I32 Rt Cmp]]
                      [s.t :refer [CASE_UPPER_LEN]]
                      [s.v :refer [case-upper-at]]))
    (defn ^:pub ^:method ^Cmp delta [^Rt rt ^I32 i] (return (case-upper-at rt i 2)))"})

(def tables
  (kp/resolve-exports
   (kp/project {:vocabularies [vocabulary]
                :targets {:rust (target-with kin.target/rust)
                          :java (target-with kin.target/java)}
                :target-order [:rust :java]
                :sources {:vfs (vfs/memory-vfs table-only) :match "*.kin"}})))

(is "3. a NAME-ONLY module speaks the targets it GENERATED for"
    #{:rust :java}
    (:targets (get-in tables [:vocabularies 's.t])))
(is "3. it really does export only names -- no form supplies them"
    [[] '[CASE_UPPER_LEN]]
    [(vec (keys (:forms (get-in tables [:vocabularies 's.t]))))
     (vec (sort (keys (:names (get-in tables [:vocabularies 's.t])))))])
(is "3. and a module with no `defn` but a `^:pub` table speaks them too"
    #{:rust :java}
    (:targets (get-in tables [:vocabularies 's.v])))

;; THE SHAPE THAT FAILED: a source requiring the table-only modules. It threw
;; `generates for NO target` before emitting a byte, because the intersection
;; with `s.t`'s empty set is empty.
(let [out (kp/generate tables (get table-only "u.kin") "u.kin")]
  (is "3. a source requiring them generates for BOTH targets, not none"
      [:rust :java] (vec (keys out)))
  (is "3. and the accessor call crosses the boundary" true
      (str/includes? (:java out) "return CASE_UPPER[i * 4 + 2];")))

;; And the table-only module still emits everything it should on its own --
;; generating it ALONE always worked, which is what made the defect confusing.
(let [out (:rust (kp/generate tables (get table-only "t.kin") "t.kin"))]
  (is "3. the name-only module emits its constants and its table" true
      (and (str/includes? out "pub const CASE_UPPER_LEN: i32 = 8;")
           (str/includes? out "pub(crate) const CASE_LOWER_LEN: i32 = 4;")
           (str/includes? out "pub(crate) static CASE_LOWER: [u32; 4] = ["))))

;; ---------------------------------------------------------------------------
;; 4. AN EMITTER THAT SUPPLIES ONLY ACCESSORS. `:type` and `:expr` are the
;; courtesy; `:accessors` is the contract.

(def bare-src
  "(ns s.bare (:require [demo :refer [defn defdata return I32 Rt]]))
   (defdata weights
     :data [3 4 5]
     :emitter only-accessors
     :accessors {^I32 weights-n [^Rt rt] ^I32 weights-sum [^Rt rt ^I32 i]})
   (defn ^:method ^I32 total [^Rt rt] (return (weights-sum rt 0)))")

(let [out (:rust (kp/generate prj bare-src "bare.kin"))]
  (is "4. no `:expr` means NO binding is emitted" true
      (not (str/includes? out "WEIGHTS")))
  (is "4. and the accessors still work" true
      (str/includes? out "return 12;")))

;; AND AN ACCESSOR THAT IS NOT A ONE-LINE EXPANSION. The emitter writes the
;; body ONCE, into the module, and its template calls it -- so the thing that
;; is written per target stays a template, and the thing that is written once
;; stays a function.
(let [src "(ns s.help (:require [demo :refer [defn defdata return I32 Rt]]))
           (defdata weights
             :data [3 4 5]
             :emitter helpful
             :accessors {^I32 weights-at [^Rt rt ^I32 i]})
           (defn ^:method ^I32 one [^Rt rt] (return (weights-at rt 1)))"
      out (:java (kp/generate prj src "help.kin"))]
  (is "4. the helper body reaches the module" true
      (str/includes? out "static int lookupWEIGHTS(int i) {\n    return [3 4 5][i] * 2;\n}"))
  (is "4. and the accessor's template calls it" true
      (str/includes? out "return lookupWEIGHTS(1);")))

;; ---------------------------------------------------------------------------
;; 5. THE EMITTER IS NAMED, NEVER DEFAULTED, and comes out of the target map.

(refuses "5. a declaration with no `:emitter` is refused"
         #(kp/generate prj (str/replace inline-src ":emitter kin.lang/flat-array" "")
                       "noemitter.kin"))
(refuses "5. an emitter the target does not carry is refused, listing what it has"
         #(kp/generate prj (str/replace inline-src "kin.lang/flat-array" "nope")
                       "unknown.kin"))
(refuses "5. and a target with no `:data-emitters` at all is refused"
         #(kp/generate (kp/project {:vocabularies [vocabulary]
                                    :targets {:rust kin.target/rust}
                                    :target-order [:rust]})
                       inline-src "notable.kin"))

;; ---------------------------------------------------------------------------
;; 6. THE DECLARED SIGNATURE IS KIN'S CONTRACT, NOT THE EMITTER'S.

(refuses "6. an emitter that invents an accessor is refused, naming it"
         #(kp/generate prj (str/replace inline-src "kin.lang/flat-array" "liar")
                       "liar.kin"))
(refuses "6. an emitter reading past the declared arity is refused"
         #(kp/generate prj (str/replace inline-src "kin.lang/flat-array" "overreacher")
                       "over.kin"))
;; flat-array states its own convention rather than guessing one: two indices
;; are a record and a field, and nothing but the declaration says how wide a
;; record is.
(refuses "6. two indices with no `:stride` is refused by the emitter"
         #(kp/generate prj (str/replace inline-src ":stride 4" "")
                       "nostride.kin"))
(refuses "6. and a table of things that are not numbers is refused"
         #(kp/generate prj (str/replace inline-src "[[65 90 32 0] [192 214 32 0]]"
                                        "[\"a\" \"b\"]")
                       "strings.kin"))

;; AND THE NEIGHBOUR THIS CHANGE WOKE. `defconst`'s `const-name` splits on
;; `_` and only on `_`, so a kebab-case name went through untouched and
;; emitted `pub const case-upper-len: i32 = 200;` and `public const int
;; Case-upper-len = 200;` -- code that compiles in no target, and nothing
;; said so. It is refused by name now, the same way `literal` refuses a
;; constant-shaped symbol nobody declared. `defdata` is NOT held to it: a
;; table is named like a `defn` and the screaming spelling is derived.
(refuses "6. a kebab-case `defconst` name is refused, and says what to write"
         #(kp/generate prj
                       "(ns s.kebab (:require [demo :refer [defconst I32]]))
                        (defconst ^I32 case-upper-len 200)"
                       "kebab.kin"))
(let [out (:csharp (kp/generate
                    (kp/project {:vocabularies
                                 [(assoc vocabulary :targets #{:csharp}
                                         :tags {'I32 {:name 'I32 :types {:csharp "int"}}})]
                                 :targets {:csharp (target-with kin.target/csharp)}
                                 :target-order [:csharp]})
                    "(ns s.ok (:require [demo :refer [defconst I32]]))
                     (defconst ^I32 CASE_UPPER_LEN 200)"
                    "ok.kin"))]
  (is "6. and the SCREAMING_SNAKE name it asks for still pascalises" true
      (str/includes? out "internal const int CaseUpperLen = 200;")))

;; The return tag flows: an accessor's declared tag is the tag its call
;; produces, so an unannotated local takes it. `case-upper-at` is `^Cmp`,
;; which is `u32` in Rust and `int` in Java -- and neither is the default.
(let [src (-> inline-src
              (str/replace "[defn defdata return" "[defn defdata let return")
              (str/replace "(return (case-upper-at rt i 2))"
                           "(let [x (case-upper-at rt i 2)] (return x))"))
      out (kp/generate prj src "tag.kin")]
  (is "6. the DECLARED return tag is what a call produces -- rust" true
      (str/includes? (:rust out) "let x: u32 = CASE_UPPER[(i * 4 + 2) as usize];"))
  (is "6. and java reads the same tag its own way" true
      (str/includes? (:java out) "int x = CASE_UPPER[i * 4 + 2];")))


;; ---------------------------------------------------------------------------
;; 7. A MODULE THAT REFERS ONLY AN ACCESSOR MUST STILL GET ITS IMPORT.
;;
;; Java and C# reach a sibling module's names through a static import, and a
;; consumer derives that import by asking which module defines each name it
;; mentions. Asking used to mean READING THE SOURCE AND MATCHING FORM HEADS --
;; `#{'defn 'defconst}` -- which is a copy of the vocabulary kept where the
;; vocabulary cannot see it, and it went stale the moment `defdata` shipped:
;;
;;   * a module referring `case-full-at` and nothing else derived NO import
;;     and emitted `CASE_FULL[fx * 5 + 1]` against nothing -- `cannot find
;;     symbol: variable CASE_FULL`;
;;   * a module that ALSO referred one of the table's `defconst`s compiled,
;;     because the CONSTANT pulled the import in. The working case worked by
;;     accident, and from the outside the two were indistinguishable.
;;
;; `kin.project/declared-names` is the fix: the names a form defines are known
;; to the FORM -- that is what its `:declare` slot registers -- so a consumer
;; asks kin instead of keeping a head list. A declaration form added tomorrow
;; is covered the day it is written.

(def imp-I32 {:name 'I32 :types {:java "int" :csharp "int"}})
(def imp-Rt  {:name 'Rt  :types {:java "Rt"  :csharp "Rt"}})
(def imp-Cmp {:name 'Cmp :types {:java "int" :csharp "uint"}})

(def imp-vocabulary
  {:namespace 'demo :targets #{:java :csharp}
   :tags {'I32 imp-I32 'Rt imp-Rt 'Cmp imp-Cmp} :names {}
   :forms (core/forms {:default-tag imp-I32})})

(def imp-sources
  {;; The table module. NO `defn`, NO `defconst` -- nothing a head-matching
   ;; scan can see, which is `casetable.kin`.
   "casetable.kin"
   "(ns s.casetable (:require [demo :refer [defdata I32 Rt Cmp]]))
    (defdata ^:pub case-full
      :data [[1 2 3 4 5] [6 7 8 9 10]]
      :emitter kin.lang/flat-array :stride 5
      :accessors {^I32 case-full-n  [^Rt rt]
                  ^Cmp case-full-at [^Rt rt ^I32 i ^I32 f]})"
   ;; And the module that refers ONLY an accessor of it. No constant, no
   ;; other name from that module -- this is `casechange.kin`.
   "casechange.kin"
   "(ns s.casechange (:require [demo :refer [defn return I32 Rt Cmp]]
                               [s.casetable :refer [case-full-at]]))
    (defn ^:pub ^:method ^Cmp fold [^Rt rt ^I32 fx] (return (case-full-at rt fx 1)))"})

(defn- unit-name [ns-name] (str/capitalize (last (str/split (name ns-name) #"\."))))

(defn- mentioned
  "Every symbol appearing anywhere in `x` -- what a consumer scans a body for."
  [x]
  (let [out (atom #{})]
    ((fn walk [y] (cond (symbol? y) (swap! out conj y) (coll? y) (run! walk y))) x)
    @out))

(defn- read-all [text] (edn/read-string {:readers {}} (str "[" text "]")))

;; THE OLD DERIVATION, kept here as the thing that failed. It is not used to
;; emit anything -- it is asserted to be WRONG, so the bug has a name and the
;; next person to reach for a head list finds this first.
(defn- owns-by-head
  "Which module defines each name, by matching form HEADS. Stale by design."
  [sources]
  (into {} (for [[f text] sources
                 :let [module (unit-name (second (first (read-all text))))]
                 form (read-all text)
                 :when (and (seq? form) (#{'defn 'defconst} (first form)))]
             [(second form) module])))

;; A project with no `:emit`, used only to ASK what each source defines.
(def imp-plain
  (kp/project {:vocabularies [imp-vocabulary]
               :targets {:java (target-with kin.target/java)
                         :csharp (target-with kin.target/csharp)}
               :target-order [:java :csharp]}))

(defn- owns-by-asking
  "Which module defines each name, asked of kin. Covers every declaration
  form, including the ones written after this line."
  [sources]
  (into {} (for [[f text] sources
                 :let [module (unit-name (second (first (read-all text))))
                       d (kp/declared-names imp-plain text f)]
                 sym (concat (:forms d) (:names d))]
             [sym module])))

(defn- derived-import-emit
  "An `:emit` that derives its static imports the way a real host tree does:
  the siblings whose names this module actually mentions."
  [owns spell]
  (fn [ctx forms]
    (let [ns-form (first (filter #(and (seq? %) (= 'ns (first %))) forms))
          self (unit-name (second ns-form))
          body (remove #(and (seq? %) (= 'ns (first %))) forms)
          sibs (sort (distinct (remove #{self} (keep owns (mentioned body)))))]
      (doseq [sib sibs] (kin/emit! ctx (spell sib)))
      (kin/emit! ctx "\n")
      (kin/scoped ctx {:key :kin/unit :value self}
                  (fn [inner] (doseq [f body] (kin/statement! inner f)))))))

(defn- imp-project [owns]
  (kp/resolve-exports
   (kp/project
    {:vocabularies [imp-vocabulary]
     :target-order [:java :csharp]
     :sources {:vfs (vfs/memory-vfs imp-sources) :match "*.kin"}
     :targets
     ;; A STATIC-IMPORT TARGET: the reference is BARE and the import carries
     ;; it. That combination is what `:cross-unit` exists for -- `need!`
     ;; still fires, so the import is derived, and the name is not prefixed.
     {:java (merge (target-with kin.target/java)
                   {:cross-unit (fn [_ _ nm] nm)
                    :emit (derived-import-emit
                           owns #(str "import static com.example." % ".*;\n"))})
      :csharp (merge (target-with kin.target/csharp)
                     {:cross-unit (fn [_ _ nm] nm)
                      :emit (derived-import-emit
                             owns #(str "using static global::Example." % ";\n"))})}})))

;; THE BUG, pinned. A head-matching scan cannot see a `defdata` at all, so it
;; does not know that `case-full-at` belongs to `casetable`.
(is "7. matching form heads MISSES what a `defdata` defines"
    nil (get (owns-by-head imp-sources) 'case-full-at))
(is "7. and asking kin finds it -- table, accessors and all"
    ["Casetable" "Casetable" "Casetable"]
    (mapv (owns-by-asking imp-sources) '[case-full case-full-n case-full-at]))

;; EVERY DECLARATION FORM, not just the new one. A consumer swapping a head
;; list for this question must not lose the cases the head list got right --
;; `defconst` had no `:declare` slot at all, so its name existed only once
;; something had been GENERATED, and asking without emitting saw nothing.
(is "7. asking covers `defn`, `defconst` and `defdata` alike"
    [#{'helper 'case-full-n 'case-full-at} #{'CASE_UPPER_LEN 'case-full}]
    (let [d (kp/declared-names
             imp-plain
             "(ns s.every (:require [demo :refer [defn defdata defconst return I32 Rt Cmp]]))
              (defconst ^:pub ^I32 CASE_UPPER_LEN 8)
              (defdata ^:pub case-full
                :data [[1 2 3 4 5]] :emitter kin.lang/flat-array :stride 5
                :accessors {^I32 case-full-n  [^Rt rt]
                            ^Cmp case-full-at [^Rt rt ^I32 i ^I32 f]})
              (defn ^:pub ^:method ^I32 helper [^Rt rt] (return 1))"
             "every.kin")]
      [(:forms d) (:names d)]))

;; THE SHAPE THAT FAILED, end to end: `casechange` refers ONLY an accessor.
(let [out (kp/generate (imp-project (owns-by-asking imp-sources))
                       (get imp-sources "casechange.kin") "casechange.kin")]
  (is "7. Java carries the import for the table it only reads through an accessor"
      true (str/includes? (:java out) "import static com.example.Casetable.*;"))
  (is "7. and the body is the bare reference that import exists to resolve"
      true (str/includes? (:java out) "return CASE_FULL[fx * 5 + 1];"))
  (is "7. C# carries its own spelling of the same import"
      true (str/includes? (:csharp out) "using static global::Example.Casetable;"))
  (is "7. and the same bare reference"
      true (str/includes? (:csharp out) "return CaseFull[fx * 5 + 1];")))

;; And with the OLD derivation the very same source emits no import at all --
;; which is the uncompilable output, reproduced.
(let [out (kp/generate (imp-project (owns-by-head imp-sources))
                       (get imp-sources "casechange.kin") "casechange.kin")]
  (is "7. the head-matching derivation emits NO import -- the bug, reproduced"
      false (str/includes? (:java out) "import static")))

(println)
(if (zero? @failures)
  (println "defdata: the table is data, the emitter is named, the signature is kin's\n")
  (do (println (format "defdata: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
