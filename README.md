# kin

Write a unit of logic once; emit it into every language that has to agree.

kin is a **code generator whose vocabulary is yours**. It ships no language.
What a source file may say is what its `ns` form asked for, and what those
words mean in each target is something a project writes down — in ordinary
Clojure, in its own tree, for languages kin has never heard of.

It exists for a specific problem: several runtimes meant to be verbatim
mirrors of each other, kept in step by hand, paying the cost every time
anything changes. kin makes one source the origin and generates the rest. Its
output is ordinary, idiomatic, checked-in code that a person reads and
reviews.

## 1. One source, N languages, and the vocabulary is yours

```clojure
(ns example.gcd
  (:require [example.go :refer [defn return let set while comment != rem I32]]))

(defn ^I32 gcd [^I32 a ^I32 b]
  (comment "Euclid's algorithm, by repeated remainder.")
  (let [x a y b]
    (while (!= y 0)
      (let [t (rem x y)]
        (set x y)
        (set y t)))
    (return x)))
```

Every symbol in that file — `defn`, `while`, `rem`, `I32` — comes from
`example.go`, a vocabulary written in the example project, not from kin. kin
supplied the `ns` form, the require scope, and the machinery that turns a
form into text. It supplied no `defn`.

That is the whole idea. **kin is not a language**, and a form you did not ask
for is not in scope.

## 2. Defining a vocabulary

A vocabulary is one var holding one map:

```clojure
(def vocabulary
  {:namespace 'example.go
   :targets   #{:go}          ; what this vocabulary can SPEAK
   :tags      {'I32 I32 'Bool Bool}
   :names     {}
   :forms     {'defn defn-form 'return return-form ...}})
```

* **`:targets`** is the load-bearing part. It makes *can this source be
  generated for X* a question with an answer, computed before anything is
  emitted, instead of a template lookup that returns nil deep inside a render
  and produces a plausible-looking string. kin checks it when the vocabulary
  loads: every tag needs a type for every declared target, and every name a
  spelling.

* **A form** is `(fn [ctx form] ...)` that emits text. `kin.lang/call` builds
  the common case from a per-target template:

  ```clojure
  'popcount (call {:rust "{0}.count_ones()"
                   :java "Integer.bitCount({0})"
                   :csharp "BitOperations.PopCount((uint) {0})"})
  ```

* **A tag** is *data attached to a value*, and kin never looks inside one. It
  usually carries the type each target spells it as, but it can carry anything
  a form of yours wants to read:

  ```clojure
  (def I32 {:name 'I32 :types {:rust "u32" :java "int" :csharp "int"}})
  (def U32 {:name 'U32 :types {:rust "u32" :java "int" :csharp "int"}})
  ```

  Those two have **identical types** and are different tags. `U32` means *this
  one may carry the high bit*, and above 2^31 Rust's `u32` and the ports'
  signed `int` disagree about `<` and `/`. A form that reads its arguments'
  tags emits `Integer.compareUnsigned` for two `U32`s and a plain `<`
  otherwise — and that choice is made by **the vocabulary**, never by kin.
  kin has no dispatch, no match rules, and no opinion about what any tag
  means.

  Tags reach a form four ways: a `^I32` on a parameter, a `:tag` on a `call`,
  a `defn`'s declared return, and an unannotated `let` binding taking its
  initialiser's tag.

* **A name** is a value spelled differently per target — `TY_CONS` against
  `Obj.TyCons`. It cannot be a form, because it appears in a `case` label
  where a call cannot go.

## 3. Adding a language kin has never heard of

**`examples/go`** is this, end to end, and it runs:

```
cd examples/go && bb ../../bin/kin gen kin/gcd.kin
```

Three files, none of which kin knows anything about:

**The target** (`src/example/targets.cljc`) — a map of facts and functions:

```clojure
{:go {:key :go :ext "go"
      :reserved reserved                 ; Go's keywords
      :local-name (namer camel)          ; how a local is spelled
      :fn-name (namer pascal)            ; an exported Go function is capitalised
      :indent-unit "\t"                  ; Go indents with tabs
      :dest "out"                        ; where its files live
      :path (fn [ns] (str (last-segment ns) ".go"))}}
```

`:dest` and `:path` are how a target says where a namespace's code goes. It is
**computed, not listed**: give it a namespace and it answers a file, with no
table anywhere. `:path` may answer `nil`, which means this namespace is
generated and written nowhere — a source that exists to be verified rather
than shipped.

**The vocabulary** (`src/example/go.cljc`) — eight forms and two tags, because
a vocabulary is as big as the sources that use it and no bigger. There is no
base class to inherit and no set of forms you are obliged to provide.

**`kin.edn`** — two lines:

```clojure
{:vocabularies [example.go]
 :targets example.targets}
```

`:targets` names a *namespace* rather than listing descriptors, because a
`:path` is a function and a function cannot be written in EDN.

