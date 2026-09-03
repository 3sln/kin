# Decisions taken during the redesign

`doc/redesign.md` leaves three questions open on purpose. This file settles
them, with the reasoning, so that a later reader can tell a decision from an
accident. Each says what was chosen, what was rejected, and what would change
the answer.

---

## A — Target SELECTION lives in the source; target DESTINATION stays in the sidecar

**The question** (redesign item 3): the `.targets` file already maps
target → output path and indent, per source. Item 3 puts target selection in
the `ns` form while destination stays in a sidecar. That may be right, or the
two may want to be one thing.

**Decided: they stay separate, and they are cross-checked.**

They answer different questions, and the questions have different owners:

* **Selection is a property of the logic.** "This can be said in Rust, Java
  and C#, and not in a language without a receiver" is true of the source
  wherever the source is. Two projects vendoring the same `.kin` file want the
  same selection, and would want it even with no files to write into at all —
  `unsigned.kin` in the flint tree generates for three targets and has no
  destination anywhere, because it exists to be *verified* rather than
  shipped. Selection has to survive that; a source whose target set lived in a
  destination table would have no target set.

* **Destination is a property of the project's tree.** `runtime/src/map.rs` is
  where *this* repository keeps its map today. Paths move when directories are
  reorganised, and one source can legitimately be written into two files in
  two checkouts. Putting a path inside the source couples the logic to one
  repo's layout, which is exactly the coupling that made a vocabulary's target
  set implicit in the first place.

There is also a mechanical reason, and it is not a small one: the `.targets`
file is read by shell (`kin/emit`'s `while read -r target file indent`). Being
a flat table of three columns is what lets a build script that has no reader
in it write generated code. Folding it into the `ns` form would mean the emit
path needs a Clojure reader to find out where a file goes.

**The cost of separating them is that they can disagree**, and disagreement
here is silent by nature: a missing destination is a file that is simply never
written, which looks exactly like a file that did not need writing. So both
directions are refused, by name, in `kin/emit`:

* a target the source generates for with no line in `.targets` — *"generates
  for csharp, but kin/champ.targets gives a destination only for: rust java"*;
* a line in `.targets` for a target the source does not generate for — *"sends
  csharp to runtimes/clr/src/rt/Maps.cs, but kin/champ.kin does not generate
  for csharp"*.

Both checks run **before anything is written**. The first cut checked as it
went, which wrote two of three files and then refused the third — a tree that
is neither the old thing nor the new one, and no gate that can say which files
are which. Both refusals were tested by making them fire.

**What would change this.** If a project ever wants the same source written to
different destinations *per target set* — say a Rust file for one target set
and a different one for another — the sidecar becomes a matrix and the case
for folding it into the source gets stronger. Nothing needs that today.

---

## B — Tag dispatch is a FUNCTION, and a table is sugar over it

**The question** (redesign item 5.1): a table `{[I32 I32] "..." [F64 F64]
"..."}` is simple and closed; a function `(fn [ctx arg-tags] template)` is
open. The spec suggests the function, with a table as sugar.

**Decided: as suggested — `kin.lang/by-tags` takes a function, and
`kin.lang/tag-table` builds one from a map.** The primitive is

    (by-tags choose)      ; choose : (fn [ctx arg-tags] -> form-fn | nil)

and what `choose` returns is a **form implementation**, not a template. That
is one step more general than the spec asked for and it costs nothing: an
entry can be a `call` with its own templates, an `op-form`, or the form the
subject is overriding, so a table can say "unsigned when both are `U32`,
*otherwise exactly what `kin.lang` already does*" without restating it. A
table of templates could not express the fallback without copying it.

The reason the function is the primitive rather than the table is the same
reason the rest of kin is functions rather than data, and it is stated in
`kin`'s own docstring: per-target knowledge kept escaping a table. A closed
table cannot express "any integer tag", "the wider of the two", or a tag the
subject computes; a function can, and the table is four lines on top of it.

Dispatch is on the tag VALUE, not the tag's name. Tags are values already
(`{:name 'I32 :types {...}}`), two vocabularies may both define an `I32`, and
they are the same tag only if they say the same thing.

---

## C — An unmatched tag is an error, but only for forms that opted in

**The question** (redesign item 5.2): strict is right for this project but
would break every existing source at once, because most expressions have no
tag at all.

**Decided: as the spec suggests — a form MAY declare tag dispatch; a form that
does not keeps exactly today's behaviour; a form that does and gets a
combination it has no case for is an error naming the form and the tags.**

The alternative — falling back to some default when dispatch misses — is the
`LS_THUNK` failure with a new coat: a plausible-looking string emitted where
the vocabulary had nothing to say. A form that has opted into knowing about
tags has claimed it can tell them apart, and the honest answer to a
combination it has never heard of is to say so.

A table may still supply `:else`, which is not a weakening: `:else` is a case
the author WROTE, and the difference between "I chose a fallback" and "the
lookup returned nil" is the whole of what this decision is about.

**Unknown tags do not, on their own, make a form strict.** An argument whose
tag cannot be determined dispatches with `nil` in that position, and a table
that has no entry for it fails like any other miss. That keeps the adoption
path additive: a subject makes one form tag-dispatching, discovers which of
its uses have no tag, and either tags them or writes the `:else`.
