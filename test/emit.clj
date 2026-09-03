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
(require '[kin :as sp] '[kin.lang :as core] '[kin.target]
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

;; And the claim this file is really making.
(println)
(println "  (no directory was created, opened, or written by any of the above)")

(println)
(if (zero? @failures)
  (println "emit: the region splices, twice, and never touched a disk\n")
  (do (println (format "emit: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
