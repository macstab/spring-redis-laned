# Reactive Transactions

*By [Christian Schnapka (Per)](https://macstab.com) · Principal+ Embedded Engineer · [Macstab GmbH](https://macstab.com)*

**Status:** Planned for future release

---

## Table of Contents

<!-- TOC -->
* [Reactive Transactions](#reactive-transactions)
  * [Table of Contents](#table-of-contents)
  * [The Problem](#the-problem)
  * [Planned Solution: Reactor Context Propagation](#planned-solution-reactor-context-propagation)
  * [Workaround (Current Release)](#workaround-current-release)
  * [Timeline](#timeline)
<!-- TOC -->


## The Problem

`ThreadLocal` lane pinning (used for imperative WATCH/MULTI/EXEC transactions) does not work in Project Reactor / WebFlux environments. Reactive chains can switch threads at any suspension point (e.g., `flatMap`, `delayElement`, network I/O).

Example broken case:

```java
// Thread 1: WATCH fires, pins lane 2 to ThreadLocal
redisTemplate.watch("key");

// Network I/O causes suspension, resumes on Thread 2
// Thread 2: MULTI looks up ThreadLocal → null (different thread!)
redisTemplate.multi();  // Goes to random lane, WATCH guard lost
```

---

## Planned Solution: Reactor Context Propagation

Use Reactor's `Context` (immutable per-chain) instead of `ThreadLocal` (mutable per-thread).

**Implementation sketch:**

```java
// LanedLettuceConnectionProvider (reactive)
public <K, V, T> Mono<RedisCommand<K, V, T>> write(RedisCommand<K, V, T> command) {
    return Mono.deferContextual(ctx -> {
        if (command instanceof WatchCommand) {
            int lane = selectLane();
            return Mono.just(lanes[lane].write(command))
                .contextWrite(Context.of("pinnedLane", lane));
        }
        
        Integer pinnedLane = ctx.getOrDefault("pinnedLane", null);
        if (pinnedLane != null) {
            return Mono.just(lanes[pinnedLane].write(command));
        }
        
        return Mono.just(lanes[selectLane()].write(command));
    });
}
```

Reactor Context is **immutable** and propagates through the entire chain — thread-safe by design.

---

## Workaround (Current Release)

For reactive transactional code:

1. Check out a dedicated `StatefulRedisConnection` from one lane
2. Use `setAutoFlushCommands(false)` to buffer commands
3. Execute the transaction (WATCH → MULTI → commands → EXEC)
4. Call `flushCommands()` to send the batch
5. Return the connection

```java
StatefulRedisConnection<String, String> conn = laneProvider.getLane(0);
try {
    conn.setAutoFlushCommands(false);
    
    conn.async().watch("key");
    conn.async().multi();
    conn.async().set("key", "value");
    conn.async().exec();
    
    conn.flushCommands();
} finally {
    conn.setAutoFlushCommands(true);
}
```

This ensures all commands use the same connection, preserving the WATCH guard.

---

## Timeline

**Target release:** v1.1.0 (Q2 2026)

Depends on validation of MVP (v1.0.0) in production Spring WebFlux environments.

---

*Christian Schnapka, Principal+ Engineer · [Macstab GmbH](https://macstab.com)*
