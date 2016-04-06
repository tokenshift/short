(ns short.interval.test
  (:use [clojure.test]
        [short.interval]))

(deftest test-doubling
  (let [interval-fn (doubling 50 5)]
    (are [expected tries] (= expected (interval-fn tries))
         50  1
         100 2
         200 3
         400 4
         nil 5)))

(deftest test-fibonacci
  (let [interval-fn (fibonacci 50 7)]
    (are [expected tries] (= expected (interval-fn tries))
         50  1 ; 1
         50  2 ; 1
         100 3 ; 2
         150 4 ; 3
         250 5 ; 5
         400 6 ; 8
         nil 7)))

(deftest test-incrementing
  (let [interval-fn (incrementing 50 5)]
    (are [expected tries] (= expected (interval-fn tries))
         50  1
         100 2
         150 3
         200 4
         nil 5)))

(deftest test-static
  (let [interval-fn (static 50 5)]
    (are [expected tries] (= expected (interval-fn tries))
         50  1
         50  2
         50  3
         50  4
         nil 5)))
