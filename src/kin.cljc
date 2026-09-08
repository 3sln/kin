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

;; ------------------------------------------------------------------ nodes
;;
;; A NODE is a piece of output that is not text yet.
;;
;; There are two kinds and they are the same thing at different strengths:
;;
;;   an ANCHOR   a place in the output that has already gone past. Emitted
;;               into later, resolved when the buffer is joined. No
;;               dependencies -- it is settled the moment it exists, and only
;;               its CONTENT arrives late.
;;   a PROMISE   a computation that cannot run yet because something it needs
;;               is not defined. Carries a DEPENDENCY SET, is retried at join
;;               until it settles, and is an ERROR if it never does.
;;
;; An anchor is the degenerate promise: no dependencies, nothing to wait for.
;; They are one mechanism rather than two because kin carrying two deferral
;; systems is how the second one drifts from the first.
;;
;; `before!` came first and could only reach ONE level up -- before the
;; statement being built. That is enough for hoisting a temporary and enough
;; for nothing else: a loop-invariant binding wants to go before the LOOP, a
;; scratch declaration wants the top of the FUNCTION, and neither is one level
;; up.

(defn- new-sink [] (atom []))

(defn node?
  "Is `x` a deferred node -- an anchor or a promise?"
  [x]
  (and (map? x) (contains? x :kin/node)))

(defn anchor
  "A fresh anchor -- a place to emit into, resolved when the output is joined.

  A promise with no dependencies: it is settled already and only its content
  is late."
  []
  {:kin/node {:kind :anchor :sink (new-sink)}})

(defn anchor? [x] (and (node? x) (= :anchor (:kind (:kin/node x)))))

(defn emit-anchor!
  "Drop an anchor HERE and return it. Whatever is emitted against it later
  appears at this point in the output."
  [ctx]
  (let [a (anchor)]
    (swap! (:out ctx) conj a)
    a))

(defn promise-node
  "A node that cannot produce its text yet.

  `deps` is DATA -- `{:forms [...] :tags [...]}` -- and that is the whole
  reason a good error is possible: an unsettled node can say what it was
  waiting for. `thunk` is `(fn [] text)`, retried at join until `ready?`
  answers true.

  `origin` is provenance and is not optional in practice. Eager emission
  fails where the form is; a promise fails at JOIN, far from the cause, so
  without the source form and label in hand the error degrades to `something
  did not settle`, which is precisely the useless kind this project keeps
  removing."
  [{:keys [deps ready? thunk origin]}]
  {:kin/node {:kind :promise
              :deps deps
              :ready? ready?
              :thunk thunk
              :origin origin
              :settled (atom nil)}})

(defn promise-node? [x] (and (node? x) (= :promise (:kind (:kin/node x)))))

(defn- settle!
  "Try to settle one promise. True if it is settled now."
  [n]
  (let [{:keys [ready? thunk settled]} (:kin/node n)]
    (cond
      (some? @settled) true
      (ready?) (do (reset! settled (thunk)) true)
      :else false)))

;; A TOKEN is where a deferred node's text will go.
;;
;; `render` returns a String and every form composes with `str`, kin's
;; vocabularies and the user's alike. A deferred sub-expression therefore
;; cannot return a fragment object without breaking every one of those call
;; sites -- so it returns a token, and the token is substituted for the
;; settled text when the buffer is joined. That is the same trick an anchor
;; plays, one level down: an anchor is a hole in the SINK, a token is a hole
;; in the TEXT.
;;
;; The character is NUL-delimited because it must not occur in any source or
;; any generated language, and a token that could be typed would be a token
;; that could be forged.

(defn- token [i] (str "\u0000kin" i "\u0000"))

