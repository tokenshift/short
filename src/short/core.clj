(ns short.core
  (:require [clj-time.core :as t]
            [clojure.core.cache :as cache]))

;; # Base circuit definition

(defn- default-handler
  [f & args]
  (apply f args))

(defn circuit
  "Constructs a new circuit through which dependencies can be called."
  []
  {::closed (atom true) ::handler default-handler})

(defn closed?
  "Checks whether the circuit is closed."
  [circuit]
  (::closed @circuit))

(defn open?
  "Checks whether the circuit is open."
  [circuit]
  (not (closed? circuit)))

(defn close!
  "Closes the circuit, allowing additional calls to be made through it."
  [circuit]
  (swap! circuit assoc ::closed true)
  circuit)

(defn open!
  "Opens the circuit, preventing further calls from being made through it."
  [circuit]
  (swap! circuit assoc ::closed false)
  circuit)

(defn call!
  "Calls a function through the circuit."
  [circuit & args]
  (apply (::handler circuit) circuit args))

;; # Circuit strategies
;;
;; Each of these wraps a circuit with additional behavior such as breaking the
;; circuit if a certain number of requests fails, or returning a cached
;; response on failure.
;;
;; Example Use:
;;
;; ```clojure
;; (-> (circuit)
;;     (with-strategy concurrency-limit 10)
;;     (with-strategy consecutive-failures 5))
;; ```
;;
;; A `circuit->` macro is provided to make this easier:
;;
;; ```clojure
;; (circuit-> (concurrency-limit 10)
;;            (consecutive-failures 5))
;; ```

(defn with-strategy
  "Wraps a circuit with the specified strategy."
  [circuit strategy & args]
  (update-in circuit [::handler] #(apply strategy % args)))

(defmacro circuit->
  "Constructs a new circuit and wraps it with a series of strategies."
  [& forms]
  (let [injected (map #(cons 'with-strategy %) forms)]
    `(-> (circuit)
         ~@injected)))

(defn concurrency-limit
  "Limits how many calls through the circuit can currently be active, in case
  the dependency starts backing up."
  [handler n]
  (let [current (atom 0)]
    (fn [circuit & args]
      (when (>= @current n)
        (throw (ex-info "concurrency limit reached" {:current @current :limit n})))
      (swap! current inc)
      (try
        (apply handler args)
        (finally
          (swap! current dec))))))

(defn consecutive-failures
  "Breaks the circuit after N consecutive failures, resetting the count on
  every success."
  [handler n]
  (let [failures (atom 0)]
    (fn [circuit & args]
      (try
        (apply handler args)
        (reset! failures 0)
        (catch Exception ex
          (swap! failures inc)
          (when (>= @failures n)
            (open! circuit)))))))

;; # Strategies

;; Limits how many calls through the circuit can currently be active, in case
;; the dependency starts backing up.

;; Breaks the circuit after N consecutive failures, resetting the count on
;; every success.

;; Recloses the circuit after a certain amount of time has passed.
;; TTL should be clj-time Interval.

; Create a circuit: `(def my-circuit (circuit/consecutive-failures 3))`
; Invoke a function through a circuit: `(circuit/call my-circuit (get-stuff-from-foo))`
; Wrap a function in a circuit: `(def maybe-get-stuff-from-foo (circuit/wrap my-circuit get-stuff-from-foo))`
;
; Odd use case: circuit breaker that breaks only for particular arguments
; (e.g. if a given URL can't be retrieved, only break for that URL).
;
; Breakers:
;
; consecutive-failures
; Break if more than N consecutive failures.
;
; since-last-success
; Break if it's been long enough since the last successful call.
;
; Maybe:
; Make circuit breakers composable, middleware-style.
; So memoize-like functionality (return the last successful response) could be
; a wrapper, as could "arg specific" breaking (e.g. only break for a specific
; request URL), and different strategies for re-closing the circuit.
; The circuit itself could just be a map, with standardized settings and
; callbacks for various stages.
;
; Example 1: A circuit breaker that:
; * Breaks if there have been 10 failures in a row
; * Returns a memoized result if the circuit is open
; * Stays open for half an hour, then closes again
;
; Example 2:
; * Breaks if there has been an hour of failures with no successes
; * Still calls the wrapped function on every call
; * But starts handling errors differently (consumer provided handling)
;
; Example 3:
; * Breaks upon reaching a certain percentage of failures
; * Starts throttling the number of requests-per-second
; * Lets other requests error, OR returns a memoized response
;
; Example 4:
; * Automatically starts throttling on first failure, adjusts throttling
;   dynamically
;
; Example 5:
; * Add a timeout to an arbitrary function, treat slow responses (even if
;   successful) as failures.
;
; Example 6:
; * Break if the last 5 requests for a specific URL have failed, but only break
;   for that URL, others are still considered open.
;
; Example 7:
; * Add timeout to a service that doesn't have one (wrap call in a future or
;   promise and only wait a certain amount of time for it to be delivered).
;
; Example 8:
; * Automatically retry on failure
; * Up to N times
; * At increasing intervals
; * Depending on order composed, each retry could be counted as its own failure
;   (thus tripping the circuit after a certain number), or the whole retry
;   process could count as a single failure. (e.g. wrap consecutive-failures
;   before or after this?)
;
; Configurable behaviors/strategies:
; * Tracking whether the circuit is open or closed (number of consecutive
;   failures, etc)
; * How/when to re-close the circuit after it is broken (TTL?)
; * What to do on a failure
; * What to do on a call when the circuit is open (memoized response? repeat
;   the last failure? raise a new exception?)
; * Multicircuit? Like multimethod, for circuit breakers
;   Define dispatch function on fn args to determine which breaker to send the
;   call to.
;
; Middleware concept:
;
; A "circuit" is like a function that can either succeed (return something) or
; fail (throw an exception). However, where a Ring handler wraps a single
; function, circuits allow anything to be called "through" them, and exist as
; independent entities.
;
; `(circuit/call my-circuit some-fn arg1 arg2)`
; Invokes (some-fn arg1 arg2) wrapped with my-circuit.
;
; Strategies:
; * Automatic retry (up to N times)
; * Increasing retry interval
; * Timeouts
; * Break after N consecutive failures
; * Break after nothing but failures over certain time
; * On failure, return memoized result
; * On failure, rethrow caught exception
; * On failure, do something completely different (custom)
; * When broken/open, return memoized result
; * Re-close after certain amount of time
; * When broken, continue calling the wrapped function, but with different
;   error handling
; * Break after % failures
; * Throttle requests-per-second when circuit broken
; * Throttle requests-per-second dynamically, to keep under the failure level
; * Retry N times, treat this as a single failure
; * Retry N times, treat this as N failures
; * Test before reclosing (let a single request through, if fails, then leave
;   open). Potentially renew reclose TTL.
; * "Multimethod"-like circuit: dispatch function on arguments, each dispatch
;   result gets its own circuit.
;
; Maybe: use pure-function approach; a circuit just wraps a function directly
; (no `(call circuit foo)` needed). Then, if a single circuit needs to be
; shared (e.g. doesn't just wrap a single function), provide a separate
; function/macro to wrap a circuit (function) in a function that takes the fn
; to be called in the circuit. That way, circuit strategies really can be
; implemented like middleware.
;
; Except, it would be nice (maybe?) to have `open?` and `closed?` tests on the
; fn. Though, should consumers really be using these?
;
; How to combine something like "stay open/broken for N seconds (TTL)" and
; "renew TTL if next attempt still fails" as two circuit middlewares?
;
; Functional state machine approach? Each state is a function that returns the
; next state (potentially itself).
