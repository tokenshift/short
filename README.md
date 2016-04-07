# Short

[![Build Status](https://travis-ci.org/tokenshift/short.svg?branch=master)](https://travis-ci.org/tokenshift/short)

An implementation of the [circuit breaker](http://techblog.netflix.com/2011/12/making-netflix-api-more-resilient.html)
pattern. Based on [Netflix/Hystrix](https://github.com/Netflix/Hystrix),
implemented in pure Clojure.

## How it Works

The core concept of **Short** is the *circuit*. A circuit is a wrapper around
a dependency (a single API, endpoint or query) that could potentially be flaky
or unresponsive. The circuit tracks all calls to that API, and if enough calls
fail (or timeout), it "breaks", no longer allowing subsequent calls through for
a certain amount of time.

Technically, you can call any function through any circuit, though usually
you'd create a circuit-per-dependency or similar. You DO NOT need to create a
new circuit for each call; it's a gateway, not a command. In fact, doing this
would lose any of the circuit state information from the previous call.

## Example Use

```clojure
(def my-circuit (-> (circuit)
                    (with-strategy concurrency-limit 10)
                    (with-strategy consecutive-failures 5))
```

A `circuit->` macro is provided to make this easier:

```clojure
(def my-circuit (circuit-> (concurrency-limit 10)
                           (consecutive-failures 5)))
```

## Strategies

Strategies are order-dependent and wrap each other, like Ring middleware. The
last added strategy will get the call/request first, then pass it up the chain;
the return value (or exception) will flow back down the chain in the opposite
order.

**`caching`**  
Takes a clojure.core.cache/CacheProtocol implementation, and caches successful
responses. Can be configured to return a cached response on any request, or
only on failure.

**`concurrency-limit`**  
Caps the maximum number of simultaneous calls that can be made to a dependency
to avoid overloading it if it hangs.

**`consecutive-failures`**  
Breaks the circuit if N calls in a row fail.

**`fast-fail`**  
If the circuit is open (broken), fail immediately with an exception rather than
making a call against the dependency.

**`reclose-ttl`**  
Re-close the circuit after a certain amount of time has passed.

**`retry`**  
Retry on failure up to a certain number of times, with a configurable retry
interval.

**`throttle`**  
Limit the number of calls to the dependency that will be allowed through in a
certain amount of time. Any further calls will fail with an exception.

**`timeout`**  
Add a configurable timeout (in milliseconds) to a circuit.

## References

* [Netflix/Hystrix](https://github.com/Netflix/Hystrix)
  * [How it Works](https://github.com/Netflix/Hystrix/wiki/How-it-Works)
* [core/async](https://github.com/clojure/core.async)
* [CircuitBreaker](http://martinfowler.com/bliki/CircuitBreaker.html)
* [Making the Netflix API More Resilient](http://techblog.netflix.com/2011/12/making-netflix-api-more-resilient.html)
