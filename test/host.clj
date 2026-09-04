#!/usr/bin/env bb
;; Does the HOST SOURCE get to declare its own linkage, without kin reading it?
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
;; THE LINE THIS FILE EXISTS TO HOLD: kin reads the MARKER and the NAME, and
;; the payload is opaque. Everything the payload means is the target's, and
;; everything kin does with it is done with the target's ANSWER. The first
;; attempt at this namespace crossed that line -- it counted the payload's
;; binding vector to get an arity -- and the check it built was vacuous
;; because of it. Test 8 is that line, stated as a test.
;;
;; What is pinned:
;;
;;   1. an annotation is found where it sits -- kind, name, namespace, file
;;      and line -- with the comment prefix stripped from every continuation
;;      line
;;   2. trailing prose after the payload is ignored, because the reader
;;      consumes exactly one value and leaves the rest of the stream
;;   3. the comment prefix is CONFIGURATION: `;;`, `#` and `--` read the same
;;      annotations as `//`
;;   4. an unterminated payload is a NAMED error at the file and line
;;   5. an annotation before any `@kin:link:ns:` is a named error
;;   6. a MISSPELT marker is refused rather than skipped
;;   7. what `clojure.edn` does with metadata, which is why a payload says
;;      `{:type :int :name a}` and not `^:int a`
;;   8. THE PAYLOAD IS OPAQUE. kin carries a value it cannot read to the
;;      target's `:link`, with the vfs and the path, and uses only the answer
;;   9. the answer is checked against the MARKER, and an arity is a count
;;  10. two targets' ANSWERS are compared: a form one declares and the other
;;      does not, and two that state different arities. Two annotations for
;;      one symbol in one target are refused, naming both
;;  11. the agreed arity meets the CALL SITES, which is the half of the drift
;;      no cross-target comparison can reach
;;  12. what comes out is an ordinary vocabulary, and a source calling an
;;      annotated form generates the target's own text
(require '[kin] '[kin.host :as host] '[kin.vfs :as vfs] '[kin.lang :as core]
         '[kin.target] '[kin.project :as kp]
         '[clojure.edn :as edn] '[clojure.string :as str])

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
;; spread over three comment lines because a declaration does not fit on one.
(def java-file
  (str "package com.example;\n"
       "\n"
       "// @kin:link:ns: demo.rt\n"
       "public final class Vecs {\n"
       "    // @kin:link:form:vec-nth: {:kind :method :class \"Vecs\" :name \"nth\"\n"
       "    //                          :args [{:type :long :name v}\n"
       "    //                                 {:type :int :name i}]}"
       "  and this trailing prose is not part of it\n"
       "    public static long nth(Rt rt, long v, int i) { return 0; }\n"
       "\n"
       "    // @kin:link:tag:Value: {:kind :type :as \"long\"}\n"
       "}\n"))

(def java-scan (scan-of :java "//" {"Vecs.java" java-file}))

(is "1. the annotation is found, and it knows what and where it is"
    {:kind :form :sym 'vec-nth :ns 'demo.rt :path "Vecs.java" :line 5}
    (select-keys (first (:entries java-scan)) [:kind :sym :ns :path :line]))

(is "1. the payload is one value, with `//` stripped from all three lines"
    '{:kind :method :class "Vecs" :name "nth"
      :args [{:type :long :name v} {:type :int :name i}]}
    (:data (first (:entries java-scan))))

;; 2. The prose after the closing brace is on the SAME line as the end of the
;; payload, so nothing but "read one value and stop" saves it.
(is "2. trailing prose on the payload's last line is ignored"
    [[:form 'vec-nth] [:tag 'Value]]
    (mapv (juxt :kind :sym) (:entries java-scan)))

;; 3. THE PREFIX IS CONFIGURATION. The same two annotations, in four hosts
;; that spell a comment four ways, have to read identically -- that is the
;; whole of what "take the prefix as configuration rather than guessing" buys.
(def annotations
  ["@kin:link:ns: demo.rt"
   "@kin:link:form:vec-nth: {:kind :method}"])

