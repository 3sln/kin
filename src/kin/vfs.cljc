(ns kin.vfs
  "A place kin can read and write, supplied by the user.

  kin must not know what a filesystem is. It emits into a destination, and
  what a destination IS -- a directory, a map in memory, an object store, a
  ClojureScript project's Node bindings -- is the user's business. So each
  TARGET carries a `:vfs`, and every byte kin reads or writes goes through it.

  That is not tidiness. Before this, the emit path reached for `java.io.File`
  and `babashka.fs` directly, which made kin babashka-only in spite of its
  `.cljc` extension, and made `emit!` untestable without a real directory to
  scribble in. `memory-vfs` is the answer to both.

  ## The operations were CHOSEN, not given

  The author asked for `a :vfs (virtual file system) protocol impl for each
  target` and did not name the operations. These three are what `emit!`
  actually needs and nothing more:

    -exists?  so a missing destination is an error naming the file, rather
              than a read that throws something host-specific
    -read     the host file, whose hand-written parts kin preserves
    -write    it back with the region replaced

  Deliberately absent from `Vfs`: deleting, creating directories, statting.
  kin writes INTO files a person already wrote -- a region lives between
  markers in hand-written code -- so it never creates one, and a protocol
  with three operations is easier to implement for a new host than one with
  eight. A protocol grows more easily than it shrinks.

  ## Listing is a SECOND protocol, and that was a choice

  A SOURCE vfs scans and reads; a DESTINATION vfs reads and writes. Only the
  source ever lists, which C5 posed as a question: one protocol whose `-list`
  a destination may refuse, or two protocols?

  TWO, as `Vfs` plus a `Listing` capability. A destination that must
  implement `-list` in order to refuse it is a lie in its own type -- the
  protocol says it can and the implementation says it cannot, and the
  disagreement surfaces at call time rather than at construction. With two,
  `(satisfies? Listing x)` is an honest question with an answer, and a host
  implements whichever half it actually has.

  The usual objection to splitting -- that implementors now write two things
  -- does not arise: `MemoryVfs` and `DiskVfs` below each satisfy both in one
  record, because being listable and being writable are not exclusive. What
  the split buys is the ability to be one without the other, which is exactly
  what a target's destination is."
  (:require [clojure.string :as str]))

(defprotocol Vfs
  (-exists? [this path] "Is there something at `path`?")
  (-read [this path] "The contents of `path`, as a string.")
  (-write [this path content] "Put `content` at `path`."))

(defprotocol Listing
  (-list [this] "Every path this holds, in no particular order."))

;; ------------------------------------------------------------------ memory

(defrecord MemoryVfs [files]
  Vfs
  (-exists? [_ path] (contains? @files path))
  (-read [_ path]
    (or (get @files path)
        (throw (ex-info (str "kin.vfs: nothing at " (pr-str path))
                        {:path path :known (vec (sort (keys @files)))}))))
  (-write [_ path content] (swap! files assoc path content) nil)
  Listing
  (-list [_] (vec (keys @files))))

(defn memory-vfs
  "A vfs that is a map. `(memory-vfs {\"a.rs\" \"...\"})`.

  This is the point of having a protocol at all: `emit!` can be tested
  end to end -- read a host file, splice a region, write it back -- with no
  directory, no cleanup, and no chance that a passing test wrote into the
  tree it was checking. Read what it holds back with `files`."
  ([] (memory-vfs {}))
  ([initial] (->MemoryVfs (atom initial))))

(defn files
  "What a `memory-vfs` currently holds."
  [vfs]
  @(:files vfs))

;; -------------------------------------------------------------------- disk

#?(:clj
   (defrecord DiskVfs [root]
     Vfs
     (-exists? [_ path] (.exists (java.io.File. (str root "/" path))))
     (-read [_ path] (slurp (str root "/" path)))
     (-write [_ path content]
       ;; PARENT DIRECTORIES ARE MADE, because a whole-file target CREATES its
       ;; destination -- `com/example/Thing.java` needs `com/example` to
       ;; exist. The region path never needed this: it writes into a file a
       ;; person already wrote. This is the implementation growing, not the
       ;; protocol: `-write` still means `put this content there`.
       (let [f (java.io.File. (str root "/" path))]
         (when-let [dir (.getParentFile f)] (.mkdirs dir))
         (spit f content))
       nil)
     Listing
     ;; One level, not a walk. A source directory of `.kin` files is what
     ;; this is for, and a recursive listing is a thing to add when something
     ;; needs it rather than a thing to guess at.
     (-list [_]
       (let [d (java.io.File. (str root))]
         (if (.isDirectory d)
           (vec (for [f (.listFiles d) :when (.isFile f)] (.getName f)))
           [])))))

#?(:clj
   (defn disk-vfs
     "A vfs rooted at a directory. `(disk-vfs \"runtime/src\")`.

     The root lives HERE rather than beside it as a `:dest`, because a vfs
     that did not know where it was rooted would need every caller to know,
     which is the coupling the protocol exists to remove. A target's `:path`
     answers a path relative to this root."
     [root]
     (->DiskVfs root)))

(defn resolve-vfs
  "The vfs for a target, or nil.

  A string is taken as a disk root, so a project that has not thought about
  any of this can write `:vfs \"runtime/src\"` and get the obvious thing --
  the `or a path I guess if we're being lazy` in the proposal."
  [target]
  (let [v (:vfs target)]
    (cond
      (nil? v) nil
      (string? v) #?(:clj (disk-vfs v)
                     :default (throw (ex-info "kin.vfs: no disk vfs on this host"
                                              {:root v})))
      :else v)))

;; ------------------------------------------------------------------ globs

(defn matches?
  "Does `path` match `pattern`?

  A DELIBERATELY TINY GLOB: `*.kin`, `*` and an exact name, and nothing else.
  A source directory needs `*.kin` and kin should not carry a glob engine to
  say so -- a project wanting more can filter the listing itself, since it is
  an ordinary sequence."
  [pattern path]
  (cond
    (or (nil? pattern) (= "*" pattern)) true
    (str/starts-with? pattern "*") (str/ends-with? path (subs pattern 1))
    :else (= pattern path)))

(defn listing
  "Every path in `vfs` matching `pattern`, SORTED.

  Sorted because a directory listing's order is the filesystem's, and a
  report that reorders between two runs of the same tree is much harder to
  read than one that does not. Ordering is cheap; comparability is not."
  ([vfs] (listing vfs nil))
  ([vfs pattern]
   (when-not (satisfies? Listing vfs)
     (throw (ex-info "kin.vfs: this vfs cannot list -- it is a destination"
                     {:vfs (type vfs)})))
   (vec (sort (filter (partial matches? pattern) (-list vfs))))))
