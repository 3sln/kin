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

kin's API is meant to be **aliased, never referred**: call sites read
`kin/emit!`, `kin/render`, `kin/get`. That is what makes the short names safe
— a referred `get` would shadow `clojure.core/get`, a qualified `kin/get`
cannot.

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
cd examples/go && ./emit kin/gcd.kin
```

One source becomes a **Go module file** and a **Java class file**:

```go
// out/go/gcd.go
package gcd

import "strconv"

func Gcd(a int, b int) int { ... }
```
```java
// out/java/example/Gcd.java
package example;

import java.util.Objects;

public final class Gcd {
    public static int gcd(int a, int b) { ... }
}
```

**kin produces modules; the language consumes them.** Nothing is split across
files and no language needs a partial anything — something new is created and
the hand-written code imports it. `gofmt` has nothing to reformat, `javac`
compiles clean, and both print `6 21` when called.

Three files, none of which kin knows anything about:

**The target** (`src/example/targets.cljc`) — a map of facts and functions:

```clojure
{:go {:key :go :ext "go"
      :reserved reserved                 ; Go's keywords
      :local-name (namer camel)          ; how a local is spelled
      :fn-name (namer pascal)            ; an exported Go function is capitalised
      :indent-unit "\t"                  ; Go indents with tabs
      :vfs (vfs/disk-vfs "out/go")       ; WHERE, as a protocol impl
      :path (fn [ns] (str (last-segment ns) ".go"))
      :emit go-emit}}                    ; and what the FILE looks like
```

**`:emit` owns the file.** It is handed the context and every form — the `ns`
form included — and decides the prefix, the suffix, the anchors, and the
sub-emission of each form, which *it* invokes. That last part is the
difference between wrapping and owning: it can emit **between** forms, and it
can drop an anchor for imports so that a type discovered deep in a function
body appears at the top of the file.

A target with an `:emit` produces a whole file, which kin creates. A target
without one produces the forms and nothing around them — useful for printing
(a test harness wants the functions, not the module) but not something kin
will write, because there is nowhere to put a fragment.

`:vfs` and `:path` are how a target says where a namespace's code goes. It is
**computed, not listed**: give it a namespace and it answers a file, with no
table anywhere. `:path` may answer `nil`, which means this namespace is
generated and written nowhere — a source that exists to be verified rather
than shipped.

**kin performs no I/O of its own.** Every byte it reads or writes goes through
the `:vfs` on the target, which the user supplies. `kin.vfs/disk-vfs` is the
ordinary one; `kin.vfs/memory-vfs` is a map, and it is what lets `emit!` be
tested end to end with no directory, no cleanup, and no chance that a passing
test wrote into the tree it was checking (`test/emit.clj`).

**The vocabulary** (`src/example/lang.cljc`) — ten forms and three tags,
because a vocabulary is as big as the sources that use it and no bigger.
There is no base class to inherit and no set of forms you are obliged to
provide.

**The project** — two lines of ordinary Clojure:

```clojure
(def project
  (delay (kp/load-project {:vocabularies '[example.go]
                           :targets targets/targets
                           :target-order [:go]})))