(defn commented [prefix] (str/join "\n" (map #(str prefix " " %) annotations)))

(is "3. `;;`, `#` and `--` read exactly what `//` reads"
    1 (count (distinct (for [p ["//" ";;" "#" "--"]]
                         (:entries (scan-of :java p {"f" (commented p)}))))))

;; And a line that is NOT a comment in this host's spelling is not scanned,
;; which is the other half of the same claim.
(is "3. a `//` marker is invisible to a scan configured for `;;`"
    [] (:entries (scan-of :java ";;" {"f" (commented "//")})))

;; ------------------------------------------------------------ 4, 5 and 6

(is "4. an unterminated payload names the file and the line, and does not crash"
    (str "kin.host: Vecs.java:2: this annotation's payload is unterminated --"
         " a value was expected and the comment ran out at line 4. A payload"
         " continues onto the next line only while that line is still a `//`"
         " comment.")
    (threw #(scan-of :java "//" {"Vecs.java" (str "// @kin:link:ns: demo.rt\n"
                                                 "// @kin:link:form:vec-nth: {:kind :method\n"
                                                 "//   :args [\n"
                                                 "public static long nth() {}\n")})))

(is "5. an annotation with no `@kin:link:ns:` above it is refused, by name"
    (str "kin.host: Vecs.java:1: `vec-nth` is declared before any `//"
         " @kin:link:ns:` in this file, so there is no vocabulary to declare"
         " it into. Put one near the top of the file.")
    (threw #(scan-of :java "//" {"Vecs.java" "// @kin:link:form:vec-nth: {}\n"})))

;; 6. A MISSPELT MARKER IS THE FAILURE THIS EXISTS TO REMOVE. An annotation
;; that is silently not there behaves exactly like a host function nobody
;; annotated, and the first thing to notice is a build error in generated
;; code -- which is the original bug, reintroduced by a typo.
(def unknown-marker
  (str "` is not a marker kin knows. It reads `@kin:link:ns:`,"
       " `@kin:link:form:<name>:`, `@kin:link:tag:<name>:`. A marker kin does"
       " not recognise is refused rather than skipped, because a misspelt"
       " annotation and no annotation at all look identical from here."))

(defn scan-marker [line]
  (threw #(scan-of :java "//" {"Vecs.java" (str "// @kin:link:ns: demo.rt\n"
                                                "// " line "\n")})))

(is "6. `@kin:link:forms:` is refused rather than skipped"
    (str "kin.host: Vecs.java:2: `@kin:link:forms:vec-nth:" unknown-marker)
    (scan-marker "@kin:link:forms:vec-nth: {}"))

;; The same error covers a marker with the wrong number of segments, which is
;; one mistake seen from two sides: a form that forgot to name itself, and an
;; `ns` that named something.
(is "6. a form marker with no name is the same refusal"
    (str "kin.host: Vecs.java:2: `@kin:link:form:" unknown-marker)
    (scan-marker "@kin:link:form: {}"))

(is "6. and so is a marker with no closing colon to end it"
    (str "kin.host: Vecs.java:2: `@kin:link:form:vec-nth" unknown-marker)
    (scan-marker "@kin:link:form:vec-nth {}"))

;; -------------------------------------------------------------------- 7

;; WHY A PAYLOAD SAYS `{:type :int :name a}` AND NOT `^:int a`.
;;
;; Measured under bb: `clojure.edn/read` DOES attach the metadata -- it is
;; there on the symbol -- but metadata takes no part in `=` and `pr-str` does
;; not print it. So two declarations that mean different things compare EQUAL
;; and print identically, and a payload whose meaning lives in metadata is one
;; that every report, every diff and every test agrees is the same as the one
;; without it. The plain-EDN map form has no such half-visible channel.
(def with-meta-payload (edn/read-string "{:args [^:int a]}"))

(is "7. the metadata is read, and is invisible to `=`"
    [true '{:args [a]}] [(= with-meta-payload '{:args [a]}) with-meta-payload])

(is "7. it is there, and `pr-str` does not print it"
    [{:int true} "{:args [a]}"]
    [(meta (first (:args with-meta-payload))) (pr-str with-meta-payload)])

(is "7. so a print-and-read round trip is not identity: the meaning is gone"
    [nil true]
    (let [round (edn/read-string (pr-str with-meta-payload))]
      [(meta (first (:args round))) (= round with-meta-payload)]))

;; -------------------------------------------------------------------- 8

;; THE PAYLOAD IS OPAQUE, and this is the test that says so. The value below
;; is not a map, has no `:kind`, and means nothing whatever to kin -- and it
;; travels intact to the target's `:link`, which is handed the vfs and the
;; path as well, because the file is where the rest of the truth is.
(def seen (atom []))

(def spy-target
  {:key :spy
   :link (fn [data vfs path]
           (swap! seen conj [data path (count (str/split-lines (vfs/-read vfs path)))])
           {:link-fn (fn [ctx _] (kin/emit! ctx "spy"))})})

