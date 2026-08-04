#!/usr/bin/env nbb
;; nbb --classpath "src:test:../okugai/src:../denchu/src" test/run.cljs
(require '[clojure.test :as t] 'loop-okugai-survey.core-test)
(let [{:keys [fail error]} (t/run-tests 'loop-okugai-survey.core-test)]
  (js/process.exit (if (pos? (+ fail error)) 1 0)))
