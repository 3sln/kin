(ns kin
  "kin: write a runtime's shared logic once, emit it for every host.

  ## Why this is code and not data

  The first version made per-target knowledge a table of format strings. It got
  most of the way and then leaked: FOUR things turned out not to be expressible
  as data, and each ended up special-cased in the translator instead --

  * Rust needs call arguments hoisted into temporaries and the others do not;
  * a loop test cannot be hoisted and has to be rewritten into the loop;
  * `mut` has to be inferred from whether the body assigns;
  * numeric width is a per-call cast.

  A translator that knows about all four is not a translator with a config
  file; it is a compiler for three languages wearing one. So the rules are
  FUNCTIONS. Each target implements each form, and the four become ordinary
  code inside the implementations that need them -- the Rust `invoke` hoists
  because Rust's `invoke` says so, and nothing else in the system knows.

  ## The pieces

  * `vocabulary` -- a VALUE: which TARGETS it can speak, its tags, its names,
    and per-target implementations of its forms.
  * A source file is an ordinary `ns` with `:require`, so what a file may say is
    what it asked for. Two sources can use different vocabularies, and a source
    generates for the targets every vocabulary it required can speak.

  ## Emission

  Two sinks, and the difference is the whole reason hoisting works:

      (emit!  ctx \"...\")   append here
      (before! ctx \"...\")   append BEFORE the current statement

  and `render` runs a form into a string instead of the current sink, so
  an expression can be composed while a statement is emitted."
  (:refer-clojure :exclude [get])
  (:require [clojure.string :as str]
            [clojure.set]))

;; --------------------------------------------------------------- the context

(defn- new-sink [] (atom []))

;; --------------------------------------------------------------- anchors
;;
;; An ANCHOR is a named place in the output that has already gone past.
;;
;; `before!` came first and could only reach ONE level up -- before the
;; statement being built. That is enough for hoisting a temporary and enough for
;; nothing else: a loop-invariant binding wants to go before the LOOP, a scratch
;; declaration wants the top of the FUNCTION, and neither is one level up.
;;
;; An anchor is dropped where the output should later appear, carried in a scope
;; frame, and emitted against from arbitrarily deep. Resolution happens when the
;; buffer is joined, so an anchor placed early can be written to late.

(defn anchor
  "A fresh anchor -- a place to emit into, resolved when the output is joined."
  []
  {:kin/anchor (new-sink)})

(defn anchor? [x] (and (map? x) (contains? x :kin/anchor)))

(defn emit-anchor!
  "Drop an anchor HERE and return it. Whatever is emitted against it later
  appears at this point in the output."
  [ctx]
  (let [a (anchor)]
    (swap! (:out ctx) conj a)
    a))

(defn resolve-sink
  "A sink's items as one string, anchors resolved in place and recursively --
  an anchor may itself contain anchors, which is what makes them nest."
  [items]
  (str/join (mapv (fn [i]
                    (if (anchor? i)
                      (resolve-sink (deref (:kin/anchor i)))
                      i))
                  items)))

(defn context
  "A fresh emission context for `target`."
  [driver target]
  {:driver driver
   :target target
   :out (new-sink)
   ;; Statements to place BEFORE the one being built. A form that needs a
   ;; temporary writes here and the statement layer flushes it.
   :pre (new-sink)
   :scope {}
   :indent 0})

(defn scoped
  "Call `f` with `ctx` extended by one scoped entry.

  `{:key :class :value {...}}`, and `:indent` if the scope indents. Scoped
  rather than global because a form's implementation asks what encloses it --
  which class, which package, how deep -- and that is a stack, not a variable."
  [ctx entry f]
  (f (-> ctx
         (assoc-in [:scope (:key entry)] (:value entry))
         (update :indent + (or (:indent entry) 0)))))

(defn get
  "Read a scoped entry."
  [ctx k]
  (clojure.core/get (:scope ctx) k))

(defn indent-of
  "One level of indentation per enclosing scope, spelled the way THIS TARGET
  spells one.

  It used to be four spaces, always, which is a fact about three languages
  rather than about indentation. Go uses tabs and `gofmt` rewrites anything
  else -- found by writing the worked example in `examples/go`, which is what
  a worked example is for. A target that says nothing still gets four spaces,
  so nothing that existed before this changes."
  [ctx]
  (let [unit (get-in ctx [:targets (:target ctx) :indent-unit] "    ")]
    (apply str (repeat (:indent ctx) unit))))

(defn emit!
  "Append to the current sink, or to an ANCHOR.

  `(emit! ctx \"...\")` writes here; `(emit! a \"...\")` writes
  where `a` was dropped. One function for both because a form implementation
  should not have to care which it was handed -- it emits at a place, and a
  place is either \"here\" or an anchor."
  [target & parts]
  (swap! (if (anchor? target) (:kin/anchor target) (:out target))
         conj (apply str parts))
  nil)

(defn before!
  "Emit before the statement being built -- the anchor the statement layer
  dropped, looked up by name.

  Kept as a convenience because hoisting a temporary is the common case, and
  now it is one anchor among others rather than a mechanism of its own."
  [ctx & parts]
  (apply emit! (or (get ctx :kin/stmt-anchor) ctx) parts))


;; ------------------------------------------------------------------ dispatch

(declare dispatch literal)

(defn require-scope
  "The symbol table a source's `ns` form asks for.

  `{written-symbol -> [vocabulary-name simple-symbol]}`, honouring both halves
  of a require:

      (:require [flint.impl.vm :as vm :refer [let set if]])

  gives `let` (referred, unqualified) and `vm/let` (through the alias) and
  `flint.impl.vm/let` (fully qualified), and gives NOTHING else -- a form that
  was not referred is not in scope unqualified, exactly as in Clojure.

  **THE FIRST REQUIRE WINS.** Where two vocabularies offer the same symbol,
  the one required EARLIER is what the file means, and the later one is
  shadowed. That is the whole override path:

      (:require [com.example.my-ops :refer [+ - *]]
                [kin.lang       :refer [+ - * let if return]])

  gets `my-ops`'s arithmetic and `kin.lang`'s `let`, `if` and `return`, with
  no editing of `kin.lang` and no fork of it. A user who wants their own `+`
  writes one and puts it first.

  This REVERSES what the code did. It reduced with `assoc`, so the LAST
  require won -- silently, and against this docstring, which already claimed
  that `which one a file means has to be a fact about the file rather than
  about the order somebody merged two maps`. It was a fact about the order,
  and the order was the wrong way round: the later require, which reads as
  the more general one, quietly beat the earlier and more specific one.

  Shadowing is not an error and not a warning, because it is the mechanism.
  It is RECORDED -- in this map's metadata, under `:kin/shadowed` -- and
  `kin why` prints it, so the effect is visible rather than inferred. An
  override nobody can see is indistinguishable from a bug, which is how the
  last-wins behaviour survived this long."
  [ns-form vocabs]
  (let [reqs (->> (rest ns-form)
                  (filter (fn [f] (and (seq? f) (= :require (first f)))))
                  (mapcat rest))
        ;; `put` is where first-wins lives. Everything else in this function
        ;; is unchanged from the last-wins version.
        put (fn [acc sym entry]
              (if-let [held (get-in acc [:scope sym])]
                (if (= held entry)
                  acc
                  (update-in acc [:shadowed sym] (fnil conj []) entry))
                (assoc-in acc [:scope sym] entry)))
        result
        (reduce
         (fn [acc spec]
           (let [spec (if (vector? spec) spec [spec])
                 vname (first spec)
                 opts (apply hash-map (rest spec))
                 alias (:as opts)
                 referred (:refer opts)
                 vocab (clojure.core/get vocabs vname)]
             (when-not vocab
               (throw (ex-info (str "kin: no vocabulary " vname)
                               {:required vname :known (vec (keys vocabs))})))
             ;; FORMS AND TAGS ALIKE. A tag is referred and aliased exactly as a
             ;; form is -- `^Usize` has to mean whichever vocabulary's `Usize` this
             ;; file asked for, for the same reason `let` does.
             (let [names (concat (keys (:forms vocab)) (keys (:tags vocab))
                                 (keys (:names vocab)))]
               (as-> acc a
                 ;; Fully qualified always works.
                 (reduce (fn [m k] (put m (symbol (str vname) (str k)) [vname k])) a names)
                 ;; The alias, when one was asked for.
                 (if alias
                   (reduce (fn [m k] (put m (symbol (str alias) (str k)) [vname k])) a names)
                   a)
                 ;; And only what was REFERRED, unqualified.
                 (reduce (fn [m k]
                           (when-not (or (contains? (:forms vocab) k)
                                         (contains? (:tags vocab) k)
                                         (contains? (:names vocab) k))
                             (throw (ex-info (str "kin: " vname " has no " k " to refer")
                                             {:vocabulary vname :symbol k})))
                           (put m k [vname k]))
                         a (or referred []))))))
         {:scope {} :shadowed {}} reqs)]
    (with-meta (:scope result) {:kin/shadowed (:shadowed result)})))

(defn require-specs
  "The `:require` specs of an `ns` form, each normalised to a vector."
  [ns-form]
  (->> (rest ns-form)
       (filter (fn [f] (and (seq? f) (= :require (first f)))))
       (mapcat rest)
       (mapv (fn [spec] (if (vector? spec) spec [spec])))))

(defn ns-options
  "The `ns` form's attribute map -- `{:kin/only #{...}}`, `{:kin/exclude ...}`.

  At most one. Two attribute maps are refused rather than merged: the two
  keys are ALTERNATIVES, and a reader who writes both has said two things
  that a merge would quietly reconcile into a third."
  [ns-form]
  (let [maps (filter map? (drop 2 ns-form))]
    (when (< 1 (count maps))
      (throw (ex-info (str "kin: " (second ns-form) " has "
                           (count maps) " attribute maps. `:kin/only` and"
                           " `:kin/exclude` are alternatives -- put the one"
                           " you mean in a single map.")
                      {:namespace (second ns-form) :maps (vec maps)})))
    (or (first maps) {})))

;; ------------------------------------------------------- target selection
;;
;; WHICH TARGETS A SOURCE GENERATES FOR is a computation, not a lookup, and
;; the whole of it is here:
;;
;;     (intersection (targets of every required vocabulary))
;;       minus  :kin/exclude
;;       intersected with  :kin/only  (when given)
;;
;; A source generates for exactly that set and no other. A target named in
;; `:kin/only` that some required vocabulary cannot speak is an ERROR naming
;; the vocabulary and the target, because silence and success have to be
;; distinguishable -- that rule has cost this project four separate bugs.

(defn target-report
  "Everything there is to say about which targets a source generates for.

      :required   the vocabularies it required, in order
      :spoken     what each of them can speak
      :common     the intersection -- what they can ALL speak
      :only       the `:kin/only` set, or nil
      :exclude    the `:kin/exclude` set, or nil
      :targets    the effective set: what this source generates for
      :ruled-out  {target [reason ...]} for every target some vocabulary
                  speaks but this source does not generate for

  `:ruled-out` is the reason this returns a report rather than a set. `kin
  targets` has to answer not just which sources generate for a target but,
  for those that do not, WHICH vocabulary or exclusion ruled it out -- and
  that is knowable here and nowhere later."
  [ns-form vocabs]
  (let [specs (require-specs ns-form)
        required (mapv first specs)
        _ (doseq [v required]
            (when-not (clojure.core/get vocabs v)
              (throw (ex-info (str "kin: no vocabulary " v)
                              {:required v :known (vec (keys vocabs))}))))
        spoken (into {} (map (fn [v] [v (:targets (clojure.core/get vocabs v))])) required)
        all (reduce into #{} (vals spoken))
        common (if (seq spoken)
                 (reduce clojure.set/intersection (vals spoken))
                 all)
        opts (ns-options ns-form)
        only (:kin/only opts)
        exclude (:kin/exclude opts)]
    ;; `:kin/only` asking for a target no vocabulary here can speak is the
    ;; error the redesign singles out: a silent omission would generate two
    ;; files where the source asked for three, and nothing would say so.
    (doseq [t (or only [])]
      (when-not (contains? common t)
        (let [mute (mapv key (remove (fn [[_ ts]] (contains? ts t)) spoken))]
          (throw (ex-info
                  (str "kin: " (second ns-form) " asks for " t " in `:kin/only`"
                       ", but " (str/join ", " (map str mute))
                       (if (= 1 (count mute)) " cannot speak it" " cannot speak it")
                       ". A vocabulary generates for what it says it can say.")
                  {:namespace (second ns-form) :target t :mute mute
                   :spoken spoken})))))
    (let [targets (cond-> common
                    exclude (#(reduce disj % exclude))
                    only (#(clojure.set/intersection % (set only))))
          ruled-out
          (into {}
                (for [t all :when (not (contains? targets t))]
                  [t (vec (concat
                           (for [[v ts] spoken :when (not (contains? ts t))]
                             (str v " cannot speak it"))
                           (when (and exclude (contains? (set exclude) t))
                             [":kin/exclude"])
                           (when (and only (not (contains? (set only) t)))
                             [":kin/only does not name it"])))]))]
      (when (empty? targets)
        (throw (ex-info
                (str "kin: " (second ns-form) " generates for NO target."
                     " Its vocabularies have no target in common"
                     (when (or only exclude) ", or the ns form ruled the rest out")
                     ". A source that generates nothing is a source nothing"
                     " checks.")
                {:namespace (second ns-form) :spoken spoken
                 :only only :exclude exclude})))
      {:required required :spoken spoken :common common
       :only only :exclude exclude :targets targets :ruled-out ruled-out})))

(defn effective-targets
  "Which targets this source generates for. See `target-report`."
  [ns-form vocabs]
  (:targets (target-report ns-form vocabs)))

(defn shadowed
  "What each symbol in a require scope shadows: `{sym [[vocab k] ...]}`.

  Written by `require-scope` and read by `kin why`. Empty for a source that
  requires one vocabulary, which is every source in the tree this was built
  against -- so first-wins changed nothing there, and the check that says so
  is `bin/check-kin` reporting byte-identical output."
  [scope]
  (:kin/shadowed (meta scope) {}))

(defn tag
  "The TAG value a symbol names, resolved through the file's require scope.

  Tags are namespaced like everything else, so `^Usize` means the `Usize` this
  file asked for and not whichever one happened to be merged last."
  [ctx sym]
  (when sym
    (if-let [scope (:scope-syms ctx)]
      (when-let [[vname k] (clojure.core/get scope sym)]
        (get-in ctx [:vocabs vname :tags k]))
      (get-in ctx [:tags sym]))))

(defn vocab-name
  "How a VOCABULARY spells `sym` for this target, through the require scope.

  The third thing a vocabulary provides, after forms and tags. A type tag like
  `TY_CONS` is a value rather than a call -- it appears in a `case` label,
  where a call cannot go -- so it cannot be a form, and it is spelled
  `Obj.TyCons` on one of the three, so it cannot be left alone either.

  Resolved through the scope like everything else, so an alias works on a name
  exactly as it works on a form."
  [ctx sym]
  (when-let [scope (:scope-syms ctx)]
    (when-let [[vname k] (clojure.core/get scope sym)]
      (get-in ctx [:vocabs vname :names k (:target ctx)]))))

(defn declare-name!
  "Register how `sym` is SPELLED in each target, for a name whose convention is
  not the local one.

  Constants are the case that forced it. Rust and Java scream (`HASH_TRUE`) and
  C# pascalises (`HashTrue`), so a reference in the body cannot be rendered by
  a single rule, and the declaration is the only place that knows. A name not
  registered here falls back to `local-name`, which is right for everything
  else."
  [ctx sym names]
  (when-let [a (:names ctx)] (swap! a assoc sym names))
  nil)

(defn declare!
  "Register `sym` as callable by later forms in THIS source file.

  A source has to be able to define a helper and then call it. Without this,
  every helper would have to live in a vocabulary -- and a helper in a
  vocabulary is a helper written once per target, which is the cost this whole
  exercise exists to remove.

  Declarations are FILE-LOCAL and come second: a vocabulary name still wins, so
  a file cannot quietly redefine `let` out from under the reader. They are also
  ordered -- a call before the `defn` that declares it is not in scope, the
  same rule C and Rust modules differ on and the stricter of the two."
  [ctx sym f]
  (when-let [a (:locals ctx)] (swap! a assoc sym f))
  nil)

(defn- form-fn
  "The implementation of `head` for this context's target, or nil.

  Resolved through the source's require scope when there is one, so a name
  means what the file said it means, then through what the file itself has
  declared."
  [ctx head]
  (if-let [scope (:scope-syms ctx)]
    (or (when-let [[vname k] (clojure.core/get scope head)]
          (get-in ctx [:vocabs vname :forms k]))
        (clojure.core/get (some-> (:locals ctx) deref) head))
    (or (get-in ctx [:vocab head])
        (clojure.core/get (some-> (:locals ctx) deref) head))))


;; ------------------------------------------------------------------- tags
;;
;; A TAG IS DATA. kin carries one and never interprets it.
;;
;; That is the whole of the rule, and it is narrower than it looks. kin has no
;; dispatch table, no match rules, no notion of what any tag MEANS and no
;; opinion about whether two tags are compatible. A form receives each
;; argument as rendered text plus whatever tag it carries and decides for
;; itself -- including deciding to ignore it, which is what every form in the
;; tree did before this existed and still does.
;;
;; So what kin owes is four things:
;;
;;   * a form's product is text plus an optional tag;
;;   * an enclosing form sees each argument's text and tag;
;;   * declared tags -- `^I32` on a parameter, a `:tag` on a call, a `defn`
;;     return -- flow to the products that carry them;
;;   * an unannotated local may take its tag from its initialiser.
;;
;; An implementation here that finds itself asking what a tag means has gone
;; wrong. `kin.lang` may dispatch however it likes; that is a vocabulary
;; making a choice about its own forms, and a user who wants a different one
;; shadows it (see `require-scope`, first match wins).

(defn tagged!
  "Say that the form now rendering produced `tag`.

  Called by a form implementation about ITS OWN product. kin stores it and
  hands it to whoever renders this form as an argument; nothing here looks
  inside it."
  [ctx tag]
  (when-let [a (:product ctx)] (reset! a tag))
  nil)

(defn local-tag
  "The tag a local was declared or inferred with, or nil."
  [ctx sym]
  (clojure.core/get (some-> (:local-tags ctx) deref) sym))

(defn declare-tag!
  "Record that `sym` -- a parameter, a binding, a loop variable -- has `tag`."
  [ctx sym tag]
  (when-let [a (:local-tags ctx)] (swap! a assoc sym tag))
  nil)

(defn literal-tag
  "The tag a bare literal carries, asked of the source's vocabularies.

  kin cannot know what tag `5` has, because tags belong to vocabularies and
  kin has none of its own. So it ASKS: a vocabulary may carry a
  `:literal-tag`, a `(fn [v] -> tag or nil)`, and the first required
  vocabulary to answer wins -- the same first-match rule the require scope
  uses, for the same reason."
  [ctx v]
  (some (fn [vname]
          (when-let [f (get-in ctx [:vocabs vname :literal-tag])] (f v)))
        (:vocab-order ctx)))

(defn render-tagged
  "Render `form` and answer BOTH halves of its product: `{:text ... :tag ...}`.

  This is what item 5 exists for. A form that wants to know what its argument
  IS -- rather than only what it says -- calls this instead of `render`,
  and gets a tag it may use, ignore, or refuse.

  A TAG WRITTEN AT THE CALL SITE WINS (correction C1b):

      (foo ^MyTag (bar ...) ^MyOtherTag (baz ...))

  reaches `foo` as two arguments carrying `MyTag` and `MyOtherTag`, whatever
  `bar` and `baz` say their products are. An argument's tag has two sources --
  what the inner form declares, and an annotation here -- and the explicit one
  wins. It is the escape hatch that makes the scheme usable: a form cannot
  always know what it produced, and the caller often can.

  The annotation is read HERE, where the argument is rendered, rather than
  where the form is resolved, because it belongs to the argument.

  AN UNDECLARED TAG IS CARRIED, NOT REFUSED. If `^MyTag` resolves through the
  file's require scope it arrives as that tag's value; if it resolves to
  nothing it arrives as the bare symbol. kin does not ask whether a tag is
  declared, what it means, or whether it is compatible with anything --
  carrying it from where it was written to the form that receives it is the
  entire job, and what a form does with a tag it has never heard of is that
  form's business."
  [ctx form]
  (let [product (atom nil)
        sub (-> ctx
                (assoc :out (new-sink))
                (assoc :product product)
                (assoc-in [:scope :position] :expression))
        written (:tag (meta form))]
    (dispatch sub form)
    {:text (resolve-sink (deref (:out sub)))
     :tag (if written (or (tag ctx written) written) @product)}))

(defn render
  "Run `form` into a STRING rather than into the current sink.

  Expressions compose; statements emit. A form implementation that needs a
  sub-expression calls this, and one that emits a statement calls
  `emit!` -- which is how one vocabulary serves both positions without
  the translator deciding which is which.

  The text half of `render-tagged`. It is defined in terms of it rather
  than beside it so that a form rendering an argument cannot accidentally
  report that argument's tag as its OWN product -- which is what happened
  when the two shared a sub-context."
  [ctx form]
  (:text (render-tagged ctx form)))

(defn position
  "What this form is being compiled AS: `:statement` or `:expression`.

  Pushed DOWN by whatever encloses it, because that is who knows. A top-level
  form in a method body is a statement whether or not the construct could also
  be an expression, and the method body is the thing that knows it is a body.

  This replaced metadata on the implementation saying what it EMITS, plus a
  per-target wrapper that combined the two. That was answering the question from
  the wrong end: a form does not have a kind, it has a POSITION, and the same
  `if` is a statement here and an expression there --

      (if c (do-a) (do-b))            statement
      (let [x (if c a b)] ...)        expression, and legal Rust

  -- so the enclosing form says which, and the implementation reads it and emits
  what that target wants in that position. One lookup, no wrapper, and nothing
  post-processes a string it did not produce."
  [ctx]
  (or (get ctx :position) :expression))

(defn in
  "Render `form` at `position`, with an anchor for anything it hoists."
  [ctx position form]
  ;; The anchor goes down FIRST, so anything hoisted lands above whatever this
  ;; turns out to be, however deep the form that hoisted it.
  (let [a (emit-anchor! ctx)
        sub (-> ctx
                (assoc :out (new-sink))
                (assoc-in [:scope :kin/stmt-anchor] a)
                (assoc-in [:scope :position] position))]
    (dispatch sub form)
    (emit! ctx (resolve-sink (deref (:out sub))))))

(defn statement!
  "Render `form` as a statement -- the common call, kept short."
  [ctx form]
  (in ctx :statement form))

(defn dispatch
  "One form. A seq whose head is in scope goes to its implementation.

  A seq whose head is a symbol NOT in scope is an error rather than a literal.
  Falling through would emit the symbol's name and produce something that looks
  like a call and is not one -- which is the failure mode a require scope exists
  to prevent."
  [ctx form]
  (cond
    (and (seq? form) (symbol? (first form)))
    (if-let [f (form-fn ctx (first form))]
      (f ctx form)
      (throw (ex-info (str "kin: " (first form) " is not in scope"
                           (when (:scope-syms ctx)
                             (str " -- this file requires "
                                  (pr-str (vec (sort (map str (keys (:scope-syms ctx))))))))) 
                      {:symbol (first form)})))
    ;; A LOCAL CARRIES THE TAG IT WAS DECLARED WITH, and a literal whatever
    ;; the source's vocabularies say its shape implies. Both are recorded as
    ;; this form's product, so an enclosing form asking `render-tagged`
    ;; gets an answer for a bare `x` and a bare `5` as well as for a call.
    :else (do (tagged! ctx (if (symbol? form)
                                 (local-tag ctx form)
                                 (literal-tag ctx form)))
              (emit! ctx (literal ctx form)))))

(defn local-name
  "A local, spelled the way THIS TARGET spells one.

  The library knows no language. It asks the target's `:local-name`, which is
  a `(fn [ctx sym] -> String)` supplied by a project -- so a mapper can see
  what encloses the name, not only the name, and owns whatever escaping or
  refusal its language needs. A target that supplies none gets the symbol
  unchanged, which is the honest default: a tool that has not been told a
  convention must not invent one."
  [ctx sym]
  ((get-in ctx [:targets (:target ctx) :local-name] (fn [_ s] (str s))) ctx sym))

(defn- constant-shaped?
  "Does `sym` look like a CONSTANT rather than a local?

  `SCREAMING_SNAKE`, which every target in practice reserves for constants and
  no local is ever spelled as. The distinction matters because an undeclared
  symbol falls through to the local namer, and a local namer is exactly the
  wrong thing for a constant: a local is spelled by convention per target, a
  constant is spelled however the target's runtime happens to declare it.

  `LS_THUNK` is what that costs. It was not in the name table, so it fell
  through and emitted `LS_THUNK` into a C# file whose constant is `LsThunk`.
  The CLR did not compile for the whole of the work that followed, and neither
  `kin/verify` nor the host conformance run said so."
  [sym]
  (let [s (str sym)]
    (and (> (count s) 1) (some? (re-matches #"[A-Z][A-Z0-9_]*" s)))))

(defn literal
  "A non-form: a symbol, a number, a string, a boolean."
  [ctx v]
  (cond
    (string? v) (pr-str v)
    (symbol? v) (or (vocab-name ctx v)
                    (get-in (some-> (:names ctx) deref) [v (:target ctx)])
                    (when (constant-shaped? v)
                      (throw (ex-info
                              (str "kin: `" v "` is constant-shaped but is not a"
                                   " declared name. Add it to the subject's name"
                                   " table saying how each target spells it --"
                                   " passing it through verbatim is only right"
                                   " when every target agrees, and that is a"
                                   " thing to state rather than to assume.")
                              {:symbol v :target (:target ctx)})))
                    (local-name ctx v))
    (nil? v) (or (get ctx :nil) "null")
    :else (str v)))

;; ------------------------------------------------------------- declarations

(defn output
  "Everything emitted into `ctx`, with anchors resolved. What a driver writes."
  [ctx]
  (resolve-sink (deref (:out ctx))))

(defn check-vocabulary
  "Answer `v` if it is a well-formed vocabulary; throw saying why if it is not.

  Called when a vocabulary is LOADED, before any source is read, because the
  whole point of `:targets` is to move a question that used to be answered
  deep inside a render -- by a template lookup returning nil -- to a place
  where it can be answered once and named.

  So the checks here are the ones whose failure used to be silent: a tag with
  no type for a target the vocabulary claims to speak, and a name with no
  spelling for one. Both produced an empty string in the output and a build
  error three files away. Forms cannot be checked this way -- they are
  functions -- so `kin.lang/call` names the missing target at render time
  instead."
  [v]
  (let [nm (:namespace v)
        who (str "kin: vocabulary " (or nm "<unnamed>"))]
    (when-not (map? v)
      (throw (ex-info (str who " is not a map") {:vocabulary v})))
    (when-not (symbol? nm)
      (throw (ex-info (str who " has no `:namespace` -- a vocabulary names"
                           " itself, so an error about it can say which one")
                      {:vocabulary v})))
    (let [targets (:targets v)]
      (when-not (and (set? targets) (seq targets))
        (throw (ex-info (str who " declares no `:targets`. A vocabulary has to"
                             " say which targets it can speak: that is what"
                             " makes \"can this source be generated for X\" a"
                             " question with an answer.")
                        {:vocabulary nm :targets targets})))
      (doseq [k [:tags :forms :names]]
        (when-not (map? (clojure.core/get v k {}))
          (throw (ex-info (str who "'s " k " is not a map") {:vocabulary nm}))))
      ;; A tag has to have a type in every target the vocabulary speaks.
      (doseq [[sym tag] (:tags v) t targets]
        (when-not (get-in tag [:types t])
          (throw (ex-info (str who " speaks " t " but its tag `" sym "` has no"
                               " type for it -- add one to `:types`, or drop "
                               t " from `:targets`.")
                          {:vocabulary nm :tag sym :target t
                           :types (:types tag)}))))
      ;; And a name a spelling.
      (doseq [[sym spellings] (:names v) t targets]
        (when-not (clojure.core/get spellings t)
          (throw (ex-info (str who " speaks " t " but its name `" sym "` has no"
                               " spelling for it. Passing a name through"
                               " verbatim is only right when every target"
                               " agrees, and that is a thing to state rather"
                               " than to assume.")
                          {:vocabulary nm :name sym :target t
                           :spellings spellings})))))
    v))

(defn vocabulary
  "A VOCABULARY, as a value:

      {:namespace 'com.example.my-ns
       :targets   #{:rust :java :csharp}   ; what this vocabulary can speak
       :tags      {...}
       :forms     {...}
       :names     {...}}

  It used to be a namespace discovered by convention -- `forms-for`,
  `tags-for` and `names-for` resolved by name, and whatever was found merged.
  Nothing declared what the vocabulary WAS, and nothing said which targets it
  could speak. Every template map happened to carry `:rust`, `:java` and
  `:csharp` because the one subject in the tree happened to want those three.

  `:targets` is the load-bearing addition, and item 3 of the redesign is
  entirely downstream of it: a source's effective target set is the
  INTERSECTION of what its required vocabularies can speak, which is a
  computation that cannot be done at all while a vocabulary is a bag of maps.

  A TAG still carries data rather than being a name. `^Stack` can hold the
  type each target spells it as AND a dispatch table saying what `(push it x)`
  becomes -- which is the compile-time protocol, and the reason tags are
  values.

  Nothing requires this constructor: a literal map with the same keys is a
  vocabulary. `check-vocabulary` is what a loader calls, and it takes either."
  [& {:keys [namespace targets tags forms names]}]
  (check-vocabulary {:namespace namespace
                     :targets (set targets)
                     :tags (or tags {})
                     :forms (or forms {})
                     :names (or names {})}))

(defn vocabulary-targets
  "What every one of `vocabs` can speak, as a map of name -> target set."
  [vocabs]
  (reduce-kv (fn [m k v] (assoc m k (:targets v))) {} vocabs))
