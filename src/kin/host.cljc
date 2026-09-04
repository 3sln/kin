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

  Comment syntax is the host language's, and the marker is the same in all of
  them:

      // @kin:ns: flint.impl.rt

      // @kin:link:form: vec-nth
      //   (fn [ctx form]
      //     (kin/emit! ctx (str \"Vecs.nth(\" ... \")\")))
      public static long nth(Rt rt, long v, long i, long dflt) { ... }

  `@kin:ns:` says which kin vocabulary the annotations below it contribute
  to, and applies from where it appears onward -- so one host file may
  contribute to two, and an annotation before any `@kin:ns:` is an error
  rather than a guess.

  ## THE PAYLOAD IS A LINK FN, and that is the load-bearing constraint

  The function in the annotation is THE LINK FUNCTION FOR A FORM. Not a
  template, not a description of one, not a new dispatch mechanism. It has the
  same signature and the same job as a link fn written into a vocabulary by
  hand, and `vocabularies` plugs it into exactly the slot that one would
  occupy:

      read the form  ->  evaluate it  ->  it IS the vocabulary's `:forms`
                                          entry for that symbol

  So nothing downstream of here knows an annotation was involved. The
  vocabulary this produces goes through `kin/check-vocabulary`, is required by
  a source's `ns` form, resolves through `kin/require-scope`, and is reported
  by `why`, all unchanged. There is no second way to resolve a form, and if
  one ever appears here something has gone wrong.

  ## The name

  `@kin:link:`, because the payload is a link fn and lands where a link fn
  lands. That collides with kin's own LINK PHASE only in spelling: the phase
  resolves a reference to an implementation, and this is an implementation
  reaching out of the host source to be resolved TO. `@kin:extern:` was the
  alternative and says less -- `extern` names where the thing lives, `link`
  names what the annotation is for.

  ## Which target a file speaks is CONFIGURATION, not an annotation

  One annotation per target: the Java file declares Java's, the C# file C#'s,
  Rust's its own. The scanner is told the target along with the vfs and the
  glob, because the caller is already scanning one language's tree with one
  language's comment prefix, and a `@kin:target:` marker in the file would be
  a second place for the same fact to be wrong. `disagreements` is what
  compares the results.

  ## Three functions, and they are different in kind

      scan            PURE DATA IN, PURE DATA OUT. Reads the annotations and
                      evaluates nothing. This is what makes the agreement
                      check possible: it compares READ FORMS, and never
                      invokes anything.
      disagreements   what the targets do not agree about, as data.
      vocabularies    evaluates the payloads -- see `evaluate`, the one seam
                      -- and hands back ordinary kin vocabularies."
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
;; The marker is matched with a regex rather than by `starts-with?`, so that a
;; MISSPELLED one is an error rather than a line nobody reads. That is the
;; failure this whole namespace exists to remove: an annotation that is
;; silently not there behaves exactly like a host function nobody annotated,
;; and the first thing to notice is a build error in generated code.