(def ^:private token-pattern #"\u0000kin(\d+)\u0000")

(defn settle-all!
  "Settle every promise in `registry`, to a FIXPOINT.

  Repeated because settling one promise can define what another was waiting
  for. It stops when a pass settles nothing, which is either `everything is
  done` or `what is left can never be done` -- and those are the same
  observable state, told apart by the dependency sets rather than by the
  loop."
  [registry]
  (loop []
    (let [pending (remove #(some? @(:settled (:kin/node %))) registry)
          progress (reduce (fn [p n] (if (settle! n) true p)) false pending)]
      (when progress (recur))))
  registry)

(defn unsettled
  "Every promise that never settled, with its provenance and what it wanted.

  ONE SCAN, TWO FAILURES. A reference to something never defined and a true
  cycle where two nodes wait on each other are the same observable thing --
  unsettled at join. The dependency sets say which: a node nothing else is
  waiting on was never defined, and nodes waiting on each other are a cycle."
  [registry]
  (vec (for [n registry :when (nil? @(:settled (:kin/node n)))]
         (select-keys (:kin/node n) [:deps :origin]))))

(defn- substitute
  "Replace every token in `text` with what its promise settled to.

  Repeated, because a settled promise's own text may contain tokens -- a
  deferred call inside a deferred call. Bounded by the number of promises,
  so a run that cannot terminate is a bug rather than a hang."
  [text registry]
  (loop [text text n 0]
    (if (or (> n (inc (count registry))) (not (re-find token-pattern text)))
      text
      (recur (str/replace text token-pattern
                          (fn [[_ i]]
                            (or @(:settled (:kin/node (nth registry (parse-long i))))
                                "")))
             (inc n)))))

(defn resolve-sink
  "A sink's items as one string, ANCHORS resolved in place and recursively --
  an anchor may itself contain anchors, which is what makes them nest.

  Promises are not here: a promise leaves a TOKEN in the text and settles
  into the registry, because it may sit inside an expression that has already
  been flattened to a String by the time this runs."
  [items]
  (str/join (mapv (fn [i]
                    (if (node? i)
                      (resolve-sink (deref (:sink (:kin/node i))))
                      i))
                  items)))

;; ------------------------------------------------------- context protocols
;;
;; A FORM HAS TWO CONCERNS: how to USE the thing it produces, and how to
;; PRODUCE it. They run in different passes, and the passes have different
;; powers -- so they are handed different CONTEXT TYPES rather than the same
;; map with a rule attached.
;;
;;     Scoped      -scoped, -scope-get, -scope-all      BOTH
;;     Declaring   -declaring?                          link pass only
;;     Emitting    -emitting?                           generate pass only
;;
;; Enforcement by construction is the point. A `:generate` function that
;; registers a declaration would quietly reintroduce the thing this split
;; removes -- where knowing how to USE a namespace requires having EMITTED
;; it. A convention drifts; a protocol does not.
;;
;; THE SCOPE STACK IS SHARED, deliberately. `:wrap` runs in both passes: the
;; link pass needs to know it is inside a class to produce `Maps.mergeTwo`,
;; and generate needs the same frame to indent. Two copies of that logic
;; could disagree, which is the failure this codebase keeps finding. Sharing
;; it also buys a checkable invariant -- `:wrap` may use ONLY the scope
;; protocol, because it is handed whichever context the current pass uses and
;; anything else fails against one of them.

(defprotocol Scoped
  "What both passes can do: ask what encloses them."
  (-scope [this] "The scope map."))

(defprotocol Declaring
  "The LINK pass: resolve references and register definitions."
  (-declaring? [this]))

(defprotocol Emitting
  "The GENERATE pass: produce text."
  (-emitting? [this]))

(defprotocol Linked
  "THE HANDOFF CHANNEL between the two passes.

  Link writes, generate reads: per form, how the reference in its head should
  be emitted. It is a third thing both context types carry, beside the scope
  stack and their own capability, because it is the whole point of having two
  passes -- resolve, then emit what was resolved."
  (-resolutions [this]))

(defn resolution-store
  "Where link records what it resolved, keyed by form IDENTITY.

  IDENTITY, NOT VALUE, and this is the trap in the whole design. Two
  `(return x)` forms in different functions are `=`, so a Clojure map would
  merge them and hand one function's resolution to the other -- silently, and
  producing code that compiles. The reader is the same object each time it is
  walked, so identity is exactly the right key."
  []
  #?(:clj (java.util.IdentityHashMap.) :default (atom {})))

(defn record-resolution!
  "Link: this form's head resolves this way."
  [ctx form res]
  (when-let [store (and (satisfies? Linked ctx) (-resolutions ctx))]
    #?(:clj (.put ^java.util.IdentityHashMap store form res)
       :default (swap! store assoc form res)))
  nil)

(defn resolution
  "Generate: how did link say to emit this form's head?"
  [ctx form]
  (when-let [store (and (satisfies? Linked ctx) (-resolutions ctx))]
    #?(:clj (.get ^java.util.IdentityHashMap store form)
       :default (clojure.core/get @store form))))

(defrecord GenContext [driver target out pre promises scope indent resolutions]
  Scoped (-scope [_] scope)
  Emitting (-emitting? [_] true)
  Linked (-resolutions [_] resolutions))

(defrecord LinkContext [driver target scope indent resolutions]
  Scoped (-scope [_] scope)
  Declaring (-declaring? [_] true)
  Linked (-resolutions [_] resolutions))

(defn- require-capability!
  [ctx protocol what]
  (when-not (satisfies? protocol ctx)
    (throw (ex-info
            (str "kin: this context cannot " what
                 ". A `:generate` function may emit but not declare, and a"
                 " `:declare` function may declare but not emit -- the two"
                 " passes are different context TYPES so that the mistake is"
                 " impossible rather than merely discouraged.")
            {:wanted what :context (type ctx)})))
  ctx)

(defn context
  "A fresh GENERATE context for `target`."
  [driver target]
  (map->GenContext
   {:driver driver
   :target target
   :out (new-sink)
   ;; Statements to place BEFORE the one being built. A form that needs a
   ;; temporary writes here and the statement layer flushes it.
   :pre (new-sink)
   ;; Every promise made while this context is alive. One registry per
   ;; emission, because a promise may be created deep inside an expression
   ;; whose text has already been joined -- there is no tree left to find it
   ;; in by then.
   :promises (atom [])
   :scope {}
   :indent 0
   ;; Filled by the LINK pass and read here. Empty when nothing linked, in
   ;; which case resolution falls back to asking directly -- which is what a
   ;; sub-render does.
   :resolutions (resolution-store)}))

(defn scoped
  "Call `f` with `ctx` extended by one scoped entry.

  `{:key :class :value {...}}`, and `:indent` if the scope indents. Scoped
  rather than global because a form's implementation asks what encloses it --
  which class, which package, how deep -- and that is a stack, not a variable.

  IT USED TO BE A VARIABLE, in spite of that sentence. `assoc-in` meant a
  nested frame with the same key OVERWROTE the outer one, so an inner class
  did not shadow its parent -- it erased it. The docstring stated the intent
  and the code implemented the opposite; it never bit because nothing had yet
  nested two frames of one key, and nested classes are exactly the case that
  would. Each key now holds a vector, outermost first."
  [ctx entry f]
  (f (-> ctx
         (update-in [:scope (:key entry)] (fnil conj []) (:value entry))
         (update :indent + (or (:indent entry) 0)))))

(defn get
  "The INNERMOST scoped entry for `k`, or nil.

  Unchanged in meaning for every caller: `get` answered the one frame there
  was, and now answers the nearest of several."
  [ctx k]
  (peek (clojure.core/get (:scope ctx) k)))

(defn get-all
  "The whole chain of scoped entries for `k`, OUTERMOST FIRST.

  One frame cannot answer `which class am I in, what encloses that, is the
  thing I want in an ancestor or a sibling` -- an inner class referencing its
  parent needs the chain. `get` is the last element of this."
  [ctx k]
  (clojure.core/get (:scope ctx) k []))

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
  (swap! (if (node? target) (:sink (:kin/node target)) (:out target))
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
  `kin.project/source-origins` reports it, so the effect is visible rather
  than inferred. An override nobody can see is indistinguishable from a bug,
  which is how the last-wins behaviour survived this long."
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

  Written by `require-scope` and read by `kin.project/source-origins`. Empty
  for a source that requires one vocabulary, which is every source in the tree
  this was built against -- so first-wins changed nothing there, and the check that says so
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

(defn spell-name
  "What a registered NAME comes out as HERE, or nil if there is no entry.

  TWO SHAPES, and the plain one is the common one.

  A MAP of per-target strings -- `{:rust \"TY_STR\" :java \"TY_STR\" :csharp
  \"TyStr\"}` -- says `spell it this way, and there is nothing to link`. That
  is the truth for every name each target declares locally: a type tag out of
  a hand-written header is in scope wherever the header is, and a reference to
  it owes nobody anything.

  A FUNCTION `(fn [ctx] -> String)` says `ask me, at the reference`. It is
  handed the context the reference is being emitted in, so it can see which
  unit is open, spell itself bare or qualified accordingly, and `need!` an
  import on the way past -- which is what a name a MODULE declares has to do
  and what a string could not do at all. A `defconst` in another namespace was
  emitted as a bare word with no import, and compiled only where something
  ELSE happened to derive the import by scanning the sources.

  kin CALLS IT AND CARRIES THE ANSWER. What it decides -- qualified or bare,
  and what it registers -- is the declaring vocabulary's business, the same
  way a form's callable is."
  [ctx entry]
  (cond
    (nil? entry) nil
    (fn? entry) (entry ctx)
    :else (clojure.core/get entry (:target ctx))))

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
      (spell-name ctx (get-in ctx [:vocabs vname :names k])))))

