(ns kin.project
  "kin as a LIBRARY: what a project asks it to do, as functions on values.

  This used to be a 503-line `bin/kin` script, and nearly all of it was
  library logic sitting in a command-line tool. Only two things in that file
  were genuinely a CLI -- reading `kin.edn` off disk and dispatching
  `*command-line-args*` -- and both belong to the consumer, whose tree is
  where the script should live.

  ## Three layers, and they are different in kind

      generate    PURE. Source text in, `{target text}` out. Opens nothing,
                  so it runs anywhere a reader does.
      emit!       Needs a VFS. Writes each namespace's module -- creating
                  it, since kin owns the file entirely -- and every byte of
                  that goes through the implementation the user put on the
                  target.
      verify      Needs a MACHINE. Compiling with rustc and running the
                  result is process execution, which no vfs abstracts. It is
                  not here and never should have been: it belongs to the
                  consumer's tree.

  Saying that out loud is the point. A library that quietly shells out is a
  library you cannot use from a browser, a test, or a build that has no
  toolchain -- and kin was babashka-only in spite of its `.cljc` extension,
  because the emit path reached for `java.io.File` directly.

  ## A PROJECT is a value

      {:vocabularies {'my.vocab {...}}       ; name -> vocabulary
       :targets      {:rust {...}}           ; key  -> target descriptor
       :target-order [:rust :java :csharp]   ; how a report lists them
       :sources      {:vfs ... :match `*.kin`}}

  TWO KINDS OF VFS, doing different jobs. The SOURCE vfs scans and reads --
  it is the only one that ever lists. Each TARGET's vfs reads and writes its
  destinations and never lists. Giving the sources one is what turns `why`
  and `targets-report` from commands that glob a real tree into functions
  over a config, which is the only reason they can be tested at all.

  `project` builds one from vocabularies you already have. `load-project`
  is the convenience that resolves them from namespace names, and it is the
  only thing here that needs a host with `require` in it."
  (:require [kin]
            [kin.vfs :as vfs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; --------------------------------------------------------------- a project

(defn project
  "A project value from vocabularies and targets you already hold.

  Pure, and the reason `generate` can be. Nothing here loads a namespace or
  opens a file; a caller that has its vocabularies as values -- a test, a
  browser, a build that already required them -- never touches the loader."
  [{:keys [vocabularies targets target-order sources]}]
  (let [vocabs (into {} (map (fn [v] [(:namespace (kin/check-vocabulary v)) v]))
                     vocabularies)]
    {:vocabularies vocabs
     :targets (or targets {})
     :target-order (vec (or target-order (keys targets)))
     :sources sources}))

#?(:clj
   (defn load-vocabulary
     "Require `nsym` and read the vocabulary it declares.

     The var may hold the map or a function returning one; a subject that
     builds its forms by merging wants the latter, and both are the same
     value by the time anything asks."
     [nsym]
     (require nsym)
     (let [var (or (resolve (symbol (str nsym) "vocabulary"))
                   (throw (ex-info
                           (str "kin: " nsym " declares no `vocabulary`. A"
                                " vocabulary is a var holding a map -- see"
                                " `kin/vocabulary` -- so that it can say which"
                                " targets it speaks.")
                           {:namespace nsym})))
           v (if (fn? @var) (@var) @var)]
       (when-not (= nsym (:namespace v))
         (throw (ex-info (str "kin: " nsym "/vocabulary calls itself "
                              (pr-str (:namespace v))
                              " -- a vocabulary's `:namespace` is how a source"
                              " requires it, so the two have to agree")
                         {:namespace nsym :declared (:namespace v)})))
       (kin/check-vocabulary v))))

#?(:clj
   (defn load-project
     "A project from namespace NAMES, resolving each with `require`.

     The one function here that needs a host with `require` in it, kept apart
     from `project` for that reason."
     [{:keys [vocabularies targets target-order sources]}]
     (project {:vocabularies (mapv load-vocabulary vocabularies)
               :targets targets
               :target-order target-order
               :sources sources})))

;; ------------------------------------------------------------- the sources
;;
;; SCANNING IS THE LIBRARY'S JOB NOW. It used to live in the consumer's shell
;; -- `for src in kin/*.kin` appeared in flint's check-kin and in three
;; wrapper scripts -- so every consumer reimplemented the same three lines and
;; got to reimplement the ordering and the error handling with them.

(defn source-label
  "What to CALL the source at vfs path `p`.

  The source vfs is rooted at the source directory, so it answers
  `champ.kin`; the marker in every host file says `kin/champ.kin`, because
  that convention predates the vfs and is written into three runtimes. A
  `:label` on `:sources` bridges the two, and defaults to identity for a
  project with no such history."
  [prj p]
  (if-let [f (:label (:sources prj))] (f p) p))

(defn source-path
  "The vfs path for a label -- `source-label` backwards.

  A project that labels its sources has to be able to take one back, because
  a person on a command line types the label they see in the marker."
  [prj label]
  (if-let [f (:unlabel (:sources prj))] (f label) label))

(defn source-text
  "One source's text, read through the source vfs. Takes a LABEL."
  [prj label]
  (vfs/-read (:vfs (:sources prj)) (source-path prj label)))

(defn source-labels
  "Every source in the project, sorted.

  Sorted rather than in the vfs's order, because a listing's order is the
  filesystem's and a report that reorders between two runs of an unchanged
  tree is much harder to read than one that does not."
  [prj]
  (let [{:keys [vfs match]} (:sources prj)]
    (when-not vfs
      (throw (ex-info (str "kin: this project has no `:sources` vfs, so it"
                           " cannot be asked what its sources are. Give it"
                           " `{:sources {:vfs ... :match \"*.kin\"}}`.")
                      {})))
    (mapv (partial source-label prj) (vfs/listing vfs match))))

(defn sources
  "`{label text}` for every source, in sorted order."
  [prj]
  (into (sorted-map) (map (fn [l] [l (source-text prj l)])) (source-labels prj)))

;; ------------------------------------------------------------- exports
;;
;; A KIN NAMESPACE EXPORTS, so requiring one is enough.
;;
;; Without this, a function generated in one source and called from another
;; has to be re-declared in a vocabulary with its per-target spelling -- one
;; definition kept in two places, which is the drift this project keeps
;; finding bugs in. `define-form!` already makes the same argument one scope
;; down, for calls within a file.
;;
;; ORDERING: KIN FOLLOWS CLOJURE. Namespace dependencies form a DAG, so there
;; is a topological order, and emitting in it means every namespace's exports
;; are complete before any dependent starts. B is emitted in FULL -- bodies
;; and all -- before A begins, so A's calls resolve against finished exports.
;;
;; That is one pass, and it dissolves a problem rather than surviving it.
;; Three earlier designs -- a tolerant collecting pass, a scan flag on the
;; context, deferred resolution of cross-namespace calls -- existed only to
;; survive cycles, and each traded away the strictness that has caught real
;; bugs here. Resolution stays STRICT: an unresolved symbol is an error where
;; it is read. Nothing is derived from a signature, and there is no mode in
;; which a symbol may quietly resolve to nothing.
;;
;; Within a namespace, `declare-form!` and `declare-tag!` allow a forward
;; reference the same way `clojure.core/declare` does, with the same failure.

(defn parse-source
  "A source's `ns` form and top-level forms, without a require scope.

  Used only to build the dependency graph -- which namespace requires which
  -- before anything is emitted. It reads the `ns` form and nothing else."
  [text]
  (let [all (edn/read-string {:readers {}} (str "[" text "]"))]
    {:ns (first (filter #(and (seq? %) (= 'ns (first %))) all))}))

(defn- requires-of
  "The namespaces a source requires, in order."
  [ns-form]
  (mapv first (kin/require-specs ns-form)))

(defn require-order
  "Every source label, in an order where a namespace comes after everything it
  requires. Refuses a cycle, naming the loop.

  Only KIN namespaces constrain the order: a hand-written vocabulary is a
  value that exists before anything is emitted and can never be part of a
  cycle."
  [labelled]
  (let [by-ns (into {} (for [[label {:keys [ns]}] labelled :when ns] [(second ns) label]))
        deps (into {} (for [[label {:keys [ns]}] labelled :when ns]
                        [label (filterv some? (map by-ns (requires-of ns)))]))
        order (atom [])
        state (atom {})]
    (letfn [(visit [label path]
              (case (clojure.core/get @state label)
                :done nil
                :open (let [loop-part (conj (vec (drop-while #(not= % label) path)) label)]
                        (throw (ex-info
                                (str "kin: these namespaces require each other in a"
                                     " loop -- "
                                     (str/join " -> " (map #(str (second (:ns (clojure.core/get labelled %))))
                                                           loop-part))
                                     ". kin follows Clojure: namespace dependencies form"
                                     " a DAG. Two namespaces that call each other are"
                                     " two halves of one thing and belong in one"
                                     " namespace.")
                                {:cycle (mapv #(second (:ns (clojure.core/get labelled %))) loop-part)
                                 :labels loop-part})))
                (do (swap! state assoc label :open)
                    (doseq [d (clojure.core/get deps label)] (visit d (conj path label)))
                    (swap! state assoc label :done)
                    (swap! order conj label))))]
      (doseq [label (sort (keys labelled))] (visit label [])))
    @order))

(defn- export-vocabulary
  "One kin namespace's exports, AS A VOCABULARY.

  This is the whole trick and it is why `require-scope` needs no change:
  requiring `runtime.merge` goes down the identical path as requiring
  `flint.impl.rt`, so `:refer`, aliases, first-match-wins and `why`'s
  attribution all work already. If this ever needs a second resolution path,
  something has gone wrong."
  [ns-name kinds]
  (let [targets-of (fn [by-sym] (set (mapcat keys (vals by-sym))))
        ;; A FORM dispatches on the target at CALL time, so an export built
        ;; while generating one target is never used by another.
        forms (into {}
                    (for [[sym by-target] (clojure.core/get kinds :forms)]
                      [sym (fn [ctx form]
                             (if-let [f (clojure.core/get by-target (:target ctx))]
                               (f ctx form)
                               (throw (ex-info
                                       (str "kin: " ns-name "/" sym
                                            " is not exported for " (:target ctx))
                                       {:namespace ns-name :symbol sym
                                        :target (:target ctx)}))))]))
        ;; A TAG and a NAME are DATA, and the same data whichever target was
        ;; being emitted when they were recorded -- a tag carries its own
        ;; per-target `:types`, a name its own per-target spellings.
        plain (fn [k] (into {} (for [[sym by-target] (clojure.core/get kinds k)]
                                 [sym (val (first by-target))])))]
    {:namespace ns-name
     :targets (into (targets-of (clojure.core/get kinds :forms {}))
                    (targets-of (clojure.core/get kinds :tags {})))
     :forms forms
     :tags (plain :tags)
     :names (plain :names)}))

;; ---------------------------------------------------------------- analysis

(defn analyse
  "A source's `ns` form, its forms, its require scope, and its target report.

  Takes TEXT. `label` is what errors call this source -- a path, usually, and
  the marker `emit!` writes against."
  [prj text label]
  (let [vocabs (:vocabularies prj)
        order (:target-order prj)
        all (edn/read-string {:readers {}} (str "[" text "]"))
        ns-form (first (filter #(and (seq? %) (= 'ns (first %))) all))
        forms (vec (remove #(and (seq? %) (= 'ns (first %))) all))
        report (when ns-form (kin/target-report ns-form vocabs))]
    {:label label
     :ns ns-form
     :ns-name (second ns-form)
     :forms forms
     ;; EVERY form, `ns` included and in source order. A target's `:emit` is
     ;; handed this rather than `:forms`, because a function that decides what
     ;; the file looks like needs the declaration that names it.
     :all-forms (vec all)
     :scope (when ns-form (kin/require-scope ns-form vocabs))
     :report report
     ;; What this source generates for HERE: what it asks for, narrowed to
     ;; what the project has a target description for. The two are different
     ;; questions and `why` shows both -- a vocabulary speaking a language the
     ;; project has not configured is not an error, it is a project that has
     ;; not asked for it yet.
     :emit-for (let [want (if report (:targets report) (set order))
                     have (filterv want order)]
                 (when (empty? have)
                   (throw (ex-info
                           (str "kin: " label " generates for "
                                (pr-str (vec (sort-by str want)))
                                " and this project configures "
                                (pr-str order)
                                " -- nothing in common, so there is nothing"
                                " to write.")
                           {:source label :wants want :configured order})))
                 have)}))

(defn whole-file?
  "Does `target` produce a whole FILE rather than a region?

  It does exactly when it has an `:emit`, and the derivation is not a
  shortcut: a target with `:emit` has written its own `package` line and
  wrapper, so splicing that between `kin:begin` and `kin:end` in somebody
  else's file would be nonsense. The two go together because one produces
  what the other consumes.

  The alternative was an explicit second key -- `:whole-file? true` alongside
  `:emit` -- and it was rejected as a knob with exactly one sensible setting,
  which is a thing to get wrong rather than a thing to choose. If a target
  ever wants anchors AND splicing, that is the moment to add the key, with a
  case to point at."
  [target]
  (some? (:emit target)))

(defn- render
  "Render `analysis` for one target, into a string.

  A target's `:emit` -- when it has one -- is handed the context and ALL the
  forms and drives the emission ITSELF. That is the difference between
  wrapping and owning: it can emit a prefix, drop an anchor for imports, emit
  BETWEEN forms, and close with a suffix, because it is the thing calling
  `statement!` rather than something kin calls around a loop it owns.

  A target without one keeps exactly the loop kin always ran, which is why
  the whole change is additive and why a project generating regions today
  notices nothing."
  [prj analysis target]
  (let [ctx (assoc (kin/context {} target)
                   :vocabs (:vocabularies prj)
                   ;; WHICH NAMESPACE IS BEING EMITTED. A target computes its
                   ;; unit name from this exactly as it computes its `:path`,
                   ;; which is what lets a definition know where it lives
                   ;; during the SCAN -- when no `:emit` has run and no unit
                   ;; frame has been pushed.
                   :kin/ns (:ns-name analysis)
                   :scope-syms (:scope analysis)
                   ;; The order the source REQUIRED its vocabularies in, so
                   ;; `literal-tag` can ask them first-match-first -- the same
                   ;; rule the require scope resolves by.
                   :vocab-order (:required (:report analysis))
                   :targets (:targets prj)
                   :exports (:exports-atom prj)
                   :locals (atom {}) :names (atom {})
                   :local-tags (atom {}) :tmp (atom 0))]
    ;; LINK FIRST, over the same registries generate will use. It walks every
    ;; form in order, resolves each reference against what is declared BY THAT
    ;; POINT, and records the answer; generate then emits what it decided.
    ;; LINK FIRST, and LINK'S REGISTRIES ARE ITS OWN. They exist to answer
    ;; `is this declared by HERE?` and nothing else; the closure a call is
    ;; emitted through is the one GENERATE builds, in generate's own ordered
    ;; walk. Sharing them would let a marker link registered be CALLED during
    ;; generate, for a function generate has not reached yet.
    ;;
    ;; What crosses between them is the RESOLUTION STORE, and only that.
    (kin/link! (kin/map->LinkContext
                (assoc (into {} ctx)
                       :scope {} :indent 0
                       :resolutions (kin/-resolutions ctx)
                       :locals (atom {}) :local-tags (atom {}) :names (atom {})))
               (cons 'do (:forms analysis)))
    (if-let [emit (get-in prj [:targets target :emit])]
      (emit ctx (:all-forms analysis))
      (doseq [f (:forms analysis)] (kin/statement! ctx f)))
    (kin/output ctx)))

(defn generate
  "Source TEXT in, `{target text}` out, for every target it generates for.

  PURE: it does not open the source file, and it does not write anything. The
  caller reads the text and decides what to do with the answer, which is what
  makes this usable from a test, a REPL, or a host with no filesystem."
  ([prj text] (generate prj text "<source>"))
  ([prj text label]
   (let [a (analyse prj text label)]
     (into {} (map (fn [t] [t (render prj a t)])) (:emit-for a)))))

(defn generate-in-order
  "Every source, emitted ONCE, in dependency order.

  `{:order [label ...] :generated {label {target text}} :project prj'}`.

  This is the whole of the export mechanism's machinery. A namespace's
  exports are a BY-PRODUCT of emitting it: the target's `defn` calls
  `define-form!` as it goes, and what it registers publicly becomes a
  vocabulary the moment that namespace is finished. Emitting in dependency
  order is what makes that enough -- everything a source requires has already
  been emitted, so its exports are complete rather than half-built.

  One pass. There is no scan, no collecting mode, and no point at which an
  unresolved symbol is tolerated."
  [prj entries]
  (let [labelled (into {} (for [{:keys [label text]} entries]
                            [label (assoc (parse-source text) :text text)]))
        order (require-order labelled)]
    (reduce
     (fn [acc label]
       (let [{:keys [text ns]} (clojure.core/get labelled label)
             prj (:project acc)
             a (analyse prj text label)
             ;; ONE EXPORT REGISTRY PER TARGET, because what a definition
             ;; exports is the TARGET's call shape -- `self.merge_two(..)`
             ;; against `Maps.mergeTwo(..)`. Sharing one atom across targets
             ;; would leave whichever ran last standing for all of them.
             per-target (atom {})
             out (into {} (map (fn [t]
                                 (let [ex (atom {:forms {} :tags {} :names {}})
                                       text (render (assoc prj :exports-atom ex) a t)]
                                   (swap! per-target assoc t @ex)
                                   [t text])))
                       (:emit-for a))
             ;; {target {kind {sym v}}} -> {kind {sym {target v}}}
             exports (atom (reduce-kv
                            (fn [m t kinds]
                              (reduce-kv (fn [m k by-sym]
                                           (reduce-kv (fn [m sym v]
                                                        (assoc-in m [k sym t] v))
                                                      m by-sym))
                                         m kinds))
                            {} @per-target))]
         (-> acc
             (assoc-in [:generated label] out)
             (update :order conj label)
             ;; The namespace is finished, so what it made public IS a
             ;; vocabulary now. Every later namespace resolves against it the
             ;; same way it resolves a hand-written one.
             (assoc :project
                    (cond-> prj
                      ns (assoc-in [:vocabularies (second ns)]
                                   (export-vocabulary (second ns) @exports)))))))
     {:order [] :generated {} :project prj}
     order)))

;; -------------------------------------------------------------------- emit
;;
;; WHERE A NAMESPACE'S CODE GOES IS COMPUTED, NOT LISTED.
;;
;; Each target carries a `:vfs` -- a place, supplied by the user -- and
;; `:path`, a function from namespace to a file within it. The answer is
;; deterministic and there is no table. This replaced a per-source sidecar,
;; `<source>.targets`, three columns of target, file and indent, sixteen of
;; them in the one project using kin.

(defn destination
  "Where `ns-name` goes for `target`: `[vfs path]`, or nil if it goes nowhere.

  NIL IS A LEGITIMATE ANSWER. A source can generate for a target and be
  written nowhere -- `unsigned.kin` in the flint tree generates for three
  targets and exists to be verified rather than shipped. `generates for` and
  `is written somewhere` are different questions."
  [prj target ns-name]
  (let [d (get (:targets prj) target)
        rel (when-let [f (:path d)] (f ns-name))
        fs (vfs/resolve-vfs d)]
    (when (and rel fs) [fs rel])))

(defn- stage
  "Phases 1 and 2 of an emit: generate everything, in memory. Writes nothing.

  Answers `{:emitted {label [{:target :path}]} :staged [{...}]}` where each
  staged entry holds the destination's ORIGINAL content, if it had any, and
  its next content -- which is all the rollback data phase 3 can need.

  THERE USED TO BE A SPLICE HERE. kin read the destination, found a
  `kin:begin`/`kin:end` pair, and replaced the lines between them; a
  destination could have several writers, and getting the rollback right
  meant accumulating every region for a file before writing it once. All of
  that is gone. A target's `:emit` writes a whole file, so the text IS the
  file, one namespace names one destination, and there is no hand-written
  content to preserve because kin created the file."
  [prj entries]
  ;; PHASE 1, in DEPENDENCY ORDER and once per namespace. A form not in scope
  ;; or an undeclared constant throws here, having written nothing -- and a
  ;; namespace's exports are complete before anything requiring it starts.
  (let [{:keys [generated project]} (generate-in-order prj entries)
        plans (mapv (fn [{:keys [label text]}]
                      (let [a (analyse project text label)]
                        {:label label :ns-name (:ns-name a)
                         :emit-for (:emit-for a)
                         :generated (clojure.core/get generated label)}))
                    entries)
        modules (for [p plans
                      t (:emit-for p)
                      :let [d (destination prj t (:ns-name p))]]
                  {:label (:label p) :target t :vfs (first d) :path (second d)
                   :text (get (:generated p) t)
                   :whole (whole-file? (get (:targets prj) t))})
        by-dest (group-by (juxt :target :path) (filter :path modules))]
    {:emitted (reduce (fn [m r] (update m (:label r) (fnil conj [])
                                        {:target (:target r) :path (:path r)}))
                      {} modules)
     :staged (mapv (fn [[[target path] rs]]
                     (let [fs (:vfs (first rs))]
                       ;; A TARGET THAT WRITES MUST HAVE AN `:emit`. Without
                       ;; one `render` produces the forms and nothing else --
                       ;; no package clause, no wrapper -- which is a fragment
                       ;; and used to be spliced into a file somebody else
                       ;; wrote. Now there is nowhere to put it, so a target
                       ;; that asks to write without one is refused by name
                       ;; rather than quietly writing a file that compiles
                       ;; nowhere.
                       (when-not (:whole (first rs))
                         (throw (ex-info
                                 (str "kin: target " target " has a `:path`"
                                      " and no `:emit`, so it asks kin to"
                                      " write " path " out of a fragment --"
                                      " forms with no module around them."
                                      " Give it an `:emit` that owns the"
                                      " file, or a `:path` that answers nil.")
                                 {:target target :path path
                                  :labels (mapv :label rs)})))
                       (when (< 1 (count rs))
                         (throw (ex-info
                                 (str "kin: " (count rs) " sources -- "
                                      (str/join ", " (map :label rs))
                                      " -- all want to BE " path ". A"
                                      " whole-file target is one namespace"
                                      " to one file.")
                                 {:target target :path path
                                  :labels (mapv :label rs)})))
                       {:vfs fs :target target :path path :whole true
                        :existed? (vfs/-exists? fs path)
                        :original (when (vfs/-exists? fs path) (vfs/-read fs path))
                        :next (:text (first rs))}))
                   (sort-by (comp str first) by-dest))}))

(defn- write-staged!
  "Phase 3: write, and put everything back if a write fails partway."
  [staged]
  (loop [done [] todo staged]
    (if-let [{:keys [vfs path next] :as one} (first todo)]
      (let [err (try (vfs/-write vfs path next) nil
                     (catch #?(:clj Exception :default :default) e e))]
        (if err
          ;; ROLLBACK. `-write` with content already in hand -- no new
          ;; protocol operation, no temp paths, no `-delete`. That the design
          ;; needs no wider protocol is evidence the three operations were
          ;; the right three.
          (let [created (vec (keep (fn [d] (when-not (:existed? d) (:path d))) done))
                failed (reduce (fn [acc {:keys [vfs path original existed?]}]
                                 (if-not existed?
                                   ;; A FILE KIN CREATED CANNOT BE UNMADE.
                                   ;; Rollback is `-write` with content already
                                   ;; in hand, and there is no content for a
                                   ;; file that did not exist -- the protocol
                                   ;; has no `-delete` and decision E says a
                                   ;; capability is how it would get one, if a
                                   ;; consumer ever needs true reversibility.
                                   ;; Until then this is REPORTED rather than
                                   ;; papered over.
                                   acc
                                   (try (vfs/-write vfs path original) acc
                                        (catch #?(:clj Exception :default :default) _
                                          (conj acc path)))))
                               [] done)]
            (if (seq failed)
              ;; A restore that fails silently leaves a tree that is neither
              ;; state AND no record of which files are which -- strictly
              ;; worse than the failure it was undoing, because the next
              ;; run's drift check reports it without saying why.
              (throw (ex-info
                      (str "kin: emit failed at " path ", AND THE ROLLBACK"
                           " DID NOT COMPLETE. These hold NEW content: "
                           (pr-str failed) ". These were restored: "
                           (pr-str (vec (remove (set failed) (map :path done))))
                           ". Every other destination holds its original.")
                      {:failed-write path :not-restored failed}
                      #?(:clj err)))
              (throw (ex-info
                      (str "kin: emit failed at " path " -- the whole batch"
                           " was reverted, so the tree is exactly as it was"
                           (when (seq created)
                             (str ", EXCEPT that these were newly created and"
                                  " cannot be unmade: " (pr-str created)))
                           ".")
                      {:failed-write path
                       :created created
                       :reverted (vec (keep (fn [d] (when (:existed? d) (:path d))) done))}
                      #?(:clj err)))))
          (recur (conj done one) (rest todo))))
      (vec (map (juxt :target :path) done)))))

(defn emit-sources!
  "Emit a batch of `{:label :text}` entries. ATOMIC: every source, every
  target, or nothing.

  Three phases -- generate, stage, write -- and the first two write nothing,
  so anything that can be detected before touching a destination is. A write
  that fails partway restores the originals it already replaced.

  The wider granularity beats per-source recovery for three reasons: a
  partial tree is a state nobody designed and no gate describes; recovery
  becomes `fix and re-run` with nothing to reason about; and the invariant
  this whole project exists to hold is that the runtimes AGREE, which a
  half-emitted run breaks on purpose."
  [prj entries]
  (let [{:keys [emitted staged]} (stage prj entries)]
    (write-staged! staged)
    emitted))

(defn resolve-exports
  "The project, with every kin namespace's exports available as a vocabulary.

  Emits each namespace once, in dependency order, and keeps the exports. The
  generated text is discarded here -- `emit-sources!` does its own pass and
  keeps it -- so this exists for callers that want to generate ONE source and
  need what it requires to be resolvable first."
  [prj]
  (:project (generate-in-order
             prj
             (mapv (fn [l] {:label l :text (source-text prj l)})
                   (source-labels prj)))))

(defn emit!
  "Emit ONE source, atomically over its targets.

  Answers `[{:target :rust :path \"...\"} ...]`, with `:path` nil for a target
  that generates and is written nowhere."
  [prj text label]
  (get (emit-sources! prj [{:label label :text text}]) label))

(defn emit-all!
  "Emit EVERY source in the project, atomically over the whole batch.

  `{:order [label ...] :emitted {label [{:target :path}]}}`. There is no
  `:failed` key: a batch either happens or does not, so a failure is thrown
  rather than reported per source. The order is `source-labels`, which is
  sorted -- all-or-nothing means order cannot affect the RESULT, but it can
  still affect a failure message, and one whose lines move between runs is
  much harder to read."
  [prj]
  (let [labels (source-labels prj)
        entries (mapv (fn [l] {:label l :text (source-text prj l)}) labels)]
    {:order labels :emitted (emit-sources! prj entries)}))

(defn destinations
  "Every `[target path]` this project would write, given `sources`.

  `sources` is `{label text}`. A gate needs this: a project that commits its
  generated code has to re-emit everything and compare, and the list of what
  to compare is not knowable any other way once destination is a function."
  ([prj] (destinations prj (sources prj)))
  ([prj srcs]
  (vec (sort-by (comp str second)
                (distinct
                 (for [[label text] srcs
                       :let [a (try (analyse prj text label) (catch #?(:clj Exception :default :default) _ nil))]
                       :when a
                       t (:emit-for a)
                       :let [d (destination prj t (:ns-name a))]
                       :when d]
                   [t (second d)]))))))

;; --------------------------------------------------------------------- why
;;
;; TWO QUESTIONS THAT WERE ONLY ANSWERABLE BY READING THE GENERATOR, and both
;; answered as DATA. Printing is the caller's business, which is what makes
;; them usable from something that is not a terminal.
;;
;; `why` is the one that would have caught the two worst bugs this tool has
;; produced. `LS_THUNK` was missing from a name table, so it fell through to
;; the local namer and emitted an identifier C# does not have; the CLR failed
;; to compile for the whole of the work that followed and every gate stayed
;; green. A report saying where each symbol comes from makes "it comes from
;; nowhere" a thing you can look at.

(defn- symbols-in
  "Every symbol in `form`, and every symbol used as a `^Tag`."
  [form]
  (let [acc (atom #{})]
    ((fn walk [f]
       (when-let [tag (:tag (meta f))] (when (symbol? tag) (swap! acc conj tag)))
       (cond
         (symbol? f) (swap! acc conj f)
         (or (seq? f) (vector? f) (set? f)) (doseq [x f] (walk x))
         (map? f) (doseq [[k v] f] (walk k) (walk v))
         :else nil))
     form)
    @acc))

(defn- bound-names
  "Names the source BINDS: parameters, let bindings, loop variables.

  Resolved through the source's require scope, so a file that aliased its
  `let` still has its bindings found. This is a heuristic and `why` is
  diagnostic output rather than a gate -- but the bucket it separates out,
  `a local you bound` against `a symbol that comes from nowhere`, is exactly
  the distinction the LS_THUNK bug hid in."
  [forms scope]
  (let [acc (atom #{})
        head-of (fn [f] (when (and (seq? f) (symbol? (first f)))
                          (or (second (get scope (first f))) (first f))))]
    ((fn walk [f]
       (when (seq? f)
         (case (head-of f)
           defn (do (swap! acc into (filter symbol? (nth f 2 [])))
                    (swap! acc conj (second f)))
           let (swap! acc into (take-nth 2 (nth f 1 [])))
           for (swap! acc conj (first (nth f 1 [])))
           local (swap! acc conj (second f))
           defstruct (swap! acc into (filter symbol? (nth f 2 [])))
           defconst (swap! acc conj (second f))
           nil)
         (doseq [x f] (walk x))))
     (cons 'do forms))
    @acc))

(defn- kind-of
  "Is `k` a form, a tag or a name in `vocab`?"
  [vocab k]
  (cond (contains? (:forms vocab) k) :form
        (contains? (:tags vocab) k) :tag
        (contains? (:names vocab) k) :name
        :else :unknown))

(defn why
  "Where everything in a source comes from, as DATA.

      :label       what the source was called
      :report      the target computation (see `kin/target-report`)
      :emit-for    what it generates for HERE
      :unconfigured  targets it asks for that this project does not describe
      :failure     the message, if rendering it threw
      :by-vocab    {[vocabulary kind] #{symbol ...}}
      :declared    symbols this file's own `defn`s registered
      :named       symbols it registered a spelling for
      :bound       locals it bound
      :nowhere     symbols in NO vocabulary, declared by nothing and bound by
                   nothing -- the bucket that justifies the whole report
      :shadowed    {symbol [[vocabulary sym] ...]}

  Rendering the source first is what makes `:declared` exact rather than
  guessed: `defn` registers its own name while it emits, so the file's
  declarations are read back from the render rather than inferred from its
  shape. A RENDER THAT THROWS still answers -- `why` is the report you reach
  for BECAUSE generation broke, so the failure is recorded and the tables are
  built from however far it got."
  ([prj label] (why prj (source-text prj label) label))
  ([prj text label]
  (let [{:keys [forms scope report emit-for ns-name]} (analyse prj text label)
        ctx (assoc (kin/context {} (first emit-for))
                   :vocabs (:vocabularies prj) :scope-syms scope
                   :targets (:targets prj)
                   :vocab-order (:required report)
                   :locals (atom {}) :names (atom {})
                   :local-tags (atom {}) :tmp (atom 0))
        failure (try (doseq [f forms] (kin/statement! ctx f)) nil
                     (catch #?(:clj Exception :default :default) e
                       #?(:clj (ex-message e) :default (str e))))
        declared (set (keys @(:locals ctx)))
        named (set (keys @(:names ctx)))
        bound (bound-names forms scope)
        used (symbols-in forms)
        by-vocab (reduce (fn [m sym]
                           (if-let [[v k] (get scope sym)]
                             (update m [v (kind-of (get (:vocabularies prj) v) k)]
                                     (fnil conj #{}) sym)
                             m))
                         {} used)
        local (into #{} (remove #(get scope %)) used)]
    {:label label
     :ns-name ns-name
     :report report
     :emit-for emit-for
     :unconfigured (vec (remove (set (:target-order prj))
                                (sort-by str (:targets report))))
     :failure failure
     :by-vocab by-vocab
     :declared (into #{} (filter declared) local)
     :named (into #{} (filter named) local)
     :bound (into #{} (comp (remove declared) (remove named) (filter bound)) local)
     :nowhere (into #{} (comp (remove declared) (remove named) (remove bound)) local)
     :shadowed (kin/shadowed scope)})))

(defn targets-report
  "Every target, and for each which sources reach it and what ruled out the
  rest. `sources` is `{label text}`. DATA:

      :order     the targets, in the order to show them
      :count     how many sources were considered
      :errors    {label message} for sources that would not even read
      :by-target {target {:generates [label ...]
                          :ruled-out {reason [label ...]}}}

  Reasons are GROUPED because a target no vocabulary speaks rules out every
  source for the same reason, and sixteen identical lines say that worse than
  one line naming sixteen sources."
  ([prj] (targets-report prj (sources prj)))
  ([prj srcs]
  (let [order (:target-order prj)
        analysed (into {} (for [[label text] srcs]
                            [label (try {:ok (analyse prj text label)}
                                        (catch #?(:clj Exception :default :default) e
                                          {:error #?(:clj (ex-message e) :default (str e))}))]))
        ok (into {} (filter (comp :ok val)) analysed)
        extra (distinct (sort-by str (remove (set order)
                                             (mapcat (fn [[_ r]]
                                                       (keys (:ruled-out (:report (:ok r)))))
                                                     ok))))]
    {:order (vec (concat order extra))
     :count (count analysed)
     :errors (into {} (for [[l r] analysed :when (:error r)] [l (:error r)]))
     :by-target
     (into {}
           (for [t (concat order extra)]
             (let [gen (vec (sort (keys (filter (fn [[_ r]] (some #{t} (:emit-for (:ok r)))) ok))))
                   out (remove (set gen) (sort (keys ok)))]
               [t {:generates gen
                   :ruled-out
                   (group-by
                    (fn [label]
                      (let [rep (:report (:ok (get analysed label)))]
                        (or (seq (get (:ruled-out rep) t))
                            (when-not (some #{t} order)
                              ["this project has no target description for it"])
                            ["?"])))
                    out)}])))})))
