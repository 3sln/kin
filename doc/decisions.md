# Decisions taken during the redesign

`doc/redesign.md` left three questions open. This file records what was
decided about each and what became of it.

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