(def ^:private registries
  "Where each KIND of definition is kept, locally and for export.

  Three kinds, and the split is load-bearing at both ends already:
  `require-scope` concats a vocabulary's `:forms`, `:tags` and `:names` when
  it builds a scope, and a tag can appear where a call cannot -- a parameter
  list, a return position, a `case` label."
  {:form {:local :locals  :export :forms}
   :tag  {:local :local-tags :export :tags}
   :name {:local :names   :export :names}})

(defn define!
  "Record a definition of `kind` under `sym`, at the visibility `opts` asks.

      (kin/define-form! ctx {:scope :public}  'merge-two f)
      (kin/define-tag!  ctx {:scope :private} 'Node      tag)
      (kin/define-name! ctx {:scope :public}  'CN_BASE   spellings)

  ONE FUNCTION PER KIND, with visibility as an option rather than a second
  set of functions per visibility -- so it does not double if a third scope
  ever exists, and the local/export symmetry is structural rather than a
  convention two APIs happen to share.

  `:public` MEANS LOCAL AND EXPORTED, not exported instead of local. A
  definition marked public is obviously still callable from its own file, so
  registration is CUMULATIVE and `:scope` is a MAXIMUM VISIBILITY rather than
  a destination. The exclusive reading is an easy thing to implement by
  accident and it fails in the one direction nothing notices: the source
  itself still generates, and only a caller in the same file breaks.

  WHAT A DEFINITION IS IS THE TARGET'S BUSINESS. `self.merge_two(..)` against
  `Maps.mergeTwo(..)` is a call shape kin has no basis for choosing, so the
  target's `defn` builds the value and calls this. kin carries the registries,
  guarantees exports are visible before any dependent generates, and shapes
  nothing."
  [ctx kind {:keys [scope] :or {scope :private}} sym v]
  (let [{:keys [local export]} (clojure.core/get registries kind)]
    (when-not local
      (throw (ex-info (str "kin: there is no definition kind " (pr-str kind))
                      {:kind kind :known (vec (keys registries))})))
    ;; LOCAL ALWAYS. A public definition is a local one that is also exported.
    ;;
    ;; A DEFINITION FILLS A PLACEHOLDER RATHER THAN REPLACING IT. Anything
    ;; that already took a reference to the declared name -- a body emitted
    ;; between the `declare-` and the `define-` -- holds the indirection, so
    ;; filling the atom is what makes that reference work.
    (when-let [a (clojure.core/get ctx local)]
      (if-let [pending (:kin/pending (meta (clojure.core/get @a sym)))]
        (reset! pending v)
        (swap! a assoc sym v)))
    (when (= :public scope)
      (when-let [a (:exports ctx)] (swap! a assoc-in [export sym] v)))
    nil))

(defn- placeholder
  "An indirecting stand-in for a definition that has not arrived yet.

  Exactly `clojure.core/declare` and exactly its failure mode: the NAME
  resolves, so a body may mention it, and using it before the definition
  lands throws saying so. `filled` is the atom the real value is put into."
  [kind sym filled]
  (fn [& args]
    (if-let [v @filled]
      (apply v args)
      (throw (ex-info
              (str "kin: `" sym "` was declared and used before it was"
                   " defined, in a position that cannot defer.")
              {:symbol sym :kind kind :kin/unfilled true})))))

(defn declare!
  "Reserve `sym` as a forward reference, to be filled by a later `define-`.

  A BARE declare is the case that settles kin's emission model. Given

      (declare foo)
      (defn zip ... (foo x) ...)
      (defn foo ...)

  there is no signature to emit a call from, so `zip` genuinely cannot
  produce text until `foo` is defined. A placeholder that emitted something
  provisional would be guessing; one that threw would refuse a legal program.
  So the reference DEFERS: `with` turns it into a promise waiting on `foo`,
  and the promise settles the moment the definition lands.

  Two mutually recursive functions in ONE namespace need this, the same way
  Clojure does. ACROSS namespaces it is neither needed nor available --
  namespace dependencies form a DAG, so everything a namespace requires is
  already emitted in full by the time it starts, and the only unsettled nodes
  at join are local ones."
  [ctx kind opts sym]
  (let [filled (atom nil)]
    (define! ctx kind opts sym (with-meta (placeholder kind sym filled)
                                 {:kin/pending filled}))
    nil))

(defn declare-form! [ctx opts sym] (declare! ctx :form opts sym))
(defn declare-tag! [ctx opts sym] (declare! ctx :tag opts sym))

(defn define-form!
  "Define `sym` as CALLABLE -- `(merge-two rt ...)`.

  A source has to be able to define a helper and then call it. Without this,
  every helper would have to live in a vocabulary -- and a helper in a
  vocabulary is a helper written once per target, which is the cost this whole
  exercise exists to remove.

  File-local definitions come second in resolution: a vocabulary name still
  wins, so a file cannot quietly redefine `let` out from under the reader.
  They are also ordered -- a call before the `defn` that defines it is not in
  scope, the same rule C and Rust modules differ on and the stricter of the
  two."
  [ctx opts sym f]
  (define! ctx :form opts sym f))

(defn define-tag!
  "Define `sym` as a TAG -- `^Value`, `^RootIx`, and the tag of a parameter,
  a binding or a loop variable."
  [ctx opts sym tag]
  (define! ctx :tag opts sym tag))

(defn define-name!
  "Define how `sym` is SPELLED in each target, for a name whose convention is
  not the local one.

  Constants are the case that forced it. Rust and Java scream (`HASH_TRUE`)
  and C# pascalises (`HashTrue`), so a reference in the body cannot be
  rendered by a single rule, and the definition is the only place that knows.
  A name not defined here falls back to `local-name`, which is right for
  everything else."
  [ctx opts sym spellings]
  (define! ctx :name opts sym spellings))

(defn define!
  "Record a definition of `kind` under `sym`, at the visibility `opts` asks.

      (kin/define-form! ctx {:scope :public}  'merge-two f)
      (kin/define-tag!  ctx {:scope :private} 'Node      tag)
      (kin/define-name! ctx {:scope :public}  'CN_BASE   spellings)

  ONE FUNCTION PER KIND, with visibility as an option rather than a second
  set of functions per visibility -- so it does not double if a third scope
  ever exists, and the local/export symmetry is structural rather than a
  convention two APIs happen to share.

  `:public` MEANS LOCAL AND EXPORTED, not exported instead of local. A
  definition marked public is obviously still callable from its own file, so
  registration is CUMULATIVE and `:scope` is a MAXIMUM VISIBILITY rather than
  a destination. The exclusive reading is an easy thing to implement by
  accident and it fails in the one direction nothing notices: the source
  itself still generates, and only a caller in the same file breaks.

  WHAT A DEFINITION IS IS THE TARGET'S BUSINESS. `self.merge_two(..)` against
  `Maps.mergeTwo(..)` is a call shape kin has no basis for choosing, so the
  target's `defn` builds the value and calls this. kin carries the registries,
  guarantees exports are visible before any dependent generates, and shapes
  nothing."
  [ctx kind {:keys [scope] :or {scope :private}} sym v]
  (let [{:keys [local export]} (clojure.core/get registries kind)]
    (when-not local
      (throw (ex-info (str "kin: there is no definition kind " (pr-str kind))
                      {:kind kind :known (vec (keys registries))})))
    ;; LOCAL ALWAYS. A public definition is a local one that is also exported.
    ;;
    ;; A DEFINITION FILLS A PLACEHOLDER RATHER THAN REPLACING IT. Anything
    ;; that already took a reference to the declared name -- a body emitted
    ;; between the `declare-` and the `define-` -- holds the indirection, so
    ;; filling the atom is what makes that reference work.
    (when-let [a (clojure.core/get ctx local)]
      (if-let [pending (:kin/pending (meta (clojure.core/get @a sym)))]
        (reset! pending v)
        (swap! a assoc sym v)))
    (when (= :public scope)
      (when-let [a (:exports ctx)] (swap! a assoc-in [export sym] v)))
    nil))

(defn define-form!
  "Define `sym` as CALLABLE -- `(merge-two rt ...)`.

  A source has to be able to define a helper and then call it. Without this,
  every helper would have to live in a vocabulary -- and a helper in a
  vocabulary is a helper written once per target, which is the cost this whole
  exercise exists to remove.

  File-local definitions come second in resolution: a vocabulary name still
  wins, so a file cannot quietly redefine `let` out from under the reader.
  They are also ordered -- a call before the `defn` that defines it is not in
  scope, the same rule C and Rust modules differ on and the stricter of the
  two."
  [ctx opts sym f]
  (define! ctx :form opts sym f))

(defn define-tag!
  "Define `sym` as a TAG -- `^Value`, `^RootIx`, and the tag of a parameter,
  a binding or a loop variable."
  [ctx opts sym tag]
  (define! ctx :tag opts sym tag))

(defn define-name!
  "Define how `sym` is SPELLED in each target, for a name whose convention is
  not the local one.

  Constants are the case that forced it. Rust and Java scream (`HASH_TRUE`)
  and C# pascalises (`HashTrue`), so a reference in the body cannot be
  rendered by a single rule, and the definition is the only place that knows.
  A name not defined here falls back to `local-name`, which is right for
  everything else."
  [ctx opts sym spellings]
  (define! ctx :name opts sym spellings))

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

(defn available?
  "Are all the forms and tags in `deps` resolvable right now?

  `{:forms [...] :tags [...]}` -- the same data a promise carries, asked of
  the context. A form is available when it resolves AND is not still an
  unfilled `declare-` placeholder; a name that resolves to a stand-in is a
  name whose content is not there yet, which is the whole distinction."
  [ctx deps]
  (and (every? (fn [sym]
                 (let [f (form-fn ctx sym)
                       pending (:kin/pending (meta f))]
                   ;; Resolvable AND actually defined. A `declare-`d name
                   ;; resolves from the moment it is declared -- that is the
                   ;; point -- so `resolves` is not the question; `has its
                   ;; content yet` is.
                   (and f (or (nil? pending) (some? @pending)))))
               (:forms deps))
       (every? (fn [sym] (some? (tag ctx sym))) (:tags deps))))

(defn with
  "Run `f` when everything in `deps` is available, deferring if it is not.

      (kin/with ctx {:forms ['foo] :tags ['Node]}
        (fn [{:keys [forms tags]}] ...))

  `f` receives the resolved values, keyed the way the request was, and
  returns TEXT.

  IT RUNS IMMEDIATELY WHEN IT CAN, which is the overwhelmingly common case
  and is byte-for-byte the path kin took before promises existed. Only a
  genuine forward reference defers, so a project with none pays nothing and
  cannot change -- which is what makes this safe to put underneath an
  existing tree.

  When it defers it answers a TOKEN and registers a promise. The token flows
  through `str` like any other text, and is replaced by the settled text when
  the buffer is joined."
  [ctx deps f]
  (let [resolve-deps (fn []
                       {:forms (into {} (map (fn [s] [s (form-fn ctx s)])) (:forms deps))
                        :tags (into {} (map (fn [s] [s (tag ctx s)])) (:tags deps))})]
    (if (available? ctx deps)
      (f (resolve-deps))
      (let [reg (:promises ctx)
            n (promise-node
               {:deps deps
                :ready? (fn [] (available? ctx deps))
                :thunk (fn [] (f (resolve-deps)))
                :origin {:label (:kin/label ctx)
                         :form (:kin/form ctx)
                         :provides (:kin/provides ctx)}})
            i (count @reg)]
        (swap! reg conj n)
        (token i)))))

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
                (update-in [:scope :position] (fnil conj []) :expression))
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
                (update-in [:scope :kin/stmt-anchor] (fnil conj []) a)
                (update-in [:scope :position] (fnil conj []) position))]
    (dispatch sub form)
    (emit! ctx (resolve-sink (deref (:out sub))))))

(defn statement!
  "Render `form` as a statement -- the common call, kept short."
  [ctx form]
  (in ctx :statement form))

(defn form-slots
  "A form implementation, normalised to its three slots.

      {:wrap f :declare f :generate f}

  A BARE FUNCTION MEANS `:generate`, and that default is right because most
  forms have neither of the others: `let`, `if`, `while` and every operator
  are generate-only. The bare function stays the common case and the map is
  the exception.

  A map rather than metadata, for the reason vocabularies became maps: things
  kin must CHECK should be visible to it. kin can say `this entry has a
  :declare that is not a function`; it cannot say that about metadata nobody
  looked at."
  [f]
  (cond
    (map? f) f
    (fn? f) {:generate f}
    :else (throw (ex-info
                  (str "kin: a form implementation must be a function or a map"
                       " of {:wrap :declare :generate}, not "
                       (pr-str (type f)))
                  {:implementation f}))))

(defn- run-slot
  "Run one slot of a form, inside its `:wrap` if it has one.

  `:wrap` is handed WHICHEVER CONTEXT the current pass uses, which is what
  makes the invariant enforce itself: a `:wrap` that tried to emit would fail
  against the declare context, so it can only use the scope protocol."
  [slots slot ctx form]
  (let [run (fn [c] (when-let [f (clojure.core/get slots slot)] (f c form)))]
    (if-let [w (:wrap slots)]
      (w ctx (fn [inner] (run inner)))
      (run ctx))))

;; ------------------------------------------------------------------- link
;;
;; RESOLUTION IS POSITIONAL, so it needs an ordered walk.
;;
;; "Locals win once declared" is not a precedence rule that a static lookup
;; can answer -- it is a question about a POINT in the file: is this declared
;; by HERE? Only a phase walking in order knows, so the declare phase becomes
;; a LINK phase: it walks everything, bodies included, resolves every
;; reference, and RECORDS how each should be emitted. Generate then emits what
;; link decided rather than deciding again, which is the two-pass shape every
;; compiler has -- resolve, then emit.
;;
;; Only the EXPORT half can stop at a head. A reference lives INSIDE a body,
;; so link has to go in.

(defn- resolve-reference
  "How should `head` be emitted, at THIS point in the walk?

  Locals first, because a local that has been declared by here is what the
  reader means. That is the reversal the link phase exists to make possible:
  the old rule asked the require scope first, which was right when a require
  could only bring in `let` and `if`, and wrong once it can bring in another
  module's function names."
  [ctx head]
  (cond
    (clojure.core/get (some-> (:locals ctx) deref) head)
    {:kind :local :sym head}

    (clojure.core/get (:scope-syms ctx) head)
    (let [[vname k] (clojure.core/get (:scope-syms ctx) head)]
      {:kind :vocabulary :namespace vname :sym k})

    :else nil))

(defn implementation-of
  "The implementation a recorded resolution names, looked up HERE.

  Link decides the KIND -- local, or from which vocabulary -- and generate
  looks the value up in its own registries. So the decision crosses the
  handoff channel while the closure stays the one generate built, which is
  what keeps a link-built closure from carrying link's context into generated
  text."
  [ctx res]
  (case (:kind res)
    :local (clojure.core/get (some-> (:locals ctx) deref) (:sym res))
    :vocabulary (get-in ctx [:vocabs (:namespace res) :forms (:sym res)])
    nil))

(defn link!
  "Walk `form` in order, resolving every reference and recording it.

  A form's `:declare` slot runs BEFORE its children are walked, so a `defn`
  is in scope inside its own body and everything after it -- which is what
  makes `is this declared by here?` answerable."
  [ctx form]
  (when (and (seq? form) (symbol? (first form)))
    (let [head (first form)
          res (resolve-reference ctx head)]
      (record-resolution! ctx form res)
      (when-let [impl (form-fn ctx head)]
        (run-slot (form-slots impl) :declare ctx form))))
  (when (coll? form)
    (doseq [x form] (link! ctx x)))
  nil)

(defn dispatch
  "One form. A seq whose head is in scope goes to its implementation.

  A seq whose head is a symbol NOT in scope is an error rather than a literal.
  Falling through would emit the symbol's name and produce something that looks
  like a call and is not one -- which is the failure mode a require scope exists
  to prevent."
  [ctx form]
  (cond
    (and (seq? form) (symbol? (first form)))
    ;; WHAT LINK DECIDED WINS. Generate emits the resolution rather than
    ;; recomputing it, which is the whole point of the two passes: by the time
    ;; anything is emitted the question `what does this name mean here` has
    ;; already been answered, in order, by something that could see the order.
    ;; A form with no recorded resolution -- a sub-render, or a project that
    ;; never linked -- asks directly, exactly as before.
    (if-let [f (or (when-let [res (resolution ctx form)]
                     (implementation-of ctx res))
                   (form-fn ctx (first form)))]
      ;; A DECLARED-BUT-NOT-YET-DEFINED head DEFERS. This is the bare
      ;; `(declare foo)` case: there is no signature to emit a call from, so
      ;; the reference becomes a promise waiting on `foo` and settles the
      ;; moment the definition lands. Anything already defined runs now, which
      ;; is every call in a project that uses no forward references.
      (if (and (:kin/pending (meta f)) (nil? @(:kin/pending (meta f))))
        (emit! ctx (with (assoc ctx :kin/form form) {:forms [(first form)]}
                    (fn [_]
                      (let [sub (assoc ctx :out (new-sink))]
                        ((form-fn ctx (first form)) sub form)
                        (resolve-sink (deref (:out sub)))))))
        (run-slot (form-slots f) :generate ctx form))
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
                    ;; THE FILE'S OWN NAMES, through the same door. A name
                    ;; registered here by a `defconst` in THIS file and one
                    ;; reaching in from another namespace's exports are the
                    ;; same kind of thing, so they are spelled by the same
                    ;; function -- otherwise the linking half works across
                    ;; namespaces and silently does not work within one.
                    (spell-name ctx (clojure.core/get
                                     (some-> (:names ctx) deref) v))
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

(defn- describe-deps [deps]
  (str/join ", " (for [[kind syms] deps :when (seq syms)]
                   (str (name kind) " " (str/join " " (map str syms))))))

(defn output
  "Everything emitted into `ctx`: promises settled, tokens substituted, and an
  ERROR if anything is still waiting. What a driver writes.

  THIS IS WHERE DEFERRAL IS PAID FOR. Emission is dependency-ordered rather
  than time-ordered, so a forward reference is legal while the buffer is
  being built and illegal once it is joined -- by then every promise has had
  every chance it will get.

  The message carries provenance because it has to. A promise fails at join,
  far from the form that made it, so an error naming only the failure sends
  the reader to find the cause by hand -- which is the useless kind of error
  this project keeps removing."
  [ctx]
  (let [registry @(:promises ctx)
        _ (settle-all! registry)
        stuck (unsettled registry)]
    (when (seq stuck)
      (let [wanted (into #{} (mapcat (fn [u] (mapcat val (:deps u)))) stuck)
            ;; A node NOTHING ELSE DEFINES was never defined; nodes waiting
            ;; on each other are a cycle. The same observable state, told
            ;; apart by the dependency data rather than by how it was reached.
            provided (into #{} (mapcat (fn [u] (:provides (:origin u)))) stuck)
            cyclic (clojure.set/intersection wanted provided)]
        (throw (ex-info
                (str "kin: " (count stuck)
                     (if (= 1 (count stuck)) " reference never settled"
                         " references never settled")
                     ".\n"
                     (str/join "\n"
                               (for [u stuck]
                                 (str "  "
                                      (when-let [l (:label (:origin u))] (str l ": "))
                                      (pr-str (:form (:origin u)))
                                      "\n      waiting on "
                                      (str/join ", "
                                                (for [[kind syms] (:deps u) :when (seq syms)]
                                                  (str (name kind) " "
                                                       (str/join " " (map str syms))))))))
                     (if (seq cyclic)
                       (str "\n  These wait on each other: "
                            (str/join " " (map str (sort-by str cyclic)))
                            " -- a cycle, not a missing definition.")
                       "\n  Nothing defines what they wait on."))
                {:unsettled stuck :waiting-on wanted :cyclic cyclic}))))
    (substitute (resolve-sink (deref (:out ctx))) registry)))

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
      ;;
      ;; A FUNCTION ANSWERS FOR EVERY TARGET BY CONSTRUCTION -- it is asked at
      ;; the reference, where the target is known -- so there is nothing here
      ;; to check. This is the same exemption forms have and for the same
      ;; reason: what cannot be read as data is checked when it is asked.
      (doseq [[sym spellings] (:names v) t targets
              :when (not (fn? spellings))]
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
