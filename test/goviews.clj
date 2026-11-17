#!/usr/bin/env bb
;; A Go `defn` binds a parameter's VIEW once and reads through it.
;;
;;     bb test/goviews.clj
;;
;; Go has no loop-invariant motion through a pointer. A host form like
;; `port.CellsAt(c, i)` inlines to `c.V[i]`, and `c.V`'s header is loaded
;; again for every read because the compiler cannot prove nothing between two
;; reads moved it. A form whose `call` carries `:view` says how to spell
;; itself through a local bound once (`cV := c.V`), and `defn` binds that
;; local only for a parameter NOTHING in the body could move: every
;; occurrence is the subject of a viewed form under one `:bind`, or an
;; argument a generated callee KEEPS.
;;
;; What is pinned:
;;
;;   1. a parameter only read is viewed: one binding at the top, every read
;;      through it, a write through a view too
;;   2. a parameter also PUSHED (a form with no view) is not viewed
;;   3. a parameter REASSIGNED by `set` is not viewed
;;   4. a parameter passed to a call that does not keep it is not viewed
;;   5. a parameter passed to a generated callee that KEEPS it is viewed, and
;;      one whose only use is that argument gets no unused local
;;   6. a callee that pushes does not keep, so its caller does not view
;;   7. two views of different `:bind`s are not one view
;;   8. the view's name avoids every name the function already spells
;;   9. only the target whose template says `:view` changes: Rust is as before
;;  10. AND IT COMPILES AND RUNS: a write through a kept callee is seen by the
;;      caller's view, and a callee that grows the arena is not viewed past
(require '[kin] '[kin.lang :as core] '[kin.target] '[kin.project :as kp]
         '[clojure.string :as str]
         '[babashka.process :as p] '[babashka.fs :as fs])

(def failures (atom 0))

(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))

(defn has [label needle text]
  (if (str/includes? text needle)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         wanted   %s\n         in\n%s"
                         label (pr-str needle) text)))))

(defn lacks [label needle text]
  (if-not (str/includes? text needle)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         did not want %s\n         in\n%s"
                         label (pr-str needle) text)))))

(println "\ngoviews: a parameter nothing moves is viewed once\n")