(def ^:private marker-pattern #"@kin:([a-z]+(?::[a-z]+)*):")

(def ^:private markers
  "Every marker there is, and how many values follows each.

  `@kin:ns:` takes one -- the namespace. A definition takes TWO, the kin
  symbol and then the payload, in that order because that is the order the
  sentence runs in: `vec-nth is (fn [ctx form] ...)`."
  {"ns" {:reads 1}
   "link:form" {:reads 2 :kind :form}
   "link:tag" {:reads 2 :kind :tag}
   "link:name" {:reads 2 :kind :name}})

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

(defn- read-values
  "Read `n` values from `s`, or `nil` if `s` does not hold that many yet.

  `clojure.edn/read` on a `PushbackReader` consumes exactly ONE value and
  leaves the rest of the stream, which is what a multi-line payload needs and
  is why it needs no new reader. Measured: reading `(fn [a] 1) TRAILING JUNK`
  answers `(fn [a] 1)`, and the next read answers `TRAILING`. So trailing
  prose on the last line of an annotation is ignored rather than being a
  syntax error.

  THE TWO WAYS TO RUN OUT ARE DIFFERENT and are told apart here. `:eof`
  answers the sentinel when there is nothing left at all; an INCOMPLETE value
  -- `(fn [ctx` -- throws `EOF while reading` instead. Both mean `feed me
  another line`; anything else is a genuine syntax error in the payload and is
  re-thrown, so a stray `)` is reported where it is rather than swallowing the
  rest of the file looking for a close."
  [s n]
  (let [r (reader s)]
    (loop [acc [] i 0]
      (if (= i n)
        acc
        (let [v (try (edn/read {:readers {} :eof ::eof} r)
                     (catch #?(:clj Exception :default :default) e
                       (if (str/includes? (or (ex-message e) "") "EOF while reading")
                         ::eof
                         (throw e))))]
          (if (= ::eof v) nil (recur (conj acc v) (inc i))))))))

(defn- read-payload
  "Read `n` values starting on `lines[i]` after `tail`, over as many
  consecutive COMMENT lines as it takes. Answers `[values next-i]`.

  A multi-line payload sits inside consecutive comment lines, so the comment
  prefix has to come off every continuation line before the reader sees it --
  otherwise the `//` of the second line lands in the middle of the form. Lines
  are joined with a newline rather than a space so that a string spanning two
  lines keeps the newline the author wrote.

  Running out of comment lines is a NAMED error pointing at the marker, not a
  crash: an unterminated form is a thing a person typed and has to find."
  [prefix lines i tail n path line-no]
  (loop [buf tail j i]
    (if-let [vs (read-values buf n)]
      [vs (inc j)]
      (let [next-body (when (< (inc j) (count lines))
                        (comment-body prefix (nth lines (inc j))))]
        (when-not next-body
          (fail path line-no
                (str "this annotation's payload is unterminated -- " n
                     (if (= 1 n) " value was" " values were")
                     " expected and the comment ran out at line " (inc (inc j))
                     ". A payload continues onto the next line only while that"
                     " line is still a `" prefix "` comment.")
                {:expected n :read buf}))
        (recur (str buf "\n" next-body) (inc j))))))

;; ------------------------------------------------------------------ arities

(defn arities
  "The arities a read `fn` form states, as a set, or nil if it states none.

  READ AS DATA, BEFORE ANY EVALUATION, which is the whole reason the agreement
  check can exist: `(fn [ctx a b] ...)` gives its binding vector directly, so
  the arity is a count rather than something reflection has to ask a compiled
  function for. Multi-arity `(fn ([a] 1) ([a b] 2))` answers `#{1 2}` the same
  way. Measured under bb.

  A variadic arity answers `[n &]`, meaning n-or-more, because two targets
  agreeing that a form takes `[ctx & args]` is a different agreement from two
  targets agreeing it takes exactly two, and collapsing them to `2` would call
  those the same.

  NIL IS AN HONEST ANSWER, not a failure. A payload that is a CALL --
  `(my.vocab/sibling \"nth\" 3)` -- has no binding vector to count, and kin
  will not evaluate it to find out. `disagreements` reports an arity mismatch
  only between payloads that both state one."
  [form]
  (when (and (seq? form) (contains? '#{fn fn*} (first form)))
    (let [body (rest form)
          ;; `(fn nm [a b] ...)` -- the optional self-name comes before the
          ;; binding vector and is not part of it.
          body (if (symbol? (first body)) (rest body) body)
          arity (fn [v]
                  (let [n (count (take-while #(not= '& %) v))]
                    (if (some #(= '& %) v) [n '&] n)))]
      (cond
        (vector? (first body)) #{(arity (first body))}
        :else (let [vs (keep (fn [c] (when (and (seq? c) (vector? (first c)))
                                       (arity (first c))))
                             body)]
                (when (seq vs) (set vs)))))))

;; ------------------------------------------------------------------- scan

(defn scan-text
  "Every annotation in one host file, as data. Evaluates nothing.

  `{:kind :form :sym 'vec-nth :payload '(fn ...) :arities #{2}
    :ns 'flint.impl.rt :path \"Vecs.java\" :line 41}`

  The payload is kept as the FORM IT WAS READ AS. That is what
  `disagreements` compares and it is what `evaluate` is later handed, so the
  data a check runs over and the data a vocabulary is built from are the same
  data -- a check that ran over something else would be checking something
  else."
  [{:keys [comment] :or {comment "//"}} path text]
  (let [lines (str/split text #"\n" -1)]
    (loop [i 0 nsym nil out []]
      (if (>= i (count lines))
        out
        (let [line-no (inc i)
              body (comment-body comment (nth lines i))
              m (when body (re-find marker-pattern body))]
          (if-not m
            (recur (inc i) nsym out)
            (let [[matched path-str] m
                  spec (or (get markers path-str)
                           (fail path line-no
                                 (str "`@kin:" path-str ":` is not a marker kin"
                                      " knows. It reads "
                                      (str/join ", " (for [k (sort (keys markers))]
                                                       (str "`@kin:" k ":`")))
                                      ". A marker kin does not recognise is"
                                      " refused rather than skipped, because a"
                                      " misspelt annotation and no annotation"
                                      " at all look identical from here.")
                                 {:marker path-str :known (vec (sort (keys markers)))}))
                  tail (subs body (+ (str/index-of body matched) (count matched)))
                  [vs next-i] (read-payload comment lines i tail (:reads spec)
                                            path line-no)]
              (if-let [kind (:kind spec)]
                (let [[sym payload] vs]
                  (when-not nsym
                    (fail path line-no
                          (str "`" sym "` is declared before any `" comment
                               " @kin:ns:` in this file, so there is no"
                               " vocabulary to declare it into. Put one near"
                               " the top of the file.")
                          {:symbol sym}))
                  (when-not (symbol? sym)
                    (fail path line-no
                          (str "a definition names a SYMBOL first and then its"
                               " payload -- this one begins with "
                               (pr-str sym) ".")
                          {:read sym}))
                  (recur next-i nsym
                         (conj out (cond-> {:kind kind :sym sym :payload payload
                                            :ns nsym :path path :line line-no}
                                     (= :form kind) (assoc :arities (arities payload))))))
                (let [[declared] vs]
                  (when-not (symbol? declared)
                    (fail path line-no
                          (str "`@kin:ns:` names a namespace, which is a"
                               " symbol -- this one reads " (pr-str declared) ".")
                          {:read declared}))
                  (recur next-i declared out))))))))))

(defn scan
  "Every annotation one target's host tree declares.

      (scan {:vfs (vfs/disk-vfs \"runtime/java\") :match \"*.java\"
             :target :java :comment \"//\"})

  Answers `{:target :java :entries [...]}`, with the entries sorted by file
  and line, because a listing's order is the filesystem's and a report that
  reorders between two runs of an unchanged tree is much harder to read than
  one that does not -- the same rule `kin.vfs/listing` follows and for the
  same reason.

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
   :entries (into []
                  (mapcat (fn [p] (scan-text opts p (vfs/-read vfs p))))
                  (vfs/listing vfs match))})

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
  "What `scans` -- one per target -- do not agree about, as data.

  Two findings, and they catch different things:

    `:missing`  a symbol some targets declare and others do not. This is the
                one that bites in practice: a host function ported to two of
                three runtimes, with the third's annotation never written, is
                invisible until a kin source calls it.
    `:arity`    two targets whose payloads both state an arity, and state
                different ones. Compared from the READ FORMS -- nothing here
                is evaluated, and nothing is invoked.

  WHAT THIS DOES NOT CATCH, said plainly, because a gate whose reach is
  misunderstood is worse than none: a link fn written the ordinary way is
  `(fn [ctx form] ...)` on every target, so its arity is 2 on every target and
  the arity finding is vacuous for it. What defends against the arity drift
  that started all this is not this check -- it is that the annotation sits
  NEXT TO the host function, so growing the function's parameter list and
  leaving the annotation alone is an edit a person makes while looking at
  both. The check earns its place on the `:missing` half.

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
            ;; Only between payloads that BOTH state an arity: a payload that
            ;; is a call rather than a `fn` states none, and `nil` is not a
            ;; disagreement with `#{2}`.
            (let [stated (into (sorted-map)
                               (keep (fn [[t e]] (when (:arities e) [t (:arities e)])))
                               by-target)]
              (when (< 1 (count (set (vals stated))))
                [{:issue :arity :ns nsym :kind kind :sym sym
                  :arities stated
                  :sites (into (sorted-map) (map (fn [t] [t (where t)])) (keys stated))}])))))
       (by-key scans))))))

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
                   (str/join
                    "\n"
                    (for [d ds]
                      (case (:issue d)
                        :missing
                        (str "  " (:ns d) "/" (:sym d) " (" (name (:kind d)) ")"
                             " is declared by "
                             (str/join " " (map name (:declared d)))
                             " and not by "
                             (str/join " " (map name (:missing d)))
                             "\n      declared at "
                             (str/join ", " (for [[t w] (:sites d)]
                                              (str (name t) " " (:path w)
                                                   ":" (:line w)))))
                        :arity
                        (str "  " (:ns d) "/" (:sym d)
                             " states different arities: "
                             (str/join ", " (for [[t a] (:arities d)]
                                              (str (name t) " "
                                                   (pr-str (vec (sort-by pr-str a))))))
                             "\n      declared at "
                             (str/join ", " (for [[t w] (:sites d)]
                                              (str (name t) " " (:path w)
                                                   ":" (:line w)))))))))
              {:disagreements ds})))
    scans))

;; -------------------------------------------------------------- the seam
;;
;; ONE PLACE turns a read form into something callable, and this is it.

(defn evaluate
  "Turn a read payload into the thing it denotes.

  **TEMPORARY, AND ON PURPOSE.** This goes through SCI today -- under
  babashka `clojure.core/eval` IS SCI, which is measurable rather than
  asserted: the value it answers for `(fn [ctx form] ...)` is an
  `sci.impl.fns$fun`. That is a stopgap. Once flint is ready to interpret its
  own syntax, FLINT becomes the interpreter and this function's body is what
  changes -- which is the reason there is exactly one of it. If SCI is ever
  reached for anywhere else in this namespace, the seam has leaked and the
  swap becomes a search.

  Do not read this as a decision about evaluation. The decision is that the
  payload is CLOJURE/FLINT SYNTAX -- an annotation in a `.java` comment holds
  a form that maps directly onto a kin link fn, and inventing a second
  mini-language to avoid evaluating one would be a worse answer than
  evaluating one. How it is evaluated is an implementation detail with a
  known replacement.

  MEASURED, because it constrains what an annotation may say: a
  fully-qualified name resolves -- `kin/emit!` and `kin.lang/call` both do,
  from a caller that never required either -- and an ALIAS does not: `(lang/call
  ...)` throws `Could not resolve symbol: lang/call`. An annotation therefore
  names things in full. That is not a property of SCI worth designing around;
  it is the property any evaluator with no ambient namespace will have,
  flint's included."
  [form]
  #?(:clj (eval form)
     :default (throw (ex-info (str "kin.host: this host cannot evaluate an"
                                   " annotation's payload.")
                              {:form form}))))

