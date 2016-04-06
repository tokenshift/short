(ns short.core
  (:require [clj-time.coerce :as c]
            [clj-time.core :as t]
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
  @(::closed circuit))

(defn open?
  "Checks whether the circuit is open."
  [circuit]
  (not (closed? circuit)))

(defn close!
  "Closes the circuit, allowing additional calls to be made through it."
  [circuit]
  (reset! (::closed circuit) true)
  circuit)

(defn open!
  "Opens the circuit, preventing further calls from being made through it."
  [circuit]
  (reset! (::closed circuit) false)
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

(defn caching
  "Returns cached responses when available to reduce load on a dependency.
  `cache` should be a clojure.core.cache.CacheProtocol implementation. If
  `intercept` is false, cached responses will only be returned when the call
  to the dependency fails."
  ([handler cache intercept]
   (let [cache-atom (atom cache)]
     (fn [circuit & args]
       (if intercept
         ; Check the cache first, then hit the dependency.
         (let [c @cache-atom]
           (if (cache/has? c args)
             (let [result (cache/lookup c args)]
               (swap! cache-atom cache/hit args)
               result)
             (let [result (apply handler args)]
               (swap! cache-atom cache/miss args result)
               result)))
         ; Hit the dependency, and only check the cache if it fails.
         (try
           (let [result (apply handler args)]
             (swap! cache-atom cache/miss args result)
             result)
           (catch Exception ex
             (let [c @cache-atom]
               (if (cache/has? c args)
                 (let [result (cache/lookup c args)]
                   (swap! cache-atom cache/hit args)
                   result)
                 (throw ex)))))))))
  ([handler cache] (caching handler cache true)))

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

(defn fast-fail
  "Fails immediately if the circuit is open, without making any calls to
  subsequent handlers."
  [handler]
  (fn [circuit & args]
    (if (closed? circuit)
      (apply handler args)
      (throw (ex-info "circuit is broken" {})))))

(defn reclose-ttl
  "Recloses the circuit after a certain amount of time has passed.
  TTL should be a clj-time Interval."
  [handler ttl]
  (let [deadline (atom nil)]
    (fn [circuit & args]
      ; Close the circuit if the TTL has passed.
      (when (and @deadline (t/after? (t/now) @deadline))
        (close! circuit)
        (reset! deadline nil))
      (apply handler args)
      ; Start the TTL countdown if the circuit is broken.
      (when (and (nil? @deadline) (open? circuit))
        (reset! deadline (t/from-now ttl))))))

(defn retry
  "Retries any failing calls at a configurable interval.
  `start-interval` should be the interval in milliseconds; `interval-fn` will
  be passed the number of attempts that have been made and should return the
  interval, or nil to stop retrying (0 to retry without waiting)."
  [handler interval-fn]
  (fn [circuit & args]
    (loop [tries 1]
      (let [result (try
                     (apply handler args)
                     (catch Exception ex
                       (let [interval (interval-fn tries)]
                         (when (nil? interval) (throw ex))
                         (when (> interval 0) (Thread/sleep interval))
                         ::retry)))]
        (if (= ::retry result)
          (recur (+ 1 tries))
          result)))))

(defn throttle
  "Throttles the number of requests that will be passed through to the
  dependency. `cap` is the count of requests that will be passed through in the
  specified `period` (as a clj-time Interval)."
  [handler cap period]
  (let [log (atom [])]
    (fn [circuit & args]
      (let [cutoff (c/to-long (t/ago period))
            cleanup (fn [reqs] (drop-while #(< % cutoff) reqs))]
        (if (>= (count (swap! log cleanup)) cap)
          (throw (ex-info "request throttling in effect" {:cap cap :period period}))
          (try
            (apply handler args)
            (finally
              (swap! log conj (c/to-long (t/now))))))))))

(defn timeout
  "Adds a configurable timeout to a dependency."
  [handler timeout-ms]
  (fn [circuit & args]
    (let [result (deref (future (apply handler args)) timeout-ms ::timeout)]
      (if (= ::timeout result)
        (throw (ex-info "request timed out" {:timeout-ms timeout-ms}))
        result))))

; Odd use case: circuit breaker that breaks only for particular arguments
; (e.g. if a given URL can't be retrieved, only break for that URL).
