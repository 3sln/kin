(ns kin.memo
  "Code generation for bounded, synchronized snapshots of integer arenas.

  An arena is opaque: length/read/drop/push forms are supplied by the source.
  No native arena fields or application types are assumed. Stored arrays are
  private immutable snapshots; reads copy into a caller-owned arena. Builders
  run outside this storage primitive, so unrelated work never holds its lock."
  (:require [kin] [kin.lang :as lang] [clojure.string :as str]))

(defn arena-cache
  "A defdata emitter with :load and :store accessors, N keys and one value.

  :data is the capacity in 32-bit cells (keys + values + one per entry).
  :key-ops supplies :bytes for a native byte-array key, or [length read] for
  an integer arena. Byte keys use native byte comparison and snapshots; each
  retained byte conservatively counts as one cell. :value-ops is
  [length read drop push].
  Both accessors return 1 on success, 0 on miss/oversize. Load clears its output
  on miss as well as hit. Store is idempotent for a key. FIFO eviction affects
  performance only. Keys include every cell, with no probabilistic equality."
  [ctx {:keys [data accessors options] bound :binding}]
  (let [target (:target ctx)
        {:keys [load store key-ops value-ops]} options
        ld (some #(when (= load (:name %)) %) accessors)
        st (some #(when (= store (:name %)) %) accessors)
        n (count key-ops)
        _ (when-not (and (integer? data) (pos? data) (<= data 16777216)
                         (= 2 (count accessors)) ld st (<= 1 n 16)
                         (= (inc n) (:arity ld) (:arity st))
                         (= (mapv :type (:params ld)) (mapv :type (:params st)))
                         (every? #(or (= :bytes %) (and (sequential? %) (= 2 (count %)))) key-ops) (= 4 (count value-ops)))
            (throw (ex-info "kin.memo/arena-cache: positive capacity, matching load/store signatures and arena operations required" {:binding bound})))
        native? (fn [k] (= :bytes (nth key-ops k)))
        _ (doseq [k (range n) :when (native? k)]
            (when-not (= (get {:rust "&[u8]" :go "[]byte" :java "byte[]" :csharp "byte[]"} target)
                         (:type (nth (:params ld) k)))
              (throw (ex-info "kin.memo/arena-cache: :bytes requires a native byte-array parameter" {:binding bound :key k}))))
        expr (fn [op arg & more]
               (-> (kin/render ctx (apply list op (symbol arg) more))
                   (str/replace "7654321" (if (= target :rust) "i as i32" "i"))))
        lens (mapv (fn [k ops]
                     (if (native? k)
                       (case target
                         :rust (str "key" k ".len() as i32")
                         :go (str "int32(len(key" k "))")
                         :java (str "key" k ".length")
                         :csharp (str "key" k ".Length"))
                       (expr (first ops) (str "key" k)))) (range) key-ops)
        read-at (fn [k at]
                  (str/replace (kin/render ctx (list (second (nth key-ops k)) (symbol (str "key" k)) 7654321))
                               "7654321" at))
        reads (mapv (fn [k] (when-not (native? k) (read-at k (if (= target :rust) "i as i32" "i")))) (range n))
        [vlen vread vdrop vpush] value-ops
        vl (expr vlen "value") vr (expr vread "value" 7654321)
        clear (expr vdrop "value" 0)
        ;; Render the push with a distinct placeholder: the cached cell is
        ;; not an index and must not inherit Rust's index cast.
        push (str/replace (kin/render ctx (list vpush 'value 7654322)) "7654322" "cell")
        names (mapv #(str "key" %) (range n))
        args (str/join ", " names)
        ;; Go's parameter syntax differs from the other two prefix-type targets.
        params (fn [acc]
                 (str/join ", "
                   (map (fn [p nm]
                          (case target
                            :rust (str nm ": " (:type p))
                            :go (str nm " " (:type p))
                            (str (:type p) " " nm)))
                        (:params acc) (conj names "value"))))
        keyparams (str/join ", " (take n (str/split (params ld) #", ")))
        entry (str bound "Entry") eq (str bound "Equal") getfn (str bound "Load") putfn (str bound "Store")
        state (str bound "State") lock (str bound "Lock") used (str bound "Used")
        ref (fn [k] (str "e.key" k))
        index (if (= target :rust) "i as usize" "i")
        fori (fn [limit body]
               (case target
                 :rust (str "for i in 0.." limit " { " body " }\n")
                 :go (str "for i := int32(0); i < " limit "; i++ { " body " }\n")
                 (str "for (int i = 0; i < " limit "; i++) { " body " }\n")))
        lenof (fn [a] (case target :rust (str a ".len() as i32") :go (str "int32(len(" a "))") :java (str a ".length") :csharp (str a ".Length")))
        branch (fn [condition] (str "if " (when (#{:java :csharp} target) "(") condition
                                       (when (#{:java :csharp} target) ")") " { return false; }\n"))
        eqbody (apply str
                 (for [k (range n)]
                   (if (native? k)
                     (branch (case target
                               :go (str "!bytes.Equal(" (ref k) ", key" k ")")
                               :rust (str (ref k) ".as_slice() != key" k)
                               :java (str "!java.util.Arrays.equals(" (ref k) ", key" k ")")
                               :csharp (str "!System.MemoryExtensions.SequenceEqual<byte>(new System.ReadOnlySpan<byte>(" (ref k) "), new System.ReadOnlySpan<byte>(key" k "))")))
                     (let [offset (fn [j] (if (zero? j) "i" (str "(i + " j ")")))
                           sub (fn [j] (str (ref k) "[" (offset j) (when (= target :rust) " as usize") "]"))
                           mismatch (str "(" (str/join " | " (for [j (range 8)]
                                                  (str "(" (read-at k (offset j)) " ^ " (sub j) ")"))) ") != 0")]
                       (str "{\n" (case target :go (str "n := " (lens k) "\ni := int32(0)\n")
                                               :rust (str "let n = " (lens k) "; let mut i: i32 = 0;\n")
                                               (str "int n = " (lens k) "; int i = 0;\n"))
                            (branch (str (lenof (ref k)) " != n"))
                            (if (#{:java :csharp} target) "while (i + 8 <= n) {\n" (if (= target :go) "for i + 8 <= n {\n" "while i + 8 <= n {\n"))
                            (branch mismatch) "i += 8;\n}\n"
                            (if (#{:java :csharp} target) "while (i < n) {\n" (if (= target :go) "for i < n {\n" "while i < n {\n"))
                            (branch (str (read-at k "i") " != " (sub 0)))
                            "i += 1;\n}\n}\n")))))
        copykeys (apply str
                   (for [k (range n)]
                     (if (native? k)
                       (case target
                         :rust (str "let key" k "copy = key" k ".to_vec();\n")
                         :go (str "key" k "copy := append([]byte(nil), key" k "...)\n")
                         :java (str "byte[] key" k "copy = key" k ".clone();\n")
                         :csharp (str "byte[] key" k "copy = (byte[])key" k ".Clone();\n"))
                       (str (case target
                              :rust (str "let mut key" k "copy = Vec::with_capacity(" (lens k) " as usize);\n")
                              :go (str "key" k "copy := make([]int32, " (lens k) ")\n")
                              (str "int[] key" k "copy = new int[" (lens k) "];\n"))
                            (fori (lens k) (if (= target :rust)
                                             (str "key" k "copy.push(" (reads k) ");")
                                             (str "key" k "copy[i] = " (reads k) ";")))))))
        copyvalue (str (case target
                        :rust (str "let mut snapshot = Vec::with_capacity(" vl " as usize);\n")
                        :go (str "snapshot := make([]int32, " vl ")\n")
                        (str "int[] snapshot = new int[" vl "];\n"))
                      (fori vl (if (= target :rust) (str "snapshot.push(" vr ");") (str "snapshot[i] = " vr ";"))))
        guard (apply str (for [ln (conj lens vl)]
                  (case target
                    :rust (str "if " ln " > " data " { return 0; }\n")
                    :go (str "if " ln " > " data " { return 0 };\n")
                    (str "if (" ln " > " data ") return 0;\n"))))
        weight (str "1 + " (str/join " + " (conj lens vl)))
        initargs (str/join ", " (conj (mapv #(str "key" % "copy") (range n)) "snapshot"))
        stored-type (fn [k] (case target
                              :rust (if (native? k) "Vec<u8>" "Vec<i32>")
                              :go (if (native? k) "[]byte" "[]int32")
                              (if (native? k) "byte[]" "int[]")))
        fields (case target
                 :rust (str "struct " entry " { " (apply str (for [k (range n)] (str "key" k ": " (stored-type k) ", "))) "value: Vec<i32> }\n")
                 :go (str "type " entry " struct { " (apply str (for [k (range n)] (str "key" k " " (stored-type k) "; "))) "value []int32 }\n")
                 (let [typed (conj (mapv (fn [k] [(stored-type k) (str "key" k)]) (range n)) ["int[]" "value"])]
                   (str (if (= target :java) "private static final class " "private sealed class ") entry " {\n"
                        (apply str (for [[ty nm] typed] (str (if (= target :java) "final " "internal readonly ") ty " " nm ";\n")))
                        (if (= target :java) "" "internal ") entry "(" (str/join ", " (map (fn [[ty nm]] (str ty " " nm)) typed)) ") { "
                        (apply str (for [[_ nm] typed] (str "this." nm " = " nm "; "))) "}\n}\n")))]
    (when (= target :go)
      (lang/need! ctx "sync")
      (when (some native? (range n)) (lang/need! ctx "bytes")))
    {:accessors {load (str "{unit}" getfn "(" (str/join ", " (map #(str "{" % "}") (range (inc n)))) ")")
                 store (str "{unit}" putfn "(" (str/join ", " (map #(str "{" % "}") (range (inc n)))) ")")}
     :helpers
     [(str fields
        (case target
          :go (str "var " lock " sync.Mutex\nvar " state " []" entry "\nvar " used " int32\n"
            "func " eq "(e *" entry ", " keyparams ") bool {\n" eqbody "return true\n}\n"
            "func " getfn "(" (params ld) ") int32 {\n" lock ".Lock(); defer " lock ".Unlock()\n" clear "\n"
            "for j := range " state " { e := &" state "[j]; if " eq "(e, " args ") { for _, cell := range e.value { " push " }; return 1 } }; return 0\n}\n"
            "func " putfn "(" (params st) ") int32 {\n" guard "weight := " weight "\nif weight > " data " { return 0 }; " lock ".Lock(); defer " lock ".Unlock()\n"
            "for j := range " state " { if " eq "(&" state "[j], " args ") { return 1 } }\n"
            copykeys copyvalue
            "for " used " + weight > " data " { e := &" state "[0]; " used " -= 1 + "
              (str/join " + " (map lenof (conj (mapv ref (range n)) "e.value"))) "; " state "[0] = " entry "{}; " state " = " state "[1:] }\n"
            state " = append(" state ", " entry "{" initargs "}); " used " += weight; return 1\n}\n")
          :rust (str "static " state ": std::sync::OnceLock<std::sync::Mutex<(i32, Vec<" entry ">)>> = std::sync::OnceLock::new();\n"
            "fn " eq "(e: &" entry ", " keyparams ") -> bool {\n" eqbody "true\n}\n"
            "pub(crate) fn " getfn "(" (params ld) ") -> i32 {\nlet cache = " state ".get_or_init(|| std::sync::Mutex::new((0, Vec::new()))).lock().unwrap_or_else(|e| e.into_inner());\n" clear ";\n"
            "for e in &cache.1 { if " eq "(e, " args ") { for &cell in &e.value { " push "; } return 1; } } 0\n}\n"
            "pub(crate) fn " putfn "(" (params st) ") -> i32 {\n" guard "let weight = " weight "; if weight > " data " { return 0; }\n"
            "let mut cache = " state ".get_or_init(|| std::sync::Mutex::new((0, Vec::new()))).lock().unwrap_or_else(|e| e.into_inner());\n"
            "for e in &cache.1 { if " eq "(e, " args ") { return 1; } }\n" copykeys copyvalue
            "while cache.0 + weight > " data " { let e = cache.1.remove(0); cache.0 -= 1 + "
            (str/join " + " (map lenof (conj (mapv ref (range n)) "e.value"))) "; }\n"
            "cache.1.push(" entry " { " (str/join ", " (concat (for [k (range n)] (str "key" k ": key" k "copy")) ["value: snapshot"])) " }); cache.0 += weight; 1\n}\n")
          ;; JVM and CLR keep the same lock scope, snapshot and FIFO contract.
          (let [j? (= target :java) listtype (if j? "java.util.ArrayList" "System.Collections.Generic.List")
                size (if j? ".size()" ".Count") add (if j? ".add" ".Add")
                at0 (if j? (str state ".get(0)") (str state "[0]"))
                remove0 (if j? (str state ".remove(0)") (str state ".RemoveAt(0)"))
                booltype (if j? "boolean" "bool") vis (if j? "public" "internal")
                lockopen (str (if j? "synchronized (" "lock (") lock ") {\n")]
            (str "private static " (if j? "final " "readonly ") (if j? "Object" "object") " " lock " = new " (if j? "Object" "object") "();\n"
              "private static " (if j? "final " "readonly ") listtype "<" entry "> " state " = new " listtype "<" entry ">();\nprivate static int " used " = 0;\n"
              "private static " booltype " " eq "(" entry " e, " keyparams ") {\n" eqbody "return true;\n}\n"
              vis " static int " getfn "(" (params ld) ") {\n" lockopen clear ";\n"
              (if j? (str "for (" entry " e : " state ")") (str "foreach (" entry " e in " state ")"))
              " { if (" eq "(e, " args ")) { "
              (if j? "for (int cell : e.value)" "foreach (int cell in e.value)") " { " push "; } return 1; } } return 0;\n}}\n"
              vis " static int " putfn "(" (params st) ") {\n" guard "int weight = " weight "; if (weight > " data ") return 0;\n" lockopen
              (if j? (str "for (" entry " e : " state ")") (str "foreach (" entry " e in " state ")")) " { if (" eq "(e, " args ")) return 1; }\n"
              copykeys copyvalue
              "while (" used " + weight > " data ") { " entry " e = " at0 "; " used " -= 1 + "
              (str/join " + " (map lenof (conj (mapv ref (range n)) "e.value"))) "; " remove0 "; }\n"
              state add "(new " entry "(" initargs ")); " used " += weight; return 1;\n}}\n"))))]}))
