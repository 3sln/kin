(ns kin.lang
  "The shape of a program, in three languages: `defn`, `let`, `if`, `return`.

  Split out of the codec's vocabulary once a SECOND source needed it. Nothing
  here knows what is being compiled -- a function signature, a local, a branch
  and a return are the same three-way disagreement whatever the body says, and
  a vocabulary that had to restate them per subject would make every new source
  pay for the language before it paid for its own subject.

  A vocabulary MERGES these in rather than inheriting them, so a subject that
  needs a different `let` can still have one. What it must not do is get them
  by accident, which is why this is a namespace a source has to name."
  (:require [kin]
            [kin.target]
            [clojure.string :as str]))

(defn t [ctx] (:target ctx))

(def snake kin.target/snake)
(def camel kin.target/camel)

(defn fmt [tmpl args]
  (reduce (fn [s i] (str/replace s (str "{" i "}") (nth args i ""))) tmpl (range (count args))))

(defn strip-parens
  "Drop the outer parentheses of a whole expression.

  **NOT for call arguments**, and that restriction was learned the hard way.
  Stripping them there produced

      u32(o, (int) n >>> 32)        // casts, THEN shifts -- wrong
      u32(o, (int) (n >>> 32))      // what it has to be

  because the argument is substituted into another template that does not
  re-parenthesise it, so the parens were carrying the precedence. An aesthetic
  rule silently changed the semantics, which is the one way the not-worse rule
  can do harm: correct always outranks tidy.

  Safe only where the expression is the WHOLE right-hand side of an assignment,
  which is where it is used."
  [c]
  (if (and (str/starts-with? c "(") (str/ends-with? c ")")
           (loop [i 1 d 1]
             (cond (>= i (dec (count c))) (= d 1)
                   (= \( (nth c i)) (recur (inc i) (inc d))
                   (= \) (nth c i)) (if (= d 1) false (recur (inc i) (dec d)))
                   :else (recur (inc i) d))))
    (subs c 1 (dec (count c)))
    c))

