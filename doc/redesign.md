# DECISIONS: the seven open questions, answered

## 1. Locals win once declared — and `declare` becomes a LINK phase

> Locals should win if they've been declared by the point where they're used.
> Which means our 'declare' phase becomes more than that, it becomes a 'link'
> phase, because it's what knows whether something has been declared at a
> certain point yet. The link phase should be able to hand-off state/data to
> the generator for the same form, this allows the link phase to tell the
> generator how to reference a thing, as a local or as a namespace alias, etc.

Bigger than the precedence question it answers. Resolution stops being a
static scope lookup and becomes POSITIONAL -- "is this declared by HERE?" --
which only a phase walking in order can know.

So the phase resolves references and RECORDS, per form, how each should be
emitted: as a local, through a namespace alias, fully qualified. The generator
then emits what link decided rather than deciding for itself. Two passes of
the shape every compiler has: resolve, then emit.

**This corrects something I wrote.** I said the declare pass "walks
CONTAINERS, not bodies" -- a class recurses, a `defn` produces its entry from
the head and stops. That is wrong under link. A reference lives INSIDE a body,
so link must walk bodies to resolve it. The link phase is a full walk; only
the EXPORT half of it can stop at the head.

It also needs a handoff channel: link writes per-form resolution data, the
generate context reads it. That is a third thing the context protocols must
carry, beside scope and their own capability.

## 2. `declare` stays mandatory — because not all languages hoist

> Yes keep declare mandatory, because not all languages support hoisting.

A better reason than the one I gave. I argued strictness -- that without
`declare`, a typo becomes an unsettled promise instead of an error. True, and
secondary.

The real reason is semantic: if the TARGET language cannot hoist, the
generated code needs a forward declaration of its own -- a C prototype, a Go
or Rust ordering constraint. So `(declare foo)` is not only kin bookkeeping;
in some targets it must EMIT something. That makes it a form with a
`:generate` half, not merely a `:declare` one.

## 3. How the hand-written side consumes a module

> mod for rust, import static for C# and Java.

Settled. `mod` in Rust, `import static` in Java, `using static` in C#. All
three leave existing call sites unqualified.

## 4. The three host functions: PORT them

> Port.

`bn-new`, `cn-copy-set-val`, `cn-set`. Takes flint's external surface to zero,
which is a cleaner proof of the mechanism than three permanent exceptions.
Prove the manual-namespace mechanism on them first, then port -- the mechanism
is still needed for genuinely external things.

## 5. Layout: `kingen/*` then the host language's convention

> 'kingen/*' then the host language's convention, for java that means each
> namespace element gets its own subdir, and the final namespace tail is the
> class name. Similar for C# I believe; I don't know for rust.

**Rust is the same shape**, which the author did not know and which is worth
recording: a module `flint::rt::maps` lives at `flint/rt/maps.rs`, with
`flint/rt.rs` (or `flint/rt/mod.rs`) carrying `pub mod maps;`. Directory
mirrors namespace exactly as in Java and C#.

    namespace   flint.rt.maps

    kingen/flint/rt/maps.rs       + `pub mod maps;` in flint/rt.rs
    kingen/flint/rt/Maps.java     class Maps, package flint.rt
    kingen/flint/rt/Maps.cs       namespace flint.rt, class Maps

The one asymmetry: Rust needs the parent to DECLARE the child, so something
must maintain `pub mod maps;`. Java and C# need nothing. That is the same
"who writes the consuming line" question as item 3 and should be answered the
same way.

## 6. Provenance format: the agent's call

> Find something that works, I'll leave it to the agent.

With the standing requirement from the promise section: it must name which
node, from which source form, waiting on what. "Something did not settle" is
the failure this project keeps removing.

## 7. Regions are SCRUBBED, not retired per unit

> Regions should be scrubbed, and all existing instances converted. Trying to
> do this kind of thing in pieces is a failing game and leads to bad design.

**This overrules my recommendation**, which was to retire regions per unit as
each became fully owned and keep the splice path meanwhile. The author is
right about the failure mode: supporting both mechanisms means the design
bends to accommodate a transitional state that then never ends.

So: every one of the sixteen sources becomes a whole module under `kingen/`,
the hand-written files gain their `mod`/`import static`/`using static` lines,
and the splice path is DELETED.

Note what this depends on, because the ordering matters: the scrub needs
modules working, which needs `:emit`, the link phase and exports. It is the
LAST step, not a parallel one. And `bin/check-kin` changes meaning with it --
it stops comparing regions and starts comparing whole files, which is a
simpler check than the one it replaces.

---

# OPEN QUESTIONS

Five, in the order they should be answered. The first is the sharpest because
a concrete constraint rules out the obvious reading.

## 0. A FORM HAS TWO CONCERNS: how to use it, and how to produce it

The author, on the design we had converged to:

> What we have now means, in order to reference/use things from a namespace,
> we need to regenerate/emit it. That seems wrong. It's two separate concerns:
> how do I use the thing you produce, and how should it be produced.
>
> So I think we do need a form to implement two separate concerns, so it
> breaks into two functions not one. The first is the 'how do I use the thing
> you produced from this form, in an external namespace'; most forms will have
> no impl for this, but 'defn', 'def' will. The other question is 'how should
> this form emit to generate the thing'? Maybe the first question is optional
> metadata on the form fn?

Right, and the argument is separation of concerns rather than mechanism, which
makes it stronger: depending on B should not mean rebuilding B. B may be
vendored, already generated, or simply unchanged.

### It rescues `heads-only`, in the shape that works

I retracted heads-only after framing it as "kin scans heads", which kin cannot
do -- it has no idea what a `defn` is. This fixes precisely that. THE FORM
IMPLEMENTATION supplies the use-half, so kin never needs to know what it is
looking at; it invokes what the target wrote.

    scanning B    for each top-level form, look up its implementation and call
                  the USE half if it has one. Nothing emitted, no body read.
    emitting A    `merge-two` resolves to that entry, invoked with A's CONTEXT,
                  so it does the same-module check and registers its import
                  there.

The second line is the subtle one: **the use-function runs in the DEPENDENT's
context**, not the definer's. It is the same call-emitter `define-form!` would
have registered -- obtained without emission.

### The shape: a form is a map of three, and two context TYPES

From the author, and it makes the separation structural rather than a rule:

> I don't think the 'emit'/'generate' form function should be allowed to
> register the thing to answer the first question at all. So each function
> gets a different kind of context, a implementing a different protocol. Both
> kinds of context need to implement the scope stack though, and we probably
> even want to pull that out into its own thing so it doesn't need to be
> re-implemented in each branch:

```clojure
(def class-form
  {:wrap     (fn [context f] (kin/scoped ... (f)))
   :declare  (fn ...)
   :generate (fn ...)})
```

> And if the form is a function then it's assumed to be 'generate'?

Yes -- and that default is right, because most forms have neither of the other
two. `let`, `if`, `while` and every operator are generate-only. A bare
function stays the common case; the map is the exception.

### Enforcement by construction, which is the point

Two context types implementing different protocols means a `:generate`
function CANNOT register a declaration. Not "should not" -- cannot. That
matters here specifically: permitting it would quietly reintroduce exactly
what section 0 removed, where knowing how to use a thing requires having
emitted it. A convention drifts; a protocol does not.

Sketch:

    Scoped      -scoped, -get, -get-all        BOTH context types
    Declaring   -define-form!, -define-tag!, -define-name!    declare only
    Emitting    -emit!, -render, -anchor!                     generate only

### Why the scope stack must come out, and the invariant it buys

`:wrap` runs in BOTH passes -- the declare scan needs to know it is inside a
class to produce `Maps.mergeTwo`, and generate needs the same frame to indent
and qualify. If the scope logic lived in each branch there would be two copies
that can disagree, which is the failure mode this codebase keeps finding.

So it is pulled out, and that yields a checkable invariant:

**`:wrap` may use ONLY the scope protocol.** It is handed whichever context
the current pass uses, so an attempt to emit fails against the declare
context. The constraint enforces itself instead of needing a rule.

### Two consequences worth stating before someone asks

**`:wrap` cannot emit the class header.** Generate mode needs `class Maps {`
and its closing brace; declare mode needs neither. So `:wrap` establishes
scope only and `:generate` emits header, wrapped body, footer. That keeps
`:wrap` honestly polymorphic rather than secretly generate-flavoured.

