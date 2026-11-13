#!/usr/bin/env bb
;; An import is written for what the generated code REFERENCES, not for what
;; the source CALLED.
;;
;;     bb test/inlined.clj
;;
;; A `flat-array` COUNT accessor emits as a literal -- `(oi-count 0)` is `5` --
;; and its template has no `{unit}` in it, so the call never names the other
;; module. `defdata` used to ask for the unit qualifier anyway, which
;; registers the import as a side effect, so a module that only ever called
;; such an accessor got `import "ex/ident"` for a package it never mentioned.
;; In Go that is `imported and not used`, a compile ERROR; Java and C# merely
;; warn. The header must hold exactly what the body reaches.
(require '[kin] '[kin.lang :as core] '[kin.target] '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[kin.vfs :as vfs] '[kin.project :as kp] '[clojure.string :as str])

(def I32 {:name 'I32 :types {:go "int32" :java "int" :csharp "int"}})
(def vocabulary
  {:namespace 'demo :targets #{:go :java :csharp} :tags {'I32 I32} :names {}
   :literal-tag (fn [v] (when (integer? v) I32))
   :forms (core/forms {:default-tag I32})})

(defn unit-of [ns-name]
  (let [s (str ns-name)
        base (last (str/split s #"\."))]
    {:name base :qualified (str "ex/" base)}))

(def emitters {'kin.lang/flat-array core/flat-array})
(def targets
  {:go (assoc kin.target/go :unit unit-of :data-emitters emitters)
   :java (assoc kin.target/java :data-emitters emitters)
   :csharp (assoc kin.target/csharp :data-emitters emitters)})

(def sources
  {"ident.kin"
   "(ns s.ident (:require [demo :refer [I32]] [kin.lang :refer [defdata]]))
    (defdata ^:pub tess
      :emitter kin.lang/flat-array
      :data [1 2 3 4 5]
      :accessors {^I32 tess-count [^I32 ignored]
                  ^I32 tess-at [^I32 ignored ^I32 i]})"

   ;; Only the COUNT, which inlines to a literal.
   "onlycount.kin"
   "(ns s.onlycount (:require [demo :refer [I32]] [kin.lang :refer [defn return]]
                               [s.ident :refer [tess-count]]))
    (defn ^:pub ^I32 n [] (return (tess-count 0)))"

   ;; The INDEXING accessor does name the table, so this import is needed.
   "usesat.kin"
   "(ns s.usesat (:require [demo :refer [I32]] [kin.lang :refer [defn return]]
                             [s.ident :refer [tess-at]]))
    (defn ^:pub ^I32 first-of [] (return (tess-at 0 0)))"})

(def prj
  (kp/resolve-exports
   (kp/project {:vocabularies (into [vocabulary] (kp/load-vocabulary 'kin.lang))
                :targets targets
                :target-order [:go :java :csharp]
                :sources {:vfs (vfs/memory-vfs sources) :match "*.kin"}})))

(def failures (atom 0))
(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))

(println "\ninlined: an import is for what the code references\n")

(def gen
  (into {} (for [k (keys sources)] [k (kp/generate prj (get sources k) k)])))

(let [o (:go (gen "onlycount.kin"))]
  (is "Go: the count inlines to a literal" true (str/includes? o "return 5"))
  (is "Go: and no import is written for a package never mentioned"
      false (str/includes? o "import")))
(let [o (:go (gen "usesat.kin"))]
  (is "Go: the indexing accessor does reach the package, and imports it"
      true (and (str/includes? o "import") (str/includes? o "\"ex/ident\"")
                (str/includes? o "ident.Tess["))))
(let [o (:java (gen "onlycount.kin"))]
  (is "Java: no import for an inlined count" false (str/includes? o "import ")))
(let [o (:csharp (gen "onlycount.kin"))]
  (is "C#: no using for an inlined count" false (str/includes? o "using s.")))

;; AND IT COMPILES, which is the only claim that was ever in dispute.
(if-not (zero? (:exit (p/sh {:out :string :err :string :continue true}
                            "sh" "-c" "command -v go > /dev/null")))
  (println "  SKIP go is not on PATH -- the generated Go was not compiled")
  (let [dir (str (fs/create-temp-dir {:prefix "kin-inlined"}))]
    (spit (str dir "/go.mod") "module ex\n\ngo 1.21\n")
    (doseq [[k pkg] [["ident.kin" "ident"] ["onlycount.kin" "onlycount"]
                     ["usesat.kin" "usesat"]]]
      (fs/create-dirs (str dir "/" pkg))
      (spit (str dir "/" pkg "/" pkg ".go") (:go (gen k))))
    (let [r (p/sh {:dir dir :out :string :err :string :continue true}
                  "go" "build" "./...")]
      (is "go build accepts the generated packages" 0 (:exit r))
      (when-not (zero? (:exit r)) (println (:err r))))
    (fs/delete-tree dir)))

(println (format "\ninlined: %s\n" (if (zero? @failures) "ok" (str @failures " failure(s)"))))
(when (pos? @failures) (System/exit 1))