(let [s (scan-of :spy "//" {"a.src" (str "// @kin:link:ns: demo.rt\n"
                                         "// @kin:link:form:odd: \"a bare string is a payload\"\n"
                                         "line three\n")})]
  (host/interpret {:spy spy-target} [s])
  (is "8. kin carries a payload it cannot read, with the file it came from"
      [["a bare string is a payload" "a.src" 3]] @seen))

;; And a target with annotations and no `:link` is refused: kin ships no
;; default and cannot, because the format is the target's own invention.
(is "8. a target with annotations and no `:link` is refused by name"
    (str "kin.host: :spy has 1 host annotation(s) and no `:link` on its target"
         " description, so there is nothing to read them with. kin has no view"
         " on what an annotation's payload means -- the target that defined"
         " the format is the only thing that can interpret one.")
    (threw #(host/interpret
             {:spy {:key :spy}}
             [(scan-of :spy "//" {"a.src" (str "// @kin:link:ns: demo.rt\n"
                                               "// @kin:link:form:odd: 1\n")})])))

;; -------------------------------------------------------------------- 9

(defn answering [answer]
  (threw #(host/interpret
           {:spy {:key :spy :link (fn [_ _ _] answer)}}
           [(scan-of :spy "//" {"a.src" (str "// @kin:link:ns: demo.rt\n"
                                             "// @kin:link:form:odd: 1\n")})])))

(is "9. a form whose answer has no `:link-fn` is refused, naming the site"
    (str "kin.host: a.src:2: `odd` is annotated as a FORM, and the spy"
         " target's `:link` answered no `:link-fn` for it. A form's answer"
         " carries the function kin installs in the vocabulary's `:forms`.")
    (answering {:arity 2}))

(is "9. an `:arity` that is not a count is refused: kin owns that key"
    (str "kin.host: a.src:2: the spy target's `:link` answered an `:arity` of"
         " \"two\" for `odd`. An arity is how many arguments a call takes, so"
         " it is a count -- a target that will not state one omits the key,"
         " and nothing is checked against it.")
    (answering {:link-fn identity :arity "two"}))

(is "9. and a tag's answer has to carry this target's `:type`"
    (str "kin.host: a.src:2: `T` is annotated as a TAG, and the spy target's"
         " `:link` answered no `:type` for it. A tag's answer carries this"
         " target's spelling of the type, which is what `check-vocabulary`"
         " demands of every tag in a vocabulary that speaks it.")
    (threw #(host/interpret
             {:spy {:key :spy :link (fn [_ _ _] {:link-fn identity})}}
             [(scan-of :spy "//" {"a.src" (str "// @kin:link:ns: demo.rt\n"
                                               "// @kin:link:tag:T: 1\n")})])))

;; ------------------------------------------------------------------- 10

