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

The generated code sits inside `impl Rt { }` in Rust and `class Maps { }` on
the ports, which is also where the sidecar's `indent 4` came from. Treat
`:wrap` as owning both the surrounding text and the indent it implies —
and CONFIRM that reading with the author before building on it, because it is
inference from a one-word mention rather than something they said.

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

Open question for whoever does this, NOT decided here: the `.targets` file
already maps target -> output path and indent, per source. Item 3 puts target
SELECTION in the ns form while target DESTINATION stays in a sidecar file.
That may be right (selection is a property of the source, destination is a
property of the project) or the two may want to be one thing. Decide it
deliberately and write down which.

> **DECIDED — `doc/decisions.md` A.** They stay separate, and `kin/emit`
> refuses both ways of disagreeing before it writes anything.

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

> **DECIDED — `doc/decisions.md` B and C**, and 3 is answered by the work
> rather than by argument: see the `U32` experiment below.

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
