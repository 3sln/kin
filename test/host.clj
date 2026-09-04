#!/usr/bin/env bb
;; Does the HOST SOURCE get to declare its own linkage?
;;
;;     bb test/host.clj
;;
;; A project used to describe its host runtime in a vocabulary file far from
;; the code it described, and it drifted -- an arity that was 2 for several
;; commits after the function became 3, an unqualified class name that bound
;; to the wrong class once a generated class shared its name. Both were silent
;; until some kin source happened to call them. So the host file declares what
;; it exposes, next to the thing it exposes, and kin reads it.
;;
;; What is pinned:
;;
;;   1. an annotation is found where it sits -- name, payload, file and line
;;      -- with the comment prefix stripped from every continuation line
;;   2. trailing prose after the payload is ignored, because the reader
;;      consumes exactly one value and leaves the rest of the stream
;;   3. the comment prefix is CONFIGURATION: `;;`, `#` and `--` read the same
;;      annotations as `//`
;;   4. an unterminated payload is a NAMED error at the file and line
;;   5. an annotation before any `@kin:ns:` is a named error
;;   6. a MISSPELT marker is refused rather than skipped
;;   7. arities are read as DATA off the binding vector -- fixed, multi and
;;      variadic -- with no evaluation and no reflection
;;   8. a form one target declares and another does not is reported, with the
;;      file and line of every site that has one
;;   9. two payloads stating different arities are reported, and two
;;      annotations for one symbol in one target are refused naming both
;;  10. THE PAYLOAD IS THE LINK FN. A scanned annotation ends up as the
;;      vocabulary's `:forms` entry and generates the call, per target,
;;      through the path a hand-written vocabulary takes.
(require '[kin] '[kin.host :as host] '[kin.vfs :as vfs] '[kin.lang :as core]
         '[kin.target] '[kin.project :as kp] '[clojure.string :as str])

(def failures (atom 0))

(defn is [label expected actual]
  (if (= expected actual)
    (println (format "  ok   %s" label))
    (do (swap! failures inc)
        (println (format "  FAIL %s\n         expected %s\n         actual   %s"
                         label (pr-str expected) (pr-str actual))))))

(defn threw
  "The message of what `f` throws, or `:kin/no-throw` if it does not.

  A test that only asserts `it threw` passes when the wrong thing throws, and
  every error this file is about is one whose whole value is what it names."
  [f]
  (try (f) :kin/no-throw
       (catch Exception e (ex-message e))))

(defn scan-of [target comment files]
  (host/scan {:vfs (vfs/memory-vfs files) :match "*" :target target
              :comment comment}))

(println "\nhost: the host source declares its own linkage\n")

;; ------------------------------------------------------------ 1, 2 and 3

;; A Java file, written the way one would be: the namespace near the top, the
;; annotation immediately above the method it describes, and the payload
;; spread over three comment lines because a link fn does not fit on one.
(def java-file
  (str "package com.example;\n"
       "\n"
       "// @kin:ns: demo.rt\n"
       "public final class Vecs {\n"
       "    // @kin:link:form: vec-nth\n"
       "    //   (fn [ctx form]\n"
       "    //     (kin/emit! ctx (str \"Vecs.nth(\"\n"
       "    //                         (kin/render ctx (second form)) \", \"\n"
       "    //                         (kin/render ctx (nth form 2)) \")\")))"
       "  and this trailing prose is not part of it\n"
       "    public static long nth(Rt rt, long v, long i) { return 0; }\n"
       "\n"
       "    // @kin:link:tag: Value \"long\"\n"
       "    // @kin:link:name: cn-base \"Node.CN_BASE\"\n"
       "}\n"))

(def java-scan (scan-of :java "//" {"Vecs.java" java-file}))

(is "1. the annotation is found, and it knows where it is"
    {:kind :form :sym 'vec-nth :ns 'demo.rt :path "Vecs.java" :line 5}
    (select-keys (first (:entries java-scan)) [:kind :sym :ns :path :line]))

(is "1. the payload is one form, with `//` stripped from all three lines"
    '(fn [ctx form]
       (kin/emit! ctx (str "Vecs.nth("
                           (kin/render ctx (second form)) ", "
                           (kin/render ctx (nth form 2)) ")")))
    (:payload (first (:entries java-scan))))

