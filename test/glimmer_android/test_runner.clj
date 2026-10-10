(ns glimmer-android.test-runner
  "Entry point for `jolt -M:test`. Requires each glimmer-android test namespace
  and runs clojure.test against it. Prints a summary; exits non-zero if anything
  failed (so the :test task fails CI)."
  (:require [clojure.test :as t]))

;; Print the cause chain of a test that throws. jolt's run-tests reports a
;; thrown test through clojure.test/err!, not the report multimethod, and err!
;; prints only the outer exception (stdlib/clojure/test.clj, jolt v0.8.7).
;; clj-kondo knows only the JVM clojure.test, which has no err!.
#_{:clj-kondo/ignore [:unresolved-var]}
(alter-var-root #'t/err!
  (fn [err!]
    (fn [m]
      (err! m)
      (t/with-test-out
        (loop [c (some-> (:actual m) ex-cause)]
          (when c
            (println "  caused by:" (.getName (class c)) ":" (ex-message c))
            (recur (ex-cause c))))))))

;; Call System/exit directly. (resolve 'System/exit) is nil on jolt 0.8.7,
;; so a resolve-based exit did nothing and a failed run exited 0.
(defn- exit [code]
  (System/exit code))

(defn failures
  "The failed and erred assertions in `results`, plus one for each test
  namespace in `unloaded`. A namespace that does not load runs no tests, so
  without the second count a broken test file passes."
  [results unloaded]
  (+ (:fail results 0) (:error results 0) (count unloaded)))

(defn- load-namespace
  "Require `ns`. Return nil when it loads, or `ns` when it throws."
  [ns]
  (try (require ns :reload)
       nil
       (catch Exception e
         (println "ERROR requiring" ns ":" (ex-message e))
         ns)))

(defn -main [& _]
  (let [namespaces '[glimmer-android.ffi-test
                     glimmer-android.props-test
                     glimmer-android.widget-test
                     glimmer-android.core-test
                     glimmer-android.conformance-test
                     glimmer-android.demo-test
                     glimmer-android.test-runner-test]
        unloaded   (vec (keep load-namespace namespaces))
        loaded     (remove (set unloaded) namespaces)
        results    (if (seq loaded) (apply t/run-tests loaded) {})
        failed     (failures results unloaded)]
    (println "----")
    (println "tests:" (:test results 0)
             "assertions:" (:pass results 0) "passed /"
             failed "failed")
    (when (seq unloaded)
      (println "not loaded:" (pr-str unloaded)))
    (when (pos? failed) (exit 1))
    ;; A future the tests started keeps jolt alive for 60 s after -main
    ;; returns; shut the agent pool down so a passing run exits at once.
    (shutdown-agents)))
