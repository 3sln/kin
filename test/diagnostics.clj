#!/usr/bin/env bb
;; Do `why` and `targets-report` tell the truth?
;;
;;     bb test/diagnostics.clj
;;
;; THIS TEST IS THE REASON THE SOURCE VFS EXISTS. Before it, both reports
;; globbed a real directory, so checking them meant having a real tree of
;; fabricated sources on disk -- and neither was ever checked. With a source
;; vfs they are functions over a config, and the config can be three strings
;; in a map.
;;
;; It matters more than it sounds. `why` exists to catch the class of bug that
;; let `LS_THUNK` through: a symbol resolving to nothing in particular while
;; every gate stayed green and the CLR quietly stopped compiling. A diagnostic
;; built for that job is trusted by definition -- nobody double-checks the
;; tool they reached for BECAUSE they could not see the problem. A `why` that
;; is quietly wrong is worse than no `why` at all, because it ends the search.
;;
;; Five fabricated sources, one for each thing a report has to be able to say:
;;
;;   everywhere.kin   generates for both targets
;;   only.kin         restricted to one by `:kin/only`
;;   narrow.kin       requires a vocabulary that cannot speak :java
;;
;; plus `nowhere.kin`, using a lowercase symbol that comes from nowhere and
;; renders SILENTLY, and `broken.kin`, whose constant-shaped one is refused.
;; They are the same bug and only one announces itself.
(require '[kin :as sp] '[kin.lang :as core] '[kin.target]
         '[kin.vfs :as vfs] '[kin.project :as kp] '[clojure.string :as str])

(def I32 {:name 'I32 :types {:rust "i32" :java "int"}})
(def Rt* {:name 'Rt :types {:rust "Rt" :java "Rt"}})

(def wide
  "Speaks both targets."
  {:namespace 'wide :targets #{:rust :java}
   :tags {'I32 I32 'Rt Rt*} :names {}
   :forms (core/forms {:default-tag I32})})

(def narrow
  "Speaks only :rust. A source requiring it cannot generate for :java, and
  `targets-report` has to say so and say WHOSE fault it is."
  {:namespace 'narrow :targets #{:rust}
   :tags {'Only {:name 'Only :types {:rust "i32"}}} :names {}
   :forms {'only-here (core/call {:rust "only({0})"})}})

(def sources
  {"everywhere.kin"
   "(ns s.everywhere (:require [wide :refer [defn return I32 Rt]]))
    (defn ^:method ^I32 a [^Rt rt ^I32 x] (return x))"

   "only.kin"
   "(ns s.only {:kin/only #{:rust}} (:require [wide :refer [defn return I32 Rt]]))
    (defn ^:method ^I32 b [^Rt rt ^I32 x] (return x))"

   "narrow.kin"
   "(ns s.narrow (:require [wide :refer [defn return I32 Rt]]
                           [narrow :refer [only-here]]))
    (defn ^:method ^I32 c [^Rt rt ^I32 x] (return (only-here x)))"

   "nowhere.kin"
   "(ns s.nowhere (:require [wide :refer [defn return I32 Rt]]))
    (defn ^:method ^I32 d [^Rt rt ^I32 x] (return ls-thunk))"

   ;; A source that will NOT render. `LS_THUNK` is constant-shaped, and an
   ;; undeclared constant is refused outright -- unlike its lowercase twin
   ;; above, which renders happily into three files that do not compile.
   "broken.kin"
   "(ns s.broken (:require [wide :refer [defn return I32 Rt]]))
    (defn ^:method ^I32 e [^Rt rt ^I32 x] (return LS_THUNK))"})

(def prj
  (kp/project {:vocabularies [wide narrow]
               :targets {:rust kin.target/rust :java kin.target/java}
               :target-order [:rust :java]
               :sources {:vfs (vfs/memory-vfs sources) :match "*.kin"}}))

(def failures (atom 0))

(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))

(println "\ndiagnostics: five fabricated sources, no directory anywhere\n")

;; The source vfs enumerates. This is the operation the whole section is for.
(is "the project lists its sources, sorted"
    ["broken.kin" "everywhere.kin" "narrow.kin" "nowhere.kin" "only.kin"]
    (kp/source-labels prj))

(let [r (kp/targets-report prj)]
  (is "rust: every source reaches it"
      ["broken.kin" "everywhere.kin" "narrow.kin" "nowhere.kin" "only.kin"]
      (get-in r [:by-target :rust :generates]))
  (is "java: only the two that can"
      ["broken.kin" "everywhere.kin" "nowhere.kin"]
      (get-in r [:by-target :java :generates]))
  ;; AND WHY EACH OF THE OTHER TWO IS ABSENT, separately and by name. A
  ;; report that said only "two sources" would be true and useless.
  (is "java: `only.kin` was ruled out by its :kin/only"
      {[":kin/only does not name it"] ["only.kin"]
       ["narrow cannot speak it"] ["narrow.kin"]}
      (get-in r [:by-target :java :ruled-out])))

;; `why`, on the source whose symbol comes from nowhere.
(let [w (kp/why prj "nowhere.kin")]
  (is "why: the symbol from nowhere is named" #{'ls-thunk} (:nowhere w))
  (is "why: and it is not mistaken for a local" #{'x} (:bound w))
  (is "why: the file's own defn is declared" #{'d} (:declared w))
  (is "why: `return` is credited to the vocabulary that gave it"
      true (contains? (get (:by-vocab w) '[wide :form]) 'return)))

;; `why`, on the one that generates for fewer targets, has to agree with
;; `targets-report` -- two reports that disagree are worse than one.
(let [w (kp/why prj "only.kin")]
  (is "why: `only.kin` generates for rust alone" [:rust] (:emit-for w))
  (is "why: and names the exclusion" #{:rust} (:only (:report w))))

(let [w (kp/why prj "narrow.kin")]
  (is "why: `narrow.kin` generates for rust alone" [:rust] (:emit-for w))
  (is "why: because `narrow` cannot speak java"
      ["narrow cannot speak it"] (get-in w [:report :ruled-out :java])))

;; THE POINT OF THE WHOLE REPORT, in two sources that differ by case.
;;
;; `ls-thunk` renders WITHOUT COMPLAINT -- the target's local namer spells it
;; and emits `ls_thunk` into a file that will not compile -- so nothing but
;; this report can see it. `LS_THUNK` is constant-shaped and is refused
;; outright. Both are the same bug; only one announces itself, and the one
;; that does not is the one that cost the CLR a fortnight.
(is "why: the silent one renders clean, and only the report sees it"
    [nil #{'ls-thunk}]
    (let [w (kp/why prj "nowhere.kin")] [(:failure w) (:nowhere w)]))

;; A source that will not render still answers, from however far it got --
;; `why` is the report you reach for BECAUSE generation broke.
(let [w (kp/why prj "broken.kin")]
  (is "why: a render that throws is reported, not swallowed"
      true (boolean (:failure w)))
  (is "why: and the tables are still there afterwards"
      true (contains? (get (:by-vocab w) '[wide :form]) 'return)))

(println)
(if (zero? @failures)
  (println "diagnostics: both reports say what is true, and say why\n")
  (do (println (format "diagnostics: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
