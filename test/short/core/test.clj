(ns short.core.test
  (:use [clojure.test]
        [short.core])
  (:require [clj-time.core :as t]
            [clojure.core.cache :as cache]))

(defn failer
  "Returns a function that can be forced to start failing or succeeding on
  demand (see `start-failing!` and `stop-failing!`)."
  []
  (let [failing (atom false)
        calls (atom [])
        rval (atom nil)]
    (with-meta
      (fn [& args]
        (swap! calls conj args)
        (if @failing
          (throw (Exception. "Failed!"))
          @rval))
      {::failing failing
       ::calls calls
       ::rval rval})))

(defn failer-calls
  "Returns a history of calls made to the failer."
  [failer]
  (deref (::calls (meta failer))))

(defn set-rval!
  "Sets the value that the failer will return the next time it is called."
  [failer rval]
  (reset! (::rval (meta failer)) rval)
  failer)

(defn start-failing!
  [failer]
  (reset! (::failing (meta failer)) true)
  failer)

(defn stop-failing!
  [failer]
  (reset! (::failing (meta failer)) false)
  failer)

(deftest test-caching-with-interception
  (let [f (-> (failer) (set-rval! :foo))
        c (circuit-> (caching (cache/->BasicCache {}) true))]
    (is (= 0 (count (failer-calls f))))
    (is (= :foo (call! c f)))
    (is (= 1 (count (failer-calls f))))
    (set-rval! f :foo2)
    (is (= :foo (call! c f)))
    (is (= 1 (count (failer-calls f))))))

(deftest test-caching-without-interception
  (let [f (-> (failer) (set-rval! :foo))
        c (circuit-> (caching (cache/->BasicCache {}) false))]
    (is (= 0 (count (failer-calls f))))
    (is (= :foo (call! c f)))
    (is (= 1 (count (failer-calls f))))
    (set-rval! f :foo2)
    (is (= :foo2 (call! c f)))
    (is (= 2 (count (failer-calls f))))
    (start-failing! f)
    (set-rval! f :foo3)
    (is (= :foo2 (call! c f)))
    (is (= 3 (count (failer-calls f))))))

(deftest test-concurrency-limit
  (let [plug (promise)
        plugged #(deref plug)
        c (circuit-> (concurrency-limit 2))
        result1 (future (call! c plugged))
        result2 (future (call! c plugged))
        result3 (future (call! c plugged))
        result4 (future (call! c plugged))]
    (Thread/sleep 50)
    (deliver plug 42)
    (is (= 42 @result1))
    (is (= 42 @result2))
    (is (thrown-with-msg? Exception #"concurrency limit reached" @result3))
    (is (thrown-with-msg? Exception #"concurrency limit reached" @result4))))

(deftest test-consecutive-failures
  (let [f (-> (failer) (set-rval! :test))
        c (circuit-> (consecutive-failures 3))]
    ; Stays closed while the dependency is working
    (call! c f)
    (call! c f)
    (call! c f)
    (call! c f)
    (is (closed? c))
    ; Breaks after three failures
    (start-failing! f)
    (call! c f)
    (is (closed? c))
    (call! c f)
    (is (closed? c))
    (call! c f)
    (is (not (closed? c)))
    ; Resets the count on every success
    (close! c)
    (stop-failing! f)
    (call! c f)
    (start-failing! f)
    (call! c f)
    (is (closed? c))
    (call! c f)
    (is (closed? c))
    (stop-failing! f)
    (call! c f)
    (is (closed? c))
    (start-failing! f)
    (call! c f)
    (is (closed? c))
    (call! c f)
    (is (closed? c))
    (call! c f)
    (is (not (closed? c)))))

(deftest test-fast-fail
  (let [f (failer)
        c (circuit-> (fast-fail))]
    (is (= 0 (count (failer-calls f))))
    (is (nil? (call! c f)))
    (is (= 1 (count (failer-calls f))))
    (open! c)
    (is (thrown-with-msg? Exception #"circuit is broken" (call! c f)))
    (is (= 1 (count (failer-calls f))))))

(deftest test-reclose-ttl
  (let [f (failer)
        c (circuit-> (reclose-ttl (t/millis 250)))]
    (open! c)
    (call! c f)
    (is (not (closed? c)))
    (Thread/sleep 50)
    (call! c f)
    (is (not (closed? c)))
    (Thread/sleep 50)
    (call! c f)
    (is (not (closed? c)))
    (Thread/sleep 50)
    (call! c f)
    (is (not (closed? c)))
    (Thread/sleep 50)
    (call! c f)
    (is (not (closed? c)))
    (Thread/sleep 50)
    (call! c f)
    (is (closed? c))))

(deftest test-throttle
  (let [f (failer)
        c (circuit-> (throttle 5 (t/seconds 1)))]
    (is (nil? (call! c f)))
    (is (nil? (call! c f)))
    (is (nil? (call! c f)))
    (is (nil? (call! c f)))
    (is (nil? (call! c f)))
    (is (thrown? Exception (call! c f)))
    (Thread/sleep 250)
    (is (thrown? Exception (call! c f)))
    (Thread/sleep 250)
    (is (thrown? Exception (call! c f)))
    (Thread/sleep 250)
    (is (thrown? Exception (call! c f)))
    (Thread/sleep 250)
    (is (nil? (call! c f)))
    (is (nil? (call! c f)))
    (is (nil? (call! c f)))
    (is (nil? (call! c f)))
    (is (nil? (call! c f)))
    (is (thrown? Exception (call! c f)))))

(deftest test-timeout
  (let [c (circuit-> (timeout 50))]
    (let [plug (promise)
          plugged (fn [] @plug :success)
          call (future (call! c plugged))]
      (deliver plug nil)
      (is (= :success @call)))
    (let [plug (promise)
          plugged (fn [] @plug :success)
          call (future (call! c plugged))]
      (Thread/sleep 100)
      (deliver plug nil)
      (is (thrown-with-msg? Exception #"timed out" @call)))
    (let [plug (promise)
          plugged (fn [] @plug (throw (Exception. "FAILED!")))
          call (future (call! c plugged))]
      (Thread/sleep 100)
      (deliver plug nil)
      (is (thrown-with-msg? Exception #"timed out" @call)))))
