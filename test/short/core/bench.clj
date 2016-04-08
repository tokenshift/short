(ns short.core.bench
  "Benchmarking short-circuited calls."
  (:use [criterium.core]
        [short.core])
  (:require [clj-time.core :as t]
            [clojure.core.cache :as cache]
            [short.interval :as interval]))

(defn slow-dependency
  [pause-ms]
  (fn [& args]
    (when (>= pause-ms 0) (Thread/sleep pause-ms))
    ::here))

(defn run-benchmarks!
  []
  (let [circuit (circuit-> (fast-fail)
                           (reclose-ttl (t/seconds 30))
                           (consecutive-failures 10)
                           (concurrency-limit 5)
                           (retry (interval/incrementing 50 5))
                           (timeout 1000))]
    (doseq [pause [0 50 100]]
      (println (format "> Testing dependency with delay: %dms" pause))
      (let [f (fn [] (Thread/sleep pause) ::here)]
        (println ">> Without circuit")
        (with-progress-reporting (bench (f)))
        (println ">> With circuit")
        (with-progress-reporting (bench (call! circuit f)))))))
