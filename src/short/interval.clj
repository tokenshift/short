(ns short.interval
  "Configurable retry intervals."
  (:require [clojure.math.numeric-tower :refer [expt]]))

(defn- with-max-tries
  [interval-fn max-tries]
  #(when (> max-tries %) (interval-fn %)))

(defn doubling
  "Doubles the starting interval on each attempt."
  [start-interval max-tries]
  (with-max-tries #(* (expt 2 (- % 1)) start-interval) max-tries))

(def ^:private fib
  (memoize
    (fn [index]
      (loop [a 0
             b 1
             i 0]
        (if (>= i index)
          b
          (recur b (+ a b) (+ i 1)))))))

(defn fibonacci
  "Determines the next interval by adding together the two previous intervals,
  fibonacci-style."
  [start-interval max-tries]
  (with-max-tries #(* (fib (- % 1)) start-interval) max-tries))

(defn incrementing
  "Adds the starting interval to itself on each attempt."
  [start-interval max-tries]
  (with-max-tries #(* % start-interval) max-tries))

(defn static
  "Returns a set interval up to a certain number of times."
  [interval max-tries]
  (with-max-tries (constantly interval) max-tries))