**The declare pass walks CONTAINERS, not bodies.** A class's `:declare`
recurses into its children to find their declarations; a `defn`'s `:declare`
produces its entry from the head and does not descend. So `let`, `if`, `while`
-- nearly every form -- never take part in the declare pass and never need
anything but a bare function. "Does the declare pass have to handle every
form?" is the first question an implementer asks, and the answer is no.

### Two decisions, not defaults

**Metadata on the fn, or a map?** Metadata is lighter and leaves a bare
function working. A map -- `{:emit f :use g}` -- is discoverable and
checkable: kin can say "this entry has a `:use` that is not a function". Take
the map, for the same reason vocabularies became maps. Things kin must check
should be visible to it.

**Does the scan need B's require scope?** For name and arity, no -- both are
in the param vector. For a RETURN TAG, yes, since the tag resolves through B's
requires. So scanning B needs B's requires scanned first, which the DAG
already provides. Worth stating, because it means exports cannot be built from
a lone file and someone will assume they can.

### The two-level story this produces

    inter-namespace   SCAN based -- `:use`, no emission, DAG ordered
    intra-namespace   PROMISE based -- forward references within one file

The promise machinery stops having to carry the cross-namespace case at all.
Its scope narrows to exactly the `(declare foo)` problem it was introduced
for.

### And it permits something the old design forbade

Once exports come from a scan, an export vocabulary is a VALUE. It can be
cached, or persisted, or vendored by a downstream project that never has B's
sources. Not required now; worth knowing the design allows it, because
emit-to-learn structurally did not.

## 1. THE EMIT MODEL IS WRONG: nodes must be promises

Settled by the author, and it supersedes the two options below.

    (declare foo bar baz)
    (defn zip ... references foo ...)
    (defn foo ...)

A BARE declare is the crux. My option (A) assumed the declaration carried a
signature, and with a signature there is something to emit a call from. With
`(declare foo bar baz)` there is not, so `zip` genuinely cannot produce text
until `foo` is defined. Promises are not one way to do this; they are the only
way.

> We need each node to be a promise which emits when all dependencies are
> available; and we need it to be scannable so we can find the
> missing/unsettled things at the end when we're ready to emit, because the
> promises aren't time based they're just dependency/order based; by emission
> time they should all be either settled or something is referenced that isn't
> defined.

With a form declaring what it waits on:

```clojure
(kin/with ctx {:forms [...] :tags [...]}
  (fn [{:keys [forms tags]}] ...))
```

### It is a generalisation, not a replacement

`resolve-sink` already walks a tree of sink items and resolves ANCHORS at
join. An anchor IS a deferred node -- with no dependency set, and only legal
in statement position. So the change is three widenings of something that
exists:

1. deferred nodes in EXPRESSION position, not only sink position;
2. DEPENDENCY SETS on them, so resolution is ordered rather than positional;
3. the tree SCANNABLE for unsettled nodes at the end.

Anchors should then become the degenerate case -- a promise with no
dependencies -- rather than kin carrying two deferral systems.

### One scan, two failure modes

A reference to something never defined, and a true cycle where two nodes each
wait on the other, are the same observable thing: UNSETTLED AT JOIN. One scan
catches both, and the dependency sets say which is which -- nothing waiting on
it versus each waiting on the other.

### What changes hardest: `render`

`kin/render` returns a String today, and every form composes with
`(str (render a) " + " (render b))`. Once a sub-expression may be unsettled,
`render` cannot promise text. Either it returns a fragment that may be a
promise and concatenation becomes a deferring join -- blast radius inside kin
-- or every form that builds strings becomes dependency-aware, which pushes it
into every vocabulary including the user's. The first.

### The risk: diagnostics

Eager emission fails WHERE THE FORM IS. Promise resolution fails at the end,
far from the cause. So the scan must carry provenance -- which node, from
which source form, waiting on what -- or the result is "something did not
settle", which is the useless kind of error this project keeps removing.

`{:forms [...] :tags [...]}` is right for exactly this reason: the
dependencies are DATA, so an unsettled node can say what it wanted. `kin why`
gets better for free.

### The division to keep explicit

Namespaces stay a DAG resolved topologically -- coarse, ordered. Promises
handle INTRA-namespace forward references -- fine-grained, order-free. Not
competing: the DAG means every import is settled before a namespace starts, so
the only unsettled nodes at join are local ones.

> **DONE, and flint is byte-identical at every step.**
>
> A node is `{:kin/node {...}}` in one of two kinds. An ANCHOR is the
> degenerate promise -- no dependencies, only its content late -- so kin
> carries one deferral mechanism rather than two.
>
> `render` STILL RETURNS A STRING, and that is the decision that kept the
> blast radius inside kin. A true fragment type would have broken every
> `(str (render a) ...)` in kin.lang, in flint's vocabularies and in the
> user's. Instead a deferred node answers a NUL-delimited TOKEN and registers
> a promise; the token flows through `str` like any other text and is
> substituted for the settled text at join. An anchor is a hole in the SINK; a
> token is a hole in the TEXT.
>
> `kin/with ctx {:forms [...] :tags [...]} f` runs `f` IMMEDIATELY when
> everything is available, which is every call in a project with no forward
> references -- so such a project makes no promises at all and cannot change.
> `test/promises.clj` case 6 asserts exactly that.
>
> The unsettled scan carries provenance -- source label, source form, and the
> dependency set -- and distinguishes the two failures by data: nothing
> defines it, versus these wait on each other.
>
> KNOWN AND DOCUMENTED: `strip-parens` and `delimited?` inspect rendered text,
> and a deferred sub-expression is a token at that moment. So parenthesisation
> around a deferred node is decided on the token -- `return (a + b);` where an
> eager render gives `return a + b;`. Cosmetic, reachable only through a
> forward reference, and stated rather than hidden.

## SUPERSEDED: what does `declare-form!` carry?

Described as "an indirecting placeholder that gets filled later". **kin cannot
do that for call sites.** `kin/render` collapses a form to a STRING
immediately; anchors survive only in SINK position, because `resolve-sink`
walks sink items at join time and a rendered expression is text by then. So an
inline `(coll-assoc rt ...)` cannot be a hole filled later.

Two designs survive:

* **(A) the declaration carries the SIGNATURE.** C-style: enough to emit the
  call, with `define-form!` supplying the body later. One pass, errors where
  they are written. Costs a restated signature, so `define-form!` must check
  it agrees with any prior `declare-form!` and refuse if not.
* **(B) `defn` defers its BODY into an anchor.** Bodies are statement
  position, so anchors work. Every definition registers as the file is walked
  and bodies resolve at join with everything present -- no `declare-form!`
  needed at all. Costs diagnostics: a body error surfaces at join rather than
  at the form.

Leaning (A), because errors stay local and resolution stays strict where it is
read. (B) needs no new API, so it deserves explicit rejection rather than
omission.

## 2. Precedence: locals, imports, shape forms

`form-fn` is requires-first, locals-second, justified by `declare!`'s
docstring -- "a vocabulary name still wins, so a file cannot quietly redefine
`let`". Written when requires carried only `let` and `if`.

Once requires carry other modules' function names, that means an imported name
silently beats your own definition in your own file. No language does that.
But the obvious inversion breaks the case the rule protects, and C2 already
lets an earlier require shadow `kin.lang`, so "requires are inviolable" is
already false.

**Recommendation: a local definition colliding with a referred name is an
ERROR**, naming both -- not a precedence rule. Either ordering silently
changes what a symbol means, and this project has been bitten by exactly that
repeatedly. An error costs one rename and removes the class.

## 3. Layout on the hand-written side

Settled: one module per namespace, generated code in its own subtree. Open:
what the HAND-WRITTEN side does about it. A `mod` line in `lib.rs` for Rust,
an `import static` for Java, `partial class` or `using static` for C# -- three
different edits to files kin does not own. Whether kin generates them, prompts
for them, or leaves them wholly to a person is undecided.

## 4. The three host functions: expose or port

"Build the mechanism, prove it, then maybe port them." Still maybe.
`cn-copy-set-val` is 23 lines and needs nothing kin lacks; `bn-new` and
`cn-set` are similar. Porting them after the mechanism is proven would make
the external surface ZERO, which is a cleaner proof than three permanent
exceptions.

## 5. Not a design question: the red `check-kin`

Sixteen sources failing with `kin/gen` printing usage and exiting 2. It looked
like an in-flight script refactor. Asked twice; not yet answered. Worth not
closing as "probably fine".

---

# CORRECTIONS FROM THE AUTHOR — these overrule what follows

