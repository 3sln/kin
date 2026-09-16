(ns example.project
  "This example project, described to kin.

  kin is a library: it has no command-line tool and no config file, so the
  thing that says `these are my vocabularies and these are my targets` is
  ordinary code. Two of these lines are the whole configuration."
  (:require [kin.project :as kp]
            [example.targets :as targets]))

(def project
  (delay (kp/load-project {;; `example.lang` declares TWO maps -- this
                           ;; example's subject, and Go added to `kin.lang`
                           ;; -- and `kin.lang` declares its own. The
                           ;; `kin.lang` GROUP is therefore two maps, and a
                           ;; source requiring it gets Go from one and Java
                           ;; from the other.
                           :vocabularies '[example.lang kin.lang]
                           :targets targets/targets
                           :target-order [:go :java]})))