```

There is no config file, because a `:path` is a function and a `:vfs` is a
protocol implementation, and neither can be written in EDN.

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
`return`, with no editing of `kin.lang` and no fork of it. `why` reports
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

## 6. Modules, and why generated code is committed

**One namespace, one whole file, created by kin.** A generated module lives in
a subtree parallel to the hand-written source — `kingen/flint/rt/champ.rs`,
`kingen/flint/rt/Champ.java`, `kingen/flint/rt/Champ.cs` — and the shape is
the same for all three, because Rust mirrors a module path onto directories
exactly as Java mirrors a package and C# a namespace. Only the filename
differs, because only Rust has a module that is not a class.

> **There used to be a splice.** kin wrote between `kin:begin` / `kin:end`
> markers in a hand-written file, because a port arrives a function at a time
> and you cannot generate half a file. That was scaffolding with a defined
> end, and the end has arrived: the markers, the splice, the region parser and
> the indent arithmetic are all gone. kin never writes into a file it did not
> create.

The generated code is checked in on purpose. A runtime build must not need
babashka — somebody cloning a repository to build its JVM runtime should not
have to install a Clojure to do it, and a generator in the build path is a
generator that breaks the build. So the tool is run by hand and its output is
committed.

The cost of that choice is that the two can drift: a hand edit survives until
someone re-emits, and a vocabulary change silently makes every committed file
stale. **A project committing generated code needs a gate that re-emits
everything and compares.** `kin.project/destinations` answers every
`[target path]` the project would write, for exactly that purpose.

**The consuming lines are REPORTED, not written.** A generated module needs
something in hand-written code to reach it — `mod` in Rust, `import static`
in Java, `using static` in C# — and kin does not write those, because writing
into a file it does not own is the splice problem under another name. They
also change on a different clock: a consuming line changes when a namespace
is added or removed, generated content changes on every emit.

The hazard is worth naming. A missing `import static` or `using static` fails
loudly at the call site; a missing `pub mod champ;` does not — the file is
simply never compiled. So the report should be a **check**, and a project can
fail its build when a line is absent.

`emit-all!` is **atomic over the whole batch** — every source, every target,
or nothing. Three phases: generate into memory, stage every destination, then
write, restoring the originals if a write fails partway. A partial tree is a
state nobody designed and no gate describes.

## 7. The host source declares its own linkage

A vocabulary entry for a host function — *this kin name calls that method,
spelled thus per target* — sits far from the code it describes, so it drifts,
and it drifts **silently**: an entry is only exercised once some kin source
happens to call it, which may be many commits after the host function changed
underneath it. Two real instances in the one project using kin: an entry that
declared arity 2 for several commits after the function grew a default
argument and became 3, and one that emitted an unqualified `Seqs.seq(...)`,
which binds to the wrong class once a *generated* class named `Seqs` exists in
the same package.

So the host file says it, next to the thing it says it about. Comment syntax
is the host language's; the marker is the same in all of them.

```java
// @kin:link:ns: com._3sln.flint.kgen

// @kin:link:form:vec-nth: {:kind :method
//                          :args [{:type :int :name a}
//                                 {:type :int :name b}]}
public static long nth(Rt rt, long v, int i, long dflt) { ... }
```

**kin reads the marker and stops.** That this is a `form` or a `tag`, its
name, and the namespace are kin's business, because they correspond exactly to
a vocabulary's own structure — `:forms`, `:tags`, and the symbols those are
keyed by. The map after the marker is **opaque EDN**: kin does not know what
`:kind` or `:args` mean, does not validate them and does not count them. The
name lives *in the marker* for that reason — were it a key in the payload, kin
would have to reach into the payload to find it, and the line would be crossed
by its first act.

**A target interprets, and kin consumes only the answer.** A target gains a
`:link`:

```clojure
{:key  :java
 :link (fn [link-data vfs file-path]
         {:link-fn (fn [ctx form] ...)   ; the kind kin already installs
          :arity   3                     ; how many arguments a CALL takes
          ...})}                         ; whatever else it can usefully say
```

It is handed the vfs and the path as well as the data, because the file is
where the rest of the truth is. **Nothing is evaluated**: the payload is data
and the target is ordinary project code, already loaded, already a function.
There is no interpreter here and no `eval` seam.

```clojure
(def scans
  (host/interpret targets
                  [(host/scan {:vfs (vfs/disk-vfs "runtime/java") :match "*.java"
                               :target :java :comment "//"})
                   (host/scan {:vfs (vfs/disk-vfs "runtime/rust") :match "*.rs"
                               :target :rust :comment "//"})]))