;; ------------------------------------------------------- as a vocabulary

(defn- form-entry
  "One symbol's `:forms` entry: a link fn that picks the target's at CALL time.

  Deliberately the same shape as the one `kin.project` builds for a kin
  namespace's exports, including the error. Both answer the same question --
  several targets each supplied an implementation, and which one is wanted is
  not known until a form is rendered -- and answering it twice in two shapes
  would be two things to keep in step."
  [nsym sym by-target]
  (fn [ctx form]
    (if-let [f (get by-target (:target ctx))]
      (f ctx form)
      (throw (ex-info (str "kin.host: " nsym "/" sym " is annotated for "
                           (str/join " " (map name (sort (keys by-target))))
                           " and not for " (:target ctx)
                           " -- no host file declares it there.")
                      {:namespace nsym :symbol sym :target (:target ctx)
                       :annotated (vec (sort (keys by-target)))})))))

(defn vocabularies
  "The scans, as ordinary kin vocabularies: `{ns-sym vocabulary}`.

  This is where the payloads are evaluated and where the namespace stops
  being about annotations: what comes out is a value a project puts in its
  `:vocabularies` beside a hand-written one, and nothing downstream can tell
  them apart.

  Plural because `@kin:ns:` is per file, so one host tree may declare into
  several kin namespaces -- a package per namespace is the obvious layout and
  a scanner that forced them into one would be imposing a layout it has no
  business having a view on.

  A TAG'S PAYLOAD IS THIS TARGET'S TYPE and a NAME'S IS THIS TARGET'S
  SPELLING, which is narrower than what those can hold in a hand-written
  vocabulary and is narrow on purpose. `check-vocabulary` already knows a tag
  has a `:types` keyed by target and a name a spelling per target -- it
  refuses a vocabulary missing either -- so assembling exactly those from
  per-target files asks kin to know nothing new. A tag that carries MORE than
  its type, such as a dispatch table a form reads, is not a fact about one
  host file: every target's form has to see the same table, so it belongs in
  a vocabulary where they all can. Only forms go through `evaluate`; a tag
  and a name are the data they were read as.

  Every vocabulary built here goes through `kin/check-vocabulary`, the same
  gate a hand-written one passes, so a scan that produced a malformed one is
  refused here rather than deep in a render. Run `check-agreement` FIRST if
  you want to know why: `check-vocabulary` can say `speaks :rust and its tag
  has no type for it`, and only the scan knows that the Rust annotation is
  missing from a named file at a named line."
  ([scans] (vocabularies scans nil))
  ([scans opts]
   (let [ev (clojure.core/get opts :evaluate evaluate)
         built (reduce
                (fn [vs [[nsym kind sym] by-target]]
                  (let [v (or (get vs nsym)
                              {:namespace nsym :targets #{}
                               :tags {} :forms {} :names {}})
                        v (update v :targets into (keys by-target))
                        per-target (fn [f] (into {} (map (fn [[t e]] [t (f e)])) by-target))]
                    (assoc vs nsym
                           (case kind
                             :form (assoc-in v [:forms sym]
                                             (form-entry nsym sym
                                                         (per-target (comp ev :payload))))
                             :tag (assoc-in v [:tags sym]
                                            {:name sym :types (per-target :payload)})
                             :name (assoc-in v [:names sym] (per-target :payload))))))
                {} (by-key scans))]
     (reduce-kv (fn [m k v] (assoc m k (kin/check-vocabulary v))) {} built))))