Two rulings arrived after the first four commits of this work. Both overrule
text further down and one overrules a decision already taken and written up in
`doc/decisions.md`. Read these before anything else in this file.

## C1 — Tags are DATA. kin does not dispatch on them.

Verbatim:

> Tags must always be *just data*, their purpose is to let the forms decide
> what to do with them. Tags have no *dispatch*, the forms decide to (and
> whether) to handle the given tag.

kin carries tags and never interprets them. A form receives each argument as
rendered text plus whatever tag it carries, and does as it likes. kin has no
dispatch table, no match rules, no notion of what any tag MEANS, and no
opinion about whether two tags are compatible.

This DELETES two questions item 5 posed as open — "table or function for
dispatch" and "what happens on an unknown tag". Neither is kin's business. A
form that wants to refuse an unknown tag refuses it; a form that wants a
default takes one. `kin.lang` may implement whatever dispatch it likes for its
own operators, and that is `kin.lang` speaking as a vocabulary, not kin
speaking as a generator.

What kin owes is therefore narrow, which is the point:

* a form's product is text plus an optional tag;
* an enclosing form sees each argument's text and tag;
* declared tags (`^I32` on a parameter, a `:tag` on a call, a `defn` return)
  flow to the products that carry them;
* an unannotated local may take its tag from its initialiser.

Nothing else. An implementation that finds itself asking what a tag means has
gone wrong.

## C1b — A tag WRITTEN AT THE CALL SITE passes down

Verbatim:

> Also make sure something like `(foo ^MyTag (bar ...) ^MyOtherTag (baz ...))`
> passes the user provided tags down.

So an argument's tag has two possible sources, and the explicit one wins:

1. what the inner form declares its product to be — `(bar ...)` says `Value`;
2. a `^Tag` written on the argument AT THE CALL SITE — which OVERRIDES it.

`foo` above must see two arguments, one tagged `MyTag` and one `MyOtherTag`,
whatever `bar` and `baz` say about themselves. That is the escape hatch that
makes the whole scheme usable: a form cannot always know what it produced, and
the person writing the call often does.

Note what this does NOT mean. kin still does not interpret `MyTag` — it is an
arbitrary symbol, it need not be a declared tag, and kin never asks whether it
is compatible with anything. kin's whole job here is to CARRY it from where it
was written to the form that receives it. Per C1, what `foo` does with a tag
it has never heard of is `foo`'s business.

Two consequences worth designing for rather than discovering:

* metadata must survive reading. `^MyTag (bar ...)` puts metadata on the LIST
  `(bar ...)`, and anything that rebuilds forms while walking them will drop it
  unless it is careful.
* the annotation is on the ARGUMENT, so it must be read where the argument is
  rendered, not where the form is looked up.

> **DONE.** `kin-render-tagged` reads `^Tag` from the argument's metadata and
> prefers it to whatever the inner form declared. An annotation that resolves
> through the require scope arrives as that tag's value; one that resolves to
> nothing arrives as the bare symbol, because kin does not ask whether a tag
> is declared. Pinned by `test/tags.clj` case 5, including the undeclared
> case. The EDN reader preserves metadata on both lists and symbols, so
> nothing had to change about reading.

## C4 — kin is a LIBRARY. `bin/kin` should not exist.

Verbatim:

> Why is there a `bin/kin` at all, I expected kin to be exposed as a library,
> not a cli. The actual script should be in our own code. That's why my
> original proposal included a `:vfs` (virtual file system) protocol impl for
> each target.

This is the answer to "how does kin touch the filesystem", and it is not the
one the code gives today. `bin/kin` is 503 lines and nearly all of it is
LIBRARY LOGIC sitting in a script:

    load-vocabulary  read-source  generate
    destination  splice  block-lines  emit  destinations
    symbols-in  bound-names  kind-of  why
    wrapped  targets-report

Only two things in that file are genuinely a command-line tool: reading
`kin.edn` off disk, and the `*command-line-args*` dispatch at the bottom.
Everything else belongs in `src/kin/`, and the script belongs in the CONSUMER'S
tree — flint already has `kin/gen`, `kin/emit` and `kin/verify` wrappers that
would call the library directly instead of shelling to `bb ../kin/bin/kin`.

The recent work went the WRONG WAY on this, and deliberately, for a reason
that was good and an aim that was wrong: splicing moved out of a shell script
into `bin/kin` because a shell loop cannot call a namespace-to-path function.
That reasoning holds. The conclusion should have been "splicing is library
code", not "splicing goes in the CLI".

### The `:vfs`, which is why this matters

kin must not know what a filesystem is. Each target carries a vfs
implementation the user supplies:

```clojure
{:key  :rust
 :ext  "rs"
 :vfs  (->DiskVfs "runtime/src")     ; a protocol impl, from the USER
 :path (fn [ns] ...)
 :wrap ...}
```

An in-memory vfs then makes emit testable without touching a disk, a
ClojureScript project can supply a Node one, and kin stops being
babashka-only — which it is today, `.cljc` extension notwithstanding, because
`bin/kin` reaches for `java.io.File` and `babashka.fs` directly.

Shape to settle with the author, since "protocol impl" was said but the
operations were not:

```clojure
(defprotocol Vfs
  (-exists? [this path])
  (-read    [this path])
  (-write   [this path content]))
```

> **DONE, with the operations CHOSEN rather than given.** Those three are
> what `emit!` actually needs and nothing more. Listing, deleting and
> creating directories are deliberately absent: kin writes INTO files a
> person already wrote, so it never creates one, and a protocol with three
> operations is easier to implement for a new host than one with eight. A
> protocol grows more easily than it shrinks. `kin.vfs/memory-vfs` is the
> payoff -- `test/emit.clj` splices regions end to end with no directory.

### What this implies for the library's surface

The most library-shaped split, and the one to aim at:

* `generate` takes SOURCE TEXT and config and returns `{target text}`. Pure.
  It does not open the source file; the caller does.
* `emit!` uses each target's vfs to read the destination, splice the region,
  and write it back. The only I/O kin performs, and all of it through the
  user's implementation.
* `why` and `targets-report` return DATA. Printing is the caller's business,
  which is what makes them usable from something other than a terminal.

> **DONE.** `bin/kin` is deleted; 480 of its 503 lines are now `kin.project`
> and `kin.vfs`. flint's `kin/gen`, `kin/emit`, `kin/destinations` and
> `kin/kin` are real scripts over the library, and the printing that used to
> be in the CLI lives in `kin/kin` where a project can change it. `kin.edn`
> is gone too: a `:path` is a function and a `:vfs` is a protocol impl, so
> the configuration is code, in `flint.impl.project`.

### And one thing kin cannot do at all

`verify` compiles the generated code with `rustc`, `javac` and `dotnet`, and
runs it. That is PROCESS EXECUTION, not filesystem, and no vfs abstracts it.
It belongs in the consumer's tree and always did. Worth stating plainly so the
three layers are visible: `generate` is pure, `emit!` needs a vfs, and
`verify` needs a machine.

## C5 — `emit!` should be able to do the whole source tree, and sources get their own vfs

Verbatim:

> Does `emit!` scan src paths for `.kin` files and emit all? I think some
> sugar for that would be good it it doesn't already. We could give the src
> dir its own vfs for the scanning + reading.

**It does not, today.** `emit` takes one source path. The scanning and the
looping live in the CONSUMER'S shell scripts -- flint has
`for src in kin/*.kin` in `bin/check-kin`, and had the same loop in its `gen`,
`emit` and `verify` wrappers. Every consumer reimplements the same three
lines, and gets to reimplement the ordering and the error handling with them.

So: an `emit-all!` (name to taste) that scans, reads and emits the tree.

### This settles a question C4 left open

C4 asked whether there is one vfs or one per target. The answer is BOTH, and
they are different vfs's doing different jobs:

* the SOURCE vfs scans and reads -- it needs a listing operation, which is the
  only reason `-list` exists;
* each TARGET's vfs reads and writes its destinations -- it never lists.

```clojure
{:sources {:vfs (->DiskVfs "kin") :match "*.kin"}
 :targets [{:key :rust :vfs (->DiskVfs "runtime/src") :path (fn [ns] ...)}
           ...]}
```

That also means the protocol is not one flat set of operations. Either it is
one protocol whose `-list` a target vfs may refuse, or it is two protocols --
a readable/listable source and a readable/writable destination. The second is
honester and is what I would build, but it is a choice, so make it
deliberately and write down which and why.

