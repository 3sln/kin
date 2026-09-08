#!/usr/bin/env bb
;; Does `emit!` write a module correctly -- and can it do so without a disk?
;;
;;     bb test/emit.clj
;;
;; THE SECOND HALF IS THE POINT. Every assertion below runs against a
;; `memory-vfs`: no directory, no temporary files, no cleanup, and no chance
;; that a passing test wrote into the tree it was checking. Before the vfs
;; existed, emit reached for `java.io.File` and `spit` directly, so testing it
;; at all meant scribbling somewhere real -- which is why it was never tested.
;;
;; THERE USED TO BE A SPLICE. kin found a `kin:begin`/`kin:end` pair in a
;; hand-written file and replaced the lines between them, and half of this
;; file tested that: markers preserved, surrounding code surviving, several
;; sources sharing one destination. All of it is gone. A target's `:emit`
;; owns the whole file, so there is no hand-written content to preserve and
;; no marker to lose.
;;
;; What is pinned:
;;
;;   1. the destination is CREATED, and its previous content does not survive
;;      -- kin owns the file, so there is nowhere in one to put a hand edit
;;   2. emit is IDEMPOTENT: running it twice gives the same bytes
;;   3. the indent comes from the scope the `:emit` opens, not from a number
;;      beside the destination
;;   4. a target whose `:path` answers nil is generated and written NOWHERE
;;   5. a target with a `:path` and NO `:emit` is refused by name -- it is
;;      asking kin to write a fragment, which is what the splice used to take
;;   6. a batch is atomic: one failure and nothing is written
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

(def src-text
  "(ns demo.thing (:require [demo :refer [defn return I32 Rt]]))
   (defn ^:method ^I32 twice [^Rt rt ^I32 a] (return a))")

(def other-source
  "(ns demo.other (:require [demo :refer [defn return I32 Rt]]))
   (defn ^:method ^I32 half [^Rt rt ^I32 a] (return a))")

(defn impl-emit
  "An `:emit` that wraps the forms in `impl Rt { }`.

  The indent comes from the scope this opens, which is the thing that
  replaced a per-destination `:indent` number. A wrapper knows how deep its
  own body is; a table beside the file was restating it."
  [ctx forms]
  (kin/emit! ctx "// generated\n")
  (kin/emit! ctx "impl Rt {\n")
  (kin/scoped ctx {:key :impl :value "Rt" :indent 1}
              (fn [inner]
                (doseq [f (remove #(and (seq? %) (= 'ns (first %))) forms)]
                  (kin/statement! inner f))))
  (kin/emit! ctx "}\n"))

(defn project-with
  "A project whose :rust target writes into `fs`, and whose :java target --
  the one with no `:path` -- writes nowhere."
  [fs]
  (kp/project {:vocabularies [vocabulary]
               :target-order [:rust :java]
               :targets {:rust (merge kin.target/rust
                                      {:vfs fs
                                       :path (fn [_] "thing.rs")
                                       :emit impl-emit})
                         ;; A REAL TARGET THAT IS WRITTEN NOWHERE. It
                         ;; generates like any other and its `:path` answers
                         ;; nil, which is how a source says `verify me, do not
                         ;; ship me`.
                         :java (merge kin.target/java
                                      {:vfs fs :path (fn [_] nil)
                                       :emit impl-emit})}}))

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
      (println (format "  ok   %-52s %s" label (subs msg 0 (min 44 (count msg)))))
      (do (swap! failures inc)
          (println (format "  FAIL %s -- it did NOT refuse" label))))))

(println "\nemit: writing a module, with no disk anywhere\n")

;; 1, 3 and 4.
(let [fs (vfs/memory-vfs {"thing.rs" "STALE, and must not survive\n"})
      out (kp/emit! (project-with fs) src-text "thing.kin")
      written (get (vfs/files fs) "thing.rs")]
  ;; THE WHOLE FILE IS kin'S. What was there before is not merged with, not
  ;; spliced around, and not preserved -- it is replaced.
  (is "1. the previous content is gone" false (str/includes? written "STALE"))
  (is "1. and the emit's own wrapper is the file"
      [true true] [(str/starts-with? written "// generated\n")
                   (str/ends-with? written "}\n")])
  (is "3. the body is indented by the scope the :emit opened"
      true (str/includes? written "    pub(crate) fn twice(&self, a: i32) -> i32 {"))
  ;; 4. The ghost target generates and is written nowhere. That has to be
  ;; expressible: a source can exist to be VERIFIED rather than shipped.
  (is "4. a target whose :path answers nil is written nowhere"
      [{:target :rust :path "thing.rs"} {:target :java :path nil}] out)
  (is "4. and nothing else appeared in the vfs"
      ["thing.rs"] (vec (keys (vfs/files fs))))

  ;; 2. Idempotence. A generator whose second run differs from its first
  ;; cannot have a drift gate, because the gate would never be green.
  (let [again (do (kp/emit! (project-with fs) src-text "thing.kin")
                  (get (vfs/files fs) "thing.rs"))]
    (is "2. emitting twice gives the same bytes" written again)))

;; 1 again, and it is the difference from the splice: kin CREATES the file.
;; The region path refused a destination that did not exist, because a region
;; goes into something somebody else wrote.
(let [fs (vfs/memory-vfs {})]
  (kp/emit! (project-with fs) src-text "thing.kin")
  (is "1. a destination that did not exist is created"
      true (contains? (vfs/files fs) "thing.rs")))

;; 5. A `:path` with no `:emit` asks kin to write a fragment. There is
;; nowhere to put one now, so it is refused by name.
;;
;; `dissoc` RATHER THAN A BARE `kin.target/rust`, because the shipped targets
;; carry a default `:emit` now. Taking it away is what makes this the
;; configuration the refusal is about -- a target that writes files and has
;; not said what a file looks like -- and stating it that way keeps the test
;; pointed at the rule instead of at whichever target happened to lack one.
(refuses "5. a :path with no :emit"
         #(kp/emit! (kp/project
                     {:vocabularies [vocabulary]
                      :target-order [:rust]
                      :targets {:rust (merge (dissoc kin.target/rust :emit)
                                             {:vfs (vfs/memory-vfs {})
                                              :path (fn [_] "thing.rs")})}})
                    src-text "thing.kin"))

