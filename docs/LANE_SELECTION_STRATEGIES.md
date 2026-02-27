# Lane Selection Strategies

*By [Christian Schnapka (Per)](https://macstab.com) · Principal+ Embedded Engineer · [Macstab GmbH](https://macstab.com)*


The laned connection provider supports multiple dispatch strategies. Round-robin is the current implementation. All other strategies are planned enhancements.

All strategies share the same lane infrastructure — only the dispatch logic changes. Strategy selection will be configurable via `spring.data.redis.connection.lane-selection-mode`.

---

## Table of Contents

<!-- TOC -->
* [Lane Selection Strategies](#lane-selection-strategies)
  * [Table of Contents](#table-of-contents)
  * [Current: `ROUND_ROBIN` ✅](#current-round_robin-)
  * [Current : `LEAST_USED`](#planned-least_used)
  * [Planned: `KEY_AFFINITY` (MurmurHash3)](#planned-key_affinity-murmurhash3)
  * [Planned: `RANDOM`](#planned-random)
  * [Planned: `ADAPTIVE` (Latency-Weighted)](#planned-adaptive-latency-weighted)
  * [Current: `THREAD_STICKY`](#planned-thread_sticky)
  * [Strategy Comparison](#strategy-comparison)
  * [⚠️ Transaction Safety Warning](#-transaction-safety-warning)
    * [Technical Root Cause](#technical-root-cause)
    * [The Problem](#the-problem)
    * [Protocol-Level Constraint](#protocol-level-constraint)
    * [Production-Safe Configurations](#production-safe-configurations)
    * [Workarounds](#workarounds)
    * [Architectural Trade-off](#architectural-trade-off)
    * [Deep Dive](#deep-dive)
  * [Configuration (Planned)](#configuration-planned)
<!-- TOC -->


## Current: `ROUND_ROBIN` ✅

Select the next lane via atomic counter increment modulo N.

```java
private int selectLane() {
    return (counter.getAndIncrement() & Integer.MAX_VALUE) % numLanes;
}
```

**Dispatch cost:** O(1), one CAS operation  
**Contention:** Low (single shared atomic counter)  
**Distribution:** Uniform in expectation  

**Best for:** Default strategy, uniform uncorrelated workloads

**Limitation:** Blind to actual queue depth — can assign commands to a lane already carrying a slow command when another lane is idle.

---

## Planned: `LEAST_USED`

Select the lane with the fewest pending commands in its `CommandHandler.stack` at dispatch time.

Each lane tracks an `AtomicInteger pendingCount` — incremented on command write, decremented on response completion. Selection scans all N lanes and picks the minimum.

```java
private int selectLane() {
    int minLane = 0;
    int minPending = lanes[0].pendingCount();
    
    for (int i = 1; i < numLanes; i++) {
        int pending = lanes[i].pendingCount();
        if (pending < minPending) {
            minPending = pending;
            minLane = i;
        }
    }
    
    return minLane;
}
```

**Example:**
```
Lane 0: pending=3  (has a slow HGETALL in progress)
Lane 1: pending=1
Lane 2: pending=0  ← selected
Lane 3: pending=2
```

**Dispatch cost:** O(N) scan  
**Contention:** None (read-only atomic reads)  
**Distribution:** Adaptive to actual load

**Best for:** Mixed fast/slow command workloads where queue depth varies significantly

**Caveat:** Scanning N lanes on every dispatch. For N≤32 this is negligible (cache-friendly sequential reads). Under extreme concurrency, the "minimum" lane can be claimed by another thread between selection and write — the strategy is best-effort, not globally optimal. A small random tiebreak between lanes with equal depth prevents thundering-herd selection of a single empty lane.

---

## Planned: `KEY_AFFINITY` (MurmurHash3)

Route commands by key: `MurmurHash3(keyBytes) % N`. The same key always maps to the same lane.

```java
private int selectLane(RedisCommand<?, ?, ?> command) {
    byte[] key = extractKey(command);   // visitor over Lettuce's command type hierarchy
    if (key == null) return roundRobin(); // commands without a key (PING, INFO, CLIENT*)
    return (MurmurHash3.hash(key) & 0xFFFF) % numLanes;
}
```

**Dispatch cost:** O(key length) for MurmurHash3 calculation  
**Contention:** None  
**Distribution:** Uniform for uniformly distributed keys

**Best for:**
- Transactional workloads (`WATCH`/`MULTI`/`EXEC`)
- Key-isolated workloads where commands for different keys should not interfere

**Why it helps:**
- Naturally solves the `WATCH`/`MULTI`/`EXEC` transactional pinning problem for key-scoped transactions: WATCH and EXEC on the same key always hit the same lane, no `ThreadLocal` pinning needed.
- Commands for different keys are isolated — a slow `HGETALL user:1234` does not block a fast `GET session:5678` if they hash to different lanes.
- Hot key traffic concentrates on one lane (same as hash slot in cluster mode), enabling predictable isolation.

**Caveat:** Extracting the key requires a type switch over Lettuce's command hierarchy (`KeyCommand`, `KeyValueCommand`, `KeyStreamingCommand`, multi-key commands like `MGET`, etc.). Multi-key commands (`MGET k1 k2 k3`) use the first key for lane selection — a documented simplification. Commands with no key fall back to round-robin.

Uses MurmurHash3 for uniform key distribution. Note: This does NOT align with Redis cluster hash slots (which use CRC16-CCITT). Use for non-cluster or when shard affinity is not required.

---

## Planned: `RANDOM`

`ThreadLocalRandom.current().nextInt(N)` per dispatch. No shared atomic state.

```java
private int selectLane() {
    return ThreadLocalRandom.current().nextInt(numLanes);
}
```

**Dispatch cost:** O(1), zero CAS operations  
**Contention:** None (per-thread random state)  
**Distribution:** Uniform in expectation, higher variance than round-robin

**Best for:** Extreme concurrency workloads where atomic counter CAS is a measured bottleneck

Round-robin requires a shared `AtomicLong` counter. Under extreme concurrency (thousands of threads per second), even a CAS on a single cacheline causes contention. `RANDOM` eliminates that entirely: `ThreadLocalRandom` is per-thread and never contends.

Distribution is asymptotically equivalent to round-robin — uniform in expectation, with slightly higher variance in short bursts. For workloads where the atomic counter is a measured bottleneck, `RANDOM` is the zero-overhead alternative.

---

## Planned: `ADAPTIVE` (Latency-Weighted)

Track an exponential moving average (EMA) of response latency per lane. Weight lane selection inversely proportional to recent latency.

```java
// Conceptual pseudocode
private int selectLane() {
    double[] weights = new double[numLanes];
    double totalWeight = 0.0;
    
    for (int i = 0; i < numLanes; i++) {
        double emaLatency = lanes[i].getLatencyEMA();
        weights[i] = 1.0 / (1.0 + emaLatency);  // inverse latency
        totalWeight += weights[i];
    }
    
    // Weighted random selection
    double rand = ThreadLocalRandom.current().nextDouble() * totalWeight;
    double cumulative = 0.0;
    for (int i = 0; i < numLanes; i++) {
        cumulative += weights[i];
        if (rand < cumulative) return i;
    }
    return numLanes - 1;
}
```

**Example:**
```
Lane 0: EMA latency = 18ms  (carrying slow commands)  → weight 0.06
Lane 1: EMA latency = 0.4ms                           → weight 0.71
Lane 2: EMA latency = 0.3ms                           → weight 0.77
Lane 3: EMA latency = 0.5ms                           → weight 0.67
```

New commands are dispatched via weighted random selection — fast lanes get proportionally more traffic. The EMA decays quickly (configurable alpha, e.g., 0.1) so the strategy self-heals within seconds when a slow lane recovers.

**Dispatch cost:** O(N) for weight calculation + weighted random selection  
**Contention:** None (per-lane latency tracking)  
**Distribution:** Adaptive to observed latency patterns

**Best for:** Long-running mixed-SLO workloads with varying command latency profiles

**Why it helps:** Round-robin and `LEAST_USED` react to the current queue depth. `ADAPTIVE` reacts to observed latency — a more direct signal of HOL severity. A lane with one slow command but 0 pending count (command in progress, decode not complete) looks empty to `LEAST_USED` but looks slow to `ADAPTIVE`.

**Implementation complexity:** Highest of all strategies. Planned for a later milestone after `LEAST_USED` and `KEY_AFFINITY` are validated in production.

---

## Planned: `THREAD_STICKY`

Assign each thread a fixed lane via `ThreadLocal` at first access. The thread always writes to its lane.

```java
private final ThreadLocal<Integer> assignedLane =
    ThreadLocal.withInitial(() -> counter.getAndIncrement() % numLanes);

private int selectLane() {
    return assignedLane.get();
}
```

**Dispatch cost:** O(1), ThreadLocal read  
**Contention:** None  
**Distribution:** Even if thread count ≈ lane count

**Best for:** Thread-per-request architectures with low thread count (thread count ≤ lane count)

**Why it helps:** A single thread's commands are always serialized on one lane. No cross-thread interleaving on the lane's stack. If your application has thread-per-request isolation (traditional Spring MVC on a thread pool), each request's Redis commands never compete with another request's commands on the same lane queue.

**Caveat:** Requires that thread-pool size ≈ lane count for even distribution. With 200 threads and 8 lanes, approximately 25 threads share each lane — distribution is even but the isolation benefit disappears. Works best when thread count ≤ lane count, which is unusual in standard Spring deployments.

---

## Strategy Comparison

| Strategy        | Dispatch Cost       | Contention | Distribution | Best For                               |
|-----------------|---------------------|------------|--------------|----------------------------------------|
| `ROUND_ROBIN`   | O(1), 1 CAS         | Low        | Uniform      | Default, uniform workloads             |
| `LEAST_USED`    | O(N) scan           | None       | Adaptive     | Mixed fast/slow commands               |
| `KEY_AFFINITY`  | O(key length)       | None       | Key-based    | Transactional, key-isolated workloads  |
| `RANDOM`        | O(1), no CAS        | None       | Uniform      | Extreme concurrency, CAS bottleneck    |
| `ADAPTIVE`      | O(N) weighted       | None       | Latency-aware| Mixed SLO long-running workloads       |
| `THREAD_STICKY` | O(1) ThreadLocal    | None       | Thread-based | Thread-per-request, low thread count   |

---

## ⚠️ Transaction Safety Warning

**CRITICAL:** Redis transactions (`MULTI`/`EXEC`) are **NOT SAFE** with shared connections when `numLanes < numThreads`.

### Technical Root Cause

Redis transaction state (`client->flags & CLIENT_MULTI`, `client->mstate.commands`) stored in connection struct, not request metadata. MULTI sets per-connection flag. Concurrent MULTI on same TCP socket clobbers `client->flags`. Queued commands interleave in `client->mstate` command array. EXEC drains wrong thread's queue.

Lettuce multiplexes application threads onto Netty `Channel` (1:1 with TCP socket). `Channel.write()` serializes via `MpscLinkedQueue` in `ChannelOutboundBuffer`. Redis event loop dequeues FIFO, executes serially. Commands from Thread A and Thread B interleave at Redis server despite Netty thread-safety.

ThreadAffinity maps thread → lane via `MurmurHash3(threadId) % numLanes`. Collision inevitable when `numThreads > numLanes` (pigeonhole). Multiple threads share connection. Transaction state collision → `EXEC without MULTI` error or cross-thread command execution.

### The Problem

When multiple threads share the same lane (connection):

```java
// Thread 1 (Lane 2)
conn.sync().multi();   // Redis: connection.flags = IN_TRANSACTION
conn.sync().set(...);  // Queued in connection

// Thread 2 (ALSO Lane 2! - concurrent)
conn.sync().multi();   // ❌ ERROR: "MULTI calls can not be nested"
conn.sync().set(...);  // Queued in Thread 1's transaction!

// Thread 1
conn.sync().exec();    // ✅ Executes Thread 1 AND Thread 2's commands!

// Thread 2
conn.sync().exec();    // ❌ ERROR: "EXEC without MULTI" (transaction discarded)
```

**Result:** Transaction failures, data corruption, race conditions.

### Protocol-Level Constraint

Lettuce: "Multiple threads may share one connection **if they avoid blocking and transactional operations such as BLPOP and MULTI/EXEC**."

RESP protocol transmits commands as serialized byte stream over TCP. Redis server maintains per-connection state (`client` struct in `server.h`). MULTI/EXEC state machine (`CLIENT_MULTI` flag, `multiState` command queue) scoped to `client*`, not per-request. No request-ID correlation in RESP. Server cannot demultiplex interleaved transactions from same connection.

**Not a Lettuce bug. Not a spring-redis-laned bug. Fundamental RESP/Redis architecture constraint.**

ThreadAffinity guarantees `thread → lane` mapping (deterministic via `threadId` hash). Does NOT guarantee `thread → unique connection`. Collision rate: `1 - e^(-n²/2m)` where n=threads, m=lanes. At n=m=2500: 63% probability ≥1 collision. Expected collisions: ~845 threads (34%) share connection with another thread.

### Production-Safe Configurations

| Use Case                             | Configuration                | Safe for MULTI/EXEC?         |
|--------------------------------------|------------------------------|------------------------------|
| **Non-transactional commands**       | Any strategy, any `numLanes` | ✅ Yes (always safe)          |
| **Transactions, low concurrency**    | `numLanes ≥ numThreads`      | ✅ Yes (1:1 mapping)          |
| **Transactions, high concurrency**   | Dedicated connection pool    | ✅ Yes (guaranteed isolation) |
| **Transactions, shared connections** | `numLanes < numThreads`      | ❌ **NO! Will fail!**         |

### Workarounds

**Option 1: Use enough lanes** (recommended for <2500 threads)

```java
// 2500 threads → 2500 lanes (1:1 mapping)
manager = new LanedConnectionManager(
    client, codec, 2500, new ThreadAffinityStrategy()
);
```

**Option 2: Dedicated connection pool** (recommended for 10K+ threads)

```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          enabled: true
          max-active: 100
        shareNativeConnection: false  # Each LettuceConnection wraps unique StatefulRedisConnection
```

**What `shareNativeConnection: false` does:**

Spring creates `LettuceConnectionFactory` with internal `Supplier<StatefulRedisConnection>`. When false: each `getConnection()` call invokes `client.connect()` → new TCP socket → new Redis `client` struct → isolated transaction state. No multiplexing, no collision. Cost: connection creation (~1-5ms) + TCP handshake (1 RTT) + Redis AUTH (if enabled). Amortize via connection pooling (`lettuce.pool.enabled: true`).

**Option 3: Lua scripting** (atomic without MULTI/EXEC)

```java
String script = "redis.call('SET', KEYS[1], ARGV[1]); return redis.call('INCR', KEYS[2])";
conn.sync().eval(script, ScriptOutputType.INTEGER, new String[]{"key", "counter"}, "value");
```

### Architectural Trade-off

**Laned connections optimize for throughput** (multiplex N threads → M connections, M << N). Reduces TCP overhead (fewer sockets, fewer event loop registrations, better CPU cache locality). Sacrifices transaction safety (connection sharing → state collision).

**Dedicated connections optimize for isolation** (1 thread → 1 connection). Guarantees transaction correctness. Sacrifices throughput (more TCP sockets, more kernel context switches, higher memory: ~64KB socket buffer × N connections).

**Choose based on workload:**
- High-throughput non-transactional (95% of Redis use): laned connections
- Transactional critical path (5% of Redis use): dedicated connection pool with `shareNativeConnection: false`

**Cannot have both on same connection.** RESP protocol and Redis server architecture prevent per-request transaction isolation on multiplexed connections. This is not a limitation of this library—this is how Redis works at the protocol level.

### Deep Dive

For complete technical explanation including Redis source code analysis (`multi.c`, `networking.c`), Netty internals, and JVM memory model implications, see:

**[📖 Transaction Safety Deep Dive](TRANSACTION_SAFETY_DEEP_DIVE.md)**

---

## Configuration (Planned)

```yaml
spring:
  data:
    redis:
      connection:
        strategy: LANED
        lanes: 8
        lane-selection-mode: ROUND_ROBIN  # ROUND_ROBIN | LEAST_USED | KEY_AFFINITY | RANDOM | ADAPTIVE | THREAD_STICKY
```

---

*Created by Christian Schnapka, Principal+ Engineer · [Macstab GmbH](https://macstab.com)*
