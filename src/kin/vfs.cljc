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

  Deliberately absent: listing, deleting, creating directories, statting.
  kin writes INTO files a person already wrote -- a region lives between
  markers in hand-written code -- so it never creates one, and a protocol
  with three operations is easier to implement for a new host than one with
  eight. A protocol grows more easily than it shrinks."
  (:require [clojure.string :as str]))

(defprotocol Vfs
  (-exists? [this path] "Is there something at `path`?")
  (-read [this path] "The contents of `path`, as a string.")
  (-write [this path content] "Put `content` at `path`."))

;; ------------------------------------------------------------------ memory

(defrecord MemoryVfs [files]
  Vfs
  (-exists? [_ path] (contains? @files path))
  (-read [_ path]
    (or (get @files path)
        (throw (ex-info (str "kin.vfs: nothing at " (pr-str path))
                        {:path path :known (vec (sort (keys @files)))}))))
  (-write [_ path content] (swap! files assoc path content) nil))

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
     (-write [_ path content] (spit (str root "/" path) content) nil)))

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
