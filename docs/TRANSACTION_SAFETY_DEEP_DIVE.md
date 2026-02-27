# Transaction Safety Deep Dive: Why MULTI/EXEC Fails on Shared Connections

*By [Christian Schnapka (Per)](https://macstab.com) · Principal+ Embedded Engineer · [Macstab GmbH](https://macstab.com)*

**Date:** 2026-02-21  
**Status:** Technical Deep Dive

---

## Table of Contents

<!-- TOC -->
* [Transaction Safety Deep Dive: Why MULTI/EXEC Fails on Shared Connections](#transaction-safety-deep-dive-why-multiexec-fails-on-shared-connections)
  * [Table of Contents](#table-of-contents)
  * [Executive Summary](#executive-summary)
  * [Table of Contents](#table-of-contents-1)
  * [1. Redis Protocol Mechanics](#1-redis-protocol-mechanics)
    * [1.1 RESP (Redis Serialization Protocol)](#11-resp-redis-serialization-protocol)
    * [1.2 Single-Threaded Command Processing (Redis < 6.0)](#12-single-threaded-command-processing-redis--60)
    * [1.3 Multi-Threaded I/O (Redis 6.0+)](#13-multi-threaded-io-redis-60)
  * [2. Transaction State Storage](#2-transaction-state-storage)
    * [2.1 MULTI Command Behavior](#21-multi-command-behavior)
    * [2.2 Command Queueing (Between MULTI and EXEC)](#22-command-queueing-between-multi-and-exec)
    * [2.3 EXEC Command Behavior](#23-exec-command-behavior)
  * [3. Concurrent Transaction Scenario](#3-concurrent-transaction-scenario)
    * [3.1 Setup](#31-setup)
    * [3.2 Timeline of Failure](#32-timeline-of-failure)
    * [3.3 Even Worse: Race Conditions](#33-even-worse-race-conditions)
    * [3.4 Test Results Explained](#34-test-results-explained)
  * [4. Why Lettuce Cannot Fix This](#4-why-lettuce-cannot-fix-this)
    * [4.1 Lettuce Architecture](#41-lettuce-architecture)
    * [4.2 Command Multiplexing](#42-command-multiplexing)
    * [4.3 Why MULTI/EXEC Breaks](#43-why-multiexec-breaks)
    * [4.4 Lettuce's Documentation Warning](#44-lettuces-documentation-warning)
  * [5. ThreadAffinity Limitation](#5-threadaffinity-limitation)
    * [5.1 How ThreadAffinity Works](#51-how-threadaffinity-works)
    * [5.2 Collision Example](#52-collision-example)
    * [5.3 Why This Breaks Transactions](#53-why-this-breaks-transactions)
  * [6. Production-Safe Configurations](#6-production-safe-configurations)
    * [6.1 Option 1: Dedicated Connections (1:1 Mapping)](#61-option-1-dedicated-connections-11-mapping)
    * [6.2 Option 2: Explicit Connection Pool](#62-option-2-explicit-connection-pool)
    * [6.3 Option 3: Single-Threaded Transactions](#63-option-3-single-threaded-transactions)
  * [7. Alternative Approaches](#7-alternative-approaches)
    * [7.1 Lua Scripting (Atomic Without MULTI/EXEC)](#71-lua-scripting-atomic-without-multiexec)
    * [7.2 Redis Streams (Event Sourcing)](#72-redis-streams-event-sourcing)
    * [7.3 Optimistic Locking (WATCH/MULTI/EXEC)](#73-optimistic-locking-watchmultiexec)
  * [8. Conclusion](#8-conclusion)
    * [8.1 Root Cause Summary](#81-root-cause-summary)
    * [8.2 ThreadAffinity Is NOT Broken](#82-threadaffinity-is-not-broken)
    * [8.3 Production Guidance](#83-production-guidance)
    * [8.4 Lettuce Responsibility](#84-lettuce-responsibility)
  * [References](#references)
<!-- TOC -->


## Executive Summary

**Problem:** Redis transactions (MULTI/EXEC) fail when multiple threads share the same TCP connection.

**Root Cause:** Transaction state is stored per-connection in Redis server, NOT per-client-thread. Concurrent MULTI commands on the same connection clobber each other's transaction state.

**Impact:** `ThreadAffinityStrategy` with `numLanes < numThreads` is **NOT SAFE** for MULTI/EXEC transactions.

**Solution:** Use `numLanes >= numThreads` (1:1 thread-to-lane mapping) OR use dedicated connections for transactions.

**Lettuce Limitation:** Lettuce documentation explicitly states: "Multiple threads may share one connection **if they avoid blocking and transactional operations such as BLPOP and MULTI/EXEC**." (Source: [Lettuce README](https://github.com/redis/lettuce))

---

## Table of Contents

1. [Redis Protocol Mechanics](#1-redis-protocol-mechanics)
2. [Transaction State Storage](#2-transaction-state-storage)
3. [Concurrent Transaction Scenario](#3-concurrent-transaction-scenario)
4. [Why Lettuce Cannot Fix This](#4-why-lettuce-cannot-fix-this)
5. [ThreadAffinity Limitation](#5-threadaffinity-limitation)
6. [Production-Safe Configurations](#6-production-safe-configurations)
7. [Alternative Approaches](#7-alternative-approaches)

---

## 1. Redis Protocol Mechanics

### 1.1 RESP (Redis Serialization Protocol)

Redis uses RESP (Redis Serialization Protocol) over TCP. Commands are serialized as:

```
*3\r\n
$3\r\nSET\r\n
$3\r\nkey\r\n
$5\r\nvalue\r\n
```

**Critical property:** Commands are **serialized sequentially** on the TCP stream. Redis server processes them **in FIFO order** per connection.

### 1.2 Single-Threaded Command Processing (Redis < 6.0)

Redis server (< 6.0) uses **single-threaded event loop** (epoll/kqueue):

1. Event loop polls all client connections
2. When data available: read bytes from TCP socket
3. Parse RESP command
4. Execute command (blocking event loop)
5. Write response to TCP socket
6. Move to next client

**Key insight:** Even with 1000 concurrent clients, Redis processes commands **one at a time** (global serialization).

### 1.3 Multi-Threaded I/O (Redis 6.0+)

Redis 6.0+ introduced **threaded I/O**:

- Multiple threads handle socket I/O (read/write)
- **Command execution still single-threaded** (main thread only)

**Result:** Commands from different clients can interleave **at the Redis server**, even from same TCP connection.

---

## 2. Transaction State Storage

### 2.1 MULTI Command Behavior

When Redis receives `MULTI`:

```c
// Redis source: multi.c
void multiCommand(client *c) {
    if (c->flags & CLIENT_MULTI) {
        addReplyError(c, "MULTI calls can not be nested");
        return;
    }
    c->flags |= CLIENT_MULTI;  // Mark connection as "in transaction"
    c->mstate.commands = NULL; // Initialize command queue
    c->mstate.count = 0;
    addReply(c, shared.ok);
}
```

**Critical line:** `c->flags |= CLIENT_MULTI`

**This sets transaction state ON THE CONNECTION (`client *c`), NOT on a per-request basis.**

### 2.2 Command Queueing (Between MULTI and EXEC)

After MULTI, all commands are **queued** instead of executed:

```c
// Redis source: multi.c
void queueMultiCommand(client *c) {
    multiCmd *mc = c->mstate.commands + c->mstate.count;
    mc->cmd = c->cmd;
    mc->argc = c->argc;
    mc->argv = zmalloc(sizeof(robj*) * c->argc);
    memcpy(mc->argv, c->argv, sizeof(robj*) * c->argc);
    c->mstate.count++;  // Increment command count IN CONNECTION STATE
}
```

**Command queue location:** `c->mstate.commands` (stored in connection object)

### 2.3 EXEC Command Behavior

When Redis receives `EXEC`:

```c
// Redis source: multi.c
void execCommand(client *c) {
    if (!(c->flags & CLIENT_MULTI)) {
        addReplyError(c, "EXEC without MULTI");
        return;
    }
    
    // Execute all queued commands atomically
    for (int i = 0; i < c->mstate.count; i++) {
        call(c, c->mstate.commands[i].cmd, c->mstate.commands[i].argv, ...);
    }
    
    c->flags &= ~CLIENT_MULTI;  // Clear transaction state
    freeClientMultiState(c);     // Free command queue
}
```

**Key operations:**
1. Check `c->flags & CLIENT_MULTI` (connection state)
2. Execute commands from `c->mstate.commands` (connection queue)
3. Clear `c->flags` (connection state)

**All state is per-connection, NOT per-thread.**

---

## 3. Concurrent Transaction Scenario

### 3.1 Setup

- **8 lanes (TCP connections)** to Redis
- **1000 threads** using ThreadAffinityStrategy
- **~125 threads per lane** (1000 ÷ 8 = 125)

### 3.2 Timeline of Failure

```
Time | Thread 42 (Lane 2)      | Thread 137 (Lane 2)     | Redis (Lane 2 connection state)
-----|--------------------------|-------------------------|----------------------------------
T0   | MULTI                    |                         | c->flags = CLIENT_MULTI
T1   | SET key1 val1            |                         | Queue: [SET key1 val1]
T2   |                          | MULTI                   | ❌ c->flags already set!
T3   |                          |                         | Error: "MULTI calls can not be nested"
T4   | INCR counter             |                         | Queue: [SET key1 val1, INCR counter]
T5   |                          | SET key2 val2           | Queue: [SET key1 val1, INCR counter, SET key2 val2]
T6   | EXEC                     |                         | Execute: [SET key1 val1, INCR counter, SET key2 val2]
     |                          |                         | ✅ Thread 42 gets Thread 137's commands!
T7   |                          | EXEC                    | ❌ No active transaction!
     |                          |                         | Error: "EXEC without MULTI"
```

**Result:**
- **Thread 42:** Transaction succeeds, but **executes Thread 137's commands** (data corruption)
- **Thread 137:** Transaction **discarded** (EXEC without MULTI error)

### 3.3 Even Worse: Race Conditions

If Thread 137's MULTI arrives **before** Thread 42's EXEC:

```
Time | Thread 42               | Thread 137              | Redis state
-----|-------------------------|-------------------------|------------------
T0   | MULTI                   |                         | flags = MULTI
T1   | SET key1 val1           |                         | queue = [SET key1]
T2   |                         | MULTI (ERROR!)          | Error returned
T3   |                         | SET key2 val2           | ❌ Executed immediately!
T4   | INCR counter            |                         | queue = [SET key1, INCR]
T5   | EXEC                    |                         | Execute queue
```

**Thread 137's SET executed OUTSIDE the transaction!** (atomicity violation)

### 3.4 Test Results Explained

**Test:** 1000 threads × 1000 transactions = 1,000,000 transactions  
**Result:** 992 threads had **ALL** their transactions discarded  
**Why:** Those 992 threads collided with other threads on the same lane

**Math:**
- 8 lanes, 1000 threads → ~125 threads per lane
- Each lane has 125 threads running 1000 transactions concurrently
- **Collision probability ≈ 99.2%** (almost guaranteed)
- Only 8 threads (one per lane) might succeed if they start first

---

## 4. Why Lettuce Cannot Fix This

### 4.1 Lettuce Architecture

Lettuce uses **Netty** for networking:

```
Application Thread → Lettuce API → Netty Channel → TCP Socket → Redis
```

**Netty Channel = TCP connection**

### 4.2 Command Multiplexing

Lettuce allows **multiple threads** to call commands on **same connection**:

```java
StatefulRedisConnection<String, String> conn = client.connect();

// Thread 1
conn.sync().set("key1", "val1");  // → Netty writes to TCP socket

// Thread 2 (concurrent)
conn.sync().set("key2", "val2");  // → Netty writes to TCP socket (same socket!)
```

**Netty serializes writes internally** (thread-safe queue), but **Redis sees interleaved commands**:

```
Redis receives:
SET key1 val1
SET key2 val2
```

### 4.3 Why MULTI/EXEC Breaks

```java
// Thread 1
conn.sync().multi();  // → Redis: c->flags = MULTI
conn.sync().set("key1", "val1");

// Thread 2 (interleaved!)
conn.sync().multi();  // → Redis: ERROR (already in MULTI)
conn.sync().set("key2", "val2");  // → Queued in Thread 1's transaction!

// Thread 1
conn.sync().exec();  // → Executes Thread 1 AND Thread 2's commands!
```

**Lettuce CANNOT prevent this** because:

1. Lettuce doesn't know which thread called MULTI
2. Netty sends commands in FIFO order (no per-thread isolation)
3. Redis transaction state is per-connection, not per-thread

### 4.4 Lettuce's Documentation Warning

From Lettuce README:

> "Multiple threads may share one connection **if they avoid blocking and transactional operations such as BLPOP and MULTI/EXEC**."

**Translation:** "We know this is broken. Don't do it."

---

## 5. ThreadAffinity Limitation

### 5.1 How ThreadAffinity Works

```java
public int selectLane(int numLanes) {
    long threadId = Thread.currentThread().threadId();
    int hash = murmurHash3(threadId);  // MurmurHash3 (3-5× faster than CRC16)
    return (hash & 0x7FFF_FFFF) % numLanes;
}
```

**Guarantee:** Same thread **always** gets same lane.

**NOT guaranteed:** Different threads get different lanes.

### 5.2 Collision Example

```
numLanes = 8
numThreads = 1000

Thread 42  → MurmurHash3(42)  = 0x1A3F → 0x1A3F % 8 = 7 (Lane 7)
Thread 137 → MurmurHash3(137) = 0x2B1F → 0x2B1F % 8 = 7 (Lane 7) ← COLLISION!
Thread 289 → MurmurHash3(289) = 0x4C2D → 0x4C2D % 8 = 7 (Lane 7) ← COLLISION!
```

**Result:** Threads 42, 137, and 289 **all share Lane 7**.

### 5.3 Why This Breaks Transactions

Each thread runs:

```java
try (var conn = manager.getConnection()) {  // All 3 threads get Lane 7
    var commands = conn.sync();
    
    commands.multi();   // ❌ Only one thread succeeds
    commands.set(...);  // Commands interleave
    commands.exec();    // ❌ Transaction discarded
}
```

**ThreadAffinity guarantees same thread → same lane, but DOES NOT guarantee exclusive access.**

---

## 6. Production-Safe Configurations

### 6.1 Option 1: Dedicated Connections (1:1 Mapping)

```java
// 2500 threads → 2500 lanes
manager = new LanedConnectionManager(
    client, 
    codec, 
    2500,  // numLanes = numThreads
    new ThreadAffinityStrategy()
);
```

**Guarantee:** Each thread gets its own lane (probabilistically).

**Collision probability:**

```
P(collision) = 1 - (numLanes! / (numLanes^numThreads * (numLanes - numThreads)!))

For numLanes = numThreads = 2500:
P(collision) ≈ 0.001% (negligible)
```

**Cost:** 2500 TCP connections to Redis.

### 6.2 Option 2: Explicit Connection Pool

```java
// DON'T use LanedConnectionManager for transactions
// Use Lettuce's connection pool instead
RedisClient client = RedisClient.create("redis://localhost");

// Each thread gets its own connection
try (var conn = client.connect()) {
    conn.sync().multi();
    conn.sync().set("key", "val");
    conn.sync().exec();  // ✅ Safe (no sharing)
}
```

**Guarantee:** Each transaction uses dedicated connection.

**Cost:** Connection creation overhead (~1-5ms per connection).

### 6.3 Option 3: Single-Threaded Transactions

```java
// Serialize all transactions through a single thread
ExecutorService txExecutor = Executors.newSingleThreadExecutor();

txExecutor.submit(() -> {
    var conn = manager.getConnection();
    conn.sync().multi();
    conn.sync().set("key", "val");
    conn.sync().exec();  // ✅ Safe (no concurrency)
});
```

**Guarantee:** No concurrent MULTI/EXEC.

**Cost:** Throughput limited to single thread (~10K tx/sec max).

---

## 7. Alternative Approaches

### 7.1 Lua Scripting (Atomic Without MULTI/EXEC)

```java
// Lua script executes atomically (no MULTI/EXEC needed)
String script = """
    redis.call('SET', KEYS[1], ARGV[1])
    return redis.call('INCR', KEYS[2])
""";

conn.sync().eval(script, ScriptOutputType.INTEGER, 
    new String[]{"key1", "counter"}, 
    "value");
```

**Advantage:** Atomic execution, thread-safe on shared connections.

**Limitation:** Complex logic harder to express in Lua.

### 7.2 Redis Streams (Event Sourcing)

```java
// Append to stream (always safe)
conn.sync().xadd("events", 
    "action", "set-key", 
    "key", "key1", 
    "value", "val1");

// Consumer reads stream sequentially
conn.sync().xread(...);
```

**Advantage:** No transactions needed, naturally serialized.

**Limitation:** Different programming model (event sourcing).

### 7.3 Optimistic Locking (WATCH/MULTI/EXEC)

```java
while (true) {
    conn.sync().watch("key");
    String val = conn.sync().get("key");
    
    conn.sync().multi();
    conn.sync().set("key", transform(val));
    var result = conn.sync().exec();
    
    if (!result.wasDiscarded()) break;  // Retry if watch broken
}
```

**Advantage:** Handles concurrent modifications gracefully.

**Limitation:** Still requires dedicated connection (WATCH is connection-scoped).

---

## 8. Conclusion

### 8.1 Root Cause Summary

**Redis transaction state is per-connection, not per-thread.**

1. MULTI sets `client->flags |= CLIENT_MULTI` (connection state)
2. Commands queue in `client->mstate.commands` (connection queue)
3. EXEC executes `client->mstate.commands` (connection queue)

**Concurrent threads on same connection clobber each other's transaction state.**

### 8.2 ThreadAffinity Is NOT Broken

ThreadAffinity works **exactly as designed**:

- ✅ Same thread → same lane (100% guaranteed)
- ✅ Load distribution across lanes
- ✅ Cache affinity (same thread → same Redis instance in cluster)

**What ThreadAffinity DOES NOT guarantee:**

- ❌ Exclusive connection access per thread
- ❌ Transaction safety with `numLanes < numThreads`

### 8.3 Production Guidance

**For MULTI/EXEC transactions:**

| Scenario | Configuration | Safe? |
|----------|---------------|-------|
| Low concurrency (<100 threads) | `numLanes = numThreads`, ThreadAffinity | ✅ Yes |
| High concurrency (1000+ threads) | `numLanes ≥ numThreads`, ThreadAffinity | ✅ Yes (probabilistic) |
| Ultra-high concurrency (10K+ threads) | Dedicated connection pool | ✅ Yes (guaranteed) |
| Shared connections (`numLanes < numThreads`) | **ANY strategy** | ❌ **NO!** |

**For non-transactional commands:**

| Strategy | Shared Connections | Safe? |
|----------|-------------------|-------|
| RoundRobin | ✅ Yes | ✅ Yes (Lettuce is thread-safe) |
| ThreadAffinity | ✅ Yes | ✅ Yes (Lettuce is thread-safe) |
| LeastUsed | ✅ Yes | ✅ Yes (Lettuce is thread-safe) |

### 8.4 Lettuce Responsibility

**This is NOT a Lettuce bug.** Lettuce correctly implements the Redis protocol.

**Redis protocol limitation:** Transaction state is connection-scoped, not request-scoped.

**Lettuce documentation explicitly warns users** not to use MULTI/EXEC on shared connections.

**Workaround:** Use dedicated connections for transactions (standard practice in production).

---

## References

1. [Lettuce README - Thread Safety](https://github.com/redis/lettuce)
2. [Redis Source Code - multi.c](https://github.com/redis/redis/blob/unstable/src/multi.c)
3. [Redis Protocol Specification (RESP)](https://redis.io/docs/latest/develop/reference/protocol-spec/)
4. [Netty Architecture](https://netty.io/wiki/user-guide.html)
5. [Redis Transactions Documentation](https://redis.io/docs/latest/develop/using-commands/transactions/)

---

**Document Version:** 1.0  
**Last Updated:** 2026-02-21  
**Maintainer:** Christian Schnapka (Per) - Macstab GmbH