;; TWO TARGETS, TWO PAYLOAD FORMATS, ONE ANSWER SHAPE. Java's annotation lists
;; its arguments and Rust's states a count; nothing compares those two, and
;; nothing could. What is compared is what the two `:link`s answered.
(def java-target
  {:key :java
   :link (fn [data _ _]
           (case (:kind data)
             :type {:type (:as data)}
             :method {:arity (count (:args data))
                      :link-fn (fn [ctx form]
                                 (kin/emit! ctx (str (:class data) "." (:name data) "("
                                                     (str/join ", " (map #(kin/render ctx %) (rest form)))
                                                     ")")))}))})

(def rust-target
  {:key :rust
   :link (fn [data _ _]
           (case (:kind data)
             :type {:type (:as data)}
             :method {:arity (:takes data)
                      :link-fn (fn [ctx form]
                                 (kin/emit! ctx (str "self." (:fn data) "("
                                                     (str/join ", " (map #(kin/render ctx %) (rest form)))
                                                     ")")))}))})

(def targets {:java java-target :rust rust-target})

(defn host-file [& lines] (str/join "\n" (cons "// @kin:link:ns: demo.rt" lines)))

(defn scans [java-lines rust-lines]
  (host/interpret targets
                  [(scan-of :java "//" {"Vecs.java" (apply host-file java-lines)})
                   (scan-of :rust "//" {"vecs.rs" (apply host-file rust-lines)})]))

(def java-nth "// @kin:link:form:vec-nth: {:kind :method :class \"Vecs\" :name \"nth\" :args [{} {}]}")
(def rust-nth "// @kin:link:form:vec-nth: {:kind :method :fn \"vec_nth\" :takes 2}")

;; 10a. THE ONE THAT BITES. A host function ported to two of three runtimes,
;; with the third's annotation never written, is invisible until a kin source
;; calls it -- and then it is a compile error in generated code.
(let [ds (host/disagreements (scans [java-nth] []))]
  (is "10. a form one target declares and the other does not is reported"
      [{:issue :missing :sym 'vec-nth :declared #{:java} :missing #{:rust}}]
      (mapv #(select-keys % [:issue :sym :declared :missing]) ds))
  (is "10. and the report says where the site that HAS one is"
      {:java {:path "Vecs.java" :line 2}} (:sites (first ds))))

;; 10b. THE ARITY HALF IS NOT VACUOUS, and in the first attempt it was: that
;; version counted the payload's binding vector, which was `[ctx form]` on
;; every target, so it compared 2 with 2 forever. This compares what the
;; targets SAID about their own host functions, which is why they can differ.
(let [ds (host/disagreements
          (scans [java-nth]
                 ["// @kin:link:form:vec-nth: {:kind :method :fn \"vec_nth\" :takes 3}"]))]
  (is "10. two answers stating different arities are reported, with both"
      [{:issue :arity :sym 'vec-nth :arities {:java 2 :rust 3}}]
      (mapv #(select-keys % [:issue :sym :arities]) ds))
  (is "10. and the gate says all of it in one message"
      (str "kin.host: 1 disagreement between the targets' annotations.\n"
           "  demo.rt/vec-nth states different arities: java 2, rust 3\n"
           "      declared at java Vecs.java:2, rust vecs.rs:2")
      (threw #(host/check-agreement
               (scans [java-nth]
                      ["// @kin:link:form:vec-nth: {:kind :method :fn \"vec_nth\" :takes 3}"])))))

(is "10. a target that states no arity does not disagree with one that does"
    []
    (host/disagreements
     (scans [java-nth] ["// @kin:link:form:vec-nth: {:kind :method :fn \"vec_nth\"}"])))

(is "10. and an arity nobody agrees on is absent, not a coin toss"
    {}
    (host/arities
     (scans [java-nth]
            ["// @kin:link:form:vec-nth: {:kind :method :fn \"vec_nth\" :takes 3}"])))

(is "10. two annotations for one symbol in one target are refused, naming both"
    (str "kin.host: b.src:2: `vec-nth` is already declared for java at"
         " a.src:2. One target declares a symbol once -- with two, which one"
         " wins is the order the files happened to be listed in.")
    (threw #(host/disagreements
             (host/interpret
              targets
              [(scan-of :java "//" {"a.src" (host-file java-nth)
                                    "b.src" (host-file java-nth)})]))))

;; ------------------------------------------------------------------- 12
;;
;; (12 before 11, because 11 needs a project to have call sites in.)

(def agreed
  (scans [java-nth "// @kin:link:tag:Value: {:kind :type :as \"long\"}"]
         [rust-nth "// @kin:link:tag:Value: {:kind :type :as \"u64\"}"]))

(is "12. the two targets agree, so there is nothing to report" []
    (host/disagreements agreed))

(is "12. and the arity they agree on is what leaves the namespace"
    {['demo.rt 'vec-nth] 2} (host/arities agreed))

;; A shape vocabulary for `defn`/`return`, which the host tree does not
;; provide and should not: it describes a runtime, not a language.
(def Value {:name 'Value :types {:java "long" :rust "u64"}})

(def shape
  {:namespace 'shape :targets #{:java :rust}
   :names {} :tags {'Value Value}
   :forms (core/forms {:default-tag Value})})

(def prj
  (kp/project {:vocabularies [shape]
               :host agreed
               :targets {:rust kin.target/rust :java kin.target/java}
               :target-order [:rust :java]}))

(def scanned (get (:vocabularies prj) 'demo.rt))

(is "12. what comes out is an ordinary vocabulary, named and speaking two"
    ['demo.rt #{:java :rust}] [(:namespace scanned) (:targets scanned)])

;; A tag is assembled from the type each host file declared, and the per-target
;; halves make the one shape `check-vocabulary` already demands of a
;; hand-written vocabulary. That this project exists at all is that check
;; passing.
(is "12. a tag is assembled from the type each target answered"
    {:name 'Value :types {:java "long" :rust "u64"}}
    (get-in scanned [:tags 'Value]))

(def kin-source
  "(ns t (:require [shape :refer [defn return Value]]
                   [demo.rt :refer [vec-nth]]))
   (defn ^Value head [^Value v ^Value i] (return (vec-nth v i)))")

(let [out (kp/generate prj kin-source "t.kin")]
  (is "12. the Java target's own text is what the Java call emits"
      true (str/includes? (:java out) "return Vecs.nth(v, i);"))
  (is "12. and the Rust target's is what the Rust call emits"
      true (str/includes? (:rust out) "return self.vec_nth(v, i);")))

;; The other half of "it is an ordinary vocabulary": a target no host file
;; annotated is refused by name, at the call, exactly as a kin namespace's
;; exports are.
(is "12. a target no host file annotated is refused, naming what was"
    (str "kin.host: demo.rt/vec-nth is annotated for java rust and not for"
         " :csharp -- no host file declares it there.")
    (threw #((get-in scanned [:forms 'vec-nth]) {:target :csharp} '(vec-nth v i))))

;; And a namespace stated TWICE -- once by the host tree and once by hand -- is
;; the drift the annotations exist to remove, held in one project value.
(is "12. a namespace declared by both a host tree and by hand is refused"
    (str "kin: demo.rt is declared both by a host tree's annotations and by a"
         " hand-written vocabulary. Only one of them can be the statement of"
         " what the host exposes, and the annotations exist because the"
         " hand-written one drifted.")
    (threw #(kp/project {:vocabularies [shape {:namespace 'demo.rt
                                               :targets #{:java :rust}
                                               :tags {} :names {} :forms {}}]
                         :host agreed
                         :targets {:rust kin.target/rust :java kin.target/java}})))

;; ------------------------------------------------------------------- 11

;; THE HALF NO CROSS-TARGET COMPARISON CAN REACH. Both runtimes can agree
;; perfectly that `vec-nth` takes two arguments and a source can still call it
;; with three -- and until now the first thing to notice was the host
;; compiler, or, on the target whose call happened to still type-check,
;; nothing at all.
(def sources
  {"good.kin" kin-source
   "bad.kin"
   "(ns b (:require [shape :refer [defn return Value]]
                    [demo.rt :refer [vec-nth]]))
    (defn ^Value head [^Value v ^Value i] (return (vec-nth v i 0)))"})

(is "11. a call with the wrong argument count is reported, and the right one is not"
    [{:issue :arity :source "bad.kin" :namespace 'demo.rt :symbol 'vec-nth
      :as-written 'vec-nth :expected 2 :actual 3}]
    (mapv #(dissoc % :form) (kp/usage-problems prj sources)))

;; An ALIAS makes the declared name and the written one different, and a
;; message naming only the first sends the reader looking for a word that is
;; not in the file.
(is "11. the report names what the source called it, as well as what it is"
    [['vec-nth 'rt/vec-nth]]
    (mapv (juxt :symbol :as-written)
          (kp/usage-problems
           prj {"aliased.kin"
                "(ns a (:require [shape :refer [defn return Value]]
                                 [demo.rt :as rt]))
                 (defn ^Value head [^Value v] (return (rt/vec-nth v)))"})))

(is "11. and the gate says which source, which call and both counts"
    (str "kin: 1 call does not match what the host declares.\n"
         "  bad.kin: (vec-nth ...) takes 3 arguments, and demo.rt/vec-nth is"
         " declared to take 2")
    (threw #(kp/check-usage prj sources)))

;; ------------------------------------------------------------- the report
;;
;; `report` is Piece 2's, and it is here as well as in `test/diagnostics.clj`
;; because the two findings above are the ones only a host tree can produce:
;; a person asking "what is the state of this project" has to be shown them
;; without having to know that `kin.host` exists.
(let [prj' (assoc prj :sources {:vfs (vfs/memory-vfs sources) :match "*.kin"})
      r (kp/report prj')]
  (is "report: the host's namespaces and agreed arities are in it"
      [['demo.rt] {['demo.rt 'vec-nth] 2}]
      [(:namespaces (:host r)) (:arities (:host r))])
  (is "report: and the bad call is one of the diagnostics"
      [{:issue :usage-arity :where "bad.kin" :detail 'vec-nth}]
      (mapv #(select-keys % [:issue :where :detail])
            (filter #(= :usage-arity (:issue %)) (:diagnostics r)))))

(println)
(if (zero? @failures)
  (println "host: annotations read where they sit, interpreted by the targets, checked against each other and against the call sites\n")
  (do (println (format "host: %d FAILURE(S)\n" @failures))
      (System/exit 1)))
