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
      emit!       Needs a VFS. Reads the destination, splices the region,
                  writes it back -- and every byte of that goes through the
                  implementation the user put on the target.
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
       :target-order [:rust :java :csharp]}  ; how a report lists them

  `project` builds one from vocabularies you already have. `load-project`
  is the convenience that resolves them from namespace names, and it is the
  only thing here that needs a host with `require` in it."
  (:require [kin :as sp]
            [kin.vfs :as vfs]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; --------------------------------------------------------------- a project

(defn project
  "A project value from vocabularies and targets you already hold.

  Pure, and the reason `generate` can be. Nothing here loads a namespace or
  opens a file; a caller that has its vocabularies as values -- a test, a
  browser, a build that already required them -- never touches the loader."
  [{:keys [vocabularies targets target-order]}]
  (let [vocabs (into {} (map (fn [v] [(:namespace (sp/check-vocabulary v)) v]))
                     vocabularies)]
    {:vocabularies vocabs
     :targets (or targets {})
     :target-order (vec (or target-order (keys targets)))}))

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
       (sp/check-vocabulary v))))

#?(:clj
   (defn load-project
     "A project from namespace NAMES, resolving each with `require`.

     The one function here that needs a host with `require` in it, kept apart
     from `project` for that reason."
     [{:keys [vocabularies targets target-order]}]
     (project {:vocabularies (mapv load-vocabulary vocabularies)
               :targets targets
               :target-order target-order})))

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
        report (when ns-form (sp/target-report ns-form vocabs))]
    {:label label
     :ns ns-form
     :ns-name (second ns-form)
     :forms forms
     :scope (when ns-form (sp/require-scope ns-form vocabs))
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

(defn- render
  "Render `analysis` for one target, into a string."
  [prj analysis target]
  (let [ctx (assoc (sp/context {} target)
                   :vocabs (:vocabularies prj)
                   :scope-syms (:scope analysis)
                   ;; The order the source REQUIRED its vocabularies in, so
                   ;; `literal-tag` can ask them first-match-first -- the same
                   ;; rule the require scope resolves by.
                   :vocab-order (:required (:report analysis))
                   :targets (:targets prj)
                   :locals (atom {}) :names (atom {})
                   :local-tags (atom {}) :tmp (atom 0))]
    (doseq [f (:forms analysis)] (sp/kin-statement! ctx f))
    (sp/kin-output ctx)))

(defn generate
  "Source TEXT in, `{target text}` out, for every target it generates for.

  PURE: it does not open the source file, and it does not write anything. The
  caller reads the text and decides what to do with the answer, which is what
  makes this usable from a test, a REPL, or a host with no filesystem."
  ([prj text] (generate prj text "<source>"))
  ([prj text label]
   (let [a (analyse prj text label)]
     (into {} (map (fn [t] [t (render prj a t)])) (:emit-for a)))))

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

(defn- indent-for [prj target ns-name]
  (let [i (:indent (get (:targets prj) target))]
    (cond (fn? i) (or (i ns-name) 0) (number? i) i :else 0)))

(defn splice
  "`content` with the lines between the markers for `marker` replaced by
  `block`. The markers themselves stay."
  [content marker block where]
  (let [begin (str "kin:begin " marker)
        end (str "kin:end " marker)
        lines (str/split content #"\n" -1)]
    (doseq [m [begin end]]
      (when-not (some #(str/includes? % m) lines)
        (throw (ex-info (str "kin: " where " has no `" m "` marker")
                        {:marker marker :file where}))))
    (str/join
     "\n"
     (loop [out [] ls lines skip false]
       (if-let [l (first ls)]
         (cond
           (str/includes? l begin) (recur (into (conj out l) block) (rest ls) true)
           (str/includes? l end) (recur (conj out l) (rest ls) false)
           skip (recur out (rest ls) true)
           :else (recur (conj out l) (rest ls) false))
         out)))))

(defn block-lines
  "The generated text as lines, each non-empty one indented by `n` spaces."
  [text n]
  (let [pad (apply str (repeat n " "))
        ;; THE TRAILING BLANK LINE IS DELIBERATE, and it is an inheritance.
        ;; The shell `emit` this descends from piped `kin gen`, which prints
        ;; the text and then a newline of its own, into a block file -- so
        ;; every committed region ends with a blank line before its end
        ;; marker. That is an artifact of how the old pipeline printed rather
        ;; than anything the generator meant, but it is in every host file in
        ;; the tree, and a mechanism change that also reflows eighteen files
        ;; is a diff nobody can read. Reproduced here; worth removing on
        ;; purpose, in a commit that does only that.
        ls (str/split (str text "\n") #"\n" -1)
        ;; ... and the last split piece is the end of the text rather than a
        ;; line of it.
        ls (if (= "" (last ls)) (butlast ls) ls)]
    (mapv (fn [l] (if (= "" l) l (str pad l))) ls)))

(defn emit!
  "Write a source's output into each target's file, between the markers.

  THE ONLY I/O KIN PERFORMS, and all of it through the vfs the user put on
  the target. Answers what it did: `[{:target :rust :path \"...\"} ...]`, with
  `:path` nil for a target that generates and is written nowhere.

  The marker is `label` -- the source path, which is what every host file
  already says."
  [prj text label]
  (let [a (analyse prj text label)
        ns-name (:ns-name a)
        generated (generate prj text label)]
    (vec
     (for [target (:emit-for a)]
       (if-let [[fs path] (destination prj target ns-name)]
         (do
           (when-not (vfs/-exists? fs path)
             (throw (ex-info
                     (str "kin: " target " sends " ns-name " to " path
                          ", which does not exist. A region is written INTO a"
                          " hand-written file, so the file and its markers"
                          " come first.")
                     {:target target :namespace ns-name :path path})))
           (let [content (vfs/-read fs path)
                 block (block-lines (get generated target)
                                    (indent-for prj target ns-name))]
             (vfs/-write fs path (splice content label block path))
             {:target target :path path}))
         {:target target :path nil})))))

(defn destinations
  "Every `[target path]` this project would write, given `sources`.

  `sources` is `{label text}`. A gate needs this: a project that commits its
  generated code has to re-emit everything and compare, and the list of what
  to compare is not knowable any other way once destination is a function."
  [prj sources]
  (vec (sort-by (comp str second)
                (distinct
                 (for [[label text] sources
                       :let [a (try (analyse prj text label) (catch #?(:clj Exception :default :default) _ nil))]
                       :when a
                       t (:emit-for a)
                       :let [d (destination prj t (:ns-name a))]
                       :when d]
                   [t (second d)])))))

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
  [prj text label]
  (let [{:keys [forms scope report emit-for ns-name]} (analyse prj text label)
        ctx (assoc (sp/context {} (first emit-for))
                   :vocabs (:vocabularies prj) :scope-syms scope
                   :targets (:targets prj)
                   :vocab-order (:required report)
                   :locals (atom {}) :names (atom {})
                   :local-tags (atom {}) :tmp (atom 0))
        failure (try (doseq [f forms] (sp/kin-statement! ctx f)) nil
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
     :shadowed (sp/shadowed scope)}))

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
  [prj sources]
  (let [order (:target-order prj)
        analysed (into {} (for [[label text] sources]
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
                    out)}])))}))
