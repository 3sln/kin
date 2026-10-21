#!/usr/bin/env bb
(require '[kin] '[kin.lang :as core] '[kin.target] '[clojure.string :as str])
(def I32 {:name 'I32 :types {:go "int32" :rust "i32" :java "int" :csharp "int"}})
(def vocabulary {:namespace 'demo :targets #{:go :rust :java :csharp}
                 :tags {'I32 I32} :names {}
                 :literal-tag (fn [v] (when (integer? v) I32))
                 :forms (core/forms {:default-tag I32})})
(def targets {:go kin.target/go :rust kin.target/rust :java kin.target/java :csharp kin.target/csharp})
(defn render [target form]
  (let [vocabs {'demo vocabulary}
        scope (kin/require-scope '(ns probe (:require [demo :refer [panic return I32]])) vocabs)
        ctx (assoc (kin/context {} target) :vocabs vocabs :scope-syms scope
                   :vocab-order ['demo] :targets targets :locals (atom {})
                   :names (atom {}) :local-tags (atom {}) :tmp (atom 0))]
    (kin/statement! ctx form)
    (kin/output ctx)))
(def failures (atom 0))
(defn check [label yes]
  (println (str (if yes "  ok   " "  FAIL ") label))
  (when-not yes (swap! failures inc)))
(doseq [[t expected] [[:go "panic(\"profile %s {0}\")"]
                       [:rust "panic!(\"{}\", \"profile %s {0}\")"]
                       [:java "throw new IllegalArgumentException(\"profile %s {0}\")"]
                       [:csharp "throw new System.ArgumentException(\"profile %s {0}\")"]]]
  (check (str t " native refusal treats message as data")
         (str/includes? (render t '(panic "profile %s {0}")) expected))
  (doseq [form ['(panic) '(panic "a" "b") '(return (panic "a"))]]
    (check (str t " refuses " form)
           (try (render t form) false (catch clojure.lang.ExceptionInfo _ true)))))
(when (pos? @failures) (System/exit 1))
