# Decisions taken during the redesign

`doc/redesign.md` left three questions open, and item 5 posed a fourth that
was to be settled by measurement rather than argument. This file records what
was decided about each and what became of it.

**Two of the three were not decisions to take, and the third was taken and
then overruled.** That is recorded here rather than deleted: a reader who
finds the sidecar gone, or `kin.lang` dispatching on tags, needs to be able to
tell a considered reversal from something nobody thought about. The original
reasoning is kept in full underneath each ruling, because an argument that
lost is still the argument a future reader will re-invent.

| | question | status |
| --- | --- | --- |
| A | target destination vs selection | **OVERRULED** by correction C2 |
| B | tag dispatch shape | **DISSOLVED** by correction C1 — not kin's question |
| C | unknown-tag behaviour | **DISSOLVED** by correction C1 — not kin's question |
| D | does a tag subsume `u<`/`uquot`? | **ANSWERED by the work** — partly; the six stay |
| E | one vfs protocol or two? | **DECIDED** — two: `Vfs`, plus a `Listing` capability |
| F | what a tree-wide `emit-all!` does with a failure | **OVERRULED** — the whole batch reverts |
| G | how a target says it produces a whole file | **DECIDED** — it has an `:emit`; there is no second key |
| — | precedence: own definitions vs imported names | **OPEN** — recommendation below, not settled |
| — | what `declare` means with no runtime | **ANSWERED by the promise model** — a bare declare defers |
| H | provenance format for an unsettled node | **DECIDED** — form, label, deps by kind, and which failure |
| I | layout, and who writes the consuming lines | **DECIDED** — `kingen/*`; kin reports the lines, never writes them |
| K | where a host annotation's NAME lives | **DECIDED** — in the marker, never in the payload |
| L | does kin tell the target which marker it read? | **DECIDED** — no; the answer is checked against the marker instead |
| M | is the call-site arity check part of `generate`? | **DECIDED** — no; it is a gate a build calls |
| N | what `why` should be called | **DECIDED** — `source-origins`, and `:report` becomes `:target-report` |

---

## A — Target destination

### OVERRULED. Destination is computed from the namespace.

The author's ruling, verbatim:

> My intent is that the path for each unit from each target is computed from
> the namespace name. It's deterministic. For each target the user supplies a
> destination vfs (or a path I guess if we're being lazy) + a function from
> namespace -> file path.

So destination belongs to the TARGET, not to a per-source sidecar:

```clojure
{:key  :rust
 :ext  "rs"
 :dest "runtime/src"          ; a vfs, or a root path
 :path (fn [ns] ...)          ; namespace -> file, under :dest
 :wrap ...}
```

Selection stays in the source (`:kin/only` / `:kin/exclude`). Nothing is
listed per source, and the `.targets` sidecar goes away — sixteen files of
three columns each, invented on the flint side, which should never have
existed.

**Why the decision below was wrong.** It argued that destination is a property
of the project's tree and therefore belongs in the project's own file, and
that reasoning is not false — but it answered the wrong question. It took the
sidecar as the given and asked whether selection should join it. The author's
intent was that destination is not DATA at all: it is a function of the
namespace, and a namespace-to-path function needs no table because it is
deterministic. Sixteen sidecar files were sixteen restatements of a rule
nobody had written down. The decision below optimised the maintenance of a
table that should not exist.

It also, in the same move, blessed a second thing that should not exist: a
cross-check between two files that can only disagree because there are two of
them. Removing the sidecar removes the disagreement, which is strictly better
than detecting it.

**Two observations from the argument below survive**, and the correction names
both:

* **"Generates for" and "is written somewhere" have to stay separable.**
  `unsigned.kin` in the flint tree generates for three targets and has no
  destination anywhere; it exists to be VERIFIED, not shipped. A `:path`
  function that must return a path for every namespace cannot express that, so
  it has to be allowed to answer "nowhere".
* **The emit path needs a reader now.** `kin/emit` was a shell script reading
  the sidecar with `while read -r target file indent`. If destination is a
  Clojure function, that is no longer possible, and `emit` moves into kin
  proper. The correction calls this "a real consequence to design for, not a
  reason to refuse the change".

### The original decision, kept for the record

> **Decided: they stay separate, and they are cross-checked.**
>
> They answer different questions, and the questions have different owners:
>
> * **Selection is a property of the logic.** "This can be said in Rust, Java
>   and C#, and not in a language without a receiver" is true of the source
>   wherever the source is. Two projects vendoring the same `.kin` file want
>   the same selection, and would want it even with no files to write into at
>   all — `unsigned.kin` generates for three targets and has no destination
>   anywhere, because it exists to be verified rather than shipped.
>
> * **Destination is a property of the project's tree.** `runtime/src/map.rs`
>   is where *this* repository keeps its map today. Paths move when
>   directories are reorganised. Putting a path inside the source couples the
>   logic to one repo's layout.
>
> There is also a mechanical reason: the `.targets` file is read by shell
> (`kin/emit`'s `while read -r target file indent`). Being a flat table of
> three columns is what lets a build script that has no reader in it write
> generated code.
>
> The cost of separating them is that they can disagree, and disagreement here
> is silent by nature: a missing destination is a file that is simply never
> written, which looks exactly like a file that did not need writing. So both
> directions are refused, by name, in `kin/emit`, and both checks run before
> anything is written.

The mechanical reason is the one that aged worst. "A shell script cannot read
a function" is an argument for keeping the data shape that the shell script
could read — which is the tail wagging the dog, and the correction says so.

---

## B — Tag dispatch shape

### DISSOLVED. Not kin's question.

The author's ruling, verbatim:

> Tags must always be *just data*, their purpose is to let the forms decide
> what to do with them. Tags have no *dispatch*, the forms decide to (and
> whether) to handle the given tag.

kin carries tags and never interprets them. There is no dispatch table in kin
to choose a shape for, because there is no dispatch in kin at all. A form
receives each argument as rendered text plus whatever tag it carries and does
as it likes.

`kin.lang` may implement whatever dispatch it wants for its own operators, and
so may any subject vocabulary — but that is a vocabulary making a choice about
its own forms, not kin imposing a mechanism. Whichever shape `kin.lang`
chooses is a fact about `kin.lang`, changeable without touching kin, and
shadowable by a user who wants a different one (see item 2, first match wins).

The question as posed — "a table or a function, in kin" — had no answer
because it had no subject. Nothing was implemented for it.

### What was written before the ruling, kept for the record

> **Decided: `kin.lang/by-tags` takes a function, and `kin.lang/tag-table`
> builds one from a map.** The primitive is
>
>     (by-tags choose)      ; choose : (fn [ctx arg-tags] -> form-fn | nil)
>
> and what `choose` returns is a form implementation, not a template, so a
> table can say "unsigned when both are `U32`, otherwise exactly what
> `kin.lang` already does" without restating it.

Half of this survives as something `kin.lang` MAY do, if item 5's work shows
it earns its place there. It survives as a vocabulary's private business,
never as kin's interface.

---

## C — Unknown-tag behaviour

### DISSOLVED. Not kin's question.

Same ruling. kin has no opinion about whether two tags are compatible, so it
cannot have a policy for what happens when they are not. A form that wants to
refuse an unknown tag refuses it; a form that wants a default takes one.

The argument below — that a silent fallback is the `LS_THUNK` failure with a
new coat — is still a good argument, and it is now an argument to make to
whoever writes a form, in that form's own documentation. It is not a rule kin
enforces.

### What was written before the ruling, kept for the record

> **Decided: a form MAY declare tag dispatch; a form that does not keeps
> exactly today's behaviour; a form that does and gets a combination it has no
> case for is an error naming the form and the tags.**
>
> The alternative — falling back to some default when dispatch misses — is the
> `LS_THUNK` failure with a new coat: a plausible-looking string emitted where
> the vocabulary had nothing to say.

---

## D — Does tag dispatch subsume `u<` and `uquot`? Partly, and the six stay

Redesign item 5 posed this as the test of whether the tag design is real: the
flint subject had just gained `u<`, `u>`, `u<=`, `u>=`, `uquot`, `urem`
because an `I32` is Rust's `u32` and the ports' signed `int`, so the generic
operators disagree above 2^31. "If it cannot express this case, it is not
carrying its weight."

**It expresses it.** `flint.impl.rt` gained a `U32` tag -- the same host types
as `I32`, `u32` and `int`, and a different claim about the value -- and
implemented `<`, `>`, `<=`, `>=`, `quot` and `rem` as forms that read their
arguments' tags. `unsigned.kin` now says

    (defn ^:method ^Bool ult [^Rt rt ^U32 a ^U32 b] (return (< a b)))

where it said `(u< a b)`, and the generated code is byte-identical on all
three targets. Retagging those parameters `^I32` makes the ports disagree
with Rust again -- `1 0 1 1 ...` against `0 1 1 0 ...` -- which is the check
that the tag is doing the work rather than the templates coinciding.

This is also the clearest demonstration of why C1 is right. The types are
IDENTICAL: `U32` and `I32` are both `u32`/`int`/`int`. The difference is a
claim about the value, which is data, and the only thing that can act on it
is a form that chooses to.

**But the six do not collapse, and the reason is worth recording.** The
chooser requires BOTH arguments to be tagged `U32`, so

    (< hash 5)

gets the plain comparison, because a literal carries no tag saying it is
small. The alternative -- unsigned if EITHER side might be large -- is a
guess, and a guess that silently produces a plausible answer is the failure
this project keeps paying for. So `u<` remains the way a source says what it
means where a tag cannot say it.

What actually changed is which one is the default. `<` is now correct for
tagged values without anyone remembering, and `u<` is the explicit escape
hatch rather than the only route. That is a smaller win than "six forms
collapse into two", and it is the true one.

## E — Two vfs protocols, not one with a refusable operation

C5 asked it directly: a SOURCE vfs scans and reads, a DESTINATION vfs reads
and writes, and only the source ever lists — so is it one protocol whose
`-list` a destination may refuse, or two?

**Two: `Vfs` (`-exists?`, `-read`, `-write`) and a separate `Listing`
capability (`-list`).**

A destination that must implement `-list` in order to refuse it is a lie in
its own type. The protocol says it can, the implementation says it cannot,
and the two disagree at call time rather than at construction — which is the
same shape as a template lookup returning nil deep in a render, the failure
`:targets` was added to remove. With two protocols, "can this list?" is
`(satisfies? Listing x)`: a question with an answer, before anything runs.

The usual cost of splitting a protocol is that implementors write two things.
It does not arise here: `MemoryVfs` and `DiskVfs` each satisfy both in one
record, because being listable and being writable are not exclusive. What the
split buys is the ability to be one *without* the other, which is exactly what
a target's destination is.

## F — What a tree-wide emit does with a failure

### OVERRULED. The whole batch reverts.

The author, in two steps — first that a source whose targets do not all
succeed must be reverted, then:

> Actually, if *any* emission fails maybe we should revert the full batch?

Yes, and the wider granularity is better for reasons the decision below did
not weigh:

* **A partial tree is a state nobody designed and no gate describes.** With
  eight of sixteen sources emitted, `check-kin` reports the other eight as
  drifted — which is true, and says nothing about what happened.
* **Recovery becomes trivial**: fix, re-run, with nothing to reason about.
* **The invariant this project exists to hold is that the runtimes AGREE**,
  and a half-emitted run is the one state that breaks it on purpose.

**It also made the implementation simpler.** Three phases — generate every
target of every source into memory, splice each destination once, then write
— and the first two write nothing, so everything detectable before touching a
destination is detected there. The rollback data is free: splicing requires
reading the whole destination anyway, so phase 2 already holds every original.
Nothing is read twice and no snapshot is taken. And rollback is `-write` with
content already in hand, so **the protocol did not have to grow** — no
`-delete`, no temp paths. A design change that needs no wider protocol is
evidence the three operations in decision E were the right three.

One consequence that is easy to miss, and is now tested: **a destination may
be written by more than one source**, so all its regions are accumulated and
spliced in ONE pass. Read-modify-write per source is wrong in a way that only
shows on failure — emit A into `map.rs`, emit B into it, let C fail, and "the
original" to restore is whichever copy the last read saw, which is the tree
with A already in it.

If the rollback itself fails, the error names exactly which destinations hold
new content and which hold old. A restore that fails silently leaves a tree
that is neither state and no record of which files are which — strictly worse
than the failure it was undoing.

### The original decision, kept for the record

> **Continue, and report**: `{:emitted ... :failed ...}`, and the caller
> decides the exit code. You learn about every broken source in one run
> rather than one per run; and stopping does not avoid a half-written tree,
> since a run that stopped at the third leaves three emitted and the rest
> stale.

The first half was right about reporting and wrong about what to report: with
atomicity there is no partial result to describe, so a failure is thrown and
the tree is exactly as it was. The second half — "stopping does not avoid a
half-written tree" — was an argument against *stopping*, and I read it as an
argument for *continuing*, when it was really an argument for neither.

I had also written that atomicity "belongs in the vfs, which is the layer that
knows what a transaction would mean". That was wrong: staging in memory needs
nothing from the vfs beyond the three operations it already had.

**The order is still sorted.** All-or-nothing means order cannot affect the
result, but it can still affect a failure message, and one whose lines move
between runs is harder to read.

## G — A target produces a whole file exactly when it has an `:emit`

C7 gives a target an `:emit` that owns the file: prefix, anchors, the context
the form emitters run in, and the sub-emission of each form, invoked by it.
That leaves a question C7 does not ask — how does the WRITE side know whether
it has a region to splice or a file to write?

**Decided: `:emit` present means whole file. There is no second key.**

The two are not independent. A target with `:emit` has written its own
`package` clause and wrapper class; splicing that between `kin:begin` and
`kin:end` in a file somebody else wrote would be nonsense, and a target
without `:emit` produces a fragment that is nothing but a region. One
produces what the other consumes.

The alternative was `:whole-file? true` alongside `:emit` — explicit, and a
knob with exactly one sensible setting, which is a thing to get wrong rather
than a thing to choose. Two configurations of it are meaningful in principle
(`:emit` with splicing, for anchors inside a region) and neither is wanted by
anything. If one ever is, that is the moment to add the key, with a case to
point at rather than a guess to defend.

**What the whole-file path does differently**, and it is a short list:

* it CREATES the destination, where the region path refuses one that does not
  exist. A region is written into hand-written code, so the file and its
  markers come first; a module is the file, so kin makes it — and
  `DiskVfs/-write` makes the parent directories, since `example/Gcd.java`
  needs `example` to be there. Implementation, not protocol.
* it refuses TWO sources claiming one destination. The region path allows
  several writers per file and flint has nine into `map.rs`; one namespace to
  one file cannot.
* rollback (decision F) is incomplete for it, and this is stated rather than
  hidden. Reverting is `-write` with content already held, and a file that
  did not exist has no content to restore — the protocol has no `-delete`.
  A failed batch that created files names them in the error as created and
  unmakeable. A `Removable` capability is the shape if a consumer ever needs
  true reversibility; decision E is the precedent, and nothing needs it yet.

## OPEN — what `declare` means when there is no runtime

**Not decided. Implemented one way, raised because the other way is
defensible and the difference is visible.**

The ruling: `declare-form!` injects "an indirecting placeholder that gets
filled later; however these cannot be *invoked* or used before they're
defined or they throw. This matches clojure."

In Clojure the mapping is clean because there are two times. `(declare odd)`
makes a var; `(defn even [] (odd))` COMPILES against it; and calling `even`
before `odd` is defined throws at RUNTIME. Refer freely, call at your peril.

**kin has only one time.** Emission is all there is, and emitting `(odd rt x)`
is exactly the act of asking the placeholder for its content — so the strict
mapping makes a forward CALL throw, where Clojure's equivalent line compiles
fine. Two readings:

* **strict (implemented).** The name resolves; anything that asks it to emit
  before the definition arrives throws. Faithful to the words, and it means
  mutual recursion inside one namespace does not emit — `even` calling `odd`
  fails when `odd`'s `defn` comes later in the file.
* **head-carrying.** `declare` takes the same HEAD a `defn` does minus the
  body — `(declare ^:method ^I32 odd)` — and builds the identical call
  closure, because a call shape depends only on the head. Mutual recursion
  emits, no deferral or anchors are involved, and a name declared and never
  defined still throws.

**Recommendation: head-carrying**, on the evidence that the strict version
cannot do the job the ruling gives it. The stated purpose is mutual
recursion, and `flint`'s `node-assoc`/`coll-assoc` — the pair the namespace
merge exists to accommodate — call each other in both directions. Under the
strict reading, merging them into one namespace does not fix them; whichever
is defined second still fails when the first one's body reaches for it.

The strict version is what is in the tree, so nothing depends on the answer
yet.

## OPEN — precedence between your own definitions and imported names

**Not decided. Raised with a recommendation, deliberately not settled.**

`form-fn` resolves a head REQUIRES-FIRST, LOCALS-SECOND:

```clojure
(or (when-let [[vname k] (get scope head)] (get-in ctx [:vocabs vname :forms k]))
    (get (some-> (:locals ctx) deref) head))
```

and `define-form!`'s docstring gives the reason: *"a vocabulary name still
wins, so a file cannot quietly redefine `let` out from under the reader."*
That was written when a require could only bring in shape forms and subject
primitives — `let`, `if`, `slot`, `alloc`. Shadowing one of those by accident
would be a genuine surprise, so requires-first was right.

**C8 changes what a require can contain.** Once requiring a kin namespace
brings in another module's *function names*, requires-first means an imported
name beats your own definition in your own file. If `s.a` exports `twice` and
`s.b` requires `s.a` and also defines its own `twice`, `s.b`'s calls go to
`s.a`'s — silently, and only in the file that defined its own.

**Recommendation: locals-first.** Three reasons:

* it is what Clojure does — a `def` in your namespace shadows a `:refer`, and
  the refer is what warns;
* the surprising direction is the current one. "My own definition lost to an
  import" is harder to see than "my import lost to my own definition", because
  the second is visible in the file you are reading;
* the original reason survives intact for the case it was written for: a
  source that does not define `let` still gets the vocabulary's, and one that
  DOES define `let` has said so on the line above.

**Why it is not done here.** It changes resolution for every source, and no
flint source currently defines a name it also imports, so the change would be
invisible to `check-kin` — a semantic change with no gate on it is exactly the
kind this project has been bitten by. It wants either a deliberate decision to
take it untested, or a source written to exercise it first.

## H — The provenance format for an unsettled node

Left to me, with one standing requirement: it must name which node, from
which source form, waiting on what. "Something did not settle" is the useless
error this project keeps removing.

**Decided: an unsettled node carries `{:deps {...} :origin {...}}`**, and the
message is one block per node.

```
kin: 1 reference never settled.
  seqs.kin: (ls-thunk rt a)
      waiting on forms ls-thunk
  Nothing defines what they wait on.
```

Four choices in that, each for a reason:

* **The source FORM, printed, not a line number.** kin reads with
  `clojure.edn/read-string`, which does not carry line metadata, so a line
  number would be a lie or a second reader. The form is what the author
  wrote and is more use than a coordinate: `(ls-thunk rt a)` is searchable
  and self-explaining where `seqs.kin:41` is neither.
* **The source LABEL beside it**, because a batch emits sixteen sources and
  the form alone does not say which.
* **What it waited on, BY KIND** -- `forms ls-thunk`, `tags Node`. The
  dependency set is data, which is the whole reason a good message is
  possible at all; printing it by kind keeps `a form is missing` and `a tag
  is missing` from reading identically.
* **A closing line that names WHICH FAILURE it is.** A reference never
  defined and a genuine cycle are the same observable state -- unsettled at
  join -- and the reader needs to be told which, not left to work it out:

      Nothing defines what they wait on.
      These wait on each other: a b -- a cycle, not a missing definition.

  It is derived rather than guessed: a node whose dependencies intersect what
  the unsettled set PROVIDES is in a cycle; one whose do not was never
  defined.

**What is deliberately not in it.** No stack trace, no internal node id, no
count of settling passes. Those describe kin's execution; the reader is
debugging their source, and every line that is about kin rather than about
their code is a line they have to skip.

## I — Layout, and who maintains the consuming lines

Decision 5 of the seven settles the layout: `kingen/*` then the host
language's convention. The part left open was who maintains the lines that
make a generated module reachable.

**The layout is ONE SHAPE for all three**, which was not obvious and is worth
recording. Rust mirrors a module path onto directories exactly as Java
mirrors a package and C# a namespace:

    flint.rt.maps  ->  kingen/flint/rt/maps.rs
                       kingen/flint/rt/Maps.java
                       kingen/flint/rt/Maps.cs

Only the FILENAME differs, because only Rust has a module that is not a
class. `kin.target/module-path` is the whole of it.

Generated code lives in a subtree PARALLEL to the human source, which also
settles the naming collision the old layout had: `flint.rt.maps` writes into
the generated tree and never contends with the hand-written `Maps`.

### Four lines, two questions, one answer

There are two kinds of line in hand-written files that a generated module
needs:

* the CONSUMING line -- `mod` in Rust, `import static` in Java, `using
  static` in C# -- so existing call sites stay unqualified;
* Rust's PARENT DECLARATION, `pub mod maps;` in `flint/rt.rs`, without which
  the file is not compiled at all. Java and C# need no equivalent.

**Decided: kin REPORTS them and does not write them.**

Writing them means kin editing files it does not own, which is the splice
problem under another name -- and the splice is precisely what the whole
module design exists to delete. A generator that writes into hand-written
files has the same drift risk whether it writes a region or a single line.

They also change on a completely different clock. A consuming line changes
when a NAMESPACE is added or removed, which is rare and deliberate; generated
content changes on every emit. Coupling the rare thing to the frequent one
means re-deriving sixteen stable lines on every run to check they have not
moved.

**The one real hazard, named rather than waved at.** A missing `import
static` or `using static` fails loudly at the call site. A missing `pub mod
maps;` in Rust does NOT: the file is simply never compiled, and if nothing
happens to call into it the build stays green while the module is dead. That
is the silent failure this project keeps removing, so the report is a CHECK
rather than a courtesy -- it lists the lines each hand-written file must
carry, and a project can fail its build when one is absent.

## J — What the region scrub actually cost

Decision I settled the layout before any of it existed. Four things only
showed up once forty-two modules were on disk and three compilers had an
opinion, and all four are consequences rather than choices.

### `^:instance` is not expressible as a module in Java

`interns.kin` was four `^:instance` functions -- instance methods on all
three targets, which `^:method` cannot say because it means "Rust `self`, the
others a static taking it". They emitted `this.values` and `this.count`,
which compiles only INSIDE the class declaring those fields.

Rust can put an `impl InternTable` block in any module of the crate. C# has
`partial class`. **Java has neither**, and no way to add an instance method
to a class from another file. So the moment that source became
`kingen/flint/rt/Interns.java` rather than a region spliced into
`com.flint.rt.Interns`, `^:instance` stopped being expressible.

flint's four moved to `^:method`. Rust's output did not change at all; the
two ports gained a `t` parameter, lost a `this.`, and the table's three
fields widened. `^:instance` STAYS IN kin -- it is right for a vocabulary
whose targets can express it -- and flint cannot use it while it wants one
source to serve all three.

### The generated package had to move, not just the directory

Five sources are named after the file they used to be spliced into: `eq`,
`hash`, `interns`, `pike`, `seqs`. A generated `Eq` in `com.flint.rt` is a
SECOND `com.flint.rt.Eq`. Decision I calls the generated subtree parallel to
the human source; a parallel subtree in the same package is not parallel.

So Java gets `package flint.rt` and C# `namespace flint.rt` -- lower-case,
deliberately unlike `Flint.Rt`, so a reader can tell which tree a name came
from. Rust needs no equivalent.

It costs two things, and both are the same fact seen twice. A tag or a call
that names one of those classes has to name it IN FULL, because inside
`flint.rt` a bare `Eq` or `Interns` is the generated one -- so the `Interns`
tag is `com.flint.rt.Interns` and `val-eq` is `com.flint.rt.Eq.eq`. And the
verify harnesses have to nest a `com.flint.rt` class chain to match, which
the C# side was already doing for its own version of the same shadowing.

### Default visibility is crate-wide, and Java pays for it

A source is its own file, so everything it defines is called from outside the
file it lives in. An unmarked `fn` is module-private in Rust and an unmarked
member is private in C#: both right while the code was spliced into its
caller and wrong the moment it is not.

    Rust    pub(crate) fn      `^:pub` still means `pub`
    C#      internal static    `^:pub` still means `public`
    Java    public static      `^:pub` means nothing here

Java has package-private or public and nothing between, so once the boundary
is a package boundary everything crossing it is public and `^:pub` stops
distinguishing anything on that target. The hand-written side widened to
match: seven node-layout constants and four helpers in each runtime.

### Sibling imports are DERIVED, because importing them all does not work

A generated module calls its siblings by their bare names, so Java and C#
need a static import naming each one. Importing every sibling was the first
answer and it is wrong: `mask` is a CHAMP bit helper in `com.flint.rt.Maps`
and the intern table's slot mask in `flint.rt.Interns`, and two on-demand
static imports offering one name make it AMBIGUOUS rather than resolved.

So each source is read for the names it defines, and a module imports exactly
the siblings whose names it mentions. That is a dependency computed from the
sources rather than a list anyone maintains. `need!` -- every vocabulary
entry declaring its imports as data, which `examples/go` does -- is the
better answer for a vocabulary being written now; retrofitting it means
annotating several hundred existing entries, and this needed none.

## K — Where a host annotation's name lives

### DECIDED. In the marker: `@kin:link:form:vec-nth:`.

kin is allowed to know three things about a host annotation -- that it is a
form or a tag, what it is called, and which namespace it declares into --
because those three are a vocabulary's own structure. It is allowed to know
nothing about the payload.

The first attempt put the name in the payload's first position, `@kin:link:form:
vec-nth (fn ...)`, and read two values. That is a smaller thing than it looks:
kin then reaches INTO the payload to find the name, and the line the whole
namespace exists to hold is crossed by its first act. Putting the name in the
marker makes the boundary structural rather than a rule somebody has to keep
-- there is exactly one call site that touches the payload, `interpret`, and it
hands it to the target unread.

The cost is a marker grammar with a variable segment, which needs a parse
rather than a set membership test. The whole of that parse is: take the
non-whitespace run after `@kin:link:`, require it to end in a colon, split. A
run that does not fit is REFUSED naming the three shapes kin reads, which
folds four mistakes -- a misspelt kind, a form that forgot to name itself, an
`ns` that named something, and a missing colon -- into one message.

## L — Does kin tell the target which marker it read?

### DECIDED. No. The answer is checked against the marker instead.

The hook's signature is `(link-data vfs file-path)`. A target handling both
forms and tags therefore learns which it is being asked about from its OWN
payload -- which is where a `:kind` belongs, if it wants one, because the
payload's format is the target's invention.

The alternative was a fourth argument naming the marker. It was rejected
because it would be kin telling a target something the target already knows,
and because the useful half of it is available anyway: kin CHECKS the answer
against the marker, so a form has to come back with a `:link-fn` and a tag
with a `:type`, and a target that mixed the two up is told at the file and
line. That is the same information arriving in the direction that catches a
mistake rather than the direction that prevents kin having to think.

`:arity` is kin's key, not the target's, so kin says what may be in it: a
non-negative count, or the key omitted. A target that will not state one is
not checked against one, and `arities` leaves out a form the targets disagree
about entirely -- a usage check that picked a winner from two contradictory
declarations would be checking against a coin toss.

## M — Is the call-site arity check part of `generate`?

### DECIDED. No. It is a gate a build calls, next to `check-agreement`.

`usage-problems` is a SYNTACTIC walk. It finds a head symbol that resolves
through the source's require scope to an annotated form and compares the
argument count; it cannot see that a local of the same name shadows it, which
is the same limit `bound-names` has and for the same reason.

A heuristic makes a good report and a bad gate. Wiring it into `generate`
would mean a false positive stops a build for a source that is correct, and
the class of thing that produces a false positive here -- a local shadowing an
imported name -- is legal and occasionally deliberate. So it returns data,
`check-usage` throws over that data, `report` includes it, and a project
decides for itself. `generate` stays pure and stays about generating.

What it catches is worth being plain about, because it is the half of the
original drift that no cross-target comparison can reach: two runtimes can
agree perfectly that `vec-nth` takes two arguments and a source can still call
it with three. The cross-target check compares the runtimes with each other;
this compares them with the code that uses them.

## N — What `why` should be called

### DECIDED. `source-origins`. And its `:report` key becomes `:target-report`.

`why` named only that a question was asked, so every mention of it in a doc
had to say what it did, and `(why prj "champ.kin")` at a call site said
nothing at all. The function answers where each symbol in a source
ORIGINATES.

Three candidates:

* **`source-origins`** — names the subject and the answer, and joins the
  `source-label` / `source-text` / `source-labels` family that already reads
  as *this question, of that source*.
* **`provenance`** — the right word, and already this project's word for
  something else: `kin.cljc` uses it for a promise node's origin. Reusing it
  one layer up for a different granularity is exactly the near-collision the
  `:report` key had to be moved for.
* **`explain-source`** — names an action and implies prose. It returns data.

The result's `:report` key held `kin/target-report`'s answer, and `report` is
now a function of its own -- the whole project in one call. A key meaning
something narrower than the function of the same name is two meanings a reader
has to hold, so the key is `:target-report`, named for what produces it.
`analyse` was renamed with it, so the two agree.

`targets-report` keeps its name and is now one key of `report`. That pair is
not confusing in the way the other was: `report` is the whole, and
`targets-report` is the axis it contains.

---

## O — A namespace is a GROUP of vocabulary maps

`doc/redesign.md:1444`, verbatim, is where the whole redesign came from:

> namespaces aren't target-coupled like I'd intended, and it's not clear that
> they can be overridden. Which leads to the problem that we have all the
> `lang.kin` forms, but if the user wants to extend them to a new language,
> there's no clear path for them.

`:targets` on a vocabulary answered the first half and first-match-wins
require order the second. THE THIRD HALF WAS NEVER DONE, and the README
admitted it under "The sharp edge, stated plainly": adding a fourth language
to an existing three-language source meant writing your own shape vocabulary
with four arms, because `kin.lang`'s speak three.

**Decided: several vocabulary maps may share a `:namespace`.** A project
groups every map it has by the namespace it names, preserving declared order,
and resolving a symbol walks that group for the first map that BOTH speaks
the current target AND holds the symbol.

The rejected alternatives, so they are not re-proposed:

* **per-form `:targets` metadata.** Asymmetric -- tags and names already
  carry per-target data that `check-vocabulary` reads -- and per-entry
  narrowing would WEAKEN that check.
* **a require-order chain across differently-named vocabularies.** Works, and
  forces every source's `ns` form to list the extension, which is the
  coupling being complained about.

### It is a CONSOLIDATION. Three mechanisms became one.

kin already did per-target form dispatch in two other places, both
one-map-per-namespace workarounds, and both said so:

* `kin.host/form-entry` -- "a link fn that picks the target's at CALL time",
  throwing `is annotated for rust java and not for :csharp`;
* `kin.project/export-vocabulary` -- the same shape, and its docstring said
  as much: "answering it twice in two shapes would be two things to keep in
  step".

Both are DELETED. Each now contributes one map per target and the group walk
picks. Their two hand-written errors became one, in `kin/group-miss`, which
can say something neither could: which maps DO hold the symbol and what each
of them speaks.

### `:literal-tag` -- which map of a group answers

**Decided: the first map that SPEAKS THIS TARGET and carries one.** Require
order is walked first and the group in declared order within it, so it is the
same two-level first-match every other resolution uses.

The alternative was to let any map answer whether or not it speaks the
target, on the grounds that a literal's tag is a fact about the source rather
than about a language. That is wrong for the case grouping exists to serve: a
Go extension may want `5` to mean something a `u32` does not, and a map that
cannot speak the target cannot be asked what its types are --
`check-vocabulary` only guarantees a tag has a type for the targets ITS OWN
map claims, so a tag borrowed across that line is exactly the missing-type
silence the check exists to prevent.

### The host/hand clash -- narrowed, not removed

A namespace declared both by a host tree's annotations and by a hand-written
vocabulary used to be REFUSED outright, naming it, because the annotations
exist BECAUSE the hand-written table drifted, and a project holding both is
holding the drift it meant to remove.

**Decided: the refusal narrows to one symbol declared for one target by two
maps.** Grouping makes coexistence expressible, and the blanket refusal would
block the case grouping is FOR -- a host tree declaring a namespace for Rust
and a hand-written map extending it to Go restates nothing.

What the refusal was really about is two statements of the same fact, and
that is exactly a `(symbol, target)` pair claimed twice. Disjoint targets are
extension; the same symbol for a different target is extension; the same
symbol for the SAME target is the drift, and it is still refused -- naming
the symbol and the target rather than just the namespace.

Three details of the shape, each decided rather than fallen into:

* it applies to **every group**, not only host-versus-hand, because the
  hazard is not about where a map came from. Two hand-written maps colliding
  would have been resolved silently by declared order.
* **declared order is a tie-break, not an override mechanism.** Override is
  require order, which a SOURCE chooses and `source-origins` reports. Nobody
  chose a group's internal order as a way to shadow anything, so an ambiguity
  inside a group is an error rather than a silent win.
* **the identical entry twice is not drift.** `require-scope`'s `put` set
  this precedent -- an entry equal to the one already held is no shadowing at
  all -- and an extension restating a tag it needs for its own target is one
  fact written twice. It bites for tags and names and essentially never for
  forms, which is right: two closures are not `=`, so two implementations for
  one target stay ambiguous, which they are.

### What it costs, stated rather than discovered

Extending a namespace WIDENS what every source requiring it generates for.
The namespace speaks Go once a Go map joins it, so every source using it
generates Go. A partial extension is therefore a thing to finish or to
exclude with `:kin/only`, and a symbol it did not cover is an error naming
the symbol and the target -- not `not in scope`, which would be false.

`kin.lang` itself cannot be checked for completeness this way, because forms
are functions and `check-vocabulary` cannot ask one what it covers. That is
the same reason `kin.lang/call` names a missing target at render time, and
the new error is the group-shaped version of it.

### The `(val (first by-target))` bug, measured before it was changed

`export-vocabulary` took tags and names as `(val (first by-target))` -- ONE
TARGET'S ENTRY USED FOR ALL OF THEM -- reasoning that a tag carries its own
per-target `:types` and a name its own per-target spellings, so whichever you
picked was the same value.

That is true of everything `kin.lang` builds and false in general.
Instrumenting the current code and running flint's emit found **sixteen
divergent cases, eight distinct exported names, all function-valued** --
`spell-name`'s `(fn [ctx] -> String)` shape, which is a fresh closure per
target and therefore never `=` to its siblings.

**The bug was LATENT, not active.** Those closures come from
`const-reference` and `table-reference`, which capture the declaring
namespace and a FULL per-target spelling map and read the reference site's
target out of the `ctx` they are handed -- so any of the three behaves
identically. That is why flint's output is byte-identical either side of this
change, and it is the whole of the backward-compatibility proof.

What would have been silently wrong: a vocabulary whose `define-name!` or
`define-tag!` value depended on the target it was declared under -- a
single-target spelling map, say. `spell-name` would then have answered nil
for two of three targets, which is the empty-string-in-the-output silence
`check-vocabulary` exists to prevent. Grouping fixes it by construction,
because each target's map holds that target's own entry.

## What kin owes, after both corrections

The corrections narrow kin's job, which is the point of them. On tags, all of
it:

* a form's product is text plus an optional tag;
* an enclosing form sees each argument's text and tag;
* declared tags (`^I32` on a parameter, a `:tag` on a call, a `defn` return)
  flow to the products that carry them;
* an unannotated local may take its tag from its initialiser.

An implementation that finds itself asking what a tag MEANS has gone wrong.

## Open, and not to be guessed

**`:wrap`.** The target descriptor in C2 carries a `:wrap` whose semantics were
never written down. The generated code sits inside `impl Rt { }` in Rust and
`class Maps { }` on the ports, which is also where the old sidecar's `indent 4`
came from — so `:wrap` plausibly owns both the surrounding text and the indent
it implies.

But that surrounding text is HAND-WRITTEN in the host files today, with the
kin markers nested inside it. If `:wrap` owns the text, kin starts generating
`impl Rt {` and the host files change shape; if it owns only the indent, they
are untouched. Those are very different changes and the difference is not
recoverable from a one-word mention.

**Asked, not assumed.** Until it is answered, indent is carried as a
provisional `:indent` on the target descriptor, marked in the code as standing
in for `:wrap`.