### The source vfs is what makes the DIAGNOSTICS testable

From the author, and it is the strongest argument for the source vfs:

> The suggestions for a src vfs also helps with the 'why' question, and with
> reporting which kin files will actually be generated for which targets, and
> why.

`why` and `targets-report` are the two commands from item 4, and their whole
job is to ENUMERATE: which sources exist, which targets each generates for,
which vocabulary contributed each symbol, what got shadowed, what was ruled
out and by whom. Enumeration is exactly what the source vfs provides, so they
stop being commands that glob a real tree and become functions over a config.

**And then they can be tested, which today they cannot.** A memory vfs holding
three fabricated sources -- one that generates for every target, one excluded
by an `:only`, one whose vocabulary cannot speak a target -- is a fixture that
asserts the report says all three of those things and says WHY. No directory,
no temporary files, no chance the test writes into the tree it is checking.
The agent already built exactly that shape for `emit`; the diagnostics are the
part still without it.

This matters more than it sounds. `kin why` exists to catch the class of bug
that let `LS_THUNK` through -- a symbol resolving to nothing in particular
while every gate stayed green and the CLR quietly stopped compiling. A
diagnostic built for that job is trusted by definition: nobody double-checks
the tool they reached for BECAUSE they could not see the problem. So a `why`
that is quietly wrong is worse than no `why` at all, because it ends the
search. Of everything in this redesign it is the piece that most needs a test
and, until the source vfs, was the piece that could least easily have one.

### FAILURE: the whole batch reverts, and staging is how

Decided by the author, in two steps -- first that a source whose targets do
not all succeed must be reverted, then:

> Actually, if *any* emission fails maybe we should revert the full batch?

Yes. `emit-all!` is ATOMIC over the whole run: every source, every target, or
nothing. The reasons the wider granularity is better than per-source:

* a partial tree is a state nobody designed and no gate describes. With eight
  of sixteen sources emitted, `check-kin` reports the other eight as drifted
  -- which is true, and says nothing about what happened;
* recovery becomes trivial and needs no thought: fix, re-run;
* and the invariant this project exists to hold is that the runtimes AGREE.
  A half-emitted run is the one state that breaks it on purpose.

**It also makes the implementation simpler, not harder.** Three phases:

1. GENERATE every target of every source, into memory. A form not in scope or
   an undeclared constant stops the run here, having written nothing.
2. SPLICE. Read each destination once, apply every region bound for it, keep
   the result in memory. A missing marker stops the run here, still having
   written nothing.
3. WRITE. If a write fails partway, restore the originals.

**The rollback data is free.** Splicing a region requires reading the whole
destination anyway, so phase 2 has already got every original in hand. Nothing
is read twice and no snapshot is taken.

**And the protocol does not grow.** Rollback is `-write` with content already
held; no new operation, no `-delete`, no temp paths. A design change that does
not need the protocol widened is evidence the three operations were the right
three.

#### One consequence that is easy to miss

A destination may be written by MORE THAN ONE SOURCE -- flint had nine sources
emitting into `map.rs` before the consolidation, and nothing forbids it now.
Under batch atomicity those must be spliced ONCE, accumulating every region
for a destination before writing it, rather than read-modify-written per
source.

Doing it per source is wrong in a way that only shows up on failure: emit A
into `map.rs`, emit B into `map.rs`, then C fails, and rolling back to "the
original" restores whichever copy the last read saw -- which is the tree with
A already in it, not the tree the batch started from. Accumulate first, write
once, and the question does not arise.

#### If the rollback itself fails

Report it loudly, naming exactly which destinations hold new content and which
hold old. A restore that fails silently leaves a tree that is neither state
AND no record of which files are which -- strictly worse than the failure it
was trying to undo, because the next run's `check-kin` will report drift
without saying that a rollback is the reason.

#### Ordering

Still worth fixing, but for a smaller reason now. All-or-nothing means order
cannot affect the RESULT. It can still affect the failure REPORT, and a report
whose lines reorder between runs is much harder to read than one that does
not.

## C7 — kin produces MODULES; the language consumes them. `:emit` per target.

Verbatim:

> I don't think kin should really be splicing anyway... does it splice into
> existing files now? It seems like it makes more sense to keep kin as
> 1 namespace -> one file, and have the target code import/require/use it.

and, on my having claimed Java could not do this without partial classes:

> I really don't understand why you need partial classes for this. Most
> languages have some concept of a module. We produce modules, the language
> consumes them.
>
> For example in Java we produce a class file reflecting the namespace path
> and name. In rust we create a module file, and C# we create a namespace file
> + wrapper class (since we need a place to put methods, etc).

**It does splice today**: nine regions each in `map.rs`, `Maps.java` and
`Maps.cs`, kin owning 57% of the Java and 43% of the Rust.

### The partial-class objection was WRONG, and it was mine

I anchored on flint's current shape -- generated methods living inside the
existing `Maps` class -- and concluded that splitting them out needed Java to
split a class across files. That is not the proposal. kin produces a MODULE and
the hand-written code consumes it: a class file at the package path in Java, a
module file in Rust, a namespace file plus a wrapper class in C#. Nothing is
split; something new is created and imported. All three languages get the same
treatment and the differences are only what the wrapper looks like.

Two of the three obstacles I listed were downstream of that error and are
withdrawn. The third stands, and is below.

### `:emit`, per target

From the original proposal, and the mechanism that makes the above kin's
business to allow and nobody's business to hardcode:

```clojure
:emit (fn [ctx kin-forms] ...)
```

It is handed the context and ALL the forms, the `ns` form included, and it
decides everything about the file:

* the prefix and suffix -- `package` and class in Java, namespace and wrapper
  class in C#, `impl Rt {` or a bare module in Rust;
* ANCHORS for imports, requires and usings, so a form that needs a type in
  scope can register it and have it appear at the top. kin already has
  anchors (`kin-emit-anchor!`); this is what they are for;
* whatever other context the form emitters need, established before they run;
* and the sub-emission of each form, INVOKED BY IT -- so it may emit between
  forms, not only around them.

Named `:emit` rather than `:root`, at the author's word.

This subsumes `:wrap`, whose semantics were never written down, and retires
`:indent`, which `src/kin/target.cljc` already marks provisional and describes
as standing in for it. It is the same principle as C1 and C4: kin carries and
sequences, the user decides. kin should know nothing about what a file looks
like.

It also closes a real bug class. The `Addr`-not-in-scope failure earlier this
session was a missing import in a spliced region -- the host file had got its
imports right once, and the generated code needed one more. With `:emit`
owning the import anchor, a form that needs a type says so and the import is
there.

### How `:emit` and the forms collaborate

From the author:

> For imports, etc; the `:emit` fn and the forms can collaborate. For example
> the `:emit` can install the anchor and add an atom to context, where all
> inner forms can describe what they need as data, then `:emit` can format the
> data into a deduped clean import/require header and emit it at the anchor.

So the shape is:

1. `:emit` drops an anchor where the header belongs and puts an ATOM in the
   context beside it;
2. every form that needs something in scope `swap!`s a description of it into
   that atom -- as DATA, not as text;
3. after the forms have run, `:emit` reads the atom, dedupes and formats, and
   emits the header against the anchor.

**NOTHING HERE IS A NEW KIN FEATURE.** The author was explicit that this is
how to use what is already there, and the "atom in the context" is an ordinary
scope frame:

```clojure
(kin-scoped ctx {:key    :module          ; or :namespace, :class, :file
                 :value  {:imports (atom #{}) ...whatever else the target needs}
                 :indent 1}
  (fn [inner] ...emit each form into `inner`...))
```

`kin-scoped` already takes exactly `{:key :value :indent}`, and `kin-get`
already reads a frame back, so a form does
`(swap! (:imports (kin-get ctx :module)) conj ...)` and is done.
`kin-emit-anchor!` already resolves when the buffer is JOINED, so a header
emitted last appears first. What is missing is only `:emit` itself and a
worked example -- no new context API, no new primitive.

**And SCOPED rather than global is load-bearing here, not merely tidy.** Under
C6 a single run generates every source in one process. A global import atom
would accumulate across files and every generated file would carry every other
file's imports -- wrong output, and wrong in a way that still compiles in
Java and C# and would be found late. A scope frame dies with the file it was
opened for, so files cannot leak into one another. `kin-scoped`'s docstring
already made the general argument -- "that is a stack, not a variable" -- and
batch emission is the case where it stops being a matter of taste.

