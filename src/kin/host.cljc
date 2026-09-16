(ns kin.host
  "The HOST SOURCE declares what it exposes, next to the thing it exposes.

  Until now a project described its host runtime in a separate vocabulary
  file: a table of entries saying `this kin name calls that method, spelled
  thus per target`. The table is FAR from the code it describes, so it drifts,
  and it drifts silently -- an entry is only exercised once some kin source
  happens to call it, which may be several commits after the host function
  changed underneath it. Two measured instances in the one project using kin:

    * an entry declared arity 2 for several commits after the host function
      grew a default argument and became 3. Nothing broke, because no kin
      source had called it yet. The first one that did would have generated a
      call that does not compile.
    * an entry emitted an unqualified `Seqs.seq(...)`, which binds to the
      wrong class once a GENERATED class named `Seqs` exists in the same
      package. It stayed latent until a generated module first called it.

  Neither is a bug a checker could have found, because there was nothing to
  check against: the table was the only statement of the fact. Moving the
  statement INTO the host file next to the function makes the two change
  together, and makes a second statement of the same fact -- the other
  target's file -- something kin can compare it against.

  ## What an annotation looks like

  Comment syntax is the host language's; the marker is the same in all of
  them:

      // @kin:link:ns: com._3sln.flint.kgen

      // @kin:link:form:vec-nth: {:kind :method
      //                          :args [{:type :int :name a}
      //                                 {:type :int :name b}]}
      public static long nth(Rt rt, long v, int i, long dflt) { ... }

  `@kin:link:ns:` says which kin vocabulary the annotations below it
  contribute to, and applies from where it appears onward -- so one host file
  may contribute to two, and an annotation before any `@kin:link:ns:` is an
  error rather than a guess.

  ## WHAT KIN KNOWS, AND WHAT IT MUST NOT

  kin knows the MARKER -- that this is a `form` or a `tag`, and its NAME --
  and the namespace. Those three are kin's business because they correspond
  exactly to a vocabulary's own structure: `:forms`, `:tags`, and the symbols
  those are keyed by. Reading them is reading kin's own shape.

  **kin knows NOTHING ABOUT THE PAYLOAD.** The value after the marker is
  opaque EDN. kin does not know what `:kind` or `:args` mean, does not
  validate them, does not count them, and never reads a key out of one. A
  `(:kind data)` anywhere in this namespace is the mistake this file is the
  second attempt at not making.

  ## The target interprets; kin consumes only the ANSWER

  A TARGET carries a `:link`:

      (fn [link-data vfs file-path]
        -> {:link-fn <a form link function, the kind kin already installs>
            :arity   <how many arguments a CALL takes>
            ...whatever else that target can usefully report})

  It is handed the vfs and the path as well as the data, because the file is
  where the rest of the truth is -- a target that wants to read the signature
  under its own annotation can, and kin neither helps nor objects.

  That RETURNED METADATA -- never the payload -- is what the checks are built
  from. `disagreements` compares the targets' answers against each other;
  `arities` is what a usage check in `kin.project` compares a call site
  against. So the two things kin can say about a host declaration -- every
  target declares it, and they agree about how many arguments it takes -- are
  said in kin's own vocabulary, about data kin defined the meaning of.

  NOTHING IS EVALUATED. The payload is data and the target is ordinary
  project code, already loaded, already a function. There is no interpreter
  here, no SCI, and no `eval` seam -- the first attempt at this carried one
  and it was the other half of what was wrong with it.

  ## The pieces, and they are different in kind

      scan          TEXT IN, DATA OUT. Finds the annotations. Calls nothing
                    and interprets nothing.
      interpret     runs each target's `:link` over its own entries and
                    attaches the answer. This is the only place a target's
                    code is called.
      disagreements what the targets' ANSWERS do not agree about, as data.
      arities       `{[ns sym] n}` -- what a call site can be checked against.
      vocabularies  the answers, as ordinary kin vocabularies."
  (:require [kin]
            [kin.vfs :as vfs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ------------------------------------------------------------------ errors

(defn- fail
  "Throw naming the FILE and the LINE.

  Every error in this namespace goes through here, because an annotation is
  read out of a file kin does not own and cannot re-print: `an unterminated
  form` with no location sends the reader to find it by hand, in a tree that
  may be several thousand host files."
  [path line msg data]
  (throw (ex-info (str "kin.host: " path ":" line ": " msg)
                  (assoc data :path path :line line))))

;; ----------------------------------------------------------------- markers
;;
;; THE NAME IS IN THE MARKER, and that is what keeps kin out of the payload.
;; `@kin:link:form:vec-nth:` says the two things kin is allowed to know -- a
;; form, called `vec-nth` -- in the one place kin is allowed to read. Were the
;; name a value inside the payload instead, kin would have to reach into the
;; payload to find it, and the line this namespace exists to hold would be
;; crossed by its first act.

(def ^:private marker "@kin:link:")

(def ^:private shapes
  "The three markers, spelled as a reader will see them in a message."
  ["@kin:link:ns:" "@kin:link:form:<name>:" "@kin:link:tag:<name>:"
   "@kin:link:name:<name>:"])

(defn- refuse-marker
  "A marker kin does not recognise is REFUSED, never skipped.

  That is the failure this whole namespace exists to remove, one level along:
  an annotation that is silently not there behaves exactly like a host
  function nobody annotated, and the first thing to notice is a build error
  in generated code -- which is the original bug, reintroduced by a typo. So
  `@kin:link:` appearing in a comment commits the line to being an
  annotation, and anything after it that is not one of three shapes is an
  error."
  [path line run]
  (fail path line
        (str "`" marker run "` is not a marker kin knows. It reads "
             (str/join ", " (map #(str "`" % "`") shapes))
             ". A marker kin does not recognise is refused rather than"
             " skipped, because a misspelt annotation and no annotation at"
             " all look identical from here.")
        {:marker (str marker run) :known shapes}))

(defn- parse-marker
  "The marker beginning at `run`, as `[kind name]`, or nil if it is not one.

  `run` is the non-whitespace text after `@kin:link:`, which has to end in a
  colon: the colon is what separates the marker from the payload, and without
  it `@kin:link:form:vec-nth {:kind :method}` would have kin guessing where
  one ends and the other begins."
  [run]
  (when (str/ends-with? run ":")
    (let [segs (str/split (subs run 0 (dec (count run))) #":" -1)]
      (when (every? seq segs)
        (case (count segs)
          1 (when (= "ns" (first segs)) [:ns nil])
          2 (case (first segs)
              "form" [:form (symbol (second segs))]
              "tag" [:tag (symbol (second segs))]
              "name" [:name (symbol (second segs))]
              nil)
          nil)))))

;; ----------------------------------------------------------------- reading

(defn- comment-body
  "The text of `line` after its comment prefix, or nil if it is not a comment.

  The prefix is CONFIGURATION -- `//`, `;;`, `#`, `--` -- rather than
  something guessed from the file extension. A scanner that guessed would be
  wrong about exactly the host somebody adds next, and the caller already
  knows: it chose the glob."
  [prefix line]
  (let [t (str/triml line)]
    (when (str/starts-with? t prefix) (subs t (count prefix)))))

(defn- reader
  "A stream over `s` that a reader can take one value off at a time.

  HOST-SPECIFIC, and the only thing in this namespace that is. `kin.vfs`
  draws the same line for the same reason: reading a host tree is not
  something every host kin runs on can do, and a namespace that pretended
  otherwise would fail somewhere less obvious than here."
  [s]
  #?(:clj (java.io.PushbackReader. (java.io.StringReader. s))
     :default (throw (ex-info (str "kin.host: this host has no incremental"
                                   " reader, so an annotation's payload cannot"
                                   " be read one value at a time.")
                              {}))))

(defn- read-one
  "The first EDN value in `s`, or `::more` if `s` does not hold a whole one.

  `clojure.edn/read` on a `PushbackReader` consumes exactly ONE value and
  leaves the rest of the stream, which is what a multi-line payload needs and
  is why it needs no new reader. Re-measured under bb: reading
  `{:kind :method} and trailing prose` answers `{:kind :method}`, and the
  next read off the same stream answers `and`. So prose after the payload on
  its last line is ignored rather than being a syntax error.

  THE TWO WAYS TO RUN OUT ARE DIFFERENT and are told apart here. `:eof`
  answers the sentinel when there is nothing left at all; an INCOMPLETE value
  -- `{:kind :method` -- throws `EOF while reading` instead. Both mean `feed
  me another line`. Anything else is a genuine syntax error in the payload
  and is re-thrown, so a stray `}` is reported as `Unmatched delimiter` where
  it is, rather than swallowing the rest of the file looking for a close."
  [s]
  (let [v (try (edn/read {:readers {} :eof ::more} (reader s))
               (catch #?(:clj Exception :default :default) e
                 (if (str/includes? (or (ex-message e) "") "EOF while reading")
                   ::more
                   (throw e))))]
    v))

(defn- read-payload
  "Read one value starting on `lines[i]` after `tail`, over as many
  consecutive COMMENT lines as it takes. Answers `[value next-i]`.

  A multi-line payload sits inside consecutive comment lines, so the comment
  prefix has to come off every continuation line before the reader sees it --
  otherwise the `//` of the second line lands in the middle of the map. Lines
  are joined with a newline rather than a space so that a string spanning two
  lines keeps the newline the author wrote.

  Running out of comment lines is a NAMED error pointing at the marker, not a
  crash and not a silent skip: an unterminated form is a thing a person typed
  and has to find."
  [prefix lines i tail path line-no]
  (loop [buf tail j i]
    (let [v (read-one buf)]
      (if (not= ::more v)
        [v (inc j)]
        (let [next-body (when (< (inc j) (count lines))
                          (comment-body prefix (nth lines (inc j))))]
          (when-not next-body
            (fail path line-no
                  (str "this annotation's payload is unterminated -- a value"
                       " was expected and the comment ran out at line "
                       (inc (inc j)) ". A payload continues onto the next"
                       " line only while that line is still a `" prefix
                       "` comment.")
                  {:read buf}))
          (recur (str buf "\n" next-body) (inc j)))))))

;; -------------------------------------------------------------------- scan

(defn scan-text
  "Every annotation in one host file, as data. Interprets nothing.

      {:kind :form :sym 'vec-nth :ns 'demo.rt
       :data {:kind :method :args [...]}
       :path \"Vecs.java\" :line 5}

  `:data` is the payload EXACTLY as it was read, and it is the only thing
  here kin has no opinion about. It is carried to the target's `:link` and
  nowhere else."
  [{:keys [comment] :or {comment "//"}} path text]
  (let [lines (str/split text #"\n" -1)]
    (loop [i 0 nsym nil out []]
      (if (>= i (count lines))
        out
        (let [line-no (inc i)
              body (comment-body comment (nth lines i))
              idx (when body (str/index-of body marker))]
          (if-not idx
            (recur (inc i) nsym out)
            (let [after (subs body (+ idx (count marker)))
                  run (first (str/split after #"\s" 2))
                  [kind sym] (or (parse-marker run)
                                 (refuse-marker path line-no run))
                  tail (subs after (count run))
                  [value next-i] (read-payload comment lines i tail path line-no)]
              (if (= :ns kind)
                (do (when-not (symbol? value)
                      (fail path line-no
                            (str "`@kin:link:ns:` names a namespace, which is"
                                 " a symbol -- this one reads "
                                 (pr-str value) ".")
                            {:read value}))
                    (recur next-i value out))
                (do (when-not nsym
                      (fail path line-no
                            (str "`" sym "` is declared before any `" comment
                                 " @kin:link:ns:` in this file, so there is no"
                                 " vocabulary to declare it into. Put one near"
                                 " the top of the file.")
                            {:symbol sym :kind kind}))
                    (recur next-i nsym
                           (conj out {:kind kind :sym sym :ns nsym :data value
                                      :path path :line line-no})))))))))))

(defn scan
  "Every annotation one target's host tree declares.

      (scan {:vfs (vfs/disk-vfs \"runtime/java\") :match \"*.java\"
             :target :java :comment \"//\"})

  Answers `{:target :java :vfs <the vfs> :entries [...]}`, with the entries in
  file and line order, because a listing's order is the filesystem's and a
  report that reorders between two runs of an unchanged tree is much harder to
  read than one that does not -- the same rule `kin.vfs/listing` follows and
  for the same reason.

  THE VFS IS KEPT, because `interpret` hands it to the target: the annotation
  says what the target needs beyond what the file already says, so a target
  that wants to read the declaration under its own comment has to be able to.

  WHICH TARGET A FILE SPEAKS IS CONFIGURATION. The Java tree declares Java's
  linkage and the Rust tree Rust's, and the caller is already scanning one
  language's tree with one language's comment prefix -- a `@kin:target:`
  marker in the file would be a second place for the same fact to be wrong.

  The vfs is the SOURCE kind: it lists and reads, and this never writes. That
  is also what makes the scanner testable without a host tree -- a
  `memory-vfs` holding two strings is a runtime as far as this is concerned."
  [{:keys [vfs match target] :as opts}]
  (when-not target
    (throw (ex-info (str "kin.host: a scan is of ONE target's tree -- the Java"
                         " files declare Java's linkage and the Rust files"
                         " Rust's -- so `scan` needs a `:target` to say which"
                         " it is reading.")
                    {:opts (dissoc opts :vfs)})))
  {:target target
   :vfs vfs
   :entries (into []
                  (mapcat (fn [p] (scan-text opts p (vfs/-read vfs p))))
                  (vfs/listing vfs match))})

;; --------------------------------------------------------------- interpret
;;
;; THE ONE PLACE A TARGET'S CODE IS CALLED. Everything above this line is text
;; handling and everything below it reads an ANSWER; if a payload is ever
;; consulted anywhere but here, the boundary has leaked.

(defn- check-answer
  "The target's answer for one entry, checked against the MARKER.

  kin checks the shape of the answer and nothing about the data that produced
  it. A form has to come back with a `:link-fn`, because that is the slot a
  vocabulary has for it; a tag with a `:type`, because that is the slot a tag
  has. An `:arity` is optional and, when given, is a count -- kin defined that
  key and so may say what may be in it.

  A target learns which of the two it is being asked about from ITS OWN
  payload, not from kin: the signature is `(data vfs path)` and the data is
  the target's own format, which is where a `:kind` -- if it wants one --
  belongs. What kin does is check that the answer fits the marker it was
  asked under, so a target that mixed the two up is told where."
  [target {:keys [kind sym path line]} answer]
  (when-not (map? answer)
    (fail path line
          (str "the " (name target) " target's `:link` answered "
               (pr-str answer) " for `" sym "`. It answers a MAP -- at least "
               (case kind :form "a `:link-fn`" :tag "a `:type`" "a `:spelling`")
               " -- because kin reads the answer and never the annotation.")
          {:target target :symbol sym :answer answer}))
  (case kind
    :form (when-not (fn? (:link-fn answer))
            (fail path line
                  (str "`" sym "` is annotated as a FORM, and the "
                       (name target) " target's `:link` answered no"
                       " `:link-fn` for it. A form's answer carries the"
                       " function kin installs in the vocabulary's `:forms`.")
                  {:target target :symbol sym :answered (vec (sort (keys answer)))}))
    :name (when-not (string? (:spelling answer))
            (fail path line
                  (str "`" sym "` is annotated as a NAME, and the "
                       (name target) " target's `:link` answered no"
                       " `:spelling` string for it. A name's answer carries"
                       " how THIS target writes it, because that is the whole"
                       " of what a name is: `spell-name` takes a map of"
                       " per-target strings, and a host tree contributes one"
                       " target's entry in it.")
                  {:target target :symbol sym :answered (vec (sort (keys answer)))}))
    :tag (when-not (:type answer)
           (fail path line
                 (str "`" sym "` is annotated as a TAG, and the "
                      (name target) " target's `:link` answered no `:type`"
                      " for it. A tag's answer carries this target's spelling"
                      " of the type, which is what `check-vocabulary` demands"
                      " of every tag in a vocabulary that speaks it.")
                 {:target target :symbol sym :answered (vec (sort (keys answer)))})))
  (when-let [a (:arity answer)]
    (when-not (and (int? a) (not (neg? a)))
      (fail path line
            (str "the " (name target) " target's `:link` answered an `:arity`"
                 " of " (pr-str a) " for `" sym "`. An arity is how many"
                 " arguments a call takes, so it is a count -- a target that"
                 " will not state one omits the key, and nothing is checked"
                 " against it.")
            {:target target :symbol sym :arity a})))
  answer)

(defn interpret
  "Run each target's `:link` over its own scan, attaching the answer as
  `:link` on every entry.

  `targets` is a project's target map, `{:java {...} :rust {...}}`. The
  target's `:link` is what turns an opaque payload into something kin can use,
  and there is no default: kin ships none and cannot, because the payload's
  format is the target's own invention. A target with annotations and no
  `:link` is refused by name rather than quietly contributing nothing; a
  target whose tree holds none needs none, because there is nothing to read."
  [targets scans]
  (mapv
   (fn [{:keys [target vfs entries] :as scan}]
     (let [link (or (:link (get targets target))
                    (when (empty? entries) (constantly nil))
                    (throw (ex-info
                            (str "kin.host: " target " has " (count entries)
                                 " host annotation(s) and no `:link` on its"
                                 " target description, so there is nothing to"
                                 " read them with. kin has no view on what an"
                                 " annotation's payload means -- the target"
                                 " that defined the format is the only thing"
                                 " that can interpret one.")
                            {:target target :entries (count entries)})))]
       (assoc scan :entries
              (mapv (fn [e]
                      (assoc e :link
                             (check-answer target e (link (:data e) vfs (:path e)))))
                    entries))))
   scans))

;; ---------------------------------------------------------- the agreement
;;
;; ONE ANNOTATION PER TARGET is what makes this check possible and is also
;; what makes it necessary. Three files state the same fact three ways, and
;; nothing but a comparison holds them together.

(defn- by-key
  "Every entry across every scan, as `{[ns kind sym] {target entry}}`.

  TWO ANNOTATIONS FOR ONE SYMBOL IN ONE TARGET ARE REFUSED. The obvious
  implementation lets the second overwrite the first, and that is the same
  silence this whole namespace exists to remove one level along: two host
  files both claiming `vec-nth` for Java is somebody having moved a function
  and annotated it in its new home without deleting the old annotation, and
  the loser is chosen by the vfs listing order. Both sites are named, because
  which one is stale is the reader's question and not kin's."
  [scans]
  (reduce (fn [m {:keys [target entries]}]
            (reduce (fn [m e]
                      (let [k [(:ns e) (:kind e) (:sym e)]]
                        (when-let [prior (get-in m [k target])]
                          (fail (:path e) (:line e)
                                (str "`" (:sym e) "` is already declared for "
                                     (name target) " at " (:path prior) ":"
                                     (:line prior) ". One target declares a"
                                     " symbol once -- with two, which one wins"
                                     " is the order the files happened to be"
                                     " listed in.")
                                {:symbol (:sym e) :kind (:kind e) :namespace (:ns e)
                                 :target target :prior (select-keys prior [:path :line])}))
                        (assoc-in m [k target] e)))
                    m entries))
          {} scans))

(defn disagreements
  "What the targets' ANSWERS do not agree about, as data. `scans` are
  interpreted ones.

  Two findings, and they catch different things:

    `:missing`  a symbol some targets declare and others do not. This is the
                one that bites in practice: a host function ported to two of
                three runtimes, with the third's annotation never written, is
                invisible until a kin source calls it.
    `:arity`    two targets whose answers both state an arity, and state
                different ones.

  THE ARITY HALF IS NOT VACUOUS, and the first attempt at this namespace made
  it so. That version counted the binding vector of the payload, which was a
  link fn -- `(fn [ctx form] ...)` on every target, so every target's arity
  was 2 and the check compared a constant with itself. Here an arity is what
  the TARGET says a call to the host function takes, computed from the host
  file it is reading, so two targets whose functions have drifted apart say
  different numbers.

  Compared from ANSWERS, never from payloads. Two targets' payloads are two
  different formats and comparing them would be comparing a Java annotation
  with a Rust one as if the words meant the same thing.

  Returns a vector, sorted, so two runs over an unchanged tree compare."
  [scans]
  (let [targets (into (sorted-set) (map :target) scans)]
    (vec
     (sort-by
      (juxt (comp str :ns) (comp name :kind) (comp str :sym) (comp name :issue))
      (mapcat
       (fn [[[nsym kind sym] by-target]]
         (let [declared (set (keys by-target))
               where (fn [t] (select-keys (get by-target t) [:path :line]))]
           (concat
            (when (not= declared targets)
              [{:issue :missing :ns nsym :kind kind :sym sym
                :declared (into (sorted-set) declared)
                :missing (into (sorted-set) (remove declared targets))
                :sites (into (sorted-map) (map (fn [t] [t (where t)])) declared)}])
            ;; Only between answers that BOTH state an arity: a target that
            ;; omits the key is saying it will not state one, and `nil` is not
            ;; a disagreement with 2.
            (let [stated (into (sorted-map)
                               (keep (fn [[t e]] (when-let [a (:arity (:link e))] [t a])))
                               by-target)]
              (when (< 1 (count (set (vals stated))))
                [{:issue :arity :ns nsym :kind kind :sym sym
                  :arities stated
                  :sites (into (sorted-map) (map (fn [t] [t (where t)])) (keys stated))}])))))
       (by-key scans))))))

(defn explain
  "One disagreement as a line of text, for a message a person reads."
  [d]
  (let [sites (str/join ", " (for [[t w] (:sites d)]
                               (str (name t) " " (:path w) ":" (:line w))))]
    (case (:issue d)
      :missing (str (:ns d) "/" (:sym d) " (" (name (:kind d)) ") is declared by "
                    (str/join " " (map name (:declared d)))
                    " and not by " (str/join " " (map name (:missing d)))
                    "\n      declared at " sites)
      :arity (str (:ns d) "/" (:sym d) " states different arities: "
                  (str/join ", " (for [[t a] (:arities d)] (str (name t) " " a)))
                  "\n      declared at " sites))))

(defn check-agreement
  "Answer `scans` if every target declares the same linkage; throw saying what
  differs if they do not.

  Throws rather than returning data because it is a GATE -- the thing a
  project's build calls -- and `disagreements` is right there for a caller
  that wants to decide for itself. `kin/check-vocabulary` is the same pair of
  shapes one level down, and for the same reason."
  [scans]
  (let [ds (disagreements scans)]
    (when (seq ds)
      (throw (ex-info
              (str "kin.host: " (count ds)
                   (if (= 1 (count ds)) " disagreement" " disagreements")
                   " between the targets' annotations.\n"
                   (str/join "\n" (map #(str "  " (explain %)) ds)))
              {:disagreements ds})))
    scans))

;; ------------------------------------------------------------- the arities

(defn arities
  "`{[ns sym] n}` for every annotated FORM the targets agree the arity of.

  This is the metadata leaving the namespace, and it is the whole point of
  the target hook: a number kin obtained without knowing what a Java
  annotation says, which a call site in a kin source can be checked against.
  See `kin.project/usage-problems`.

  A form no target stated an arity for is ABSENT rather than nil, and a form
  the targets disagree about is absent too -- `disagreements` is where a
  disagreement is reported, and a usage check that picked a winner from among
  two contradictory declarations would be checking against a coin toss."
  [scans]
  (into {}
        (keep (fn [[[nsym kind sym] by-target]]
                (when (= :form kind)
                  (let [stated (set (keep (comp :arity :link val) by-target))]
                    (when (= 1 (count stated))
                      [[nsym sym] (first stated)])))))
        (by-key scans)))

;; ------------------------------------------------------- as a vocabulary

(defn vocabularies
  "The interpreted scans, as ordinary kin vocabularies: `{ns-sym [vocabulary ...]}`.

  This is where the namespace stops being about annotations: what comes out is
  a value a project puts in its `:vocabularies` beside a hand-written one, and
  nothing downstream can tell them apart. A source requires it by name, the
  require scope resolves through it, `source-origins` attributes symbols to
  it, and none of them know an annotation was involved.

  ONE MAP PER TARGET, which is what deleted a dispatcher. Every `:forms`
  entry used to be a function that read `(:target ctx)` and picked from a
  `{target link-fn}` map, throwing `is annotated for rust java and not for
  :go` when the target was not there -- and it existed only because a
  namespace could hold one map. It can hold a group, so each target
  contributes its own map and `kin/group-entry` picks, the same walk every
  other resolution uses. The error goes with it: `kin/group-miss` says the
  same thing once, for host trees and for a kin namespace's exports alike.

  Splitting by target also makes the tags honest. A tag used to be assembled
  with a `:types` map gathered from every target that annotated it, which is
  the right value and the wrong claim: the map said it spoke three languages
  while one file's annotation might have been missing. Now a target's map
  claims that target and carries that target's type, and `check-vocabulary`
  -- which is UNCHANGED, and is what makes grouping safe -- checks exactly
  that.

  Plural because `@kin:link:ns:` is per file, so one host tree may declare
  into several kin namespaces -- a package per namespace is the obvious layout
  and a scanner that forced them into one would be imposing a layout it has no
  business having a view on.

  Every vocabulary built here goes through `kin/check-vocabulary`, the same
  gate a hand-written one passes, so a scan that produced a malformed one is
  refused here rather than deep in a render. Run `check-agreement` FIRST if
  you want to know why: `check-vocabulary` can say `speaks :rust and its tag
  has no type for it`, and only the scan knows that the Rust annotation is
  missing from a named file at a named line."
  [scans]
  (let [;; {[nsym target] vocabulary}, built one annotation at a time.
        built (reduce
               (fn [vs [[nsym kind sym] by-target]]
                 (reduce
                  (fn [vs [t e]]
                    (let [k [nsym t]
                          v (or (get vs k)
                                {:namespace nsym :targets #{t}
                                 :tags {} :forms {} :names {}})]
                      (assoc vs k
                             (case kind
                               :form (assoc-in v [:forms sym] (:link-fn (:link e)))
                               :tag (assoc-in v [:tags sym]
                                              {:name sym
                                               :types {t (:type (:link e))}})
                               ;; A MAP OF PER-TARGET STRINGS, which is the
                               ;; shape `spell-name` calls the plain one and
                               ;; the only shape a host tree can contribute:
                               ;; each tree declares ITS spelling, and there
                               ;; is nothing to link because a name a target
                               ;; writes locally is in scope wherever its
                               ;; header is. The other shape -- one `(fn
                               ;; [ctx] -> String)` for the whole vocabulary
                               ;; -- cannot be assembled from three trees each
                               ;; answering separately, and a hand written
                               ;; vocabulary is where it belongs.
                               :name (assoc-in v [:names sym]
                                               {t (:spelling (:link e))})))))
                  vs by-target))
               {} (by-key scans))]
    (reduce (fn [m [[nsym _] v]]
              (update m nsym (fnil conj []) (kin/check-vocabulary v)))
            {}
            (sort-by (comp str first) built))))