(host/check-agreement scans)                 ; the targets agree — a gate
(kp/check-usage (kp/project {... :host scans}))   ; and the call sites match
```

What comes out is an **ordinary vocabulary**: it passes `check-vocabulary`, a
source requires it by name, `source-origins` attributes symbols to it, and
nothing downstream knows an annotation was involved. A namespace declared both
by a host tree and by a hand-written vocabulary is refused — the annotations
exist because the table drifted, so holding both is holding the drift.

The returned metadata — never the payload — is what the two checks are built
from:

* **`disagreements`** compares the targets against *each other*: a form some
  declare and others do not, and two that state different arities. The
  `:missing` half is the one that bites — a host function ported to two of
  three runtimes with the third's annotation never written. The arity half is
  *not* vacuous, and an earlier attempt at this made it so by computing arity
  from the payload rather than asking the target; every target's payload was a
  `(fn [ctx form] ...)`, so the check compared 2 with 2 forever.
* **`usage-problems`** turns the agreed arity on the *call sites*. Two
  runtimes can agree perfectly that `vec-nth` takes two arguments and a source
  can still call it with three; until now the first thing to notice was the
  host compiler, or — on the target whose call happened to still type-check —
  nothing at all. It is a syntactic walk, so it is a **gate a build calls**
  rather than something `generate` throws from: a heuristic that cannot see a
  shadowing local makes a good report and a bad gate.

A payload is read one value at a time, so a multi-line one needs no new
reader — only the comment prefix stripped off each continuation line, which is
**configuration** (`//`, `;;`, `#`, `--`) rather than something guessed from a
file extension. An unterminated payload, a misspelt marker, an annotation with
no `@kin:link:ns:` above it, and two annotations for one symbol in one target
are each an error naming the file and the line. A misspelt marker is *refused*
rather than skipped, because a misspelt annotation and no annotation at all
look identical from the far end.

The payload is plain EDN, and `{:type :int :name a}` rather than `^:int a` is
a deliberate consequence. Measured under bb: `clojure.edn/read` *does* attach
metadata, but metadata takes no part in `=` and `pr-str` does not print it —
so `{:args [^:int a]}` and `{:args [a]}` are equal, print identically, and
survive a print-and-read round trip as the same value. A payload whose meaning
lives in metadata is one that every report, every diff and every test agrees
is something it is not.

## 8. kin is a library, not a command

**There is no `kin` executable.** The script belongs to the project using it,
because what a project wants from a generator — where its sources live, how
its output is printed, what its gates are — is the project's business. flint
has `kin/scripts/gen`, `/emit`, `/destinations`, `/kin` and `/verify`;
`examples/go` has `gen` and `emit`; each is a dozen lines over the library.

The surface is three layers, and they differ in kind:

| | | |
| --- | --- | --- |
| `generate` | **pure** | source text in, `{target text}` out. Opens nothing. |
| `emit!` | needs a **vfs** | writes each namespace's module, creating it — all through the user's implementation. |
| *verify* | needs a **machine** | compiling with `rustc` and running it is process execution. **Not in kin**, and never should have been — it lives in the consumer's tree. |

`source-origins`, `targets-report` and `report` return **data**. Printing is
the caller's, which is what makes them usable from something that is not a
terminal.

## 9. When something is wrong

**`report`** is the one to reach for: the state of the whole project in one
call, and every finding in one sorted `:diagnostics` vector — a symbol from
nowhere, a render that died, a target two host files disagree about, a call
with the wrong argument count. They are found by four different mechanisms and
are the same thing to a reader: something to go and look at.

Under it, **`source-origins`** answers about one source. It says which targets
that source generates for and how that was computed, which vocabulary
contributed each symbol, what an earlier require shadowed, and — the bucket
that justifies the whole report — **`FROM NOWHERE`**: a symbol in no
vocabulary, declared by nothing and bound by nothing, which the target's local
namer will spell and emit as written.

It used to be called `why`, which named only that a question was asked.

That is not hypothetical. A constant missing from a name table passed through
verbatim and emitted an identifier C# does not have; the CLR failed to compile
for the whole of the work that followed and every gate stayed green.
`generate` emits it happily. `source-origins` reports:

```
    FROM NOWHERE               ls-thunk
```

A render that throws still answers, from however far it got, because this is
the report you reach for when generation is already broken. `report` never
throws for a project that is merely wrong, for the same reason:
`check-usage` and `kin.host/check-agreement` are the gates, and this is the
report.

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
