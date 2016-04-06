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

### Breakers

Every circuit has a *breaker* that determines whether the circuit should be
broken based on the result of an individual call. Usually, this will be a count
or percentage of failures (exceptions thrown); once some threshold is reached,
the breaker marks the circuit as "open", and subsequent calls to (through) the
circuit go immediatly to the circuit's configured fallback behavior.

Breakers also have configurable "reclose" behavior, usually "half-closing" the
circuit once a certain amount of time has passed and allowing a single call
through to test whether the dependency is now responsive, and closing the
circuit completely if it is.

**Success** means the call returned a value (any value).
**Failure** is indicated by throwing an exception rather than returning.

### Timeouts

A timeout can be optionally added to any circuit, even when the underlying
request doesn't support it. In this case, **short** will execute the request in
a separate thread, blocking on the result (for a certain amount of time).

If the timeout is exceeded, the call fails. NOTE: the underlying call could
still eventually go through, so ONLY retry it if the request is idempotent (or
sending it more than once won't have a bad result).

### Retry

Similarly, circuits can be configured to retry the request (upon failure) a
certain number of times. Each failure still counts as such, so a single
repeatedly retried call could be enough to break the circuit.

TBD: configurable retry interval (constant, climbing, fibonacci)

### Workers/Pools

**Short** allows you to limit how many concurrent requests can be handled by
a circuit, to avoid piling up work on a just-started-to-fail dependency before
finally breaking the circuit. If a circuit's "worker threshold" is reached, any
subsequent calls automatically fail and invoke the fallback behavior.

Pooling/semaphore implementation TBD

## References

* [Netflix/Hystrix](https://github.com/Netflix/Hystrix)
  * [How it Works](https://github.com/Netflix/Hystrix/wiki/How-it-Works)
* [core/async](https://github.com/clojure/core.async)
* [CircuitBreaker](http://martinfowler.com/bliki/CircuitBreaker.html)
* [Making the Netflix API More Resilient](http://techblog.netflix.com/2011/12/making-netflix-api-more-resilient.html)