(def I32 {:name 'I32 :types {:go "int32" :rust "i32"} :zero {:go "0"}})
(def Cells {:name 'Cells :types {:go "*port.Cells" :rust "&mut Cells"}})

(def view {:go {:bind "{0}.V" :template "{0}[{1}]"}})

(def subject
  {:namespace 'subj :targets #{:go :rust}
   :tags {'I32 I32 'Cells Cells} :names {}
   :literal-tag (fn [v] (when (integer? v) I32))
   :forms {'cells-at (core/call {:go "port.CellsAt({0}, {1})" :rust "cells_at({0}, {1})"}
                                {:tag I32 :view view})
           'cells-len (core/call {:go "port.CellsLen({0})" :rust "cells_len({0})"}
                                 {:tag I32 :view {:go {:bind "{0}.V" :template "int32(len({0}))"}}})
           'cells-set (core/call {:go "port.CellsSet({0}, {1}, {2})" :rust "cells_set({0}, {1}, {2})"}
                                 {:view {:go {:bind "{0}.V" :template "{0}[{1}] = {2}"}}})
           ;; MOVES THE VIEW: no `:view`, so a parameter pushed is never viewed.
           'cells-push (core/call {:go "port.CellsPush({0}, {1})" :rust "cells_push({0}, {1})"}
                                  {:tag I32})
           ;; A VIEW OF SOMETHING ELSE: `.W`, not `.V`.
           'cells-w (core/call {:go "port.CellsW({0})" :rust "cells_w({0})"}
                               {:tag I32 :view {:go {:bind "{0}.W" :template "{0}"}}})
           'opaque (core/call {:go "port.Opaque({0})" :rust "opaque({0})"} {:tag I32})}})

(def targets
  {:go {:key :go :ext "go" :indent-unit "\t"
        :local-name (kin.target/go-namer true)
        :fn-name (kin.target/go-namer false)}
   :rust kin.target/rust})

(def project
  (delay (kp/project {:vocabularies (into [subject] (kp/load-vocabulary 'kin.lang))
                      :targets targets :target-order [:go :rust]})))

(defn go [src] (:go (kp/generate @project src "t.kin")))
(defn rust [src] (:rust (kp/generate @project src "t.kin")))

(def head
  "(ns t (:require [subj :refer [I32 Cells cells-at cells-len cells-set cells-push cells-w opaque]]
                   [kin.lang :refer [defn return if let set while do < + -]]))\n")

(def sum-src
  "(defn ^I32 sum [^Cells c ^I32 n]
     (let [^:mut ^I32 i 0 ^:mut ^I32 s 0]
       (while (< i n) (do (set s (+ s (cells-at c i))) (set i (+ i 1))))
       (cells-set c 0 s)
       (return (+ s (cells-len c)))))\n")

;; 1
(let [out (go (str head sum-src))]
  (has "1. the view is bound once, at the top" "func sum(c *port.Cells, n int32) int32 {\n\tcV := c.V\n" out)
  (has "1. a read goes through it" "s += cV[i]" out)
  (has "1. a length does" "int32(len(cV))" out)
  (has "1. a write does" "cV[0] = s" out)
  (lacks "1. and nothing reads the header again" "port.Cells" (subs out (str/index-of out "{"))))

;; 2
(let [out (go (str head "(defn ^I32 grow [^Cells c] (cells-push c 1) (return (cells-at c 0)))"))]
  (lacks "2. a pushed parameter is not viewed" "cV" out)
  (has "2. and reads as before" "port.CellsAt(c, 0)" out))

;; 3
(let [out (go (str head "(defn ^I32 swap [^:mut ^Cells c ^Cells d] (set c d) (return (cells-at c 0)))"))]
  (lacks "3. a reassigned parameter is not viewed" "cV" out)
  (lacks "3. nor what it is assigned from" "dV" out))

;; 4
(let [out (go (str head "(defn ^I32 hand [^Cells c] (opaque c) (return (cells-at c 0)))"))]
  (lacks "4. a parameter handed to a host form is not viewed" "cV" out))

;; 5, 6
(let [out (go (str head sum-src
                   "(defn ^I32 grow [^Cells c] (cells-push c 1) (return 0))\n"
                   "(defn ^I32 both [^Cells a ^Cells b ^Cells k] (sum k 1) (return (+ (sum a 2) (cells-at a 0))))\n"
                   "(defn ^I32 pushy [^Cells a] (grow a) (return (cells-at a 0)))\n"))]
  (has "5. kept by the callee, so the caller views it" "\taV := a.V\n" out)
  (has "5. and still hands the callee the arena" "sum(a, 2)" out)
  (has "5. reads through the view" "aV[0]" out)
  (lacks "5. a parameter only handed on gets no local" "kV" out)
  (lacks "5. nor does one never used" "bV" out)
  (has "6. a callee that pushes does not keep" "func pushy(a *port.Cells) int32 {\n\tgrow(a);\n\treturn port.CellsAt(a, 0)" out))

;; 7
(let [out (go (str head "(defn ^I32 mixed [^Cells c] (return (+ (cells-w c) (cells-at c 0))))"))]
  (lacks "7. two binds of one parameter are not one view" "cV" out)
  (has "7. both read as before" "port.CellsW(c) + port.CellsAt(c, 0)" out))

;; 8
(let [out (go (str head "(defn ^I32 named [^Cells c] (let [^I32 c-v 3] (return (+ c-v (cells-at c 0)))))"))]
  (has "8. the view takes a name nothing else spells" "cVV := c.V" out)
  (has "8. and reads through it" "cVV[0]" out))

;; 9
(let [out (rust (str head sum-src))]
  (lacks "9. Rust is unchanged" "c_v" out)
  (has "9. Rust reads as before" "cells_at(c, i)" out))

;; 10 -- compiled and run
(def run-src
  (str head
       "(defn ^I32 poke [^Cells c ^I32 i ^I32 v] (cells-set c i v) (return 0))\n"
       "(defn ^I32 grow [^Cells c] (cells-push c 7) (return 0))\n"
       ;; A WRITE THROUGH A KEPT CALLEE, SEEN BY THE VIEW: one backing array.
       "(defn ^I32 ^:pub kept [^Cells c] (poke c 0 41) (return (+ (cells-at c 0) 1)))\n"
       ;; A CALLEE THAT GROWS THE ARENA: a stale view would read past the old
       ;; length and panic, or read the old array.
       "(defn ^I32 ^:pub grown [^Cells c] (grow c) (return (cells-at c (- (cells-len c) 1))))\n"))

(def port-go
  "package port\n\ntype Cells struct{ V []int32 }\n\nfunc CellsAt(c *Cells, i int32) int32 { return c.V[i] }\nfunc CellsLen(c *Cells) int32 { return int32(len(c.V)) }\nfunc CellsSet(c *Cells, i int32, v int32) { c.V[i] = v }\nfunc CellsPush(c *Cells, v int32) int32 { c.V = append(c.V, v); return int32(len(c.V) - 1) }\n")

(def main-go
  "package main\n\nimport (\n\t\"fmt\"\n\n\t\"example/port\"\n\t\"example/t\"\n)\n\nfunc main() {\n\ta := &port.Cells{V: make([]int32, 1, 1)}\n\tfmt.Println(t.Kept(a))\n\tfmt.Println(t.Grown(a))\n}\n")

(defn- go-on-path? []
  (zero? (:exit (p/sh {:out :string :err :string :continue true}
                      "sh" "-c" "command -v go > /dev/null"))))

(if-not (go-on-path?)
  (println "  SKIP go is not on PATH -- the generated Go was not compiled")
  (let [dir (str (fs/create-temp-dir {:prefix "kin-goviews"}))
        body (go run-src)]
    (fs/create-dirs (str dir "/t"))
    (fs/create-dirs (str dir "/port"))
    (spit (str dir "/go.mod") "module example\n\ngo 1.21\n")
    (spit (str dir "/port/port.go") port-go)
    (spit (str dir "/t/t.go") (str "package t\n\nimport \"example/port\"\n\n" body))
    (spit (str dir "/main.go") main-go)
    (let [r (p/sh {:dir dir :out :string :err :string :continue true} "go" "vet" "./...")]
      (is "10. go vet has nothing to say about it" 0 (:exit r))
      (when-not (zero? (:exit r)) (println (:err r))))
    (let [r (p/sh {:dir dir :out :string :err :string :continue true} "go" "run" ".")]
      (if-not (zero? (:exit r))
        (do (swap! failures inc)
            (println "  FAIL go refused or the program did not run:")
            (println (str/join "\n" (map #(str "    " %) (str/split-lines (str (:err r)))))))
        (is "10. a kept write is seen through the view, a grown arena is read fresh"
            "42\n7" (str/trim (:out r)))))
    (fs/delete-tree dir)))

(println (format "\ngoviews: %s\n" (if (zero? @failures) "ok" (str @failures " failure(s)"))))
(when (pos? @failures) (System/exit 1))