The generated Go is what `gofmt` would have written — checked, not asserted:
`gofmt -l` has nothing to say about it.

### The sharp edge, stated plainly

`kin.lang`'s forms frame their output with a three-armed `case` over `:rust`,
`:java` and `:csharp`. So the example above writes its own `defn`, `let` and
`while` rather than reusing `kin.lang`'s.

For a source that generates **only** for your new language, that is fine and
is what the example does. For adding a fourth language to an **existing**
three-language source, it is not: you need forms that speak all four, and
`kin.lang`'s speak three. Today that means writing your own shape vocabulary
with four arms.

kin.lang's *helpers* are reusable even when its forms are not — the example
uses `kin.lang/strip-parens`, which knows how to drop an expression's outer
parentheses safely and was got right the hard way.

## 4. `kin.lang` is one vocabulary that ships in the box

It is **not the language**. It provides `defn`, `let`, `if`, `case`, `for`,
`while`, `return`, the operators, and the marks that say what one target needs
and the others do not (`^:mut`, `^:inline`, `^:unchecked`, `^:throws`,
`^:method`). Every form in it is one a user could have written, and it
declares `:targets #{:rust :java :csharp}` because that is what its `case`
arms actually cover.

**It can be shadowed, and shadowing is the override path.** The first require
wins:

```clojure
(ns my.source
  (:require [com.example.my-ops :refer [+ - *]]
            [kin.lang           :refer [+ - * let if return]]))
```

That file gets `my-ops`'s arithmetic and `kin.lang`'s `let`, `if` and
`return`, with no editing of `kin.lang` and no fork of it. `kin why` prints
every symbol shadowed this way, so an override is visible rather than
inferred.

## 5. Which targets a source generates for

A source declares what it wants; kin computes what it gets.

```clojure
(ns runtime.champ
  {:kin/exclude #{:wasm}}        ; or {:kin/only #{:rust :java}}
  (:require ...))
```

    effective = (intersection of every required vocabulary's :targets)
                  minus :kin/exclude
                  intersected with :kin/only, when given

A target named in `:kin/only` that some required vocabulary cannot speak is an
**error naming both** — not a silent omission. A source that ends up
generating for nothing is an error too. Silence and success have to be
distinguishable.

## 6. Regions, and why generated code is committed

`kin emit` writes **between markers** in a hand-written file:

```rust
// kin:begin kin/hash.kin
...generated...
// kin:end kin/hash.kin
```

The generated code is checked in on purpose. A runtime build must not need
babashka — somebody cloning a repository to build its JVM runtime should not
have to install a Clojure to do it, and a generator in the build path is a
generator that breaks the build. So the tool is run by hand, its output is
committed, and the markers make the next run a diff rather than a merge.

The cost of that choice is that the two can drift: a hand edit inside a region
survives until someone re-emits, and a vocabulary change silently makes every
committed region stale. **A project committing generated code needs a gate
that re-emits everything and compares.** `kin destinations` prints every file
kin would write, for exactly that purpose.

## 7. When something is wrong

```
kin gen     <source.kin>   print what each target would get
kin emit    <source.kin>   write it into the target files, between markers
kin emit                   every source
kin destinations           every file kin would write
kin why     <source.kin>   where every symbol comes from
kin targets                every target, and what ruled the rest out
```

**`kin why`** is the one to reach for. It prints which targets a source
generates for and how that was computed, which vocabulary contributed each
symbol, what an earlier require shadowed, and — the bucket that justifies the
command — **`FROM NOWHERE`**: a symbol in no vocabulary, declared by nothing
and bound by nothing, which the target's local namer will spell and emit as
written.

That is not hypothetical. A constant missing from a name table passed through
verbatim and emitted an identifier C# does not have; the CLR failed to compile
for the whole of the work that followed and every gate stayed green. `kin gen`
emits it happily. `kin why` says:

```
    FROM NOWHERE               ls-thunk
```

A render that throws still prints the table, from however far it got, because
`why` is the command you run when generation is already broken.

## The rule that governs the output

**Generated code may not be worse than the hand-written code it replaces.**

Judged by measurement, not by reading. That rule has refused ports: a
four-byte writer generated into Rust measured 50 instructions against the
hand-written 15, so it stays hand-written. It has also improved them: naming a
rotate in the vocabulary kept Rust identical and upgraded two targets to their
intrinsics.

A corollary that has cost real time: **measure a generated function as a
library, never as a binary.** A `main` calling each form once on constants
lets the compiler specialise both against that call site, and the answer is
about the benchmark.

## Status

Early. Its one real consumer generates a CHAMP map, murmur3, a type-category
dispatch and a set of sequence accessors into a Rust, a JVM and a CLR runtime
— sixteen sources, each verified byte-identical across three targets and
passing that project's cross-runtime conformance suite.

`doc/redesign.md` is the current design and `doc/decisions.md` records the
decisions taken while implementing it, including one that was taken and then
overruled.

## Licence

See LICENSE.
