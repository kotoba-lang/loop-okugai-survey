#!/usr/bin/env nbb
;; nbb --classpath "src:test:../denchu/src" test/run.cljs
(require '[clojure.test :as t] 'loop-denchu-survey.core-test)

(let [{:keys [fail error]} (t/run-tests 'loop-denchu-survey.core-test)]
  (js/process.exit (if (pos? (+ fail error)) 1 0)))