;; ---------------------------------------------------------------------------
;; 6. ATOMICITY. A batch either happens or does not.

;; One good source, and a second that collides with it -- two namespaces
;; cannot BE one file. The good one must not be written: not `written and
;; then reverted`, but indistinguishable from never having run.
(let [fs (vfs/memory-vfs {})
      prj (kp/project {:vocabularies [vocabulary]
                       :target-order [:rust]
                       :targets {:rust (merge kin.target/rust
                                              {:vfs fs
                                               :emit impl-emit
                                               :path (fn [_] "same.rs")})}})
      err (try (kp/emit-sources! prj [{:label "a.kin" :text src-text}
                                      {:label "b.kin" :text other-source}])
               nil
               (catch Exception e (ex-message e)))]
  (is "6. the batch refused" true (boolean err))
  (is "6. and NOTHING was created" {} (vfs/files fs)))

;; A write that fails PARTWAY is the harder case: some destinations are
;; already replaced. Everything must go back.
(let [one-was "// one, as it was\n"
      two-was "// two, as it was\n"
      backing (atom {"one.rs" one-was "two.rs" two-was})
      exploding (reify vfs/Vfs
                  (-exists? [_ p] (contains? @backing p))
                  (-read [_ p] (get @backing p))
                  (-write [_ p c]
                    (if (= p "two.rs")
                      (throw (ex-info "disk full" {}))
                      (do (swap! backing assoc p c) nil))))
      prj (kp/project {:vocabularies [vocabulary]
                       :target-order [:rust]
                       :targets {:rust (merge kin.target/rust
                                              {:vfs exploding
                                               :emit impl-emit
                                               :path (fn [ns-name]
                                                       (if (= 'demo.thing ns-name)
                                                         "one.rs" "two.rs"))})}})
      err (try (kp/emit-sources! prj [{:label "a.kin" :text src-text}
                                      {:label "b.kin" :text other-source}])
               nil (catch Exception e (ex-message e)))]
  (is "6. a write failing partway is reported" true (boolean err))
  (is "6. and says the batch was reverted"
      true (boolean (and err (str/includes? err "reverted"))))
  (is "6. and the already-written destination went back"
      one-was (get @backing "one.rs")))

;; ---------------------------------------------------------------------------
;; WHOLE FILES. A target with an `:emit` owns the file: no markers, no splice,
;; and the destination is CREATED rather than written into.

(def whole-target
  (merge kin.target/rust
         {:path (fn [_] "gen/thing.rs")
          ;; `:emit` is handed every form, the ns form included, and drives
          ;; the emission itself -- so it can put text before, after AND
          ;; between the forms.
          :emit (fn [ctx forms]
                  (kin/emit! ctx "// generated\n")
                  (let [imports (kin/emit-anchor! ctx)]
                    (kin/emit! ctx "mod thing {\n")
                    (kin/scoped
                     ctx {:key :mod :value true :indent 1}
                     (fn [inner]
                       (doseq [[i f] (map-indexed vector
                                                  (remove #(and (seq? %) (= 'ns (first %)))
                                                          forms))]
                         (when (pos? i) (kin/emit! inner "\n"))
                         (kin/statement! inner f))))
                    (kin/emit! ctx "}\n")
                    ;; Written LAST, into a place the output went past first.
                    (kin/emit! imports "use std::fmt;\n")))}))

(is "10. a target with :emit produces whole files" true
    (kp/whole-file? whole-target))
(is "10. and one without does not" false
    (kp/whole-file? (merge (dissoc kin.target/rust :emit)
                           {:path (fn [_] "x.rs")})))
;; AND THE SHIPPED ONE HAS ONE, which is the change: a project no longer has
;; to write a `:emit` before it can write a file at all.
(is "10. and the shipped target ships with one" true
    (kp/whole-file? kin.target/rust))

(let [fs (vfs/memory-vfs {})
      prj (kp/project {:vocabularies [vocabulary]
                       :target-order [:rust]
                       :targets {:rust (assoc whole-target :vfs fs)}})
      out (kp/emit! prj src-text "thing.kin")
      written (get (vfs/files fs) "gen/thing.rs")]
  ;; THE DESTINATION DID NOT EXIST. The region path refuses that by design;
  ;; a whole-file target creates it, which is the one real difference.
  (is "10. the file was created, not required to exist"
      [{:target :rust :path "gen/thing.rs"}] out)
  (is "10. the prefix and suffix are the target's"
      [true true] [(str/starts-with? written "// generated\n")
                   (str/ends-with? written "}\n")])
  ;; The anchor is the whole point: emitted after the body, appearing before it.
  (is "10. an anchor put the import above the code it was found in"
      true (< (str/index-of written "use std::fmt;")
              (str/index-of written "mod thing {")))
  (is "10. and there are no markers anywhere"
      false (str/includes? written "kin:begin"))
  ;; Idempotent, like the region path.
  (is "10. writing it twice gives the same bytes"
      written (do (kp/emit! prj src-text "thing.kin")
                  (get (vfs/files fs) "gen/thing.rs"))))

;; THE HEADER MUST BE DETERMINISTIC. Forms contribute what they need as data
;; into an atom `:emit` put in scope; `:emit` sorts before formatting. A set is
;; unordered, so without the sort the same source could emit two byte-different
;; files that mean the same thing -- and a drift gate would report a difference
;; that is not there. A gate that fails at random is worse than no gate: it
;; gets re-run until it passes, and then it gets ignored.
(let [collected (atom [])
      header-target
      (merge kin.target/rust
             {:path (fn [_] "h.rs")
              :emit (fn [ctx forms]
                      (let [at (kin/emit-anchor! ctx)
                            needs (atom #{})]
                        (kin/scoped
                         ctx {:key :needs :value needs}
                         (fn [inner]
                           ;; Contributed in DELIBERATELY reverse order, with
                           ;; a duplicate, which is what a real file does.
                           (doseq [n ["zeta" "alpha" "zeta" "middle"]]
                             (swap! (kin/get inner :needs) conj n))
                           (doseq [f (remove #(and (seq? %) (= 'ns (first %))) forms)]
                             (kin/statement! inner f))))
                        (reset! collected (vec (sort @needs)))
                        (kin/emit! at (str/join (map #(str "use " % ";\n")
                                                        (sort @needs))))))})
      fs (vfs/memory-vfs {})
      prj (kp/project {:vocabularies [vocabulary]
                       :target-order [:rust]
                       :targets {:rust (assoc header-target :vfs fs)}})
      _ (kp/emit! prj src-text "h.kin")
      written (get (vfs/files fs) "h.rs")]
  (is "12. duplicate contributions collapse to one"
      ["alpha" "middle" "zeta"] @collected)
  (is "12. and the header is emitted in sorted order"
      "use alpha;\nuse middle;\nuse zeta;\n"
      (subs written 0 (str/index-of written "pub(crate) fn "))))

;; Two namespaces cannot BE the same file. The region path allows several
;; writers per destination; this one cannot, and says so.
(refuses "11. two sources claiming one whole file"
         #(let [fs (vfs/memory-vfs {})]
            (kp/emit-sources!
             (kp/project {:vocabularies [vocabulary]
                          :target-order [:rust]
                          :targets {:rust (assoc whole-target :vfs fs)}})
             [{:label "a.kin" :text src-text}
              {:label "b.kin" :text other-source}])))

;; And the claim this file is really making.
(println)
(println "  (no directory was created, opened, or written by any of the above)")

(println)
(if (zero? @failures)
  (println "emit: a module is written whole, twice, and never touched a disk\n")
  (do (println (format "emit: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
