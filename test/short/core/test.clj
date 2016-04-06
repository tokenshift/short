(ns short.core.test
  (:use [clojure.test]
        [short.core]))

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

(deftest test-concurrency-limit
  (let [plug (promise)
        plugged #(deref plug)
        c (circuit-> (concurrency-limit 2))
        result1 (future (call! c plugged))
        result2 (future (call! c plugged))
        result3 (future (call! c plugged))
        result4 (future (call! c plugged))]
    (deliver plug 42)
    (is (= 42 @result1))
    (is (= 42 @result2))
    (is (thrown-with-msg? Exception #"concurrency limit reached" @result3))
    (is (thrown-with-msg? Exception #"concurrency limit reached" @result4))))