**kin must not know what an import IS.** A form contributes whatever its
vocabulary and target have agreed on -- `{:type "Addr" :from "crate::mem"}`,
or a bare symbol, or anything else -- and `:emit` turns it into
`use crate::mem::Addr;` or `import com.flint.rt.Addr;`. The data shape is a
contract between the vocabulary and the target, and kin's whole part is
carrying the atom and guaranteeing the ordering. Same division as tags,
targets and the vfs.

**This is the fix for a bug that already happened.** `Addr` went unimported in
a generated `map.rs` earlier this session, because the host file had got its
imports right ONCE and the generated code later needed one more. Under this
pattern the form that emits an allocation declares that it needs `Addr` and
the import is simply there.

> **DONE, in `examples/go`.** `:emit` drops the anchor and puts an atom in
> scope; `need!` in the vocabulary `swap!`s a plain string into it; `:emit`
> sorts, formats and emits against the anchor afterwards. The form knows
> nothing about how a header is written, which is why one `to-str` serves a
> language that says `import "strconv"` and one that says
> `import java.util.Objects;`. Pinned by `test/emit.clj` case 12, including
> the duplicate and the deliberate reverse-order contribution.

#### The hazard: the header must be DETERMINISTIC

An atom collecting contributions is unordered, and Clojure sets are unordered.
If the header comes out in a different order on two runs, the generated file
differs byte for byte while meaning exactly the same thing -- and `check-kin`,
whose entire job is comparing committed output against a fresh generation,
reports drift that is not there.

A gate that fails at random is worse than no gate: it gets re-run until it
passes, and then it gets ignored. So dedupe AND SORT before formatting, and
make the ordering part of what the example demonstrates rather than something
each vocabulary rediscovers.

### What still stands: regions are what made the port INCREMENTAL

Whole-file ownership means kin owns a unit ENTIRELY, and today it owns 43% of
`map.rs`. The rest is blocked on capabilities kin does not have -- closures for
`map_for_each`, tag dispatch for `map_assoc`/`map_get`/`map_dissoc` -- about
375 lines of it. You cannot generate half a file.

So regions are SCAFFOLDING with a defined end, not a feature.

### RETRACTED: ownership percentage was never the constraint

I wrote here that no flint unit could adopt whole-file emission because kin
owns only 43% of `map.rs`, and told the agent not to convert one. The author:

> It doesn't matter if every destination file is majority hand written, the
> generated part just needs to be extracted and the boundaries/interfacing
> plugged in.

That is right and the section it replaces was wrong. The generated code does
not stay in the file it sits in today -- it MOVES OUT into its own module, and
the hand-written file imports it. "You cannot generate half a file" assumed
the half had to stay put. It does not.

This is the SECOND constraint in this document I invented from the same root
cause. The first claimed Java needed partial classes. Both came from taking
where flint's generated code happens to live today as though it were a
requirement. The ownership percentages, which the previous version of this
section made much of, are not a constraint on anything.

### The interfacing surface, measured on `runtime/src/map.rs`

The file kin has worked on most: 1875 lines, 9 regions, 28 generated functions
and 28 hand-written ones.

    generated  -> hand-written    2 calls   bn_new  cn_copy_set_val
    hand-written -> generated    14 calls   bn_datamap bn_key bn_node
                                            bn_nodemap bn_val cn_count cn_key
                                            cn_new cn_val is_bmnode node_assoc
                                            node_dissoc node_find
                                            node_find_scalar

The dependency is strongly ONE-DIRECTIONAL: the hand-written half is mostly a
consumer of the generated half, with two calls going the other way. That is a
far better boundary than a 43/57 split suggests, and it is the number that
matters rather than the percentage.

What each language needs to plug it in:

* **Rust** -- generated functions are `impl Rt` methods, and a crate may have
  several inherent impls. A `maps_gen.rs` with its own `impl Rt` block needs
  ONE `mod` line in `lib.rs` and nothing at any call site. Both back-calls
  work unchanged, since both halves are methods on the same type.
* **Java** -- generated becomes `class MapsGen` of statics; `Maps.java` adds
  `import static com.flint.rt.MapsGen.*;` and its fourteen call sites stay
  unqualified. `MapsGen` calls back to `Maps.bnNew(...)`; mutual references
  between classes in a package are ordinary.
* **C#** -- `partial class Maps` in a second file leaves call sites unchanged
  in both directions, or a separate static class with `using static`.

### THE PLAN, from the author

**1. One module per kin NAMESPACE.** "That's the obvious and expected answer."

**2. Build the external-reference mechanism, prove it on this case, then maybe
port.** Verbatim:

> We're going to need a way to reference native things or things external to
> the namespace at some point anyway; so let's build that, prove it with this
> case, then maybe port them.
>
> For things in our human code, or other generated code, that we need from a
> kin namespace... we simply need to expose them as kin namespaces. Not
> automatically. Manually.
>
> So for example we need access to `bn_new`, so we build a kin namespace
> representing the part/unit those live in, and expose `(bn_new ...)` as a
> form, which generates the code to access that unit.

**3. Generated code lives in its own SUBTREE**, parallel to the human source
tree -- a `kin` or `gen` prefix. Which also settles the naming collision:
`flint.rt.maps` writes to the generated subtree and never contends with the
hand-written `Maps`.

### Why (2) is already proven, and what it makes principled

The mechanism is what flint's `flint.impl.rt` vocabulary ALREADY IS: a
namespace of forms that generate calls into code kin did not write. Every kin
source reaches `slot`, `alloc`, `push` and `mark` exactly this way. Nothing
new has to be invented -- it has to be applied one level up, to units rather
than to primitives.

And it turns an incidental distinction into a real one. The vocabulary has two
call helpers: `own` ("a call to a function in the SAME class") and `sibling`
(a class-qualified call into another unit). Today that encodes *where the
region happens to sit*, which is an accident of splicing. Under one module per
namespace it means what it says: `own` is this generated module, and anything
else is external and must be declared.

**Measured, so the size of the work is known rather than guessed.** Of the 30
forms declared `own` in `flint.impl.rt`:

* **27 are generated BY KIN** -- `bn-key`, `merge-two`, `node-assoc`,
  `coll-assoc`, `cn-new` and the rest. They stay `own`, and correctly so:
  under one-module-per-namespace they really are in the same module.
* **3 are hand-written host functions** -- `bn-new`, `cn-copy-set-val`,
  `cn-set`. These are the entire external surface, and the three that would
  silently break on extraction: `self.bn_new(...)` still resolves in Rust
  (same `impl Rt`, different file) while an unqualified `bnNew(...)` does not
  resolve in Java at all.

Three functions is a small enough surface to build the mechanism against and
large enough to prove it, which is what the author asked for. Porting them
afterwards becomes optional rather than load-bearing -- and `cn_copy_set_val`
is 23 lines needing nothing kin lacks, so it is a cheap follow-up whenever it
is wanted.

### C8 — kin namespaces EXPORT, so requiring one is enough

Proposed by the author:

