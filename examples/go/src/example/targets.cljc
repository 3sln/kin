(ns example.targets
  "Go, described to kin.

  Nothing here is registered with kin and nothing in kin knows the word `go`.
  A target is a map; this is one."
  (:require [clojure.string :as str]
            [kin.vfs :as vfs]))

(def reserved
  #{"break" "case" "chan" "const" "continue" "default" "defer" "else"
    "fallthrough" "for" "func" "go" "goto" "if" "import" "interface" "map"
    "package" "range" "return" "select" "struct" "switch" "type" "var"})

(defn- camel [s]
  (let [[h & r] (str/split (str s) #"-")] (str h (str/join (map str/capitalize r)))))

(defn- namer
  "Go has no verbatim identifier, so a reserved word is REFUSED by name
  rather than mangled -- the same choice `kin.target` makes for Java."
  [spell]
  (fn [ctx sym]
    (let [s (spell (str sym))]
      (if-not (contains? reserved s)
        s
        (throw (ex-info (str "kin: `" s "` is a keyword in Go, which has no"
                             " escape -- rename it in the source")
                        {:name s :target (:target ctx)}))))))

(def targets
  {:go {:key :go
        :ext "go"
        :line-comment "//"
        ;; GO INDENTS WITH TABS, and `gofmt` rewrites anything else. kin
        ;; defaulted to four spaces until this example asked for something
        ;; else -- which is the useful thing a worked example does.
        :indent-unit "\t"
        :reserved reserved
        :local-name (namer camel)
        ;; An exported Go function is capitalised. That is the whole of Go's
        ;; visibility rule and it lives here, where the language does.
        :fn-name (namer (fn [s] (str/join (map str/capitalize (str/split s #"-")))))
        ;; WHERE IT WRITES, as a vfs rather than a path. kin performs no I/O
        ;; of its own; every byte goes through this. Swap it for
        ;; `(vfs/memory-vfs)` and the same emit runs with no disk at all.
        :vfs (vfs/disk-vfs "out")
        ;; Namespace -> file. Deterministic, no table: `example.gcd` becomes
        ;; `out/gcd.go`.
        :path (fn [ns-name] (str (last (str/split (str ns-name) #"\.")) ".go"))
        :indent 0}})