(defn- delimited?
  "Does `tmpl` place `{i}` IMMEDIATELY inside a delimiter pair?

  This is the safe test for stripping an argument's outer parentheses, and it
  is a question about the TEMPLATE rather than about the expression -- which is
  what the earlier bug got wrong. `to-i32` is `((int) {0})`: the `{0}` sits
  after a cast, so its parens are load-bearing and stripping them produced
  `(int) n >>> 32`, which casts and then shifts. `wrapping_add({1})` puts the
  argument between `(` and `)` with nothing to bind to on either side, so its
  outer parens can only be noise.

  So the rule is not `strip when the expression looks fully parenthesised` --
  it is `strip only where the template guarantees there is nothing to bind
  with`. Same conclusion the hard way round: correct outranks tidy, and the way
  to be tidy safely is to prove there is nothing to break."
  [tmpl i]
  (let [k (str "{" i "}")
        at (.indexOf (str tmpl) k)
        ;; Skip one space, so `, {2})` counts as delimited the way `,{2})`
        ;; does. Templates are written for a human to read, and every one of
        ;; them puts a space after the comma.
        before (if (and (pos? at) (= \space (nth tmpl (dec at) \space)))
                 (nth tmpl (- at 2) \space)
                 (nth tmpl (dec at) \space))]
    (and (pos? at)
         (contains? #{\( \, \[} before)
         (< (+ at (count k)) (inc (count tmpl)))
         (contains? #{\) \, \]} (nth tmpl (+ at (count k)) \space)))))

(defn need!
  "Say that the file being emitted needs `what` in scope.

  DATA, not text: the form describes what it needs and the target's `:emit`
  formats the header. A target that opened no `:needs` frame gets nothing,
  which is right -- a project splicing regions has no header to add to."
  [ctx what]
  (when-let [needs (kin/get ctx :needs)] (swap! needs conj what))
  nil)

(defn fill
  "Fill `tmpl` with argument texts that have ALREADY been rendered.

  Split out of `call` so that a form which had to render its arguments itself
  -- because it wanted their TAGS -- can still get the parenthesis handling
  right without restating it, and without rendering them a second time. A
  second render is not merely wasteful: an argument that hoists a temporary
  would hoist it twice."
  [tmpl texts]
  (fmt tmpl (vec (map-indexed
                  (fn [i c] (if (delimited? tmpl i) (strip-parens c) c))
                  texts))))

(defn call
  "A form that is a call: render the arguments, fill the target's template, and
  emit as a statement or an expression depending on where it sits.

  `(call tmpls {:tag T})` says what the call PRODUCES, which is how a tag
  reaches an enclosing form. `T` may be a tag or a `(fn [ctx form] -> tag)`.

  A target with no template is REFUSED by name. It used to fill `nil`, which
  `fmt` turned into an empty string, so a vocabulary that claimed to speak a
  target and had missed one form emitted a blank where a call should be --
  the same shape of silence `:targets` exists to remove, one level down.
  Tags and names are checked when the vocabulary loads; a form is a function
  and cannot be, so it is checked here, the first time it is asked."
  ([tmpls] (call tmpls nil))
  ([tmpls {:keys [tag]}]
   (fn [ctx form]
    (let [tmpl (or (get tmpls (t ctx))
                   (throw (ex-info
                           (str "kin: `" (first form) "` has no template for "
                                (t ctx) " -- it speaks "
                                (pr-str (vec (sort-by str (keys tmpls)))))
                           {:form (first form) :target (t ctx)
                            :speaks (vec (keys tmpls))})))
          code (fill tmpl (mapv (fn [f] (kin/render ctx f)) (rest form)))]
      ;; WHAT THIS CALL PRODUCED. `:tag` may be a value or a function of the
      ;; context, which is the form deciding about its own product -- kin
      ;; carries the answer and does not read it.
      (kin/tagged! ctx (if (fn? tag) (tag ctx form) tag))
      (if (= :statement (kin/position ctx))
        (kin/emit! ctx (kin/indent-of ctx) code ";\n")
        (kin/emit! ctx code))))))

;; THERE IS NO `bit-shift-right` HERE, and its absence is the point.
;;
;; It used to be in this table, mapped to `>>` on all three. That is logical on
;; a Rust `u32` and ARITHMETIC on a Java or C# `int`, which are signed -- so a
;; source shifting a hash would have sign-extended on two targets and not the
;; third, silently. No source had used it yet; it was a trap set for the next
;; one.
;;
;; A shift cannot be one template because which shift is correct depends on the
;; TAG's signedness in Rust, where the type chooses and the operator does not.
;; So a subject vocabulary provides the pair explicitly, with three bodies
;; each -- `ushr` for the logical shift and `sar` for the arithmetic one --
;; and naming both is what makes a reader's intent survive the trip.
(def ops
  {'+ "+" '- "-" '* "*" '< "<" '> ">" '== "==" 'not "!"
   '>= ">=" '<= "<=" '!= "!="
   ;; THE BOOLEAN CONNECTIVES, spelled identically on all three.
   ;;
   ;; One line, and it unblocks more functions than every structural hole
   ;; combined -- fifteen in one runtime's seq, equality and regex files
   ;; alone, plus every `&&` guard elsewhere. It was missing for the first
   ;; four sources that shipped because none of them happened to need a
   ;; boolean connective, which is the whole lesson: a capability census
   ;; taken from the files that shipped is a census of what those files
   ;; needed, not of what the next one will.
   'and "&&" 'or "||"
   'bit-and "&" 'bit-or "|" 'bit-xor "^"
   ;; Integer division. `/` on two ints truncates in all three, so `quot` is
   ;; the honest name for what it does -- and `/` is left unbound rather than
   ;; meaning something different from Clojure's.
   'quot "/" 'rem "%"})

(defn op-form [sym]
  (fn [ctx form]
    (let [as (mapv (fn [f] (kin/render ctx f)) (rest form))]
      (kin/emit! ctx (if (= 1 (count as))
                             (str (get ops sym) (first as))
                             (str "(" (str/join (str " " (get ops sym) " ") as) ")"))))))

;; ------------------------------------------------------------------- naming
;;
;; One name in the source, three conventions in the output. This is the whole
;; of what a reader has to know to find the generated function by hand.

(defn- cons* [x xs] (if x (cons x xs) xs))

(defn target-name
  "A dashed function name, spelled the way this target spells one.

  From the TARGET's `:fn-name`, not from a `case` over three keywords this
  file happened to know. That is what lets a project add a fourth language
  without editing this one."
  [ctx nm]
  ((get-in ctx [:targets (:target ctx) :fn-name] (fn [_ s] (str s))) ctx nm))

(defn- ty-of [ctx default tag] (get-in (or (kin/tag ctx tag) default) [:types (t ctx)]))

(defn- defn-form
  "A function, framed the way each target frames one.

  THREE SLOTS, and the split is what the link phase needs. `:declare`
  registers the name and nothing else, so that link -- walking in order --
  knows the function is in scope from this point on and can resolve later
  references to it as LOCAL. `:generate` registers again (idempotently, and
  with a closure built in the generate context) and emits the function.

  Registering in both is deliberate rather than redundant: link must see the
  name to answer `is this declared by here?`, and generate must own the
  closure so that no link-context value reaches generated text.

  The signature is where three languages disagree most and it is entirely
  mechanical: a return type before or after, `static` or `fn`, `self` or not.

  It also DECLARES the name, so a later form in the same file can call it. A
  source that could define a function and not call it would push every helper
  into the vocabulary, and a helper in the vocabulary is a helper written three
  times -- which is the thing this whole exercise exists to stop."
  [default]
  {;; LINK: register the name and stop. Link needs only to know that this
   ;; function is in scope from here on, so that a later reference resolves
   ;; as LOCAL rather than reaching past it to a vocabulary. It registers a
   ;; MARKER, never a callable -- link's registries answer `declared by
   ;; here?` and nothing else, and the closure generate calls is the one
   ;; generate builds.
   :declare
   (fn [ctx form]
     (let [nm (second form)]
       (kin/define-form! ctx {:scope (if (:pub (meta nm)) :public :private)} nm {})))
   :generate
   (fn [ctx form]
    (let [[_ nm params & body] form
          ret (:tag (meta nm))
          throws? (:throws (meta nm))
          pub? (:pub (meta nm))
          ;; `^:unchecked`: C# wraps the BODY, which is what the hand-written
          ;; runtime does and is why murmur's wrapping arithmetic can then be
          ;; written as plain `*` and `+`. Per-expression `unchecked(...)`
          ;; nests into `unchecked(unchecked(a * b) + c)` -- the same IL and a
          ;; good deal harder to read, which the not-worse rule covers.
          unchecked? (:unchecked (meta nm))
          ;; `^:method`: the FIRST parameter is the receiver.
          ;;
          ;; Rust puts these on `impl Rt` and the JVM and CLR make them
          ;; statics that take the runtime as an argument. That is the same
          ;; function three ways, and it is the difference that has kept `Eq`,
          ;; `Seqs` and most of the bulk out of reach -- not the bodies, which
          ;; already agree, but where the receiver goes.
          ;; `^:method` -- Rust `self`, the others a static taking it.
          ;; `^:instance` -- an instance method on ALL THREE.
          ;;
          ;; Two questions that wore one mark until a table's own methods
          ;; needed the second: `Rt.category` really is a static on the JVM
          ;; taking the runtime, and `InternTable.mask` really is an instance
          ;; method there as it is in Rust.
          ;;
          ;; NOT named `instance?`: that is `clojure.core/instance?`, and a
          ;; local that shadows it reads fine until the binding is dropped,
          ;; at which point the core FUNCTION resolves in its place and is
          ;; truthy. Every function then emitted as an instance method, with
          ;; no error anywhere.
          on-inst? (:instance (meta nm))
          method? (or (:method (meta nm)) on-inst?)
          recv (when method? (first params))
          params (if method? (rest params) params)
          ps (partition 2 (interleave params (map (fn [p] (:tag (meta p))) params)))
          ty (partial ty-of ctx default)]
      ;; The receiver is spelled `self` in Rust and by its own name in the
      ;; other two, so a body that says `(. rt gc)` comes out as `self.gc`
      ;; there and `rt.gc` here. Registering the NAME is all that takes.
      (when recv
        (kin/define-name!
         ctx {:scope :private} recv (if on-inst?
                    {:rust "self" :java "this" :csharp "this"}
                    {:rust "self" :java (str recv) :csharp (str recv)})))
      ;; PUBLIC when the source says `^:pub`, and that means local AND
      ;; exported -- a `^:pub` function is obviously still callable from its
      ;; own file. `:scope` is a maximum visibility, not a destination.
      ;; WHERE THIS DEFINITION LIVES, captured now so the call can compare.
      ;;
      ;; Not which kin namespace -- the EMITTED structure. A target's `:emit`
      ;; pushes a `:kin/unit` frame naming the class or module it is opening,
      ;; and a call compares that frame against this one: same, self-
      ;; reference; different, an absolute reference plus the import it needs.
      ;;
      ;; ONE FUNCTION, and every form asks -- including private ones, which
      ;; simply always get the same answer, because nothing can call them from
      ;; another unit. kin has no branch for this and invents no reference: a
      ;; target that pushes no `:kin/unit` gets nil on both sides and
      ;; self-references always, which is what a project generating regions
      ;; did before any of this existed.
      (kin/define-form!
       ctx {:scope (if pub? :public :private)} nm
       ;; The unit a target would open for THIS namespace. Asked of the
       ;; target rather than read from the scope, because a definition is
       ;; registered during the scan -- before any `:emit` has run and before
       ;; any frame exists. A target computes it from the namespace exactly as
       ;; it computes `:path`, so the two sides agree by construction.
       (let [home (or (kin/get ctx :kin/unit)
                      (when-let [f (get-in ctx [:targets (t ctx) :unit])]
                        (f (:kin/ns ctx))))]
        (fn [c f]
         ;; A GENERATED FUNCTION REGISTERS ITS OWN RETURN TAG. It already
         ;; states one -- `defn ^Value cn-key` -- so a later call to it in the
         ;; same file carries that tag with no further annotation. This is the
         ;; cheapest of the four ways a tag arrives and the one the sources
         ;; already pay for.
         (kin/tagged! c (kin/tag c ret))
         ;; Every argument of a call sits between delimiters -- `(`, `,`,
         ;; `)` -- so its outer parentheses can only be noise. This is the
         ;; same rule `delimited?` applies to a template, arrived at from the
         ;; other side: a declared call has no template to inspect, but its
         ;; shape guarantees what a template would have to prove.
         (let [as (mapv (fn [x] (strip-parens (kin/render c x))) (rest f))
               here (kin/get c :kin/unit)
               ;; A RUST METHOD IS THE SAME EVERYWHERE: both halves are
               ;; methods on one type, and a crate may have several inherent
               ;; `impl` blocks. So `elsewhere?` only bites in the two
               ;; languages that put a function inside a class.
               elsewhere? (and home here (not= home here))
               qualified (fn [nm-str]
                           (if (and elsewhere? (not= :rust (t c)))
                             (do (need! c home) (str home "." nm-str))
                             nm-str))
               code (str (if (or on-inst? (and method? (= :rust (t c))))
                           (str (first as) "." (target-name c nm)
                                "(" (str/join ", " (rest as)) ")")
                           (str (qualified (target-name c nm))
                                "(" (str/join ", " as) ")"))
                         (if (and throws? (= :rust (t c))) "?" ""))]
           (if (= :statement (kin/position c))
             (kin/emit! c (kin/indent-of c) code ";\n")
             (kin/emit! c code))))))
      ;; EXPORTED when the source says `^:pub`, and only then. A file is full
      ;; of helpers that are nobody else's business, and without a gate every
      ;; one of them would leak and the module boundary would mean nothing.
      ;; `^:pub` already exists and already means `part of the API`.
      ;; `^:inline`. Rust is the only one that says so in the source; the JVM
      ;; and the CLR decide at run time from profile data, which is strictly
      ;; more information than a source can have. So the mark is emitted for
      ;; one target and dropped by two -- and dropping it is not a loss.
      (when (and (:inline (meta nm)) (= :rust (t ctx)))
        (kin/emit! ctx (kin/indent-of ctx) "#[inline]\n"))
      (case (t ctx)
        ;; RUST RETURNS A RESULT WHERE THE OTHERS THROW, and that is the first
        ;; divergence found in this port that is not naming: it changes the
        ;; signature and every call site. `^:throws` on the name says a function
        ;; can fail; Rust turns the return type into `Result<T, String>` and a
        ;; call to it gets `?`, and Java and C# ignore the mark entirely because
        ;; an exception needs nothing in either place.
        ;; VISIBILITY IS A MODULE QUESTION NOW. A source is its own file, so
        ;; every function it defines is reached from outside the file it
        ;; lives in -- and the DEFAULT has to be "visible to the rest of the
        ;; compilation unit" rather than "visible in this file", which is
        ;; what an unmarked `fn` means in Rust and an unmarked member means
        ;; in C#. `^:pub` still means the wider thing: part of the crate's
        ;; public API rather than of the crate.
        :rust (kin/emit!
               ctx (kin/indent-of ctx) (if pub? "pub fn " "pub(crate) fn ") (target-name ctx nm) "("
               ;; `^:mut` on a PARAMETER. Rust is the only one of the three
               ;; that has to say a parameter is reassigned; Java and C# read
               ;; the mark and emit nothing, which is the ordinary shape of a
               ;; divergence here -- one target needs a word, so the source
               ;; says the thing and each target spends what it must.
               (str/join ", " (cons* (when recv (if (:mut (meta recv)) "&mut self" "&self"))
                                     (mapv (fn [[p tag]] (str (when (:mut (meta p)) "mut ")
                                                              (kin/local-name ctx p) ": " (ty tag))) ps)))
               ")"
               (cond
                 (and ret throws?) (str " -> Result<" (ty ret) ", String>")
                 ret (str " -> " (ty ret))
                 throws? " -> Result<(), String>"
                 :else "")
               " {\n")
        :java (kin/emit!
               ctx (kin/indent-of ctx)
               ;; JAVA HAS NO ASSEMBLY-SCOPED VISIBILITY, and that is the one
               ;; place this costs something real. Rust has `pub(crate)` and
               ;; C# has `internal`; Java has package-private or public and
               ;; nothing between. A generated module is a package of its
               ;; own -- it has to be, or a source named after the file it
               ;; used to be spliced into collides with that file's class --
               ;; so everything that crosses the boundary is `public`, and
               ;; `^:pub` stops distinguishing anything HERE. It still does
               ;; on the other two.
               (cond on-inst? "public "
                     :else "public static ")
               (if ret (ty ret) "void") " " (target-name ctx nm) "("
               (str/join ", " (cons* (when (and recv (not on-inst?))
                                       (str (ty (:tag (meta recv))) " " recv))
                                     (mapv (fn [[p tag]] (str (ty tag) " " (kin/local-name ctx p))) ps))) ") {\n")
        :csharp (kin/emit!
                 ctx (kin/indent-of ctx)
                 ;; C# class members default to PRIVATE where Java defaults to
                 ;; package-private, so an unmarked instance method needs
                 ;; `internal` to mean what the Java one means.
                 (cond on-inst? (if pub? "public " "internal ")
                       pub? "public static " :else "internal static ")
                 (if ret (ty ret) "void") " " (target-name ctx nm) "("
                 (str/join ", " (cons* (when (and recv (not on-inst?))
                                         (str (ty (:tag (meta recv))) " " recv))
                                       (mapv (fn [[p tag]] (str (ty tag) " " (kin/local-name ctx p))) ps))) ") {\n"))
      (let [wrap? (and unchecked? (= :csharp (t ctx)))
            ;; A FRESH TAG TABLE PER FUNCTION, seeded with the parameters.
            ;; Fresh because a table that outlived its function would let a
            ;; parameter called `n` in one body decide what an unannotated `n`
            ;; means in the next, which is inference by coincidence.
            ctx (assoc ctx :local-tags
                       (atom (into {} (for [[p tag] (cons* (when recv [recv (:tag (meta recv))])
                                                           ps)
                                            :let [tv (kin/tag ctx tag)]
                                            :when tv]
                                        [p tv]))))]
        (kin/scoped
         ctx {:key :fn :value nm :indent 1}
         (fn [inner]
           (when wrap? (kin/emit! inner (kin/indent-of inner) "unchecked {\n"))
           (kin/scoped
            inner {:key :throws :value throws? :indent (if wrap? 1 0)}
            (fn [in2] (doseq [f body] (kin/statement! in2 f))))
           (when wrap? (kin/emit! inner (kin/indent-of inner) "}\n")))))
      (kin/emit! ctx (kin/indent-of ctx) "}\n")))})

(defn- let-form [default]
  (fn [ctx form]
    (let [[_ bindings & body] form]
      (doseq [[nm init] (partition 2 bindings)]
        (let [{code :text produced :tag} (kin/render-tagged ctx init)
              code (strip-parens code)
              ;; AN UNANNOTATED LOCAL TAKES THE TAG OF ITS INITIALISER, and
              ;; only then falls back to the vocabulary's default. Declared
              ;; wins over inferred, because a source that says `^I32` has
              ;; said something and inference must not argue with it.
              declared (kin/tag ctx (:tag (meta nm)))
              tag (or declared produced)
              ty (get-in (or tag default) [:types (t ctx)])
              _ (kin/define-tag! ctx {:scope :private} nm tag)]
          (kin/emit! ctx (kin/indent-of ctx)
                           ;; `^:mut` on a LOCAL, for the same reason it is on
                           ;; a parameter: Rust alone has to say that a binding
                           ;; is reassigned. Found the moment a loop existed to
                           ;; accumulate into one -- `let acc: u32 = 0;`
                           ;; followed by `acc = ...` does not compile.
                           (let [n (kin/local-name ctx nm)]
                             (case (t ctx)
                               :rust (str "let " (when (:mut (meta nm)) "mut ")
                                          n ": " ty " = " code ";\n")
                               (str ty " " n " = " code ";\n"))))))
      (doseq [f body] (kin/statement! ctx f)))))

(defn- local-form
  "`(local ^:mut ^T x)` -- DECLARE a local without giving it a value.

  Needed because a variable assigned on every branch of an `if` has no
  sensible initialiser, and inventing one is not free: `let mut out: Value =
  NIL;` makes rustc warn that the value is never read, where the hand-written
  code says `let mut out: Value;` and warns about nothing. The not-worse rule
  covers what a build prints, not only what it emits.

  Rust requires the type annotation when there is no initialiser, which the
  tag already supplies."
  [default]
  (fn [ctx form]
    (let [nm (second form)
          tag (kin/tag ctx (:tag (meta nm)))
          ty (get-in (or tag default) [:types (t ctx)])
          _ (kin/define-tag! ctx {:scope :private} nm tag)
          n (kin/local-name ctx nm)]
      (kin/emit! ctx (kin/indent-of ctx)
                    (case (t ctx)
                      :rust (str "let " (when (:mut (meta nm)) "mut ") n ": " ty ";\n")
                      (str ty " " n ";\n"))))))

(defn- defstruct-form
  "A small mutable record, declared the way each target declares one.

  The three differ in mechanism rather than meaning: Rust wants a `struct`,
  Java and C# want a class with fields. What a source says is the FIELDS."
  [default]
  (fn [ctx form]
    (let [[_ nm fields] form
          fs (mapv (fn [f] [f (:tag (meta f))]) fields)
          pascal (str/join (mapv str/capitalize (str/split (str nm) #"-")))]
      ;; A STRUCT DEFINES A TAG. It is a type, so it belongs in the tag
      ;; registry and -- when `^:pub` -- in the namespace's exported tags,
      ;; which is what makes a struct usable from another kin namespace at
      ;; all. It is also the only way a kin namespace can currently define a
      ;; tag, and therefore the only way a TAG CYCLE can be constructed.
      (kin/define-tag! ctx {:scope (if (:pub (meta nm)) :public :private)} nm
                       {:name nm :types (zipmap (keys (:targets ctx))
                                                (repeat pascal))})
      (case (t ctx)
        :rust (do (kin/emit! ctx (kin/indent-of ctx) "struct " pascal " {\n")
                  (doseq [[f tag] fs]
                    (kin/emit! ctx (kin/indent-of ctx) "    " f ": "
                                     (ty-of ctx default tag) ",\n"))
                  (kin/emit! ctx (kin/indent-of ctx) "}\n"))
        :java (do (kin/emit! ctx (kin/indent-of ctx) "static final class " pascal " {\n")
                  (doseq [[f tag] fs]
                    (kin/emit! ctx (kin/indent-of ctx) "    " (ty-of ctx default tag) " " f ";\n"))
                  (kin/emit! ctx (kin/indent-of ctx) "}\n"))
        :csharp (do (kin/emit! ctx (kin/indent-of ctx) "sealed class " pascal " {\n")
                    (doseq [[f tag] fs]
                      (kin/emit! ctx (kin/indent-of ctx) "    internal "
                                       (ty-of ctx default tag) " " f ";\n"))
                    (kin/emit! ctx (kin/indent-of ctx) "}\n"))))))

(defn comment-form
  "`(comment \"line\" \"line\")` -- a comment, in the output.

  Added the moment the first port LOST one. `category` carries an explanation
  of why a row ref is in the map category, with a pointer to the decision that
  settled it, and generating the function silently dropped it from two
  runtimes. A generator that discards the reasoning keeps the code and throws
  away the part that was expensive to work out.

  `;;` comments in a kin source are for the SOURCE and never reach the
  output -- the reader discards them before any of this runs. So a comment
  meant for a reader of the generated file has to be said as a form, and the
  difference between the two is exactly the difference between explaining the
  rule and explaining the code it produces."
  [ctx form]
  (doseq [line (rest form)]
    (kin/emit! ctx (kin/indent-of ctx) "// " line "\n")))

(defn doc-form
  "`(doc \"line\" ...)` -- a DOC comment, `///` on all three.

  Distinct from `comment` because the distinction is real in the output: `///`
  is picked up by rustdoc, javadoc and the C# XML doc pipeline, and `//` is
  not. The first emit of the CHAMP accessors turned a `///` into a `//` and
  quietly demoted a documented function to an undocumented one in three
  runtimes at once."
  [ctx form]
  (doseq [line (rest form)]
    (kin/emit! ctx (kin/indent-of ctx) "/// " line "\n")))

(defn- field-form
  "`(. r i)` -- a field, readable and assignable.

  The FIELD NAME goes through the target's namer like any other name, so
  `(. rt champ-added)` is `self.champ_added` in one language and
  `rt.champAdded` in the next. It used to emit the symbol verbatim, which
  happened to be right for every field written so far because none of them had
  a dash in it -- a rule that holds until the first name that tests it."
  [ctx form]
  (let [[_ obj f] form]
    (kin/emit! ctx (kin/render ctx obj) "." (kin/local-name ctx f))))

(def base-compound
  "Forms with a compound-assignment spelling, PER TARGET.

  Per target because a form can have one in two languages and not the third:
  `mul32` is `*=` on the JVM and the CLR and `wrapping_mul` in Rust, which has
  no compound spelling at all. A target with no entry simply gets the long
  form, which is what it would have been written by hand.

  Shifts are deliberately absent everywhere: `>>>` against `>>` is the
  difference the vocabulary exists to hide, and `>>>=` would put it back in
  the source."
  (let [everywhere (fn [op] {:rust op :java op :csharp op})]
    {'+ (everywhere "+=") '- (everywhere "-=") '* (everywhere "*=")
     'bit-and (everywhere "&=") 'bit-or (everywhere "|=")
     'bit-xor (everywhere "^=")}))

(defn- head-op
  "The compound spelling of a head symbol for this target, through the file's
  require scope."
  [table ctx head]
  (when (symbol? head)
    (let [k (if (:scope-syms ctx) (second (get (:scope-syms ctx) head)) head)]
      (get-in table [k (t ctx)]))))

(defn- set-form
  "`(set x v)`.

  `x = x ^ y` is written `x ^= y` where the operator has a compound form, which
  is not an optimisation -- the two compile identically -- but is what the
  hand-written code says, and the rule is that generated code may not be worse
  than what it replaces. A diff full of `h1 = h1 ^ len` against `h1 ^= len` is
  a diff nobody reads, and an unread diff is the acceptance check not running."
  [table]
  (fn [ctx form]
    (let [[_ place value] form
          p (kin/render ctx place)
          cmp (when (seq? value) (head-op table ctx (first value)))]
      (if (and cmp (= 3 (count value)) (= p (kin/render ctx (second value))))
        (kin/emit! ctx (kin/indent-of ctx) p " " cmp " "
                         (strip-parens (kin/render ctx (nth value 2))) ";\n")
        (kin/emit! ctx (kin/indent-of ctx) p " = "
                         (strip-parens (kin/render ctx value)) ";\n")))))

(defn- head-is-if?
  "Is this seq's head the `if` THIS FILE means? Resolved through the require
  scope, so a source that aliased something else to `if` is not chained by
  accident."
  [ctx form]
  (let [h (first form)]
    (if-let [scope (:scope-syms ctx)]
      (= 'if (second (get scope h)))
      (= 'if h))))

(declare if-body)

(defn- if-form [ctx form]
  (kin/emit! ctx (kin/indent-of ctx))
  (if-body ctx form))

(defn- if-body [ctx form]
  (let [[_ test then else] form
        c (kin/render ctx test)]
    (kin/emit! ctx
                     ;; The test is a WHOLE expression with nothing to bind
                     ;; with, so its outer parens are the safe case to strip --
                     ;; Rust warns on them and the other two would otherwise
                     ;; get `if ((x == 0))`.
                     (let [c (strip-parens c)]
                       (if (= :rust (t ctx)) (str "if " c " {\n") (str "if (" c ") {\n"))))
    (kin/scoped ctx {:key :in-if :value true :indent 1}
                      (fn [inner] (kin/statement! inner then)))
    ;; An `else` whose body is itself an `if` becomes `} else if (...) {`
    ;; rather than a nested block. Without this every extra arm cost a brace
    ;; level, and a five-arm dispatch came out indented five deep -- which no
    ;; hand-written file here does, so it fails the not-worse rule on reading
    ;; even though it compiles.
    (cond
      (and else (seq? else) (= 'if (first else)) (head-is-if? ctx else))
      (do (kin/emit! ctx (kin/indent-of ctx) "} else ")
          (kin/scoped ctx {:key :else-if :value true}
                            (fn [inner] (if-body inner else))))

      else
      (do (kin/emit! ctx (kin/indent-of ctx) "} else {\n")
          (kin/scoped ctx {:key :in-if :value true :indent 1}
                            (fn [inner] (kin/statement! inner else)))
          (kin/emit! ctx (kin/indent-of ctx) "}\n"))

      :else (kin/emit! ctx (kin/indent-of ctx) "}\n"))))

(defn- return-form
  "`(return v)`, and `(return)` from a function with no value.

  The bare form emitted `return null;` on all three and compiled nowhere,
  which is a hole `Codec.java` alone would hit fourteen times. Rust's is
  `Ok(())` when the function can fail, because `^:throws` turns its return
  type into `Result<(), String>`."
  [ctx form]
  (let [throws? (kin/get ctx :throws)]
    (if (= 1 (count form))
      (kin/emit! ctx (kin/indent-of ctx)
                       (if (and (= :rust (t ctx)) throws?) "return Ok(());\n" "return;\n"))
      (let [v (strip-parens (kin/render ctx (second form)))]
        (kin/emit! ctx (kin/indent-of ctx) "return "
                         (if (and (= :rust (t ctx)) throws?) (str "Ok(" v ")") v) ";\n")))))

(defn- for-form
  "`(for [^I32 c start end] body...)` -- a COUNTED loop, half-open.

  Blocker number one for everything after `Hash`. `for (int c = 0; c < n; c++)`
  appears twelve times in `Codec.java` and seventeen in `codec.rs`, and `Maps`,
  `Table`, `Vec`, `Str`, `Bytes`, `Snap` and `Pike` are all loops over arrays.
  Nothing in phase 3 can be written without it.

  Rust says the range and infers the index type from it; the other two spell
  out all three clauses and need the type. The bound is evaluated ONCE in
  Rust's `start..end` and once per iteration in a C-style `for`, so a bound
  with a side effect would differ -- but a bound with a side effect in a loop
  header is not something any of the three hand-written runtimes does, and a
  source that wants one can hoist it into a `let`."
  [default]
  (fn [ctx form]
    (let [[_ binding & body] form
          [nm start end] binding
          tag (kin/tag ctx (:tag (meta nm)))
          ty (get-in (or tag default) [:types (t ctx)])
          _ (kin/define-tag! ctx {:scope :private} nm tag)
          n (kin/local-name ctx nm)
          a (strip-parens (kin/render ctx start))
          b (strip-parens (kin/render ctx end))]
      (kin/emit! ctx (kin/indent-of ctx)
                       (case (t ctx)
                         :rust (str "for " n " in " a ".." b " {\n")
                         (str "for (" ty " " n " = " a "; " n " < " b "; " n "++) {\n")))
      (kin/scoped ctx {:key :in-loop :value true :indent 1}
                        (fn [inner] (doseq [f body] (kin/statement! inner f))))
      (kin/emit! ctx (kin/indent-of ctx) "}\n"))))

(defn- while-form
  "`(while test body...)`.

  Moved here from `flint.impl.vm`, which was the only vocabulary that had one
  -- so a codec or a collections source could not loop at all, even where a
  `while` was exactly right. It carried its own `let`, `set` and `if` alongside
  it, which predate this file.

  THE TEST GOES INSIDE only when it has to. A loop test is evaluated every
  iteration, so a test needing a temporary cannot stay in the condition; but
  one that does not need a temporary can, and rewriting it anyway emits

      while true { if !c { break; } ... }

  where a person would write `while !c { ... }`. That is worse than the
  hand-written code, and generated code that is worse is not worth generating."
  [ctx form]
  (let [[_ test & body] form
        c (strip-parens (kin/render ctx test))]
    (kin/emit! ctx (kin/indent-of ctx)
                     (if (= :rust (t ctx)) (str "while " c " {\n") (str "while (" c ") {\n")))
    (kin/scoped ctx {:key :in-loop :value true :indent 1}
                      (fn [inner] (doseq [f body] (kin/statement! inner f))))
    (kin/emit! ctx (kin/indent-of ctx) "}\n")))

(defn- forever-form
  "`(forever body...)` -- a loop with no test, left only by `break` or `return`.

  Rust spells it `loop`; the other two spell it `for (;;)`. Neither `while`
  nor `for` can stand in:

  * `(while true ...)` emits `while true` in Rust, which rustc lints
    (`while_true`, warn by default) precisely because `loop` is the intended
    spelling. Generated code that trips a lint the hand-written code did not
    is a regression in the output.
  * and `loop` is not merely `while true` to the type checker. Rust knows a
    `loop` with no `break` diverges, so a function whose every exit is a
    `return` inside one needs nothing after it; `while true` does not carry
    that, and the same function then fails to compile for want of a trailing
    return -- which JAVA in turn rejects as unreachable. Between them the two
    spellings leave no shape that satisfies all three, which is the argument
    for having this form at all rather than a workaround at each use."
  [ctx form]
  (kin/emit! ctx (kin/indent-of ctx)
                   (if (= :rust (t ctx)) "loop {\n" "for (;;) {\n"))
  (kin/scoped ctx {:key :in-loop :value true :indent 1}
                    (fn [inner] (doseq [f (rest form)] (kin/statement! inner f))))
  (kin/emit! ctx (kin/indent-of ctx) "}\n"))

(defn- case-form
  "`(case expr [tags...] value ... :else value)`. A form that RETURNS.

  The arms are VALUES, not statements, and that is forced rather than chosen.
  A Rust match arm is an expression, so writing `return X;` inside one emits
  `=> return CAT_MAP;,` -- which is what the first attempt did. The JVM and
  CLR need the opposite: a `case` label cannot yield a value, so each arm has
  to `return` for itself.

  So the source says the VALUE and each target spends what it must: Rust wraps
  the whole match in one `return`, the other two put a `return` in every arm.
  This is the statement/expression split the design anticipated, arriving in
  the first place it actually bites."
  [ctx form]
  (let [[_ subject & clauses] form
        ;; A `(comment ...)` between arms is emitted where it stands and does
        ;; NOT consume an arm. Without this a comment could only sit outside
        ;; the switch, which is not where it explains anything.
        pairs (loop [cs clauses acc []]
                (cond (empty? cs) acc
                      (and (seq? (first cs)) (= 'comment (first (first cs))))
                      (recur (rest cs) (conj acc [:comment (first cs)]))
                      :else (recur (drop 2 cs) (conj acc [(first cs) (second cs)]))))
        scrut (strip-parens (kin/render ctx subject))]
    (kin/emit! ctx (kin/indent-of ctx)
                     (if (= :rust (t ctx))
                       (str "return match " scrut " {\n")
                       (str "switch (" scrut ") {\n")))
    (kin/scoped
     ctx {:key :in-case :value true :indent 1}
     (fn [inner]
       (doseq [[labels body] pairs]
         (if (= :comment labels)
           (comment-form inner body)
           (let [else? (= :else labels)
               ls (when-not else? (mapv (fn [l] (kin/render inner l)) labels))]
           (case (t inner)
             :rust (kin/emit! inner (kin/indent-of inner)
                                    (if else? "_" (str/join " | " ls)) " => ")
             ;; FOUR LABELS TO A LINE, which is what the hand-written files
             ;; do. A one-per-arm line for eight tags runs past 150 columns,
             ;; and the not-worse rule covers what a diff reads like as much
             ;; as what it compiles to.
             (if else?
               (kin/emit! inner (kin/indent-of inner) "default:\n")
               (doseq [chunk (partition-all 4 ls)]
                 (kin/emit! inner (kin/indent-of inner)
                                  (str/join " " (mapv (fn [l] (str "case " l ":")) chunk))
                                  "\n"))))
           (if (= :rust (t inner))
             (kin/emit! inner (strip-parens (kin/render inner body)) ",\n")
             (kin/emit! inner (kin/indent-of inner) "    return "
                              (strip-parens (kin/render inner body)) ";\n")))))))
    (kin/emit! ctx (kin/indent-of ctx) (if (= :rust (t ctx)) "};\n" "}\n"))))

(defn- const-name
  "Rust and Java SCREAM a constant; C# pascalises it. `SEED` against `Seed`,
  `HASH_TRUE` against `HashTrue` -- the existing three files already disagree
  this way and their callers are written to it, so the port has to keep it."
  [target nm]
  (if (= :csharp target)
    (str/join (mapv str/capitalize (str/split (str nm) #"_")))
    (str nm)))

(defn- defconst-form
  "A named constant. `^:pub` when it is part of the API."
  [ctx form]
  (let [[_ nm v] form
        pub? (:pub (meta nm))
        cn (const-name (t ctx) nm)
        tag (kin/tag ctx (:tag (meta nm)))
        _ (kin/define-tag! ctx {:scope :private} nm tag)
        ty (get-in tag [:types (t ctx)])
        ;; The SOURCE says whether a constant is written in hex, by wrapping
        ;; it in `(hex ...)` or not. Deriving it from the value produced
        ;; `HASH_TRUE = 0x4cf`, which is the right number and the wrong
        ;; constant -- 1231 is a number a reader recognises and 0x4cf is not.
        lit (if (seq? v) (kin/render ctx v) (str v))]
    (kin/emit!
     ctx (kin/indent-of ctx)
     (case (t ctx)
       ;; Same rule as `defn`, and for the same reason: a constant defined
       ;; in a generated module is read from outside it.
       :rust (str (if pub? "pub " "pub(crate) ") "const " cn ": " ty " = " lit ";\n")
       :java (str "public static final " ty " " cn " = " lit ";\n")
       :csharp (str (if pub? "public " "internal ") "const " ty " " cn " = " lit ";\n")))
    (kin/define-name!
     ctx {:scope (if pub? :public :private)} nm (reduce (fn [m tg] (assoc m tg (const-name tg nm))) {} [:rust :java :csharp]))))

(defn forms
  "The shape forms. `:default-tag` is the tag an untagged name is given, which
  is a per-subject choice and so is asked for rather than assumed.

  NOT called `forms-for` any more: that name was the CONVENTION kin used to
  discover a vocabulary by, and a function that merely builds a form table
  should not look like one. A subject calls this and merges the result into
  its own `:forms`."
  [{:keys [default-tag compound]}]
  (merge
   {'defn (defn-form default-tag)
    'let (let-form default-tag)
    'defstruct (defstruct-form default-tag)
    '. field-form 'set (set-form (merge base-compound compound)) 'if if-form 'return return-form
    'comment comment-form 'doc doc-form
    'case case-form 'defconst defconst-form
    'local (local-form default-tag)
    'for (for-form default-tag) 'while while-form 'forever forever-form
    ;; `(declare foo bar)` -- a FORWARD REFERENCE within this namespace.
    ;;
    ;; Two mutually recursive functions in one file need it, the same way
    ;; Clojure does. Across namespaces it is neither needed nor available:
    ;; dependencies form a DAG, so everything a namespace requires is already
    ;; emitted in full before it starts.
    ;; `(declare foo bar)` -- a FORWARD REFERENCE, in two halves.
    ;;
    ;; `:declare` reserves the names so link can resolve a reference to them
    ;; before the definition arrives. `:generate` EMITS a forward declaration
    ;; where the target language needs one, and that is the better reason for
    ;; keeping `declare` mandatory than strictness was: if the target cannot
    ;; HOIST, the generated code needs its own forward declaration -- a C
    ;; prototype, a Rust ordering constraint. Java hoists within a class and
    ;; needs nothing, so it emits nothing.
    ;;
    ;; A TARGET SAYS WHETHER IT HOISTS, and how it spells a forward
    ;; declaration if it does not. kin has no view on either: `:hoists?`
    ;; false plus `:forward-declaration` is the target describing its own
    ;; language, exactly as `:reserved` and `:local-name` are.
    'declare {:declare (fn [ctx form]
                         (doseq [nm (rest form)]
                           (kin/declare-form!
                            ctx {:scope (if (:pub (meta nm)) :public :private)} nm)))
              :generate
              (fn [ctx form]
                (doseq [nm (rest form)]
                  (kin/declare-form!
                   ctx {:scope (if (:pub (meta nm)) :public :private)} nm))
                (let [tgt (get-in ctx [:targets (t ctx)])]
                  (when-not (:hoists? tgt true)
                    (if-let [spell (:forward-declaration tgt)]
                      (doseq [nm (rest form)]
                        (kin/emit! ctx (kin/indent-of ctx) (spell ctx nm) "\n"))
                      (throw (ex-info
                              (str "kin: " (t ctx) " says it does not hoist, so"
                                   " `(declare " (str/join " " (rest form))
                                   ")` needs a forward declaration in the"
                                   " output -- but the target supplies no"
                                   " `:forward-declaration` to spell one.")
                              {:target (t ctx) :names (vec (rest form))}))))))}
    'break (fn [ctx _] (kin/emit! ctx (kin/indent-of ctx) "break;\n"))
    'continue (fn [ctx _] (kin/emit! ctx (kin/indent-of ctx) "continue;\n"))
    'do (fn [ctx form] (doseq [f (rest form)] (kin/statement! ctx f)))}
   (reduce (fn [m s] (assoc m s (op-form s))) {} (keys ops))))

;; ---------------------------------------------------------- as a vocabulary

(def targets
  "The targets this vocabulary can speak.

  Three, because the `case` in every form above has three arms. That is a
  FACT ABOUT THIS FILE rather than about kin: a project that wants a fourth
  language writes its own shape vocabulary, or shadows the forms it needs
  (see `require-scope` -- first match wins), and kin needs no change either
  way. `kin.lang` is one vocabulary that ships in the box, not the language."
  #{:rust :java :csharp})

(def vocabulary
  "`kin.lang` as an ordinary vocabulary, requireable from a source:

      (ns runtime.thing
        (:require [my.subject :refer [Value slot]]
                  [kin.lang :refer [defn let if return]]))

  It carries NO tags and NO names -- those are the subject's, always -- and
  no default tag, so every binding in a source that requires this directly
  has to say what it is. A subject that wants an untagged local to mean
  something calls `forms` with a `:default-tag` and merges the result
  instead, which is what every vocabulary in the tree does today."
  {:namespace 'kin.lang
   :targets targets
   :tags {}
   :names {}
   :forms (forms {:default-tag nil})})