;; 2. The prose after the closing paren is on the SAME line as the end of the
;; payload, so nothing but "read one value and stop" saves it.
(is "2. trailing prose on the payload's last line is ignored"
    1 (count (filter #(= :form (:kind %)) (:entries java-scan))))

(is "1. and the tag and the name are found too"
    [[:form 'vec-nth] [:tag 'Value] [:name 'cn-base]]
    (mapv (juxt :kind :sym) (:entries java-scan)))

;; 3. THE PREFIX IS CONFIGURATION. The same two annotations, in three hosts
;; that spell a comment three ways, have to read identically -- that is the
;; whole of what "take the prefix as configuration rather than guessing" buys.
(def annotations
  ["@kin:ns: demo.rt"
   "@kin:link:form: vec-nth"
   "  (fn [ctx form] (kin/emit! ctx \"x\"))"])

(defn commented [prefix] (str/join "\n" (map #(str prefix " " %) annotations)))

(is "3. `;;`, `#` and `--` read exactly what `//` reads"
    1 (count (distinct (for [p ["//" ";;" "#" "--"]]
                         (mapv #(dissoc % :path)
                               (:entries (scan-of :java p {"f" (commented p)})))))))

;; And a line that is NOT a comment in this host's spelling is not scanned,
;; which is the other half of the same claim.
(is "3. a `//` marker is invisible to a scan configured for `;;`"
    [] (:entries (scan-of :java ";;" {"f" (commented "//")})))

;; ------------------------------------------------------------ 4, 5 and 6

(is "4. an unterminated payload names the file and the line, and does not crash"
    (str "kin.host: Vecs.java:2: this annotation's payload is unterminated"
         " -- 2 values were expected and the comment ran out at line 4. A"
         " payload continues onto the next line only while that line is still"
         " a `//` comment.")
    (threw #(scan-of :java "//" {"Vecs.java" (str "// @kin:ns: demo.rt\n"
                                                 "// @kin:link:form: vec-nth\n"
                                                 "//   (fn [ctx form]\n"
                                                 "public static long nth() {}\n")})))

(is "5. an annotation with no `@kin:ns:` above it is refused, by name"
    (str "kin.host: Vecs.java:1: `vec-nth` is declared before any `//"
         " @kin:ns:` in this file, so there is no vocabulary to declare it"
         " into. Put one near the top of the file.")
    (threw #(scan-of :java "//" {"Vecs.java" "// @kin:link:form: vec-nth (fn [c f])\n"})))

;; 6. A MISSPELT MARKER IS THE FAILURE THIS EXISTS TO REMOVE. An annotation
;; that is silently not there behaves exactly like a host function nobody
;; annotated, and the first thing to notice is a build error in generated
;; code -- which is the original bug, reintroduced by a typo.
(is "6. `@kin:link:forms:` is refused rather than skipped"
    (str "kin.host: Vecs.java:2: `@kin:link:forms:` is not a marker kin knows."
         " It reads `@kin:link:form:`, `@kin:link:name:`, `@kin:link:tag:`,"
         " `@kin:ns:`. A marker kin does not recognise is refused rather than"
         " skipped, because a misspelt annotation and no annotation at all"
         " look identical from here.")
    (threw #(scan-of :java "//" {"Vecs.java" (str "// @kin:ns: demo.rt\n"
                                                 "// @kin:link:forms: vec-nth (fn [c f])\n")})))

;; ------------------------------------------------------------------- 7

;; NO EVALUATION AND NO REFLECTION. The binding vector is right there in the
;; read form, so an arity is a count.
(is "7. a fixed arity is the length of the binding vector"
    #{3} (host/arities '(fn [ctx a b] 1)))
(is "7. a multi-arity fn states every one of them"
    #{1 2} (host/arities '(fn ([a] 1) ([a b] 2))))
(is "7. the optional self-name is not a parameter"
    #{2} (host/arities '(fn nth [ctx form] 1)))
(is "7. a variadic arity is n-or-more, and not the same fact as n"
    #{[2 '&]} (host/arities '(fn [ctx form & more] 1)))
(is "7. a payload that is a CALL states no arity, and nil is the honest answer"
    nil (host/arities '(kin.lang/call {:java "Vecs.nth({0})"})))

;; ---------------------------------------------------------------- 8 and 9

(defn annotate [target sym payload]
  (scan-of target "//"
           {(str (name target) ".src")
            (str "// @kin:ns: demo.rt\n"
                 "// @kin:link:form: " sym " " payload "\n")}))

;; 8. THE ONE THAT BITES. A host function ported to two of three runtimes,
;; with the third's annotation never written, is invisible until a kin source
;; calls it -- and then it is a compile error in generated code.
(let [ds (host/disagreements [(annotate :java 'vec-nth "(fn [ctx form] 1)")
                              (annotate :rust 'vec-nth "(fn [ctx form] 1)")
                              (annotate :csharp 'other "(fn [ctx form] 1)")])]
  (is "8. a form two targets declare and a third does not is reported"
      [{:issue :missing :sym 'other :declared #{:csharp} :missing #{:java :rust}}
       {:issue :missing :sym 'vec-nth :declared #{:java :rust} :missing #{:csharp}}]
      (mapv #(select-keys % [:issue :sym :declared :missing]) ds))
  (is "8. and the report says where each site that HAS one is"
      {:java {:path "java.src" :line 2} :rust {:path "rust.src" :line 2}}
      (:sites (second ds))))

;; 9. Compared from the READ FORMS. Nothing here is evaluated and nothing is
;; invoked, which is what lets the check run over a tree that does not build.
(let [ds (host/disagreements [(annotate :java 'vec-nth "(fn [ctx a b] 1)")
                              (annotate :rust 'vec-nth "(fn [ctx a] 1)")])]
  (is "9. two payloads stating different arities are reported, with both"
      [{:issue :arity :sym 'vec-nth :arities {:java #{3} :rust #{2}}}]
      (mapv #(select-keys % [:issue :sym :arities]) ds)))

(is "8. two annotations for one symbol in one target are refused, naming both"
    (str "kin.host: b.src:2: `vec-nth` is already declared for java at"
         " a.src:2. One target declares a symbol once -- with two, which one"
         " wins is the order the files happened to be listed in.")
    (threw #(host/disagreements
             [(scan-of :java "//"
                       {"a.src" "// @kin:ns: demo.rt\n// @kin:link:form: vec-nth (fn [c f])\n"
                        "b.src" "// @kin:ns: demo.rt\n// @kin:link:form: vec-nth (fn [c f])\n"})])))

(is "9. a payload stating no arity does not disagree with one that does"
    [] (host/disagreements [(annotate :java 'vec-nth "(fn [ctx a b] 1)")
                            (annotate :rust 'vec-nth "(kin.lang/call {:rust \"x\"})")]))

;; `check-agreement` is the GATE, and it says all of it in one message.
(is "8. the gate names the symbol, both target sets and every site"
    (str "kin.host: 1 disagreement between the targets' annotations.\n"
         "  demo.rt/vec-nth (form) is declared by java and not by rust\n"
         "      declared at java java.src:2")
    (threw #(host/check-agreement [(annotate :java 'vec-nth "(fn [ctx form] 1)")
                                   {:target :rust :entries []}])))

;; ------------------------------------------------------------------- 10

;; THE LOAD-BEARING ONE. The payload is not a template and not a description
;; of a link fn -- it IS the link fn, and it lands in the slot a hand-written
;; vocabulary's would occupy. So the proof is an ordinary generate: a source
;; requires the scanned namespace, calls the annotated form, and the text the
;; annotation itself emits comes out, once per target.
(defn payload [call]
  (str "(fn [ctx form]"
       "  (kin/emit! ctx (str \"" call "(\""
       "                      (kin/render ctx (second form)) \", \""
       "                      (kin/render ctx (nth form 2)) \")\")))"))

(def host-scans
  [(scan-of :java "//"
            {"Vecs.java" (str "// @kin:ns: demo.rt\n"
                              "// @kin:link:form: vec-nth " (payload "Vecs.nth") "\n"
                              "// @kin:link:tag: Value \"long\"\n")})
   (scan-of :rust "//"
            {"vecs.rs" (str "// @kin:ns: demo.rt\n"
                            "// @kin:link:form: vec-nth " (payload "self.vec_nth") "\n"
                            "// @kin:link:tag: Value \"u64\"\n")})])

(is "10. the two targets agree, so there is nothing to report"
    [] (host/disagreements host-scans))

(def scanned (get (host/vocabularies host-scans) 'demo.rt))

(is "10. what comes out is an ordinary vocabulary, named and speaking two"
    ['demo.rt #{:java :rust}] [(:namespace scanned) (:targets scanned)])

;; A tag's payload is THIS target's type, and the per-target halves assemble
;; into the one shape `check-vocabulary` already demands of a hand-written
;; vocabulary. That the vocabulary above exists at all is that check passing.
(is "10. a tag is assembled from the type each host file declared"
    {:name 'Value :types {:java "long" :rust "u64"}}
    (get-in scanned [:tags 'Value]))

;; A shape vocabulary for `defn`/`return`, which the scanned one does not
;; provide and should not: it describes a host runtime, not a language.
(def shape
  {:namespace 'shape :targets #{:java :rust}
   :names {} :tags {'Value {:name 'Value :types {:java "long" :rust "u64"}}}
   :forms (core/forms {:default-tag {:name 'Value :types {:java "long" :rust "u64"}}})})

(def prj (kp/project {:vocabularies [shape scanned]
                      :targets {:rust kin.target/rust :java kin.target/java}
                      :target-order [:rust :java]}))

(def kin-source
  "(ns t (:require [shape :refer [defn return Value]]
                   [demo.rt :refer [vec-nth]]))
   (defn ^Value head [^Value v ^Value i] (return (vec-nth v i)))")

(let [out (kp/generate prj kin-source "t.kin")]
  (is "10. the Java annotation's own text is what the Java call emits"
      true (str/includes? (:java out) "return Vecs.nth(v, i);"))
  (is "10. and the Rust annotation's is what the Rust call emits"
      true (str/includes? (:rust out) "return self.vec_nth(v, i);")))

;; The other half of "it is an ordinary vocabulary": a target it was not
;; annotated for is refused by name, at the call, exactly as a kin namespace's
;; exports are.
(is "10. a target no host file annotated is refused, naming what was"
    "kin.host: demo.rt/vec-nth is annotated for java rust and not for :csharp -- no host file declares it there."
    (threw #((get-in scanned [:forms 'vec-nth]) {:target :csharp} '(vec-nth v i))))

(println)
(if (zero? @failures)
  (println "host: annotations read where they sit, checked across targets, linked as written\n")
  (do (println (format "host: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
