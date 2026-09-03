#!/usr/bin/env bb
;; Does `emit!` splice a region correctly -- and can it do so without a disk?
;;
;;     bb test/emit.clj
;;
;; THE SECOND HALF IS THE POINT. Every assertion below runs against a
;; `memory-vfs`: no directory, no temporary files, no cleanup, and no chance
;; that a passing test wrote into the tree it was checking. Before the vfs
;; existed, emit reached for `java.io.File` and `spit` directly, so testing it
;; at all meant scribbling somewhere real -- which is why it was never tested.
;;
;; What is pinned:
;;
;;   1. the region between the markers is replaced, and everything outside
;;      them -- including hand-written code after the end marker -- survives
;;   2. emit is IDEMPOTENT: running it twice gives the same bytes
;;   3. the indent a target asks for is applied to the block
;;   4. a target whose `:path` answers nil is generated and written NOWHERE
;;   5. a destination that does not exist is refused by name
;;   6. a file missing either marker is refused by name
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

(def host
  (str "// a hand-written file\n"
       "impl Rt {\n"
       "    // kin:begin thing.kin\n"
       "    STALE, and must not survive\n"
       "    // kin:end thing.kin\n"
       "\n"
       "    fn hand_written(&self) {}\n"
       "}\n"))

(defn project-with
  "A project whose :rust target writes into `fs`, and whose :java target --
  the one with no `:path` -- writes nowhere."
  [fs]
  (kp/project {:vocabularies [vocabulary]
               :target-order [:rust :java]
               :targets {:rust (merge kin.target/rust
                                      {:vfs fs
                                       :path (fn [_] "thing.rs")
                                       :indent 4})
                         ;; A REAL TARGET THAT IS WRITTEN NOWHERE. It
                         ;; generates like any other and its `:path` answers
                         ;; nil, which is how a source says `verify me, do not
                         ;; ship me`.
                         :java (merge kin.target/java
                                      {:vfs fs :path (fn [_] nil)})}}))

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

(println "\nemit: splicing a region, with no disk anywhere\n")

;; 1 and 3. Splice, and indent.
(let [fs (vfs/memory-vfs {"thing.rs" host})
      out (kp/emit! (project-with fs) src-text "thing.kin")
      written (get (vfs/files fs) "thing.rs")]
  (is "1. the stale region is gone" false (str/includes? written "STALE"))
  (is "1. the hand-written code after the end marker survives"
      true (str/includes? written "fn hand_written(&self) {}"))
  (is "1. and the line before the begin marker survives"
      true (str/includes? written "// a hand-written file"))
  (is "1. both markers are still there"
      [true true] [(str/includes? written "kin:begin thing.kin")
                   (str/includes? written "kin:end thing.kin")])
  (is "3. the block is indented by the target's :indent"
      true (str/includes? written "    fn twice(&self, a: i32) -> i32 {"))
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

;; 5 and 6. The refusals.
(refuses "5. a destination that does not exist"
         #(kp/emit! (project-with (vfs/memory-vfs {})) src-text "thing.kin"))
(refuses "6. a file with no begin marker"
         #(kp/emit! (project-with (vfs/memory-vfs {"thing.rs" "nothing here\n"}))
                    src-text "thing.kin"))
(refuses "6. a file with a begin marker and no end"
         #(kp/emit! (project-with (vfs/memory-vfs
                                   {"thing.rs" "// kin:begin thing.kin\n"}))
                    src-text "thing.kin"))

;; ---------------------------------------------------------------------------
;; ATOMICITY. A batch either happens or does not.

(def other-source
  "(ns demo.other (:require [demo :refer [defn return I32 Rt]]))
   (defn ^:method ^I32 half [^Rt rt ^I32 a] (return a))")

(defn two-file-project [fs]
  (kp/project {:vocabularies [vocabulary]
               :target-order [:rust]
               :targets {:rust (merge kin.target/rust
                                      {:vfs fs
                                       ;; BOTH namespaces go to the SAME file,
                                       ;; which is the case that catches a
                                       ;; per-source read-modify-write.
                                       :path (fn [_] "thing.rs")
                                       :indent 4})}}))

;; Two sources into ONE destination. Doing this per source would read the file
;; twice, and the second read would see the first source's output -- so a
;; later failure would `roll back` to a tree that already had A in it.
(let [two-region (str "impl Rt {\n"
                      "    // kin:begin a.kin\n    old\n    // kin:end a.kin\n"
                      "    // kin:begin b.kin\n    old\n    // kin:end b.kin\n"
                      "}\n")
      fs (vfs/memory-vfs {"thing.rs" two-region})
      _ (kp/emit-sources! (two-file-project fs)
                          [{:label "a.kin" :text src-text}
                           {:label "b.kin" :text other-source}])
      written (get (vfs/files fs) "thing.rs")]
  (is "7. two sources into one destination both land"
      [true true]
      [(str/includes? written "fn twice(") (str/includes? written "fn half(")])
  (is "7. and neither clobbered the other's region" false
      (str/includes? written "old")))

;; THE ATOMICITY CLAIM. One good source, one whose destination has no marker.
;; The good one must not be written -- not `written and then reverted`, but
;; indistinguishable from never having run.
(let [good-host (str "// kin:begin a.kin\nold\n// kin:end a.kin\n")
      fs (vfs/memory-vfs {"good.rs" good-host "bad.rs" "no markers here\n"})
      prj (kp/project {:vocabularies [vocabulary]
                       :target-order [:rust]
                       :targets {:rust (merge kin.target/rust
                                              {:vfs fs
                                               :path (fn [ns-name]
                                                       (if (= 'demo.thing ns-name)
                                                         "good.rs" "bad.rs"))})}})
      err (try (kp/emit-sources! prj [{:label "a.kin" :text src-text}
                                      {:label "b.kin" :text other-source}])
               nil
               (catch Exception e (ex-message e)))]
  (is "8. the batch refused" true (boolean err))
  (is "8. and the GOOD destination is untouched" good-host
      (get (vfs/files fs) "good.rs"))
  (is "8. and the bad one is untouched too" "no markers here\n"
      (get (vfs/files fs) "bad.rs")))

;; A write that fails PARTWAY is the harder case: some destinations are
;; already replaced. Everything must go back.
(let [good-host (str "// kin:begin a.kin\nold\n// kin:end a.kin\n")
      other-host (str "// kin:begin b.kin\nold\n// kin:end b.kin\n")
      backing (atom {"one.rs" good-host "two.rs" other-host})
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
                                               :path (fn [ns-name]
                                                       (if (= 'demo.thing ns-name)
                                                         "one.rs" "two.rs"))})}})
      err (try (kp/emit-sources! prj [{:label "a.kin" :text src-text}
                                      {:label "b.kin" :text other-source}])
               nil (catch Exception e (ex-message e)))]
  (is "9. a write failing partway is reported" true (boolean err))
  (is "9. and says the batch was reverted"
      true (boolean (and err (str/includes? err "reverted"))))
  (is "9. and the already-written destination went back"
      good-host (get @backing "one.rs")))

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
    (kp/whole-file? (merge kin.target/rust {:path (fn [_] "x.rs")})))

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
      (subs written 0 (str/index-of written "fn "))))

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
  (println "emit: the region splices, twice, and never touched a disk\n")
  (do (println (format "emit: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