> Maybe we should have a concept for allowing a kin target to automate linking
> between kin modules? For example in a defn or def we can register (in the
> 'context') an export form or an export tag, like `(reg-ns-tag! context ...)`
> or `(reg-ns-form! context ...)`. Then, when a kin module/namespace requires
> another kin namespace, they already know how to talk. The exported forms
> need to know how to add their requirements (including import for the thing
> they're defined in if needed) to the dependent; just like a normal form.

**Yes, and kin already makes this argument one scope short.** `kin-declare!`
registers a `defn` as callable by later forms in THE SAME FILE, and its
docstring gives the reason:

> Without this, every helper would have to live in a vocabulary -- and a
> helper in a vocabulary is a helper written once per target, which is the
> cost this whole exercise exists to remove.

That is the proposal, at namespace scope. And the cost it names is already
being paid: of the 30 forms `flint.impl.rt` declares with `own`, **27 are
functions kin itself generates**. `merge-two` is defined in `merge.kin` and
re-declared in the vocabulary with its per-target spelling -- one definition
kept in two places, which is the drift this project keeps finding bugs in.

Prediction, so the result is checkable: with exports, `flint.impl.rt` should
lose those 27 and keep only what kin does not define -- the primitives
(`slot`, `alloc`, `push`, `mark`) and the three host functions from the plan
above.

#### `^:pub` is the gate, and it already exists

Not every `defn` should export. `lang.cljc` already reads `^:pub` on `defn`
and `defconst`, and a source is full of helpers that are nobody else's
business. Export the marked ones; leave the rest file-local exactly as
`kin-declare!` has them now. Without this every helper leaks and the module
boundary means nothing.

#### It composes with the import anchor, and that is the evidence it fits

"The exported forms need to know how to add their requirements to the
dependent; just like a normal form" is the pattern from `:emit` above: the
form contributes data to the dependent's import atom, and `:emit` renders the
header. An export needs NO new machinery -- it is a form, and forms already do
this. A design where a new feature needs no new mechanism is usually the right
shape.

#### The real cost is ORDERING, and it has a cheap answer

If A requires B, B's exports must be known before A is emitted. That is a new
constraint -- today each source generates independently.

The obvious answer, emitting in dependency order, is the WRONG one: mutual
references between modules are legal and ordinary in every target here (same
crate in Rust, same package in Java and C#), so a cycle is not a user error to
be refused, and a topological sort has nowhere to start.

The cheap answer is a scan pass. Exports are derivable from a source's `defn`
and `def` forms WITHOUT emitting their bodies -- a signature is all an export
needs. So:

    phase 0   scan every source for its ^:pub definitions -> exports
    phase 1   generate every target of every source, with all exports known
    phase 2   splice
    phase 3   write

Cycles stop mattering, because phase 0 does not evaluate anything. This slots
in ahead of the three phases C6 already defines rather than reshaping them.

#### Why automatic here and manual for host code

The plan above says host code is exposed as kin namespaces MANUALLY. This
proposal is automatic. That is not a contradiction and the line is worth
stating: kin knows its own definitions and cannot know anyone else's. Where it
knows, requiring a hand-written declaration is pure duplication; where it does
not, a declaration is the only way it can learn.

#### One thing kin must not decide

The export is registered BY THE TARGET'S handling of `defn`, not by kin. The
call shape is target-specific -- `self.merge_two(...)` against
`Maps.mergeTwo(...)` -- and kin has no basis for either. kin carries the
registry and guarantees exports are visible before dependents generate; the
target decides what an export IS. Same division as everywhere else.

### The old questions, now answered



1. **Granularity.** Nine regions in this one file. One generated module per
   DESTINATION (`maps_gen.rs`), or one per kin NAMESPACE (`champ.rs`,
   `merge.rs`, ...)? The `.targets` sidecars were consolidated; the nine
   sources were not.
2. **The two back-calls.** `bn_new` and `cn_copy_set_val` are hand-written and
   called by generated code. Port them -- both look portable, `cn_copy_set_val`
   is 23 lines and needs nothing kin lacks -- or accept a generated module
   that depends on its host?
3. **Naming.** Under C7 the path comes from the namespace, so the namespace
   name IS the file name, and `flint.rt.maps` collides with the hand-written
   `Maps`. That wants a convention rather than a suffix chosen once.

## C9 — Drop the `kin-` prefix from kin's own API

> Do we need the 'kin-' prefix when we're already in the 'kin' namespace,
> presumably? Can probable get rid of the prefix for all kin library exports.

No, and it should go. Seventeen functions carry it -- `kin-emit!`,
`kin-render`, `kin-scoped`, `kin-get`, `kin-statement!`, `kin-tag`,
`kin-declare!` and the rest -- across roughly 180 call sites, every one of them
QUALIFIED. Nothing `:refer`s kin's API; vocabularies alias it and call through
the alias. So the prefix buys nothing and costs a stutter at every use.

**The alias makes it worse than a stutter.** Every call site reads `sp/kin-emit!`,
and `sp` is left over from SPLINT -- the name this project had before it was
renamed. So the most common expression in every vocabulary carries two pieces
of dead history: a stale project name and a redundant prefix. `kin/emit!` says
the same thing and is true.

Safe because it is always qualified: `kin/get` does not shadow
`clojure.core/get` the way a referred `get` would. Worth saying in the README
that kin's API is meant to be aliased rather than referred, since that is what
makes the short names safe.

**Verification is unusually clean for a rename this size.** It is a pure
renaming, so the generated output must be BYTE-IDENTICAL afterwards, and
`bin/check-kin` in flint re-emits every source and compares against the
committed files. A rename that changes any generated byte has done something
other than rename. That is a stronger check than the compiler passing.

Do it when the tree is free -- 180 sites across two repositories will conflict
with anything else in flight.

> **DONE.** All 17, plus the `sp` alias: call sites now read `kin/emit!`.
> `kin.cljc` carries `(:refer-clojure :exclude [get])` and qualifies its own
> twelve core `get` calls, which is the only collision among the seventeen.
>
> THE BYTE-IDENTITY CHECK EARNED ITS KEEP IMMEDIATELY. The first attempt
> renamed `kin-get` to `get` and only then qualified the core calls -- so
> kin's own scope lookups were rewritten into map lookups on the context,
> `position` began reading a key that was not there, and every statement
> emitted as an expression: no indent, no semicolon, the closing brace on the
> same line. Six of eighteen files changed. Nothing else in the tree would
> have caught it -- the Clojure compiles, the tests passed, and the output
> was wrong. Qualify first, rename second.

## C10 — How a namespace records what is callable, inside and out

I first wrote this section claiming that `kin.lang` naming `:rust`, `:java` and
`:csharp` thirty-one times was a failure of the redesign -- that the user still
had no path to a new language. The author:

> It's fine if the path is to re-implement defn let etc for the target, that's
> intentional.

So that framing is withdrawn. A vocabulary speaks the languages it speaks; a
new target brings its own `defn`, `let` and the rest, and the go example
writing its own `defn-form` is the design working, not a workaround. The
question underneath was the real one:

> But how does the namespace record what's available for other things to
> call/invoke within the file, and for external files?

### Within the file: already done

The target's `defn` calls `kin/declare!` with a function that emits the call.
That goes into the `(:locals ctx)` atom, lives for one file's emission, and is
consulted by `form-fn` AFTER the require scope. A source can define a helper
and call it two lines later, and nothing outside the file can see it.

### For external files: a namespace's exports ARE a vocabulary

This is the part that needs building, and it needs almost no new machinery.
`require-scope` already resolves against a map of `namespace-symbol ->
vocabulary`, and a vocabulary is just

```clojure
{:namespace 'runtime.merge :targets #{...} :forms {...} :tags {...}}
```

So the export registry's job is to PRODUCE ONE OF THOSE per kin namespace.
Then requiring `runtime.merge` goes down the identical code path as requiring
`flint.impl.rt`, first-match-wins and aliasing and `:refer` all work already,
and `require-scope` needs no change at all.

    within a file   `kin/declare!`  -> (:locals ctx)      one file's emission
    across files    `kin/export!`   -> a vocabulary       the whole run

Two registries, two lifetimes, one resolution mechanism that already exists.

### What the target supplies, and what kin supplies

The target's `defn` registers BOTH, at the same moment, because they are two
shapes of one definition: the local call (`self.merge_two(...)`) and the
external call (`Maps.mergeTwo(...)` plus the import it registers). Only the
target can spell either. kin carries the two registries, guarantees exports
are visible before dependents generate, and shapes neither.

### What goes in: three kinds, and the split already exists

kin already keeps three local registries, and a vocabulary already carries the
same three:

    kin/declare!        a FORM   -- callable   (merge-two rt ...)
    kin/declare-tag!    a TAG    -- a type     ^Value, ^RootIx
    kin/declare-name!   a NAME   -- a value spelled per target   NIL, CN_BASE

`require-scope` concats `(:forms v)`, `(:tags v)` and `(:names v)` when it
builds a scope, so the distinction is load-bearing at both ends already.

**The author's API, which is better than the `declare!`/`export!` pair I
proposed:**

```clojure
(kin/define-form! ctx {:scope :public}  sym f)
(kin/define-tag!  ctx {:scope :private} sym t)
(kin/define-name! ctx {:scope :public}  sym spellings)
```

One function per KIND, with visibility as an OPTION rather than as a second
set of functions. Three reasons it is the better shape:

* it is one function per kind instead of kind x visibility, so it does not
  double if a third scope ever exists;
* `define` is honester than `declare` for something that supplies an
  implementation rather than announcing one;
* and it makes the local/export symmetry structural instead of a convention
  two APIs happen to share.

It maps straight onto the existing mark: the target's `defn` reads `^:pub` and
passes `{:scope :public}` or `{:scope :private}`.

**`:public` must mean local AND exported, not exported only.** A `^:pub`
function is obviously callable from its own file, so registration is
CUMULATIVE and `:scope` names a maximum visibility rather than a destination.
Worth stating because the exclusive reading is an easy thing to implement by
accident, and it fails in the one direction nothing tests: the source itself
still compiles, and only a same-file caller breaks.

**And it raises the question of what ONE function value means for two call
shapes.** A local call is `self.merge_two(...)` and an external one is
`Maps.mergeTwo(...)` plus an import -- but `define-form!` takes one `f`.
Three ways out:

  a. one function that asks the CONTEXT which namespace is calling, and
     qualifies or not accordingly;
  b. a map, `{:local f :external g}`;
  c. kin wraps the local one for external use.

**DECIDED (a), and not the way I first read it.** The author:

> I think the form impl should always ask where it's being used to decide how
> to generate the reference.
>
> I don't mean it should ask which kin namespace it's being used in. Generally
> our kin emitters will already have a `:class` or some other scope telling us
> what we're inside of, we just check that, if it's the same thing our thing is
> defined in, then we self reference; otherwise we use an absolute reference.

The question is not about kin namespaces at all. It is about the EMITTED
structure -- the `:class` or `:module` frame the target's own `:emit` already
pushed. A form compares where it is being used against where its definition
lives, and self-references or absolute-references accordingly. kin supplies
nothing new for this: the scope stack is already the mechanism, and targets
already push frames onto it.

So `define-form!` takes ONE function, every form asks, and a private form asks
too -- it simply always gets the same answer. No branch in kin, no map of two
shapes, and no case where kin invents a reference it has no basis for.

### `get-all`, and the bug underneath it

> We probably need to give kin a way to 'get-all' to return the full/ordered
> stack of scope entries with the given key too, for example for referencing
> things from the parent class or sibling classes from within an inner class.

`kin/get` returns one frame. An inner class referencing something in its
parent needs the CHAIN -- which class am I in, what encloses that, is the
target in an ancestor or a sibling -- and one frame cannot answer it.

**And the current implementation cannot answer it either, for a reason worth
recording.** `scoped` stores with `assoc-in`:

```clojure
(assoc-in ctx [:scope (:key entry)] (:value entry))
```

A nested frame with the same key OVERWRITES the outer one. So an inner class
does not shadow its parent in the stack -- it erases it. Meanwhile the
function's own docstring says:

> Scoped rather than global because a form's implementation asks what encloses
> it -- which class, which package, how deep -- and that is a stack, not a
> variable.

The docstring states the intent and the code implements the variable. It has
not bitten because nothing has nested two frames of one key yet, and nested
classes are exactly the case that would.

The fix is small and changes one shape:

    :scope {key -> [outermost ... innermost]}
    get     -> the innermost frame (peek), unchanged for every caller today
    get-all -> the whole vector, outermost first

`get` keeps its current meaning, so nothing that exists has to change.

Should there be a distinction? There already is one and it is real: a tag can
appear where a call cannot -- in a parameter list, a return position, a `case`
label -- and the three are resolved in the same scope but used in different
places.

### CYCLES: kin follows Clojure. No namespace cycles, and `declare-` for the rest

**DECIDED, and it dissolves the problem rather than surviving it.** The author:

> What about the third option? kin follows clojure's lead and doesn't support
> cycling namespace dependencies? What we can allow as a deferred form
> implementation via 'declare-form', 'declare-tag' etc type counterparts to the
> define ones. The declare ones inject an indirecting placeholder that gets
> filled later; however these cannot be *invoked* or used before they're
> defined or they throw. This matches clojure.

So:

* **namespace dependencies form a DAG.** Sort topologically, emit in
  dependency order, and every namespace's exports are complete before any
  dependent is emitted;
* **within** a namespace, `declare-form!` and `declare-tag!` mirror the
  `define-` pair and allow forward reference -- an indirecting placeholder
  filled when the definition arrives, which throws if something needs its
  content before then. Exactly `clojure.core/declare` and exactly its failure
  mode.

**Everything below this was scaffolding for cycles, and goes.** One pass, not
two. No tolerant collecting mode, no `:scan?` flag in the context, no deferred
anchor resolution for calls, and resolution stays STRICT -- an unresolved
symbol is an error at the moment it is read, which is the property that has
caught real bugs here.

The `heads-only` rule goes too. It existed so that exports could be collected
without emitting bodies, because bodies reference other namespaces. With
dependency order, B is fully emitted -- bodies and all -- before A is started,
so A's calls resolve against B's completed exports. Nothing has to be derived
from a signature.

I had offered three options (a tolerant discarded pass, a scan flag, deferred
resolution) and recommended the first. All three existed only to survive
cycles. This is better than the best of them because it removes the thing they
were surviving.

#### What it costs flint, which is nothing it has not already decided

`nodeassoc.kin` and `collassoc.kin` call each other -- a genuine cycle. Under
this rule they merge into one namespace, which is right: they are two halves
of one algorithm, and Clojure would say the same.

**The tree already agrees.** `dissoc.kin` ships `node-dissoc` and
`coll-dissoc` together, and its own comment gives the reason: "they call each
other, so they ship as one source." That call was made once, deliberately.
`nodeassoc` and `collassoc` are separate only because they were ported in
separate slices -- sequencing, not design.

So the rule is one the tree follows where anyone thought about it, and
violates where nobody did. That is the best evidence a rule can have.

### The one real constraint: an export must derive from the HEAD

C8's scan pass collects exports before generation. For that to be cheap, an
export must be derivable from a `defn`'s HEAD alone -- its name, tags and
marks -- without emitting the body. If it needs the body, phase 0 becomes a
full emission and the run doubles.

That is a constraint on how a target writes its `defn`, and it is worth
stating as a rule rather than discovering as a performance problem: the export
form is a function of the SIGNATURE. Everything a caller needs -- the name,
the arity, the return tag, the import to register -- is in the head. Nothing a
caller needs is in the body.

## C2 — Destination is COMPUTED from the namespace. Decision A is overruled.

Verbatim:

> My intent is that the path for each unit from each target is computed from
> the namespace name. It's deterministic. For each target the user supplies a
> destination vfs (or a path I guess if we're being lazy) + a function from
> namespace -> file path.

So a target descriptor carries its own destination:

```clojure
{:key  :rust
 :ext  "rs"
 :dest "runtime/src"          ; a vfs, or a root path
 :path (fn [ns] ...)          ; namespace -> file, under :dest
 :wrap ...}
```

Selection stays in the source (`:kin/only` / `:kin/exclude`); destination
belongs to the target. Nothing is listed per source, and the `.targets`
sidecar goes away — sixteen files of three columns each, invented on the
flint side, which should never have existed.

`doc/decisions.md` decision A argued for keeping the sidecar and cross-checking
it. That reasoning is superseded. Two of its observations survive and should be
carried into whatever replaces it:

* a source can generate for a target and have no destination at all —
  `unsigned.kin` in the flint tree exists to be VERIFIED, not shipped, so
  "generates for" and "is written somewhere" must stay separable;
* `kin/emit` is a shell script that reads the sidecar with `while read`. If
  destination becomes a Clojure function, the emit path needs a reader. That
  is a real consequence to design for, not a reason to refuse the change.

### What C2 implies for flint, which is the expensive part

Nine kin sources — `champ`, `merge`, `copies`, `nodeassoc`, `collassoc`,
`dissoc`, `find`, `collnode`, `nodeclass` — all emit into `map.rs`,
`Maps.java` and `Maps.cs`. A namespace-to-path function CAN be many-to-one,
but then the config carries a lookup table: the sidecar moved rather than
removed.

Those nine exist because the port went function by function, not because the
subject wanted nine namespaces. One source per destination file — ns
`flint.rt.maps` → `map.rs` / `Maps.java` / `Maps.cs` — makes the mapping
deterministic with no table anywhere, and collapses nine regions per file into
one.

Do the consolidation, as its OWN commit, after the mechanism works, with
`bin/check-kin` green either side. A merge of nine sources and a change in how
paths are computed must never be in the same diff.

### `:wrap`, whose semantics were never written down

> **ANSWERED, and the name has since been reused.** I guessed `:wrap` owned
> the surrounding text and the indent, and flagged the guess for confirmation.
> The answer came as `:emit` (C7): a per-target function handed the context
> and every form, which owns the whole file -- prefix, suffix, import anchors
> and the sub-emission of each form. That is strictly more than the guess.
>
> `:wrap` now names something DIFFERENT: the first slot of a form map
> (`{:wrap :declare :generate}`), which establishes scope around whichever
> pass is running and may use only the scope protocol. Target-level `:wrap`
> does not exist. Nothing below needs confirming.

The generated code sits inside `impl Rt { }` in Rust and `class Maps { }` on
the ports, which is also where the sidecar's `indent 4` came from.

---

# kin, as it should have been

Written after fifteen sources shipped through it into three runtimes. It
works; it is not the shape it was meant to be. Six changes, in the order they
depend on each other.

The complaint that generated this, verbatim:

> namespaces aren't target-coupled like I'd intended, and it's not clear that
> they can be overridden. Which leads to the problem that we have all the
> `lang.kin` forms, but if the user wants to extend them to a new language,
> there's no clear path for them.

Every item below is downstream of that.

---

## 1. A vocabulary is a MAP, and it says which targets it supports

Today a vocabulary namespace is discovered by convention: `kin` resolves
`forms-for`, `tags-for` and `names-for` by name and merges what it finds.
Nothing declares what the vocabulary IS, and nothing says which targets it can
speak. Every template map happens to carry `:rust`, `:java` and `:csharp`
because the one subject in the tree happens to want those three.

It should be a value:

```clojure
{:namespace 'com.example.my-ns
 :targets   #{:rust :java :csharp}   ; what this vocabulary can speak
 :tags      {...}
 :forms     {...}
 :names     {...}}
```

`:targets` is the load-bearing addition. It makes "can this source be
generated for target X" a question with an answer, computed before anything is
emitted, instead of a template lookup that returns nil somewhere deep in a
render and produces either a crash or -- worse -- a plausible-looking string.

## 2. FIRST match wins, and shadowing is a feature

`require-scope` today reduces over the requires with `assoc`, so for a referred
symbol the LAST require wins, silently. Its own docstring claims the opposite:

> which one a file means has to be a fact about the file rather than about the
> order somebody merged two maps

Make it first-wins and say so. Then a user who wants their own `+` writes:

```clojure
(:require [com.example.my-ops :refer [+ - *]]
          [kin.lang :refer [+ - * let if return]])
```

and gets theirs, because it came first, while still getting `let` and `if`
from `kin.lang`. That is the override path that does not exist today.

This is a REVERSAL of current behaviour, not a clarification. Any existing
source relying on last-wins changes meaning. Change the docstring in the same
commit, and have `kin why` (item 4) show every shadowed symbol so the effect
is visible rather than inferred.

## 3. A source declares which targets it wants; kin computes which it gets

The kin file's `ns` form takes an include/exclude list:

```clojure
(ns runtime.champ
  {:kin/only    #{:rust :java :csharp}}   ; or
  {:kin/exclude #{:wasm}}
  (:require ...))
```

and the EFFECTIVE target set for a source is:

    (intersection (targets of every required vocabulary))
      minus  :kin/exclude
      intersected with  :kin/only  (when given)

A source generates for exactly that set and no other. A target named in
`:kin/only` that some required vocabulary cannot speak is an ERROR naming the
vocabulary and the target -- not a silent omission. Silence and success must
be distinguishable; that rule has cost this project four separate bugs.

> **SETTLED by C2 -- this is no longer an open question.** Destination is
> computed from the namespace by a per-target `:path` function; the `.targets`
> sidecar is gone. Selection stays in the source, destination belongs to the
> target, and neither is listed per source. The text below is kept because the
> reasoning it records -- that a source can generate for a target and have no
> destination at all, as `unsigned.kin` does -- survived the decision and is
> still true.

> **OVERRULED — see correction C2 at the top of this file.** This was decided
> the other way (`doc/decisions.md` A: keep the sidecar, cross-check it) and
> then overruled by the author. Destination is computed from the namespace and
> the sidecar goes away.

## 4. Commands that answer "why"

Two directions, both needed, because today neither is answerable without
reading the generator:

    kin targets              every target, and for each, which sources
                             generate for it -- and for those that do not,
                             WHICH vocabulary or exclusion ruled it out
    kin why <source.kin>     which targets this source generates for, which
                             vocabulary contributed each symbol it uses, and
                             every symbol that is SHADOWED by an earlier
                             require

`kin why` is the one that would have caught the two worst bugs this project
found. `LS_THUNK` was missing from a name table, so it passed through verbatim
and emitted an identifier C# does not have; the CLR then failed to compile for
the whole of the work that followed and every gate stayed green. A command
that prints where each symbol comes from makes "it comes from nowhere" visible.

## 5. Forms declare the TAG of what they produce

The deepest change, and the one with the most payoff.

Today a form is `(fn [ctx form] ...)` that emits a string. Tags exist only as
`^I32` metadata on `defn` parameters and `let` bindings, and are used solely to
emit declarations. Nothing downstream knows what an expression produced.

Make a form's product carry a tag, so an outer form can dispatch on the tags of
its arguments. Then one `*` serves every width:

    (* x y)   ->   x * y          when both are I32
                   x *. y         when both are F64
                   mul64(x, y)    when the target needs a call for 64-bit

instead of `mul32`, `mul64`, `mulf` spelled by hand at every use.

### Where the tags come from

* A CALL form declares its return tag -- `core/call` gains a `:tag` key. A
  generated `defn ^Value cn-key` should register its own return tag
  automatically, since it already states one.
* A LITERAL has the tag its shape implies.
* A LOCAL has its declared tag; and an UNANNOTATED local can INFER from its
  initialiser, which removes most of the annotation noise in the current
  sources.

### Three things to decide, deliberately

> **1 and 2 are DISSOLVED — see correction C1 at the top of this file.**
> Neither is kin's question: tags are data and kin does not dispatch on them.
> `doc/decisions.md` B and C record what was written before that ruling and
> why it no longer applies.
>
> **3 is ANSWERED — `doc/decisions.md` D.** A `U32` tag does express the case,
> and `unsigned.kin` is now written with the plain operators and generates
> byte-identical code. The six `u*` forms nonetheless stay, because a literal
> carries no tag and `(< hash 5)` must not be guessed at.

1. **Dispatch shape.** A table `{[I32 I32] "..." [F64 F64] "..."}` is simple
   and closed; a function `(fn [ctx arg-tags] template)` is open and matches
   the "everything is extensible" rule the rest of this document is about.
   Prefer the function, with a table as sugar over it.

2. **What happens when a tag is unknown.** Strict (an error naming the form
   and the tags it got) is the right default for this project -- but it would
   break every existing source at once, because most expressions today have no
   tag at all. Suggested path: a form MAY declare tag dispatch; forms that do
   not keep exactly today's behaviour; a form that DOES and gets tags it has
   no case for is an error. That makes the change additive and lets the
   subject adopt it a form at a time.

3. **Whether this subsumes `u<`/`uquot`.** The flint subject just gained
   `u<`, `u>`, `u<=`, `u>=`, `uquot`, `urem` because an `I32` is `u32` in Rust
   and a SIGNED `int` on the ports, so the generic operators disagree above
   2^31. With tag dispatch, a `U32` tag distinct from `I32` would let plain
   `<` do the right thing per target and those six forms would collapse back
   into two. That is a good test of whether the tag design is real: if it
   cannot express this case, it is not carrying its weight.

## 6. The README describes something kin is not

It reads as though kin were a language. It is not: it is a code generator
whose vocabulary is supplied by the user. The current text also assumes the
reader will use `kin.lang` and emit for the three built-in targets, which is
precisely the assumption items 1-3 exist to remove.

It should answer, in this order:

1. What kin does -- one source, N languages, and the vocabulary is YOURS.
2. How to define a vocabulary: a namespace map, its tags, forms and names.
3. How to add a TARGET that kin has never heard of -- the thing a new user
   most needs and cannot currently find. A worked example, end to end, of a
   language not in the box.
4. `kin.lang` as ONE vocabulary that ships in the box, not as the language.
   Say plainly that every form in it is one a user could have written, and
   that it can be shadowed (item 2).
5. Regions, and why generated code is committed.
6. The troubleshooting commands from item 4.

## What must not regress

`~/Projects/@3sln/flint` is the only real consumer and has fifteen sources
through five gates. From that directory:

    ./kin/verify kin/<name>.kin   one source, three targets, byte-identical
    ./bin/check-kin               every source, plus: the COMMITTED generated
                                  code still matches what the sources produce
    ./bin/test                    the full suite, which runs check-kin

`bin/check-kin` is the one that matters for this work: a vocabulary change
re-emits nothing, so it is entirely possible to change kin, leave every
committed region stale, and see green. That has already happened once.
