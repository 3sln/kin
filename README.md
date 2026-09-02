# kin

Write a unit of logic once; emit it into every runtime that has to agree.

kin exists for a specific problem: a project with several runtimes that are
meant to be verbatim mirrors of each other — a Rust one, a JVM one, a CLR one —
where keeping them in step is done by hand, and the cost is paid every time
anything changes. kin makes one source the origin and generates the rest.

It is **not** a compiler and **not** a linter. Its output is ordinary,
idiomatic, checked-in Rust, Java and C# that a person reads and reviews. The
generator runs by hand, and what it wrote is committed.

## What it looks like

```clojure
(ns runtime.hash
  (:require [flint.impl.hash :refer [defn return mul32 rotl hex U32]]))

(defn ^:inline ^:unchecked ^U32 mix-k1 [^U32 k1]
  (return (mul32 (rotl (mul32 k1 C1) 15) C2)))
```

becomes, in three files:

```rust
#[inline]
fn mix_k1(k1: u32) -> u32 {
    return k1.wrapping_mul(C1).rotate_left(15).wrapping_mul(C2);
}
```
```java
static int mixK1(int k1) {
    return Integer.rotateLeft((k1 * C1), 15) * C2;
}
```
```csharp
static int MixK1(int k1) {
    unchecked {
        return ((int) BitOperations.RotateLeft((uint) (k1 * C1), 15)) * C2;
    }
}
```

Note what the targets do NOT share: Rust says `wrapping_mul` because its `*`
panics on overflow in debug; C# wraps the body in `unchecked` and reaches for
`BitOperations`; Java's `int` wraps and needs neither. **The outputs are not
copies.** They are what a person would have written in each language, which is
the whole claim — and the reason the tool is called kin rather than something
that means duplicate.

## The rule that governs it

**Generated code may not be worse than the hand-written code it replaces.**

Judged by measurement, not by reading. That rule has refused ports: a
four-byte writer generated into Rust measured 50 instructions against the
hand-written 15, so it stays hand-written. It has also improved them: naming a
rotate in the vocabulary kept Rust identical and upgraded two targets to their
intrinsics.

A corollary that has cost real time: **measure a generated function as a
library, never as a binary.** A `main` calling each form once on constants lets
the compiler specialise both against that call site, and the answer is about
the benchmark.

## How it works

A source is an ordinary `ns` with `:require`, so what a file may say is what it
asked for. A **vocabulary** implements each form per target; a **tag** carries
both the type each target spells it as and how to reach its operations; a
**name** is a value spelled differently per target (`TY_CONS` against
`Obj.TyCons`) — it cannot be a form, because it appears where a call cannot go.

kin ships the language: `defn`, `let`, `if`, `case`, `for`, `while`, `return`,
the operators, and the marks that say what one target needs and the others do
not (`^:mut`, `^:inline`, `^:unchecked`, `^:throws`, `^:method`). A project
ships its own subject: what its types are called, how its heap is read.

## Verify, then emit

```
kin gen     src.kin    print what each target would get
kin verify  src.kin    compile and RUN all three, require identical output
kin emit    src.kin    substitute into the target files, between markers
```

`verify` is the one that matters. Three implementations can each compile and
still disagree, so the check is that they produce the same bytes when run.

`emit` writes between markers, so the generated code is checked in and a build
never needs kin present:

```rust
// kin:begin kin/hash.kin
...generated...
// kin:end kin/hash.kin
```

## Status

Early. Extracted from the runtime it was built for, where it currently
generates murmur3, a type-category dispatch, a set of CHAMP node accessors and
a range predicate into three runtimes, each verified byte-identical and passing
that project's cross-runtime conformance suite.

## Licence

See LICENSE.
