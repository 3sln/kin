#!/usr/bin/env bb
;; Do `source-origins`, `targets-report` and `report` tell the truth?
;;
;;     bb test/diagnostics.clj
;;
;; THIS TEST IS THE REASON THE SOURCE VFS EXISTS. Before it, both reports
;; globbed a real directory, so checking them meant having a real tree of
;; fabricated sources on disk -- and neither was ever checked. With a source
;; vfs they are functions over a config, and the config can be three strings
;; in a map.
;;
;; It matters more than it sounds. `source-origins` exists to catch the class
;; of bug that
;; let `LS_THUNK` through: a symbol resolving to nothing in particular while
;; every gate stayed green and the CLR quietly stopped compiling. A diagnostic
;; built for that job is trusted by definition -- nobody double-checks the
;; tool they reached for BECAUSE they could not see the problem. One that is
;; quietly wrong is worse than none at all, because it ends the search.
;;
;; Five fabricated sources, one for each thing a report has to be able to say:
;;
;;   everywhere.kin   generates for both targets
;;   only.kin         restricted to one by `:kin/only`
;;   narrow.kin       requires a vocabulary that cannot speak :java
;;
;; plus `nowhere.kin`, using a lowercase symbol that comes from nowhere and
;; renders SILENTLY, and `broken.kin`, whose constant-shaped one is refused.
;; They are the same bug and only one announces itself -- and `report`, at the
;; end, has to put both of them in one list.
(require '[kin] '[kin.lang :as core] '[kin.target]
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

;; `source-origins`, on the source whose symbol comes from nowhere.
(let [w (kp/source-origins prj "nowhere.kin")]
  (is "source-origins: the symbol from nowhere is named" #{'ls-thunk} (:nowhere w))
  (is "source-origins: and it is not mistaken for a local" #{'x} (:bound w))
  (is "source-origins: the file's own defn is declared" #{'d} (:declared w))
  (is "source-origins: `return` is credited to the vocabulary that gave it"
      true (contains? (get (:by-vocab w) '[wide :form]) 'return)))

;; `source-origins`, on the one that generates for fewer targets, has to
;; agree with `targets-report` -- two reports that disagree are worse than one.
(let [w (kp/source-origins prj "only.kin")]
  (is "source-origins: `only.kin` generates for rust alone" [:rust] (:emit-for w))
  (is "source-origins: and names the exclusion" #{:rust} (:only (:target-report w))))

(let [w (kp/source-origins prj "narrow.kin")]
  (is "source-origins: `narrow.kin` generates for rust alone" [:rust] (:emit-for w))
  (is "source-origins: because `narrow` cannot speak java"
      ["narrow cannot speak it"] (get-in w [:target-report :ruled-out :java])))

;; THE POINT OF THE WHOLE REPORT, in two sources that differ by case.
;;
;; `ls-thunk` renders WITHOUT COMPLAINT -- the target's local namer spells it
;; and emits `ls_thunk` into a file that will not compile -- so nothing but
;; this report can see it. `LS_THUNK` is constant-shaped and is refused
;; outright. Both are the same bug; only one announces itself, and the one
;; that does not is the one that cost the CLR a fortnight.
(is "source-origins: the silent one renders clean, and only the report sees it"
    [nil #{'ls-thunk}]
    (let [w (kp/source-origins prj "nowhere.kin")] [(:failure w) (:nowhere w)]))

;; A source that will not render still answers, from however far it got --
;; `source-origins` is the report you reach for BECAUSE generation broke.
(let [w (kp/source-origins prj "broken.kin")]
  (is "source-origins: a render that throws is reported, not swallowed"
      true (boolean (:failure w)))
  (is "source-origins: and the tables are still there afterwards"
      true (contains? (get (:by-vocab w) '[wide :form]) 'return)))

;; ------------------------------------------------------------------ report
;;
;; THE WHOLE PROJECT IN ONE CALL. `source-origins` answers about one source
;; and `targets-report` about one axis of all of them, and a person opening a
;; project they have not read wants neither question -- they want the state,
;; and they want everything that is wrong in ONE list rather than distributed
;; over calls they have to know to make.

(def r (kp/report prj))

(is "report: every source and every vocabulary, with what each can speak"
    [["broken.kin" "everywhere.kin" "narrow.kin" "nowhere.kin" "only.kin"]
     {'narrow {:targets #{:rust} :forms 1 :tags 1 :names 0}
      'wide {:targets #{:rust :java} :forms 37 :tags 2 :names 0}}]
    [(:sources r) (into {} (:vocabularies r))])

;; ONE LIST, ACROSS KINDS. The two bugs this file is about are found by two
;; different mechanisms -- one is a symbol attributed to nothing, the other an
;; exception out of a render -- and they are the same thing to a reader:
;; something to go and look at. Keeping them in separate keys would make
;; noticing them a function of which one you thought to read.
(is "report: the silent bug and the loud one are in the same list"
    [[:nowhere "broken.kin" 'LS_THUNK]
     [:nowhere "nowhere.kin" 'ls-thunk]
     [:render-failure "broken.kin"]]
    (mapv (fn [d] (if (= :render-failure (:issue d))
                    [(:issue d) (:where d)]
                    [(:issue d) (:where d) (:detail d)]))
          (:diagnostics r)))

;; And it does not throw for a project that is merely wrong, which is the
;; whole point of it: this is the call you make when you already know
;; something is broken and want to see all of it at once.
(is "report: a source that will not render is a diagnostic, not an exception"
    [(kp/source-origins prj "broken.kin") true]
    [(get (:origins r) "broken.kin")
     (boolean (:failure (get (:origins r) "broken.kin")))])

;; Nothing here declares a host tree, so the host half is empty rather than
;; absent -- a caller printing the report should not have to know whether this
;; project happens to have annotations. `test/host.clj` is where a populated
;; one is checked.
(is "report: a project with no host annotations still answers the host keys"
    {:targets [] :namespaces [] :disagreements [] :arities {}} (:host r))

(println)
(if (zero? @failures)
  (println "diagnostics: the reports say what is true, and say why\n")
  (do (println (format "diagnostics: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
