# Spring-Redis-Laned

*By [Christian Schnapka](https://macstab.com) · Principal+ Embedded Engineer · [Macstab GmbH](https://macstab.com)*

> Your p99 Redis latency is 40ms. Your p50 is 0.3ms. Redis reports 3% CPU and an empty
> slow log. Your profiler shows threads sleeping in `CompletableFuture.get()`. You switch
> to a connection pool and the p99 gets *worse*. This isn't a Redis problem, not a network
> problem, not a Spring problem. The root cause is in the wire protocol. I'll explain it
> from first principles — starting at the RESP spec — and give you the fix.

A Spring Boot auto-configuration that replaces Lettuce's default single-connection and
commons-pool2 connection-pool strategies with **N fixed multiplexed connections (lanes)**,
round-robin dispatched. One dependency, two config lines. Done.

---

## Table of Contents

<!-- TOC -->
* [Spring-Redis-Laned](#spring-redis-laned)
  * [Table of Contents](#table-of-contents)
  * [⚡ Performance: The INSANE Benefit](#-performance-the-insane-benefit)
  * [🚀 How to Use](#-how-to-use)
    * [1. Add Dependency](#1-add-dependency)
    * [2. Configure (2 Lines)](#2-configure-2-lines)
    * [3. Verify It's Working](#3-verify-its-working)
    * [Production Configuration](#production-configuration)
  * [Table of Contents](#table-of-contents-1)
  * [Origin](#origin)
  * [Technical Summary](#technical-summary)
    * [What & Why](#what--why)
    * [How (Mechanism)](#how-mechanism)
    * [When (Use Cases)](#when-use-cases)
    * [Configuration (Minimal)](#configuration-minimal)
    * [Architecture (High-Level)](#architecture-high-level)
    * [Stack Walkdown (JVM → OS → Redis)](#stack-walkdown-jvm--os--redis)
    * [Compatibility](#compatibility)
    * [References (Specs)](#references-specs)
    * [Quick Links](#quick-links)
  * [When NOT to use this](#when-not-to-use-this)
  * [The Problem, from first principles](#the-problem-from-first-principles)
    * [1. RESP has no request IDs — this is the root cause](#1-resp-has-no-request-ids--this-is-the-root-cause)
    * [2. Redis Server: One Thread Owns the Event Loop](#2-redis-server-one-thread-owns-the-event-loop)
      * [Redis 6+ IO Threads — What They Do and Don't Fix](#redis-6-io-threads--what-they-do-and-dont-fix)
    * [3. Lettuce: A Single Netty Channel With a FIFO Command Stack](#3-lettuce-a-single-netty-channel-with-a-fifo-command-stack)
      * [`setShareNativeConnection` in Spring Data Redis](#setsharenativeconnection-in-spring-data-redis)
    * [4. The DefaultEndpoint: Writes, Locks, and the Channel](#4-the-defaultendpoint-writes-locks-and-the-channel)
    * [5. Connection Pools: Why They Amplify the Problem at Scale](#5-connection-pools-why-they-amplify-the-problem-at-scale)
      * [The Connection Count Explosion](#the-connection-count-explosion)
      * [Borrow Contention Under Spike Load](#borrow-contention-under-spike-load)
      * [Redis maxclients and fd Limits](#redis-maxclients-and-fd-limits)
    * [6. OS Kernel: TCP Receive Buffer and Backpressure](#6-os-kernel-tcp-receive-buffer-and-backpressure)
    * [7. Language Clients: How They Handle (or Don't Handle) This](#7-language-clients-how-they-handle-or-dont-handle-this)
      * [redis-py (Python, synchronous)](#redis-py-python-synchronous)
      * [redis.asyncio (Python, async)](#redisasyncio-python-async)
      * [hiredis (C)](#hiredis-c)
      * [go-redis (Go)](#go-redis-go)
      * [ioredis (Node.js)](#ioredis-nodejs)
      * [StackExchange.Redis (.NET)](#stackexchangeredis-net)
    * [8. Sentinel and Redis Enterprise](#8-sentinel-and-redis-enterprise)
      * [Redis Sentinel](#redis-sentinel)
      * [Redis Enterprise (Redis Software)](#redis-enterprise-redis-software)
      * [Cluster Mode and the Laned Approach](#cluster-mode-and-the-laned-approach)
    * [9. The Laned Solution: Why It Works](#9-the-laned-solution-why-it-works)
      * [The Mechanism](#the-mechanism)
      * [Mathematical Proof of HOL Reduction](#mathematical-proof-of-hol-reduction)
      * [Connection Count: The Key Advantage Over Pools](#connection-count-the-key-advantage-over-pools)
      * [Why PubSub Gets Dedicated Connections](#why-pubsub-gets-dedicated-connections)
      * [`setShareNativeConnection(false)` and Laned Connections](#setsharenativeconnectionfalse-and-laned-connections)
  * [Architecture](#architecture)
    * [Key Classes](#key-classes)
  * [Configuration](#configuration)
    * [Minimal (Standalone, No Auth)](#minimal-standalone-no-auth)
    * [Production (TLS + Auth + Timeouts)](#production-tls--auth--timeouts)
    * [Mutual TLS (Client Certificates)](#mutual-tls-client-certificates)
    * [Sentinel (High Availability)](#sentinel-high-availability)
    * [Cluster (OSS Cluster Protocol)](#cluster-oss-cluster-protocol)
    * [Multi-Priority (Separate Factories)](#multi-priority-separate-factories)
    * [Development (Insecure Trust Manager)](#development-insecure-trust-manager)
    * [Complete Configuration Reference](#complete-configuration-reference)
  * [Quick Start](#quick-start)
  * [Metrics](#metrics)
    * [Command Latency Tracking (Optional)](#command-latency-tracking-optional)
  * [Trade-offs - what this actually costs](#trade-offs---what-this-actually-costs)
    * [1. File Descriptors](#1-file-descriptors)
    * [2. Per-Connection Memory in Netty](#2-per-connection-memory-in-netty)
    * [3. Startup Cost: N TCP Handshakes](#3-startup-cost-n-tcp-handshakes)
    * [4. Transactional Pinning: The Hard Problem](#4-transactional-pinning-the-hard-problem)
    * [5. Connection-Scoped Commands Must Run on All Lanes](#5-connection-scoped-commands-must-run-on-all-lanes)
    * [6. Round-Robin Cannot Prioritize](#6-round-robin-cannot-prioritize)
    * [7. Hot Lane Skew Under Correlated Fan-out](#7-hot-lane-skew-under-correlated-fan-out)
    * [8. BLPOP and Blocking Commands: Lane Occupancy](#8-blpop-and-blocking-commands-lane-occupancy)
  * [Why This Works: The Connection Budget Argument](#why-this-works-the-connection-budget-argument)
  * [Roadmap - Lane Selection Strategies](#roadmap---lane-selection-strategies)
    * [Planned: `LEAST_USED`](#planned-least_used)
    * [Planned: `KEY_AFFINITY` (MurmurHash3)](#planned-key_affinity-murmurhash3)
    * [Planned: `RANDOM`](#planned-random)
    * [Planned: `ADAPTIVE` (Latency-Weighted)](#planned-adaptive-latency-weighted)
    * [Planned: `THREAD_STICKY`](#planned-thread_sticky)
    * [Strategy Comparison](#strategy-comparison)
  * [⚠️ Transaction Safety (MULTI/EXEC)](#-transaction-safety-multiexec)
<!-- TOC -->


## ⚡ Performance: The INSANE Benefit

**95% latency reduction. Single Redis instance. Zero code changes.**

**Empirical Results (JMH 1.37, OpenJDK 25, ARM64):**

| Metric              | Traditional Pool (1 lane)   | Laned Pool (4 lanes)  | **Improvement**              |
|---------------------|-----------------------------|-----------------------|------------------------------|
| **P50 Latency**     | 3,318 ms                    | **166 ms**            | **-95.0% (20× faster)** ⚡⚡⚡  |
| **P99 Latency**     | 6,185 ms                    | **818 ms**            | **-86.8% (7.5× faster)** ⚡⚡⚡ |
| **Mean Latency**    | 3,277 ms                    | **233 ms**            | **-92.9% (14× faster)** ⚡⚡⚡  |
| **Throughput**      | ~6,000 req/sec              | **24,000 req/sec**    | **+300% (4× capacity)**      |
| **Memory Overhead** | 32 KB                       | 128 KB                | +96 KB (negligible)          |

**Why This Works:**

A single slow Redis command (SLOWLOG, large HGETALL, network hiccup) blocks ALL subsequent operations in traditional pools. Lanes provide **isolation** - slow operations cannot block fast ones.

**Traditional Pool (Head-of-Line Blocking):**
```
Thread 1 → HGETALL (500KB, 18ms)  ━━━━━━━━━━━━━━━━━━┓
Thread 2 → GET key1 (1ms)                           ┣━━ ALL BLOCKED!
Thread 3 → GET key2 (1ms)                           ┃   Wait 18ms
Thread 4 → GET key3 (1ms)                           ┛
```

**Laned Pool (Isolation):**
```
Thread 1 → Lane 0 → HGETALL (18ms)  ━━━━━━━━━━━━━━━━━━
Thread 2 → Lane 1 → GET (1ms)       ━━ DONE! (no blocking)
Thread 3 → Lane 2 → GET (1ms)       ━━ DONE! (no blocking)
Thread 4 → Lane 3 → GET (1ms)       ━━ DONE! (no blocking)
```

**Optimal Configuration:** 4-8 lanes for 90% of workloads (highest ROI, minimal overhead)

**Full Analysis:** [Performance Benchmarks](docs/REDIS_LANED_PERFORMANCE_BENCHMARKS.md) | **Visualizations:** Upload [results.json](redis-laned-benchmarks/build/reports/jmh/results.json) to https://jmh.morethan.io

---

## 🚀 How to Use

Choose your setup: **[Minimal](#minimal-setup-spring-boot)** · **[Recommended](#recommended-setup-production)** · **[Recommended + Metrics](#recommended-setup-with-metrics)** · **[Non-Spring](#non-spring-setup-pure-lettuce)**

---

### Minimal Setup (Spring Boot)

**1. Add Dependency**

```xml
<!-- Maven: Spring Boot 3.x -->
<dependency>
    <groupId>com.macstab.oss.redis</groupId>
    <artifactId>redis-laned-spring-boot-3-starter</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Maven: Spring Boot 4.x -->
<dependency>
    <groupId>com.macstab.oss.redis</groupId>
    <artifactId>redis-laned-spring-boot-4-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

```gradle
// Gradle: Spring Boot 3.x
implementation 'com.macstab.oss.redis:redis-laned-spring-boot-3-starter:1.0.0'

// Gradle: Spring Boot 4.x
implementation 'com.macstab.oss.redis:redis-laned-spring-boot-4-starter:1.0.0'
```

**2. Configure (2 Lines)**

```yaml
spring.data.redis.connection.strategy: LANED
spring.data.redis.connection.lanes: 8
```

**That's it.** Your existing `RedisTemplate` / `@Cacheable` / Spring Data Redis code works instantly. Zero code changes.

**Verify:**
```
INFO ... LanedRedisAutoConfiguration : Activated laned connection strategy with 8 lanes
```

---

### Recommended Setup (Production)

**1. Same Dependency** (see [Minimal Setup](#minimal-setup-spring-boot))

**2. Full Configuration**

```yaml
spring:
  data:
    redis:
      host: redis.example.com
      port: 6380
      password: ${REDIS_PASSWORD}
      database: 0
      timeout: 5s
      connect-timeout: 2s
      ssl:
        enabled: true
        bundle: redis-prod
      connection:
        strategy: LANED
        lanes: 16
      lettuce:
        shutdown-timeout: 100ms
        
  ssl:
    bundle:
      pem:
        redis-prod:
          truststore:
            certificate: file:/etc/certs/ca-cert.pem
```

**What this adds:**
- ✅ SSL/TLS encryption
- ✅ Auth + timeouts
- ✅ More lanes (16 for high-concurrency)
- ✅ Graceful shutdown

**See [Configuration](#configuration) section for Sentinel, Cluster, mTLS, multi-priority setups.**

---

### Recommended Setup (With Metrics)

**1. Add Dependencies**

```xml
<!-- Maven: Spring Boot 3.x starter -->
<dependency>
    <groupId>com.macstab.oss.redis</groupId>
    <artifactId>redis-laned-spring-boot-3-starter</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Maven: Spring Boot 4.x starter -->
<dependency>
    <groupId>com.macstab.oss.redis</groupId>
    <artifactId>redis-laned-spring-boot-4-starter</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- OPTIONAL: Micrometer metrics integration -->
<dependency>
    <groupId>com.macstab.oss.redis</groupId>
    <artifactId>redis-laned-metrics</artifactId>
    <version>1.0.0</version>
</dependency>
```

```gradle
// Gradle: Spring Boot 3.x
implementation 'com.macstab.oss.redis:redis-laned-spring-boot-3-starter:1.0.0'
implementation 'com.macstab.oss.redis:redis-laned-metrics:1.0.0'  // Optional

// Gradle: Spring Boot 4.x
implementation 'com.macstab.oss.redis:redis-laned-spring-boot-4-starter:1.0.0'
implementation 'com.macstab.oss.redis:redis-laned-metrics:1.0.0'  // Optional
```

**2. Full Configuration + Metrics**

```yaml
spring:
  data:
    redis:
      host: redis.example.com
      port: 6380
      password: ${REDIS_PASSWORD}
      database: 0
      timeout: 5s
      connect-timeout: 2s
      ssl:
        enabled: true
        bundle: redis-prod
      connection:
        strategy: LANED
        lanes: 16
      lettuce:
        shutdown-timeout: 100ms
        
  ssl:
    bundle:
      pem:
        redis-prod:
          truststore:
            certificate: file:/etc/certs/ca-cert.pem
            
  # Metrics auto-configuration (enabled when redis-laned-metrics on classpath)
  metrics:
    laned-redis:
      enabled: true
      connection-name: "primary"  # Tag value for dimensional metrics
```

**3. Metrics Exported (Micrometer)**

| Metric | Type | Description |
|--------|------|-------------|
| `redis.lettuce.laned.lane.selections` | Counter | Lane selection distribution by strategy |
| `redis.lettuce.laned.lane.in_flight` | Gauge | Current in-flight operations per lane |
| `redis.lettuce.laned.strategy.cas.retries` | Counter | CAS contention (LeastUsed strategy) |

**Tags:** `connection.name`, `lane.index`, `strategy.name`

**What this adds:**
- ✅ All production features (SSL, auth, timeouts)
- ✅ Micrometer metrics (Prometheus, Grafana, etc.)
- ✅ Lane selection distribution monitoring
- ✅ Per-lane load visibility
- ✅ Strategy performance tracking

**Grafana Dashboard:** Metrics use `redis_pool_*` naming conventions for compatibility with existing dashboards.

---

### Non-Spring Setup (Pure Lettuce)

**1. Add Dependency**

```xml
<!-- Maven: Core library only -->
<dependency>
    <groupId>com.macstab.oss.redis</groupId>
    <artifactId>redis-laned-core</artifactId>
    <version>1.0.0</version>
</dependency>

<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <version>6.7.1.RELEASE</version>
</dependency>
```

**2. Wire Manually**

```java
import com.macstab.oss.redis.laned.LanedConnectionManager;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;
import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.StringCodec;

// Create Lettuce client
RedisClient client = RedisClient.create("redis://localhost:6379");

// Create laned connection manager
LanedConnectionManager manager = new LanedConnectionManager(
    client,
    StringCodec.UTF8,
    8,                              // 8 lanes
    new RoundRobinStrategy(),       // Selection strategy
    LanedRedisMetrics.NOOP          // No metrics (zero overhead)
);

// Get connection and use it
var conn = manager.getConnection();
String value = conn.sync().get("mykey");
conn.close();  // Returns to lane pool

// Cleanup
manager.destroy();
client.shutdown();
```

**With Metrics (Optional):**

```java
// Add redis-laned-metrics dependency, then:
LanedRedisMetrics metrics = new MicrometerLanedRedisMetrics(meterRegistry, "myapp");
LanedConnectionManager manager = new LanedConnectionManager(
    client, codec, 8, strategy, metrics  // Custom metrics
);
```

**Advanced:** Switch strategies at runtime, implement custom `LaneSelectionStrategy`, integrate with any metrics backend.

---

## Origin

I built this library after hunting down a production latency issue at Macstab on a
high-throughput authorization platform running Redis Enterprise in proxy mode — a topology
where the entire cluster is exposed through a single endpoint and OSS Cluster routing is
unavailable to the client. After 30 years of backend development, I can smell head-of-line
blocking from a profiler trace. I traced the intermittent p99 spikes to `CommandHandler.stack`
contention, confirmed the root cause in Lettuce's source code, and built the initial
lane-based provider. The approach generalizes beyond Enterprise proxy mode to standalone
Redis and Sentinel. I'm open-sourcing it so you don't have to rediscover this from a
profiler trace at 2am like I did.

---

## Technical Summary

**For complete architecture, configuration reference, SSL/TLS setup, and operational details, see [TECHNICAL_REFERENCE.md](docs/TECHNICAL_REFERENCE.md).**

### What & Why

**Problem:** RESP (Redis Serialization Protocol) is positional—no request IDs, responses match commands by FIFO position in TCP byte stream (spec: Redis RESP2/RESP3). One slow command (e.g., `HGETALL` 500KB) blocks all subsequent responses until fully received. Lettuce's single `CommandHandler.stack` (ArrayDeque) amplifies this: all application threads share one TCP connection, one FIFO queue.

**Traditional solutions fail at scale:**
- **Single shared connection (Lettuce default):** 100% HOL exposure, p99 = slowest command
- **Connection pool (commons-pool2):** O(threads × pods) connections to Redis, thundering herd on shard failures, pool borrow contention

**This solution:** N fixed lanes (multiplexed connections), lock-free round-robin or strategy based dispatch via `AtomicLong` CAS.

**Mathematics:** P(blocked | N lanes) ≈ P(blocked | single) / N. With N=8: 87.5% HOL reduction.

**Production metrics (Macstab authorization platform, 30 pods):**
```
Before (50-conn pool):  1,500 Redis connections, p99 = 40ms
After  (8 lanes):         240 connections (84% reduction), p99 = 2ms (95% improvement)
```

### How (Mechanism)

**Lane selection (stateless):**
```java
lane = (counter.getAndIncrement() & 0x7FFF_FFFF) % numLanes;  // Lock-free CAS, O(1)
```

**Transaction pinning (stateful, `WATCH`/`MULTI`/`EXEC`):**
- RESP stores transaction state per-connection (`client->mstate` in Redis `networking.c`)
- `ThreadLocal<Integer>` pins thread → lane from `WATCH` through `EXEC`/`DISCARD`
- Why ThreadLocal: Same `Thread.currentThread()` = same lane (imperative code only)
- **Limitation:** Reactive (Project Reactor) suspends/resumes on different threads → ThreadLocal doesn't propagate → **not supported in v1.0**

**Patterns applied:**
- **Factory Method:** `LanedLettuceConnectionFactory` creates topology-specific lane arrays
- **Strategy:** Round-robin dispatch (future: `KEY_AFFINITY`, `LEAST_USED`, `ADAPTIVE`)
- **Adapter:** `LanedLettuceConnectionProvider` adapts Lettuce `RedisChannelWriter` to lane array

### When (Use Cases)

**✅ Use when:**
- Mixed latency workloads (p50 < 1ms, p99 > 5ms)
- Hot keys under concentrated load
- Redis Enterprise proxy mode (single endpoint)
- Standalone or Sentinel topologies
- Spring Boot 3.1+ or 4.0+ with imperative `RedisTemplate`

**❌ Avoid when:**
- Pure pipelining (HOL already batched)
- OSS Cluster mode (per-shard connections already isolate HOL)
- Uniform fast workloads (every command < 1ms)
- Heavy reactive transactions (`WATCH`/`MULTI` with Reactor—ThreadLocal doesn't propagate)

### Configuration (Minimal)

```yaml
spring:
  data:
    redis:
      host: redis.example.com
      port: 6379
      connection:
        strategy: LANED   # CLASSIC | POOLED | LANED
        lanes: 8          # 1-64, default 8
```

**SSL/TLS (Spring Boot 3.1+ SSL Bundles):**
```yaml
spring:
  data:
    redis:
      ssl:
        enabled: true
        bundle: my-redis-tls
  ssl:
    bundle:
      pem:
        my-redis-tls:
          keystore:
            certificate: file:/etc/certs/client-cert.pem   # Mutual TLS (optional)
            private-key: file:/etc/certs/client-key.pem
          truststore:
            certificate: file:/etc/certs/ca-cert.pem       # Server verification
```

**See [TECHNICAL_REFERENCE.md](docs/TECHNICAL_REFERENCE.md) for:**
- Complete configuration reference (all `spring.data.redis.*` keys)
- 7 configuration examples (standalone, Sentinel, Cluster, mTLS, multi-priority)
- SSL/TLS scenarios (server-only TLS, mutual TLS, insecure trust manager for dev)
- Performance model (hot paths, complexity, benchmarks)
- Operational runbooks (metrics, health checks, troubleshooting)

### Architecture (High-Level)

```
RedisTemplate (Spring Data Redis)
  ↓
LanedLettuceConnectionFactory (this library)
  ↓
LanedLettuceConnectionProvider
  ├─ Lane[0]: StatefulRedisConnection → Netty Channel → TCP socket FD 42
  ├─ Lane[1]: StatefulRedisConnection → Netty Channel → TCP socket FD 43
  └─ Lane[N-1]: ...
       ↓
Lettuce Core (io.lettuce.core)
  ├─ CommandHandler.stack (ArrayDeque, FIFO per lane)
  ├─ DefaultEndpoint (writes, SharedLock)
  └─ Netty (epoll/kqueue event loop)
       ↓
OS Kernel (Linux/macOS)
  ├─ TCP stack (sk_sndbuf, sk_rcvbuf)
  └─ epoll_wait / kqueue (socket readiness notification)
       ↓
Redis Server (single-threaded event loop, ae.c)
```

**Key invariants:**
- N lanes = N independent `CommandHandler.stack` queues (FIFO per lane)
- Slow command on lane K blocks only commands on lane K
- Commands on lanes 0..K-1, K+1..N-1 proceed concurrently
- PubSub gets dedicated connections (isolation from command traffic)

### Stack Walkdown (JVM → OS → Redis)

**Command execution (`GET key`):**
```
1. Application thread: redisTemplate.get("key")
2. Lane selection: (counter.getAndIncrement() & 0x7FFF_FFFF) % 8  [35-55ns, 1 CAS]
3. Lettuce encode: RESP2 "*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n"
4. Netty write: ChannelOutboundBuffer → write() syscall → sk_sndbuf  [20-30ns]
5. TCP: segment → IP route → network  [RTT/2 = 0.15ms same-AZ]
6. Redis: epoll_wait → readQueryFromClient → processCommand → lookupKey → addReply  [0.05ms server time]
7. TCP: response bytes → sk_rcvbuf  [RTT/2 = 0.15ms]
8. Netty event loop: epoll_wait → channelRead → RESP decode → stack.poll()  [50-200ns]
9. CompletableFuture.complete(value)  [10-20ns]
10. Application thread unblocks: return deserialized value

Total p50: ~0.4ms (network dominates, lane selection negligible)
```

**HOL blocking scenario:**
```
Lane 3 (8 lanes total):
  Command 1: HGETALL session:large (500KB, 18ms server + network)
  Command 2: GET user:flag (queued behind #1 in lane 3 stack)

Timeline:
T=0ms:   Both commands written to lane 3 TCP socket
T=0ms:   Redis executes both (HGETALL slow, GET fast)
T=0.1ms: GET response ready, but HGETALL response ahead in TCP stream
T=18ms:  Client receives all 500KB HGETALL bytes → stack.poll() → HGETALL complete
T=18ms:  Client receives GET bytes (was buffered) → stack.poll() → GET complete

Result: GET caller waited 18ms (blocked by TCP byte stream, not Redis)

Probability of this collision: 1/N = 12.5% (vs 100% single connection)
Expected p99: 87.5% × 0.4ms + 12.5% × 18ms ≈ 2.6ms
```

### Compatibility

| Component       | Version         | Status         |
|-----------------|-----------------|----------------|
| Spring Boot     | 3.1.0 - 3.4.x   | ✅ Production  |
| Spring Boot     | 4.0.0 - 4.0.3   | ✅ Production  |
| Java            | 21+             | ✅ Required    |
| Lettuce         | 6.x (Boot 3)    | ✅ Tested      |
| Lettuce         | 7.x (Boot 4)    | ✅ Tested      |
| Redis           | 6.0+            | ✅ Recommended |
| Virtual Threads | JDK 21+         | ✅ Supported   |

**Topologies supported:**
- ✅ Standalone (single Redis instance)
- ✅ Sentinel (HA with automatic failover)
- ✅ Enterprise proxy mode (DMC proxy, single endpoint)
- ⏳ Cluster (per-shard laning planned v1.1)

### References (Specs)

- **RESP Protocol:** Redis RESP2/RESP3 specification (positional, no request IDs)
- **TCP:** RFC 793 (FIFO byte stream, in-order delivery)
- **TLS:** RFC 8446 (TLS 1.3), RFC 5246 (TLS 1.2)
- **JMM:** JSR-133 (Java Memory Model, volatile semantics, CAS atomicity)
- **Lettuce internals:** `CommandHandler.java` (FIFO stack), `DefaultEndpoint.java` (SharedLock)
- **Redis internals:** `ae.c` (event loop), `networking.c` (client state), `server.h` (client flags)

### Quick Links

- **[Complete Technical Reference](docs/TECHNICAL_REFERENCE.md)** — Architecture, SSL/TLS, performance model, operational runbooks
- **[Design Decision: Thread Affinity](docs/DESIGN_DECISION_THREAD_AFFINITY.md)** — Why MurmurHash3(threadId) vs ThreadLocal
- **[Transaction Safety Deep Dive](docs/TRANSACTION_SAFETY_DEEP_DIVE.md)** — RESP protocol constraints, collision math
- **[Cluster Mode Support](docs/CLUSTER_MODE.md)** — Per-shard laning (future)
- **[Lane Selection Strategies](docs/LANE_SELECTION_STRATEGIES.md)** — Round-robin, key affinity, least-used, adaptive

---

## When NOT to use this

Laned connections have real costs. A library that only documents its benefits is a library
you shouldn't trust. Read this section before deploying.

**1. Pure pipelining workloads** - If your code batches hundreds of commands per
`pipeline().exec()` call, HOL is already minimized by the batching itself. Lane
distribution adds connection overhead without proportional benefit. Pipelining and laning
solve different problems and aren't additive.

**2. OSS Cluster mode** - Lettuce's `ClusterConnectionProvider` maintains one physical
connection per shard. Commands for different shards never share a queue. HOL blocking in
cluster mode is already contained to the per-shard FIFO. Applying laned connections per
shard (not yet implemented here) is only worth it if individual shards are themselves
HOL-constrained under concentrated hot-key traffic.

**3. Uniform, consistently fast workloads** - If every command completes in under 1ms and
your key distribution is clean, HOL blocking is statistically negligible. Profile first.
Add lanes if you see evidence of HOL — sleeping threads, p50/p99 divergence. Don't add
them preemptively.

**4. Heavy use of WATCH/MULTI/EXEC** - Transactions are connection-scoped in RESP. WATCH
registers on a specific connection; the MULTI..EXEC block must execute on that same
connection or the watch guard is silently voided — no exception, no warning, wrong answer.
I implemented `ThreadAffinity`-based pinning for imperative code. Reactive transactional flows
aren't supported in the initial release (see Trade-offs for the full explanation and
workarround).

**5. Many concurrent blocking commands** - `BLPOP`, `BRPOP`, `XREAD BLOCK`, and `WAIT`
hold a connection open for the full block timeout. Each concurrent blocking command occupies
one lane for its duration. With 8 lanes and 4 concurrent `BLPOP` calls, only 4 lanes
remain for normal traffic. Sizing rule: `lanes ≥ max_concurrent_blocking_commands + 4`.

**6. FD-constrained or connection-metered environments** - Each lane is a TCP connection.
Some managed Redis offerings cap connections or charge per connection. Some minimal
container images default `ulimit -n` to 1024. Know your FD budget before choosing N.

**7. Mixed-SLO workloads without priority separation** - Lane selection runs before command
inspection — a background cache-warming `HGETALL` gets the same treatment as a critical
auth `GET`. If your workload has distinct latency classes, use separate
`LanedLettuceConnectionFactory` instances per priority class (see Trade-offs).

---

## The Problem, from first principles

To understand why this matters, you need to follow the causal chain from the wire protocol
all the way through the OS kernel, the Redis server, and the Lettuce client. I'll walk you
through it.

---

### 1. RESP has no request IDs — this is the root cause

The Redis Serialization Protocol (RESP2 and RESP3) is a **positional protocol**. There are
no request IDs, no correlation tokens, no out-of-order delivery mechanisms anywhere in the
wire format. This is by design—it keeps the protocol simple and fast, but it has consequences.

A GET response looks like:
```
$5\r\nvalue\r\n
```

A write response looks like:
```
+OK\r\n
```

Neither contains any reference to the command that triggered it. The client must maintain a
FIFO queue of pending commands and match each response to the command at the front of the
queue — positionally. **Response N belongs to command N, period.** If you send GET, then SET,
then GET, the responses arrive in that exact order. Always.

**This is the only reason out-of-order responses are impossible — and it is entirely a
protocol design decision, not a TCP constraint.**

Consider what would be possible with request IDs (like HTTP/2 stream IDs):

```
Client sends:  Q1 (slow HGETALL)  id=1
               Q2 (fast GET)       id=2

Server processes Q2 first (faster), returns:
  id=2  +value\r\n       ← client reads id=2, routes to Q2's caller ✓
  id=1  *500...\r\n      ← client reads id=1, routes to Q1's caller ✓
```

With IDs, the server could return Q2's result before Q1's result. The client matches by ID,
not by position. No HOL blocking.

RESP has no IDs. The same scenario without IDs:

```
Client sends:  Q1 (slow HGETALL)
               Q2 (fast GET)

Server processes Q2 first, returns Q2's bytes first.
Client reads Q2's bytes. Its queue says Q1 is the first pending command.
→ Client assigns Q2's response to Q1's caller. WRONG ANSWER.
```

There is no way for the client to detect this mismatch. The data is silently wrong.
Therefore the server MUST return responses in exactly the order commands were received,
and the client MUST process them in that order. The FIFO contract is enforced by the
protocol's lack of IDs, not by TCP.

TCP (RFC 793) is relevant in one specific way: once the server writes bytes to the TCP send
buffer, the order is fixed. TCP guarantees the receiver gets them in exactly the order the
sender wrote them. The sender — Redis — has full control over *what* it writes and *when*.
If RESP had request IDs, Redis could write Q2's response bytes into the TCP buffer before
Q1's response bytes, and the client could match each response to the right caller by ID.
Without IDs, writing Q2 before Q1 corrupts Q1's caller. So Redis is forced to write
responses in the same order it received the commands — and TCP then preserves that order
to the client. TCP is a consequence here, not the constraint.

**This is intentional protocol design, not an oversight.**

RESP was designed for maximum throughput with minimum overhead. Compare the wire cost of a
single `GET key` round-trip:

```
RESP2:      *2\r\n$3\r\nGET\r\n$3\r\nkey\r\n   → ~21 bytes request
            $5\r\nhello\r\n                       → ~12 bytes response
            Total overhead beyond data: ~12 bytes

HTTP/1.1:  GET /key HTTP/1.1\r\nHost: ...\r\n\r\n  → 40+ bytes headers (request)
            HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello  → 50+ bytes
            Total overhead: ~80 bytes

gRPC:      HTTP/2 frame header (9 bytes) + protobuf field tags + HTTP/2 stream ID
            Total overhead: ~30-50 bytes per message
```

RESP's overhead per command is **nearly unmeasurable** compared to any other protocol in
common use. No content-type, no method, no host header, no framing, no field tags — just a
type byte, a length, `\r\n`, and the data.

Removing request IDs saves 4–8 bytes per message and eliminates the correlation lookup on
both sender and receiver. For a Redis server processing 1 million ops/sec, that is a
non-trivial cost at scale. The trade-off accepted: FIFO ordering is mandatory, and the
server must be fast enough that HOL blocking is rarely a problem — which holds true as long
as commands are consistently in the microsecond range.

The problem emerges when that assumption breaks: large values, expensive scans, or slow
commands create millisecond-range outliers that block everything behind them.

**RESP3 does not change this.** RESP3 (Redis 7.0+) adds push messages, server-side
attributes, typed maps and sets. It does NOT introduce request IDs. The design philosophy
— minimal overhead, positional matching — is unchanged.

---

### 2. Redis Server: One Thread Owns the Event Loop

Redis runs a **single-threaded event loop** for all command processing. The implementation
is in `src/ae.c`:

```c
// ae.c — aeMain()
void aeMain(aeEventLoop *eventLoop) {
    eventLoop->stop = 0;
    while (!eventLoop->stop) {
        aeProcessEvents(eventLoop, AE_ALL_EVENTS |
                                   AE_CALL_BEFORE_SLEEP |
                                   AE_CALL_AFTER_SLEEP);
    }
}
```

`aeProcessEvents()` calls `aeApiPoll()` (epoll on Linux, kqueue on BSD/macOS) to collect
all ready file descriptors, then iterates through them in a flat `for` loop:

```c
// ae.c — aeProcessEvents()
for (j = 0; j < numevents; j++) {
    int fd = eventLoop->fired[j].fd;
    aeFileEvent *fe = &eventLoop->events[fd];
    // ...
    if (!invert && fe->mask & mask & AE_READABLE) {
        fe->rfileProc(eventLoop, fd, fe->clientData, mask);
        fired++;
    }
    if (fe->mask & mask & AE_WRITABLE) {
        fe->wfileProc(eventLoop, fd, fe->clientData, mask);
        fired++;
    }
}
```

For each client connection, `rfileProc` is `readQueryFromClient` (`src/networking.c`):

```c
// networking.c — createClient()
connSetReadHandler(conn, readQueryFromClient);
```

`readQueryFromClient` reads bytes from the socket into `c->querybuf`, parses them into
commands using the RESP state machine, and calls `processCommand()` for each complete
command. `processCommand()` executes synchronously and appends the result to the client's
output buffer via `addReply()`.

**Critical point:** Every command from every client is processed **sequentially** on this
one thread. A `LRANGE mylist 0 1000000` that takes 20ms occupies the event loop for those
full 20ms. No other command — from no other connection — can be processed until it
finishes.

#### Redis 6+ IO Threads — What They Do and Don't Fix

Redis 6 introduced `io-threads N` (configurable via `redis.conf`). This parallelizes
**reading from sockets** and **writing replies to sockets** across N threads. From
`networking.c`:

```c
// networking.c
c->tid = IOTHREAD_MAIN_THREAD_ID;      // assigned at create time
c->running_tid = IOTHREAD_MAIN_THREAD_ID;
```

IO threads handle raw bytes in/out. But `processCommand()` — the actual execution of every
Redis command — **still runs exclusively on the main thread**. IO threads cannot change the
fundamental fact that command execution is single-threaded and that a slow command blocks
all subsequent commands on the same client connection.

What `io-threads` actually improves: throughput on high-connection-count workloads where
the bottleneck is socket I/O, not command execution. For HOL-blocking scenarios caused by
slow commands, it provides no relief.

---

### 3. Lettuce: A Single Netty Channel With a FIFO Command Stack

Lettuce (the default Spring Data Redis async driver since Spring Boot 2.x) uses a **single
multiplexed TCP connection** per `StatefulRedisConnection`. All commands from all threads
are sent over this one channel and matched to responses on the way back.

The core mechanism is in `CommandHandler.java`:

```java
// CommandHandler.java
public class CommandHandler extends ChannelDuplexHandler implements HasQueuedCommands {

    private final Queue<RedisCommand<?, ?, ?>> stack;  // ArrayDeque or HashIndexedQueue

    public CommandHandler(ClientOptions clientOptions,
                          ClientResources clientResources,
                          Endpoint endpoint) {
        // ...
        this.stack = clientOptions.isUseHashIndexedQueue()
                ? new HashIndexedQueue<>()
                : new ArrayDeque<>();
    }
```

**Write path** (`write()` → `writeSingleCommand()` → `addToStack()`):

```java
// CommandHandler.java — addToStack()
private void addToStack(RedisCommand<?, ?, ?> command, ChannelPromise promise) {
    // ...
    stack.add(redisCommand);  // appended to TAIL of queue
    // ...
}
```

Every command submitted by any thread — GET, SET, HGETALL, LRANGE — is appended to the
tail of this single `ArrayDeque`.

**Read path** (`channelRead()` → `decode()`):

```java
// CommandHandler.java — decode()
protected void decode(ChannelHandlerContext ctx, ByteBuf buffer) throws InterruptedException {
    while (canDecode(buffer)) {
        // ...
        RedisCommand<?, ?, ?> command = stack.peek();  // always reads HEAD
        // ...
        if (!decode(ctx, buffer, command)) {
            // incomplete response — stop, wait for more bytes
            hasDecodeProgress = true;
            decodeBufferPolicy.afterPartialDecode(buffer);
            return;
        }
        // complete response:
        stack.poll();   // remove from HEAD
        complete(command);  // resolve the CompletableFuture
    }
}
```

The decode loop **always operates on the head of the stack**. It cannot skip over a command
with an in-progress response (e.g., a 10MB bulk string) to process a response that arrived
after it. The TCP stream does not allow it: the bytes for the earlier response must be fully
consumed before the bytes of the later response are readable.

**This is the HOL blocking mechanism** — confirmed against Lettuce source during a
production latency investigation by Christian Schnapka (Macstab GmbH):

```
Thread A submits: HGETALL session:1234   → stack = [HGETALL]    (slow, 18ms, 500KB value)
Thread B submits: GET user:flag          → stack = [HGETALL, GET]
Thread C submits: INCR counter:hits      → stack = [HGETALL, GET, INCR]

Time 0ms:   Commands written to TCP socket. Redis receives all three.
Time 0ms:   Redis event loop: executes HGETALL (slow — 500KB scan)
Time 0ms:   Redis event loop: executes GET (0.05ms)
Time 0ms:   Redis event loop: executes INCR (0.05ms)
            All three commands execute immediately on the server side.
            Responses queued to each client's output buffer.
            Response bytes: HGETALL (500KB), then GET, then INCR.

Time 18ms:  HGETALL response fully received by client.
            CommandHandler.decode() can now poll HGETALL from stack.
            GET response was already in the TCP receive buffer.
            CommandHandler.decode() immediately polls GET.
            INCR response was already in the TCP receive buffer.
            CommandHandler.decode() immediately polls INCR.

Result:
  Thread A: waited 18ms   ← correct
  Thread B: waited 18ms   ← blocked behind 18ms of HGETALL bytes
  Thread C: waited 18ms   ← blocked behind 18ms of HGETALL bytes
```

Thread B and C are blocked not because Redis was slow for them. Redis processed their
commands in ~0.1ms. They are blocked because **500KB of HGETALL response must be read off
the TCP socket before the client can read the GET and INCR responses that come after it
in the byte stream**.

The caller threads (B and C) are parked on `CompletableFuture.get()` or `Mono.block()`.
The Netty event loop thread (which runs `decode()`) is not blocked — it is reading bytes
as fast as the network delivers them. But it cannot complete the GET and INCR futures until
HGETALL is fully decoded and popped from the stack.

The number of sleeping threads equals the number of concurrent callers waiting on responses
behind the slow command.

#### `setShareNativeConnection` in Spring Data Redis

`LettuceConnectionFactory.setShareNativeConnection(true)` (the default) means all
non-transactional operations share a single `StatefulRedisConnection`. This maximises HOL
exposure: every bean in every thread that calls `redisTemplate.opsForValue().get(...)` is
sharing the same single `stack` `ArrayDeque`.

`setShareNativeConnection(false)` causes each call to `getConnection()` to borrow a
dedicated connection from a pool — which brings the pool explosion problem described below.

---

### 4. The DefaultEndpoint: Writes, Locks, and the Channel

`DefaultEndpoint.java` is the layer above `CommandHandler` that coordinates writes:

```java
// DefaultEndpoint.java
public class DefaultEndpoint implements RedisChannelWriter, Endpoint, PushHandler {

    protected volatile Channel channel;  // single Netty channel

    private final SharedLock sharedLock = new SharedLock();

    public <K, V, T> RedisCommand<K, V, T> write(RedisCommand<K, V, T> command) {
        // ...
        try {
            sharedLock.incrementWriters();
            if (autoFlushCommands) {
                Channel channel = this.channel;
                if (isConnected(channel)) {
                    writeToChannelAndFlush(channel, command);
                } else {
                    writeToDisconnectedBuffer(command);
                }
            } else {
                writeToBuffer(command);
            }
        } finally {
            sharedLock.decrementWriters();
        }
        return command;
    }

    private void writeToChannelAndFlush(Channel channel, RedisCommand<?, ?, ?> command) {
        QUEUE_SIZE.incrementAndGet(this);
        ChannelFuture channelFuture = channelWriteAndFlush(channel, command);
        // ...
    }
```

`SharedLock` is a readers-writers lock where multiple concurrent writers can proceed
simultaneously (incrementing a shared counter), but exclusive operations (like reconnect)
block until all writers finish. The QUEUE_SIZE atomic tracks in-flight commands for
backpressure.

The result: **N threads can simultaneously call `write()`**, each appending to the stack
and writing to the channel. Netty's channel write is thread-safe. But they are all writing
into the same TCP stream, and responses arrive in the same TCP stream, so FIFO applies
across all of them.

---

### 5. Connection Pools: Why They Amplify the Problem at Scale

The commons-pool2 `GenericObjectPool` (used by `LettucePoolingClientConfiguration`)
maintains a pool of `StatefulRedisConnection` objects. Each thread borrows one, uses it
for a single command (or transaction), then returns it.

This sounds like it solves HOL: each connection has only one in-flight command at a time,
so there is nothing to block behind. And at low concurrency, it does help.

At high concurrency and scale, it creates a different catastrophe.

#### The Connection Count Explosion

With a pool of 50 connections per pod and 30 pods:
- **50 × 30 = 1,500 concurrent connections** to Redis

Redis's `maxclients` defaults to 10,000, so 1,500 is technically within limit. But consider
what happens when a shard is slow.

Redis is single-threaded per instance. When a shard gets a slow command (or high key-scan
load), its event loop slows down. All 1,500 connections are actively sending requests.
Each connection has its own entry in Redis's event loop fd array. Redis processes them
round-robin (in epoll firing order). With 1,500 connections all queuing requests to the
same slow shard, every new request joins the back of the server-side queue.

The effect is a **thundering herd on the server side**: all 1,500 connections pile up
requests on a single-threaded Redis that is already behind. Each connection's pool thread
is now blocked waiting for a response, so the pod's thread pool fills up, and new requests
begin to queue in the application layer.

#### Borrow Contention Under Spike Load

`GenericObjectPool.borrowObject()` is backed by a `LinkedBlockingDeque<PooledObject<T>>`.
Under high load, when all connections are in use, `borrowObject()` blocks the calling
thread until one is returned. With 30 pods × 50 threads × 1 blocking thread each:

- 1,500 threads across the cluster are sleeping in `borrowObject()`.
- When the slow shard recovers, all 1,500 connections attempt to send simultaneously.
- The returned connections are handed immediately to waiting `borrowObject()` callers.
- This creates a traffic burst that re-overloads the recovered shard.

TCP connection setup cost is irrelevant here (connections are kept alive in the pool), but
the **OS socket buffer effects** are significant. With 1,500 active connections, the kernel
must maintain a separate `sk_buff` ring for each socket. Under memory pressure, the kernel
may begin throttling socket buffer allocation, leading to `ENOMEM` on socket operations.

#### Redis maxclients and fd Limits

From `server.h`:
```c
/* When configuring the server eventloop, we setup it so that the total number
 * of file descriptors we can handle are server.maxclients + RESERVED_FDS +
 * a few more to stay safe. */
#define CONFIG_FDSET_INCR (CONFIG_MIN_RESERVED_FDS+96)
```

Each Redis connection consumes one file descriptor on the server. With cluster mode and
multiple shards, the connection count multiplies further: 1,500 connections × N shards.
In a cluster with 6 shards, that is 9,000 file descriptors from a single application
cluster.

---

### 6. OS Kernel: TCP Receive Buffer and Backpressure

When a Lettuce connection is blocked behind a slow HGETALL response (say 500KB), the
following happens at the OS level:

1. Redis writes the 500KB response to its send buffer (`sk_sndbuf`). The kernel segments
   it into ~350 TCP packets (MTU ~1460 bytes).
2. The client OS receives these packets, reassembles them in the TCP receive buffer
   (`sk_rcvbuf`, typically 128KB–512KB default, tunable via `tcp_rmem`).
3. Netty's event loop is notified via `epoll_wait()` (Linux) or `kevent()` (macOS) that
   the socket is readable. It calls `channel.read()`.
4. Netty reads from the socket into a `ByteBuf` and calls `channelRead()` on
   `CommandHandler`.
5. `CommandHandler.decode()` processes the bytes into the HGETALL response incrementally.
   For a 500KB value, this spans multiple `channelRead()` invocations (each reads up to
   64KB or the receive buffer size).
6. Until HGETALL is fully decoded (all 500KB parsed), `stack.peek()` returns the HGETALL
   command, and the GET/INCR responses that follow in the stream are not yet reached.

The Netty event loop thread is **not blocked** — it processes bytes as fast as they arrive.
But the `CompletableFuture`s for GET and INCR cannot be completed until their position in
the stream is reached.

**TCP Nagle's Algorithm** can add up to 40ms of latency for small commands (like `GET key`)
when the socket has unacknowledged data in flight. Lettuce sets `TCP_NODELAY` by default
(visible in `networking.c`'s `connEnableTcpNoDelay(conn)`), which disables Nagle. But this
only prevents artificial batching — the fundamental FIFO ordering of the byte stream
remains.

---

### 7. Language Clients: How They Handle (or Don't Handle) This

Every language client faces the same constraint. They differ only in *how* they expose the
blockage to the application.

#### redis-py (Python, synchronous)

```python
# redis-py uses a blocking socket per connection
# connection.py — send_command() + read_response()

# Pool is a LifoQueue (last-in-first-out — MRU reuse to keep warm connections)
class ConnectionPool:
    def __init__(self, ...):
        self._created_connections = 0
        self._available_connections = []  # list-based, mutex-protected
        self._in_use_connections = set()
```

redis-py is strictly synchronous: `send_command()` writes to the blocking socket, then
`read_response()` calls `recv()` in a loop until the full response is read.

HOL behavior: the calling thread is **entirely blocked in `recv()`** for the duration of a
slow response. No async dispatch, no Netty event loop — one thread, one command, one recv
loop. Multiplexing is not used at all. This is the simplest model, and it avoids
intra-connection HOL entirely (one request per connection at a time), but suffers from
connection count explosion at scale.

#### redis.asyncio (Python, async)

```python
# asyncio uses event loop + coroutines
# send_command() → socket.write() (non-blocking)
# read_response() → await socket.read() — suspends coroutine
```

An asyncio application can issue multiple commands concurrently by running them as separate
coroutines. However, if they all share the same connection (async equivalent of Lettuce's
shared connection), the RESP FIFO rule still applies: the event loop reads bytes in order
and parses responses in order. A slow response in the stream suspends all coroutines
waiting on later responses in that connection, until the slow response is fully received.

With an async connection pool (one coroutine = one connection), HOL within a connection is
eliminated, but the pool explosion and thundering herd problems described above apply
equally.

#### hiredis (C)

hiredis is a low-level C library. From `read.c`:

```c
// hiredis/read.c — readLine()
static char *readLine(redisReader *r, int *_len) {
    char *p, *s;
    int len;

    p = r->buf + r->pos;
    s = seekNewline(p, (r->len - r->pos));
    if (s != NULL) {
        len = s - (r->buf + r->pos);
        r->pos += len + 2; /* skip \r\n */
        if (_len) *_len = len;
        return p;
    }
    return NULL;
}
```

hiredis reads bytes sequentially from a single buffer, advancing `r->pos` forward. There
is no out-of-order read path. `hiredisCommand()` (synchronous API) blocks the calling
thread entirely on `read()` until the response is complete.

The `redisAsyncContext` (async API) uses an event loop (libevent, libev, or libuv adapters)
and maintains a FIFO callback queue (`redisAsyncContext.replies`), directly mirroring
Lettuce's stack. Same HOL property applies.

#### go-redis (Go)

```go
// go-redis/internal/pool/pool.go — ConnPool
type ConnPool struct {
    cfg       *Options
    queue     chan struct{}      // semaphore for pool size
    conns     map[uint64]*Conn  // all connections
    idleConns []*Conn           // available connections

    poolSize    atomic.Int32
    idleConnsLen atomic.Int32
    // ...
}
```

go-redis uses a conventional connection pool. Each goroutine acquires a connection, runs
exactly one pipeline of commands, then returns it.

Go goroutines are cheap (2-4KB initial stack, growable) and are scheduled by the Go runtime
cooperatively. A goroutine blocked on a socket read (`conn.Read()`) is descheduled by the
Go runtime's net poller (backed by epoll/kqueue) and does not consume an OS thread.

HOL behavior in go-redis: within a single connection used with pipelining (`Pipelined()`),
the same FIFO rule applies — slow response blocks later responses on that connection. For
unpipelined use (one command per connection borrow), HOL is eliminated at the cost of pool
overhead. go-redis has no built-in lane mechanism.

#### ioredis (Node.js)

ioredis implements request pipelining over a single TCP connection (similar to Lettuce).
It maintains an internal command queue. All commands go out on one connection, responses
come back in FIFO order.

ioredis's `pipeline()` API explicitly acknowledges this: it batches commands into a single
TCP write, then expects exactly N responses in exactly that order. HOL blocking applies
to all pipelined commands: if command 1 is slow, commands 2..N wait in the queue.

Node.js's single-threaded event loop means there is no thread-level blocking — the event
loop continues processing other events. But application code waiting for command N+1
cannot proceed until the response for command N has been received and its callback fired.

#### StackExchange.Redis (.NET)

StackExchange.Redis implements the same multiplexing pattern as Lettuce, and explicitly
documents it. From `PhysicalConnection.cs`:

```csharp
// PhysicalConnection.cs
private readonly Queue<Message> _writtenAwaitingResponse = new Queue<Message>();
```

StackExchange.Redis uses **exactly 2 connections per server**: one `interactive` (for
normal commands) and one `subscriber` (for pub/sub). All interactive commands from all
threads are multiplexed over the single interactive connection. `_writtenAwaitingResponse`
is the FIFO queue that matches responses to callers — identical in purpose to Lettuce's
`CommandHandler.stack`.

The StackExchange.Redis documentation explicitly warns about `synchronous block` in a
multiplexing context. Their mitigation: async/await throughout. But async does not
eliminate HOL — it just means the coroutine suspends rather than the thread blocks.

---

### 8. Sentinel and Redis Enterprise

#### Redis Sentinel

Sentinel coordinates failover and provides service discovery. Applications connect to a
Sentinel-aware client that resolves the current master address. Sentinel does **not** proxy
commands — the client connects directly to the Redis master instance.

From a connection topology perspective, Sentinel changes nothing about the HOL problem.
Single Lettuce connection, same FIFO stack, same HOL behavior. Sentinel adds reconnect
overhead on failover (Lettuce handles this automatically), but does not mitigate HOL.

Under failover: Lettuce's `DefaultEndpoint.writeToDisconnectedBuffer()` buffers commands
in `disconnectedBuffer` while reconnecting, then replays them in order on the new
connection. FIFO is preserved through failover.

#### Redis Enterprise (Redis Software)

Redis Enterprise runs multiple shards behind a **DMC Proxy** (Data Management and Cluster
Proxy) layer. Clients connect to the proxy, which routes commands to the appropriate shard.

In **cluster mode** (OSS Cluster Protocol), Lettuce uses `ClusterConnectionProvider` and
maintains **one connection per shard**. Hash slot routing maps each key to a specific
shard. Commands for different shards go to different connections, so HOL on shard A does
NOT block commands routed to shard B or C.

However, within a single shard's connection, the same FIFO rule applies. If shard 2 is
slow (due to a hot key or large value), all commands routing to shard 2 experience HOL.

The DMC Proxy in Redis Enterprise adds an additional layer that can multiplex connections
from many clients into fewer connections to the shard, but the proxy itself obeys RESP
FIFO on both sides and does not solve HOL blocking for a single client's stream.

#### Cluster Mode and the Laned Approach

In cluster mode, laned connections should be applied per-shard, not globally. The
`ClusterConnectionProvider` would need to use `LanedLettuceConnectionProvider` for each
shard slot group. This is a planned enhancement — the MVP applies laned connections in
standalone and Sentinel modes, where a single logical Redis endpoint is the bottleneck.

---

### 9. The Laned Solution: Why It Works

#### The Mechanism

Instead of one multiplexed connection (where every command joins the same FIFO stack),
we maintain **N connections (lanes)**. Each lane is a full `StatefulRedisConnection` with
its own Netty channel, its own `CommandHandler.stack`, and its own TCP socket.

Requests are distributed across lanes via atomic round-robin:

```java
private int selectLane() {
    return (counter.getAndIncrement() & Integer.MAX_VALUE) % numLanes;
}
```

`counter.getAndIncrement()` is a CAS-based atomic increment — lock-free and linearizable.
The `& Integer.MAX_VALUE` prevents negative modulus results after int overflow.

#### Mathematical Proof of HOL Reduction

With a single connection, if any of the N in-flight commands is slow, all subsequent
commands are blocked. The probability of a random command being blocked behind a slow
command is:

```
P(blocked | single connection) = P(at least one slow command ahead in the queue)
```

In the worst case (one slow command per unit time, many fast commands), essentially every
fast command queues behind the slow one at some point.

With N lanes and uniform round-robin distribution:
- A slow command is assigned to lane `k = counter % N`.
- Commands assigned to lanes `k+1, k+2, ..., k+N-1` (mod N) are on different queues.
- Only commands that hash to lane `k` are blocked.
- Under uniform distribution, the probability of a random command landing on the same lane
  as the slow command is `1/N`.

**Expected blocking rate:**

```
P(blocked | N lanes) ≈ P(blocked | single connection) / N
```

For N=8: **87.5% reduction** in HOL-blocked commands.

This is a probabilistic bound, not a guarantee. Two consecutive slow commands on the same
lane will still block fast commands on that lane. But under realistic mixed workloads, the
improvement is proportional to N.

#### Connection Count: The Key Advantage Over Pools

```
Pool:   N_pool × M_pods = 50 × 30 = 1,500 connections
Laned:  N_lanes × M_pods = 8 × 30 = 240 connections
```

With 8 lanes, we reduce the Redis connection count by **84%** while maintaining
per-connection isolation for HOL mitigation.

The critical difference: in a pool, every thread needs its own dedicated connection.
Pool connections are borrowed exclusively — while one thread holds a connection, no other
thread can use it. Connection count is bounded by concurrent request count.

With laned connections, each lane is **shared** (multiplexed) — just like Lettuce's
default single connection. Multiple threads send commands over the same lane simultaneously.
The difference from the single-connection case is that N=8 lanes means a slow command on
lane 3 only blocks other commands that also land on lane 3, not the entire request space.

#### Why PubSub Gets Dedicated Connections

PubSub subscribes (`SUBSCRIBE`, `PSUBSCRIBE`, `SSUBSCRIBE`) fundamentally change the
behavior of a Lettuce channel. Once a subscribe command is sent, the connection enters
subscription mode: responses no longer correlate to commands in FIFO order. Instead, the
server sends asynchronous push messages whenever a matching event occurs. The
`CommandHandler.stack` no longer drives response matching — instead, push messages are
dispatched to registered listeners.

PubSub connections cannot be shared with normal command traffic. They get dedicated
connections, tracked in a `CopyOnWriteArrayList`.

#### `setShareNativeConnection(false)` and Laned Connections

Laned connections take over the `doCreateConnectionProvider()` hook in
`LettuceConnectionFactory`. For non-transactional operations, all calls route through the
laned provider. This means `setShareNativeConnection` becomes irrelevant — the laned
provider replaces both the shared-connection and pool-based providers.

---

## Architecture

The design below reflects the constraints of the original Macstab deployment: Redis
Enterprise proxy mode, a single logical endpoint, Spring Data Redis imperative API, and
Micrometer metrics already wired to a Grafana `redis_pool_*` dashboard. Every structural
decision traces back to those constraints.

```
Request → LanedLettuceConnectionFactory
              └── doCreateConnectionProvider()
                      └── LanedLettuceConnectionProvider
                              ├── Lane[0]: StatefulRedisConnection (Lettuce)
                              │     └── CommandHandler.stack: ArrayDeque
                              │         Netty channel → TCP socket → Redis
                              ├── Lane[1]: StatefulRedisConnection (Lettuce)
                              ├── ...
                              └── Lane[N-1]: StatefulRedisConnection (Lettuce)

                              PubSub → dedicated connections (subscribe mode)
```

### Key Classes

| Class                            | Role                                                         |
|----------------------------------|--------------------------------------------------------------|
| `LanedLettuceConnectionProvider` | Holds N lanes, atomic round-robin selection, PubSub handling |
| `LanedLettuceConnectionFactory`  | Extends `LettuceConnectionFactory`, injects laned provider   |
| `LanedRedisAutoConfiguration`    | Spring Boot auto-config (standalone, sentinel, cluster)      |
| `RedisConnectionProperties`      | Config binding (`strategy`, `lanes`)                         |
| `RedisConnectionStrategy`        | Enum: CLASSIC / POOLED / LANED                               |
| `RedisLanedMetricsExporter`      | Micrometer metrics (compatible with pool dashboard tags)     |

---

## Configuration

### Minimal (Standalone, No Auth)

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      connection:
        strategy: LANED   # CLASSIC (default) | POOLED | LANED
        lanes: 8          # 1–64, default 8
```

Drop-in. No code changes required in consuming services. Add the dependency and the
auto-configuration activates.

### Production (TLS + Auth + Timeouts)

```yaml
spring:
  data:
    redis:
      host: redis.example.com
      port: 6380  # TLS port (convention)
      username: app_user
      password: ${REDIS_PASSWORD}
      database: 0
      timeout: 5s
      connect-timeout: 2s
      ssl:
        enabled: true
        bundle: redis-prod
      connection:
        strategy: LANED
        lanes: 16
      lettuce:
        shutdown-timeout: 100ms
        
  ssl:
    bundle:
      pem:
        redis-prod:
          truststore:
            certificate: classpath:ca-cert.pem  # CA for server verification
```

### Mutual TLS (Client Certificates)

**Use case:** Macstab-style deployment, corporate PKI, zero-trust architecture

```yaml
spring:
  data:
    redis:
      host: redis.macstab.local
      port: 6380
      username: macstab_app
      password: ${REDIS_PASSWORD}
      ssl:
        enabled: true
        bundle: macstab-mtls
      connection:
        strategy: LANED
        lanes: 8
        
  ssl:
    bundle:
      pem:
        macstab-mtls:
          keystore:
            certificate: file:/etc/certs/client-cert.pem   # Client certificate
            private-key: file:/etc/certs/client-key.pem    # Client private key
          truststore:
            certificate: file:/etc/certs/ca-cert.pem       # CA trust store
```

**Redis server config (requires client certs):**
```conf
# redis.conf
tls-port 6380
tls-cert-file /etc/redis/server-cert.pem
tls-key-file /etc/redis/server-key.pem
tls-ca-cert-file /etc/redis/ca-cert.pem
tls-auth-clients yes  # Require client certificates
```

### Sentinel (High Availability)

```yaml
spring:
  data:
    redis:
      password: ${REDIS_PASSWORD}
      database: 0
      timeout: 5s
      connection:
        strategy: LANED
        lanes: 8
      sentinel:
        master: mymaster
        nodes:
          - sentinel1.example.com:26379
          - sentinel2.example.com:26379
          - sentinel3.example.com:26379
        password: ${SENTINEL_PASSWORD}  # Separate from Redis password
```

### Cluster (OSS Cluster Protocol)

```yaml
spring:
  data:
    redis:
      password: ${REDIS_PASSWORD}
      timeout: 5s
      connection:
        strategy: LANED
        lanes: 8
      cluster:
        nodes:
          - node1.example.com:6379
          - node2.example.com:6379
          - node3.example.com:6379
          - node4.example.com:6379
          - node5.example.com:6379
          - node6.example.com:6379
        max-redirects: 5  # MOVED/ASK redirects
```

### Multi-Priority (Separate Factories)

**Use case:** Isolate critical vs bulk traffic

```yaml
# application.yml (shared Redis config)
spring:
  data:
    redis:
      host: redis.example.com
      port: 6379
      password: ${REDIS_PASSWORD}
```

```java
// RedisConfig.java
@Configuration
public class RedisConfig {
    
    @Autowired
    private Environment env;
    
    @Bean("criticalRedisTemplate")
    public RedisTemplate<String, String> criticalTemplate() {
        // 4 lanes for critical path (auth, session, real-time)
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
            env.getProperty("spring.data.redis.host"),
            env.getProperty("spring.data.redis.port", Integer.class, 6379)
        );
        config.setPassword(env.getProperty("spring.data.redis.password"));
        
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))  // Strict timeout for critical path
            .build();
        
        LanedLettuceConnectionFactory factory = 
            new LanedLettuceConnectionFactory(config, clientConfig, 4);
        factory.afterPropertiesSet();
        
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        
        return template;
    }
    
    @Bean("bulkRedisTemplate")
    public RedisTemplate<String, String> bulkTemplate() {
        // 2 lanes for bulk operations (cache warming, analytics, background jobs)
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
            env.getProperty("spring.data.redis.host"),
            env.getProperty("spring.data.redis.port", Integer.class, 6379)
        );
        config.setPassword(env.getProperty("spring.data.redis.password"));
        
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(10))  // Relaxed timeout for bulk
            .build();
        
        LanedLettuceConnectionFactory factory = 
            new LanedLettuceConnectionFactory(config, clientConfig, 2);
        factory.afterPropertiesSet();
        
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        
        return template;
    }
}
```

**Usage:**
```java
@Service
public class UserService {
    
    @Autowired
    @Qualifier("criticalRedisTemplate")
    private RedisTemplate<String, String> criticalRedis;  // Auth, session
    
    @Autowired
    @Qualifier("bulkRedisTemplate")
    private RedisTemplate<String, String> bulkRedis;      // Cache warming
    
    public String getSession(String sessionId) {
        return criticalRedis.opsForValue().get("session:" + sessionId);  // 4 lanes, fast
    }
    
    public void warmCache(List<String> userIds) {
        userIds.forEach(id -> 
            bulkRedis.opsForValue().get("user:" + id)  // 2 lanes, isolated
        );
    }
}
```

**Connection budget:**
- Critical: 4 lanes × 30 pods = 120 connections
- Bulk: 2 lanes × 30 pods = 60 connections
- **Total: 180 connections** (vs 3,000 with 50-connection pool per priority class)

### Development (Insecure Trust Manager)

**⚠️ DEV/TEST ONLY — Never use in production**

```yaml
# application-dev.yml
spring:
  data:
    redis:
      host: redis-dev.local
      port: 6380
      ssl:
        enabled: true
        bundle: dev-self-signed
      connection:
        strategy: LANED
        lanes: 4
        
  ssl:
    bundle:
      pem:
        dev-self-signed:
          truststore:
            certificate: classpath:self-signed-ca.pem
```

```java
// DevRedisConfig.java
@Configuration
@Profile("dev")  // ⚠️ NEVER in production
public class DevRedisConfig {
    
    @Bean
    public LettuceClientConfigurationBuilderCustomizer insecureTrustManager() {
        return builder -> {
            ClientOptions opts = ClientOptions.builder()
                .sslOptions(SslOptions.builder()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)  // Disables cert verification
                    .build())
                .build();
            builder.clientOptions(opts);
        };
    }
}
```

**Why Spring Boot doesn't provide `spring.ssl.verify=false`:**
- Security by design (requires explicit code, visible in code review)
- Prevents accidental production use (property files less visible)
- Compliance (PCI-DSS, HIPAA require cert verification)

### Complete Configuration Reference

**See [TECHNICAL_REFERENCE.md](docs/TECHNICAL_REFERENCE.md) for:**
- All `spring.data.redis.*` properties (complete table)
- All `spring.ssl.bundle.*` properties (PEM + JKS formats)
- Secrets management (Vault, Kubernetes Secrets, environment variables)
- Network security (VPC, firewall rules, bind addresses)
- Troubleshooting (TLS failures, auth errors, timeouts)

---

## Quick Start

**Maven (Spring Boot 3.x):**
```xml
<dependency>
    <groupId>com.macstab.oss.redis</groupId>
    <artifactId>redis-laned-spring-boot-3-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Maven (Spring Boot 4.x):**
```xml
<dependency>
    <groupId>com.macstab.oss.redis</groupId>
    <artifactId>redis-laned-spring-boot-4-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Configuration:**
```yaml
spring:
  data:
    redis:
      connection:
        strategy: LANED
        lanes: 8
```

---

## Metrics (Optional)

**⚠️ Metrics are COMPLETELY OPTIONAL.** Core library works standalone with zero overhead.

### Three Usage Modes

#### 1. **No Metrics** (Default, Zero Overhead)

Core library uses `LanedRedisMetrics.NOOP` singleton — JIT compiler eliminates all metric calls.

```java
// No metrics dependency needed
// Works out of the box with Spring Boot starter
```

**Overhead:** Zero (dead code elimination)

---

#### 2. **Micrometer Integration** (Spring Boot Actuator)

**Add optional dependency:**

```xml
<dependency>
    <groupId>com.macstab.oss.redis</groupId>
    <artifactId>redis-laned-metrics</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Auto-configuration activates when:**
- `redis-laned-metrics` on classpath
- `io.micrometer:micrometer-core` on classpath
- `spring.metrics.laned-redis.enabled=true` (default: true when dependencies present)

**Metrics exported:**

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `redis.lettuce.laned.lane.selections` | Counter | `connection.name`, `lane.index`, `strategy.name` | Lane selection distribution |
| `redis.lettuce.laned.lane.in_flight` | Gauge | `connection.name`, `lane.index` | Current in-flight operations per lane |
| `redis.lettuce.laned.strategy.cas.retries` | Counter | `connection.name`, `strategy.name` | CAS contention in strategies |

**Configuration:**

```yaml
spring:
  metrics:
    laned-redis:
      enabled: true                    # Auto-enable when dependencies present
      connection-name: "primary"       # Tag value (default: "default")
```

**Grafana compatibility:** Reuses `redis_pool_*` conventions where applicable.

---

#### 3. **Custom Metrics Implementation**

Implement `LanedRedisMetrics` interface for Prometheus, StatsD, Dropwizard, etc.

```java
public class PrometheusLanedRedisMetrics implements LanedRedisMetrics {
    
    private final Counter selections;
    
    public PrometheusLanedRedisMetrics(CollectorRegistry registry) {
        this.selections = Counter.build()
            .name("redis_laned_lane_selections_total")
            .labelNames("connection", "lane", "strategy")
            .register(registry);
    }
    
    @Override
    public void recordLaneSelection(String conn, int lane, String strategy) {
        selections.labels(conn, String.valueOf(lane), strategy).inc();
    }
}
```

**Wire manually:**

```java
@Bean
public LanedConnectionManager lanedManager(RedisClient client) {
    LanedRedisMetrics metrics = new PrometheusLanedRedisMetrics(registry);
    return new LanedConnectionManager(client, codec, 8, strategy, metrics);
}
```

---

### Non-Spring Usage

**Core library works WITHOUT Spring Boot:**

```java
// Pure Lettuce + laned connections (no Spring, no metrics)
RedisClient client = RedisClient.create("redis://localhost");
StatefulRedisConnection<String, String> conn = client.connect();

LanedConnectionManager manager = new LanedConnectionManager(
    client,
    StringCodec.UTF8,
    8,                           // 8 lanes
    new RoundRobinStrategy(),
    LanedRedisMetrics.NOOP       // No metrics (zero overhead)
);

// Use manager.getConnection() directly
```

**With custom metrics (no Spring):**

```java
LanedRedisMetrics metrics = new PrometheusLanedRedisMetrics(registry);
LanedConnectionManager manager = new LanedConnectionManager(
    client, codec, 8, strategy, metrics  // Your metrics implementation
);
```

---

## Trade-offs - what this actually costs

Every performance decision is a trade-off. Here's what laned connections actually cost —
in detail, with numbers I measured in production. No glossing over the downsides.

---

### 1. File Descriptors

Each lane is a `StatefulRedisConnection` backed by a Netty channel backed by a TCP socket.
One socket = one file descriptor in the JVM process.

Realistic FD budget per pod at N=8:

```
  8   lane connections
+ 4   PubSub connections (one per SUBSCRIBE pattern group, typical)
+ 1   Sentinel monitor connection (Sentinel mode only)
+ 1   Spring Actuator health check connection
+ 300 JVM baseline (jars, class files, pipes, internal sockets)
─────────────────────────────────────────────────
≈ 314 FDs total
```

Under the default Linux process limit (65535 open files), this is negligible even at N=64.
The risk is container images that hardcode `--ulimit nofile=256` or similar. Check your
container spec. `lsof -p <pid> | wc -l` against a running JVM tells you the baseline before
you add lanes.

---

### 2. Per-Connection Memory in Netty

Each `StatefulRedisConnection` allocates Netty channel infrastructure that Lettuce's
default single connection does not multiply:

| Resource                          | Per-Connection Cost                                         |
|-----------------------------------|-------------------------------------------------------------|
| `ChannelPipeline` (handler chain) | ~4–8 KB                                                     |
| Incoming `ByteBuf` (pooled slab)  | 64–256 KB depending on traffic volume                       |
| Outgoing write buffer             | 64–256 KB depending on command rate                         |
| `CommandHandler.stack` ArrayDeque | 16-slot initial (64 bytes), grows under concurrency         |
| SSL context (if TLS)              | Shared via `SslContext` — **not** multiplied per lane       |
| Netty `EventLoopGroup`            | Shared across all connections — **not** multiplied per lane |

Rough estimate: **50–200 KB per lane** at steady state. With N=8 vs Lettuce's default
single connection: 400 KB–1.6 MB additional heap allocation. On a JVM with 512 MB+ heap,
this is noise. On a 64 MB minimal container, factor it into your `-Xmx` sizing.

The ByteBuf allocations come from `PooledByteBufAllocator` (Lettuce's default). They expand
under load and return to the pool when the channel is idle. Peak: a 500 KB bulk transfer on
all 8 lanes simultaneously = ~4 MB peak from the pool. One-time burst, released immediately
after decode completes.

---

### 3. Startup Cost: N TCP Handshakes

At pod startup, `LanedLettuceConnectionProvider` opens N connections. Lettuce establishes
them concurrently through its Netty event loop group — wall-clock cost approximates one
connection, not N × one connection.

Each connection requires these sequential round-trips:

```
TCP 3-way handshake          → ~1.5 RTT
AUTH user password           → 1 RTT  (if configured)
SELECT db                    → 1 RTT  (if non-default database)
CLIENT SETNAME appname       → 1 RTT  (if connection naming configured)
```

At 0.5 ms RTT (same-datacenter LAN): ~2 ms wall-clock startup overhead.
At 5 ms RTT (cross-AZ): ~15 ms.
At 50 ms RTT: fix the topology, not the client.

Spring's `ApplicationContext` initialization absorbs this comfortably. Documented because
it's real — not flagged because it matters in practice.

---

### 4. Transactional Pinning: The Hard Problem

`WATCH`, `MULTI`, `EXEC`, `DISCARD` are connection-scoped in the RESP protocol. The entire
transaction must execute on the same physical connection:

```
WATCH key         → this connection records the CAS guard
MULTI             → this connection enters queuing mode
SET key value     → queued on this connection
EXEC              → this connection executes atomically; returns result array
```

If WATCH fires on lane 2 and MULTI fires on lane 5 (normal round-robin), the WATCH is
invisible to lane 5. EXEC proceeds without the optimistic lock. **The transaction executes,
the guard is silently voided, and there is no exception.** The correctness contract is
broken without any signal to the caller.

The laned provider handles this via `ThreadLocal` lane pinning:

```java
// Pseudocode — LanedLettuceConnectionProvider
public <K, V, T> RedisCommand<K, V, T> write(RedisCommand<K, V, T> command) {
    if (command instanceof WatchCommand) {
        int lane = selectLane();
        PINNED_LANE.set(lane);            // pin this thread to this lane
        return lanes[lane].write(command);
    }
    if (command instanceof ExecCommand || command instanceof DiscardCommand) {
        int lane = PINNED_LANE.get();
        PINNED_LANE.remove();             // release pin after EXEC/DISCARD
        return lanes[lane].write(command);
    }
    Integer pinned = PINNED_LANE.get();
    if (pinned != null) {
        return lanes[pinned].write(command); // mid-transaction: stay on pinned lane
    }
    return lanes[selectLane()].write(command); // normal path: round-robin
}
```

**Limitation:** `ThreadLocal` pinning only works when a single thread drives the full
transaction from WATCH through EXEC. In Project Reactor / WebFlux, the transaction may hop
threads at any suspension point. Reactor's `Context` propagation is required instead of
`ThreadLocal` — not implemented in this release.

Workaround for reactive transactional code: check out a dedicated `StatefulRedisConnection`
directly from one lane for the transaction's duration, use
`setAutoFlushCommands(false)` / `flushCommands()`, and return it manually.

---

### 5. Connection-Scoped Commands Must Run on All Lanes

Commands that configure connection state must be sent to every lane at initialization and
re-applied on every reconnect:

| Command                 | Applied By                              | Risk If Missed                         |
|-------------------------|-----------------------------------------|----------------------------------------|
| `AUTH password`         | `RedisURI` (automatic)                  | Auth failures after reconnect          |
| `AUTH user password`    | `RedisURI` (automatic)                  | ACL violations on some lanes           |
| `SELECT db`             | `RedisURI` (automatic)                  | Commands silently hit wrong database   |
| `CLIENT SETNAME name`   | `LanedLettuceConnectionProvider.init()` | Lane missing from `CLIENT LIST` output |
| `CLIENT NO-EVICT on`    | `LaneInitializer` callback — manual     | Silent eviction under memory pressure  |
| `CLIENT NO-TOUCH on`    | `LaneInitializer` callback — manual     | LRU timestamps skewed by reads         |
| `CLIENT CACHING yes/no` | `LaneInitializer` callback — manual     | Client-side cache tracking broken      |

`spring-redis-laned` applies `AUTH`, `SELECT`, and `CLIENT SETNAME` automatically via
Lettuce's `RedisURI` builder. For advanced per-connection commands, the library exposes a
`LaneInitializer` callback run once per lane at startup and once per lane on reconnect:

```java
laneProvider.setLaneInitializer(conn -> {
    conn.sync().clientNoEvict(true);
    conn.sync().clientNoTouch(true);
});
```

---

### 6. Round-Robin Cannot Prioritize

Lane selection is: `(counter.getAndIncrement() & Integer.MAX_VALUE) % numLanes`

This runs before the command object is inspected. A background `DEBUG SLEEP 10` gets the
same lane assignment as a microsecond-critical auth-path `GET`. There is no priority queue,
no SLO-aware routing, no way to protect critical commands from sharing a lane with bulk
operations.

If your workload has distinct latency SLO classes, use separate factories:

```java
@Bean("criticalRedisTemplate")
public RedisTemplate<String, String> criticalTemplate() {
    // 4 lanes dedicated to auth, session, and critical path only
    var factory = new LanedLettuceConnectionFactory(redisConfig(), clientConfig(), 4);
    return new RedisTemplate<>(factory);
}

@Bean("bulkRedisTemplate")
public RedisTemplate<String, String> bulkTemplate() {
    // 2 lanes for cache warming, background scans, analytics
    var factory = new LanedLettuceConnectionFactory(redisConfig(), clientConfig(), 2);
    return new RedisTemplate<>(factory);
}
```

Connection budget: 4 + 2 = 6 total connections vs a pool requiring 50 + 50 = 100 for the
same isolation. Laned connections make multi-priority separation economically feasible.

---

### 7. Hot Lane Skew Under Correlated Fan-out

Round-robin assumes statistically independent command arrivals. Under correlated load — a
single request path dispatching N parallel sub-requests simultaneously — commands map to
lanes in a repeating rotation:

```
20 parallel GETs dispatched simultaneously, 8 lanes:
  Commands 1–8   → lanes 0–7  (first full rotation)
  Commands 9–16  → lanes 0–7  (second full rotation)
  Commands 17–20 → lanes 0–3  (partial third rotation)

Lane 0 gets commands: 1, 9, 17   → 3 commands queued simultaneously
Lane 4 gets commands: 4, 12      → 2 commands queued simultaneously
```

Under uniform command latency, this is harmless — all 20 complete in near-identical time.
Under skewed latency (lane 0 happens to carry a slow command from a prior request), the 3
commands on lane 0 block behind it.

Sizing heuristic: if your critical fan-out width is F, set `lanes ≥ F` to guarantee no two
commands from the same fan-out share a lane. For F=20, use 20–32 lanes. This trades FD
count for guaranteed isolation. It is a deliberate sizing choice, not a default.

---

### 8. BLPOP and Blocking Commands: Lane Occupancy

Blocking commands (`BLPOP`, `BRPOP`, `BLMOVE`, `BZPOPMIN`, `BZPOPMAX`, `XREAD BLOCK`,
`WAIT`) hold a connection open for the full block timeout. On Lettuce's default single
connection, one `BLPOP 10` blocks the entire connection for up to 10 seconds —
catastrophic.

On a laned setup: a `BLPOP 10` occupies one lane for up to 10 seconds. Other commands
still route to the remaining N−1 lanes. **This is a massive improvement over single
connection.** But it is still a resource reservation: the blocking command sits at the head
of that lane's stack, and any subsequent commands dispatched to the same lane queue behind
it for the duration.

Sizing rule for blocking-heavy workloads:

```
lanes ≥ max_concurrent_blocking_commands × 2
```

The ×2 factor ensures non-blocking commands always have uncontested lanes available. For
applications with fewer than 4 concurrent blocking commands, N=8 (the default) provides
adequate headroom without any special configuration.

---

## Why This Works: The Connection Budget Argument

The numbers below come from a real production environment — a multi-tenant authorization
platform at Macstab GmbH running Redis Enterprise with 40+ pods under sustained load.

If your organization has set a Redis `maxclients` limit (e.g., 5,000) and you are running
40 pods:

```
Pool (50 conns/pod):   50 × 40 = 2,000 connections
                       + replication + Sentinel + admin = easily 3,000+

Laned (8 lanes/pod):   8 × 40 = 320 connections
                       + replication + Sentinel + admin = ~400 total
```

The laned configuration uses **~13%** of the connection budget that a pool would use,
leaving headroom for operational tooling, replication, and other services sharing the
same Redis instance.

---

---

## Lane Selection Strategies

**✅ IMPLEMENTED:** Three production-ready strategies (v1.0.0)  
**📋 PLANNED:** Three advanced strategies (future releases)

All strategies share the same lane infrastructure — only the dispatch logic changes. Strategy selection configurable via `spring.data.redis.connection.lane-selection-mode` (future config, currently code-based).

---

## ✅ Implemented Strategies

### `ROUND_ROBIN` (Default)

**Status:** ✅ Production-ready (v1.0.0)  
**Implementation:** `RoundRobinStrategy.java`

Atomic counter increment modulo N. Lock-free, uniform distribution.

```java
private final AtomicLong counter = new AtomicLong(0);

private int selectLane(int numLanes) {
    return (int) ((counter.getAndIncrement() & 0x7FFF_FFFF_FFFF_FFFFL) % numLanes);
}
```

**Dispatch cost:** O(1), single CAS operation (~10-20ns)  
**Contention:** Low (single shared atomic counter)  
**Distribution:** Uniform in expectation

**Best for:** Default strategy, uniform uncorrelated workloads, lowest overhead

**Limitation:** Blind to queue depth — can assign to loaded lane when idle lanes available.

---

### `LEAST_USED`

**Status:** ✅ Production-ready (v1.0.0)  
**Implementation:** `LeastUsedStrategy.java`

Select the lane with the fewest in-flight commands. Tracks per-lane usage via `AtomicIntegerArray`:

```java
private int selectLane(int numLanes) {
    int minLane = 0;
    int minUsage = usageCounts.get(0);
    
    for (int i = 1; i < numLanes; i++) {
        int usage = usageCounts.get(i);
        if (usage < minUsage) {
            minUsage = usage;
            minLane = i;
        }
    }
    return minLane;
}
```

**Dispatch cost:** O(N) scan (cache-friendly sequential reads)  
**Contention:** None (atomic reads only)  
**Distribution:** Adaptive to actual load

**Example:**
```
Lane 0: in-flight=3  (slow HGETALL in progress)
Lane 1: in-flight=1
Lane 2: in-flight=0  ← selected (idle lane)
Lane 3: in-flight=2
```

**Why it helps:** Round-robin is blind to queue depth — can assign to loaded lane when idle lanes exist. `LEAST_USED` observes actual usage and avoids loaded lanes.

**Best for:** Mixed fast/slow command workloads, bursty traffic patterns

**Caveat:** O(N) scan overhead. For N≤32 this is negligible (~50-100ns, faster than L3 cache miss). Under extreme concurrency, "minimum" lane can be claimed by another thread between selection and increment — best-effort, not globally optimal.

---

### `THREAD_BASED` (Thread Affinity)

**Status:** ✅ Production-ready (v1.0.0)  
**Implementation:** `ThreadAffinityStrategy.java`

Maps thread ID → lane via MurmurHash3. Same thread always uses same lane (thread-local affinity).

```java
private int selectLane(int numLanes) {
    long threadId = Thread.currentThread().threadId();
    return (int) ((MurmurHash3.hash64(threadId) & 0x7FFF_FFFF_FFFF_FFFFL) % numLanes);
}
```

**Dispatch cost:** O(1) MurmurHash3 + modulo (~20-30ns)  
**Contention:** None (deterministic hash, no shared state)  
**Distribution:** Uniform (for uniformly distributed thread IDs)

**Why it helps:**
- **Transaction safety:** Same thread = same lane. `WATCH`/`MULTI`/`EXEC` guaranteed to hit same connection (no `ThreadLocal` pinning needed).
- **Cache locality:** Thread's commands serialized on one lane = better CPU cache utilization.
- **Predictable isolation:** Each thread's workload isolated to one lane queue.

**Best for:**
- Transactional workloads (`WATCH`/`MULTI`/`EXEC`)
- Thread-per-request architectures (Spring MVC, not WebFlux)
- Debugging (per-thread command isolation)

**Caveat:** Requires even thread distribution. With 200 threads + 8 lanes → ~25 threads/lane (load concentration). With 8 threads + 8 lanes → 1:1 mapping (perfect isolation). Works best when `numThreads ≤ 2 × numLanes`.

**⚠️ Transaction Safety:** Collision rate = `1 - e^(-n²/2m)` where n=threads, m=lanes. At n=m=50: ~39% collision probability. At n=m=2500: ~63% collision probability. Use `numLanes ≥ numThreads` for guaranteed transaction safety, or use dedicated connection pool (`shareNativeConnection: false`).

---

## 📋 Planned Strategies

### `KEY_AFFINITY` (MurmurHash3)

**Status:** 📋 Planned (future release)

Route commands by Redis key hash. Same key → same lane (key isolation + transaction safety).

```java
private int selectLane(RedisCommand<?, ?, ?> command) {
    byte[] key = extractKey(command);   // Type switch over Lettuce command hierarchy
    if (key == null) return roundRobin(); // Keyless commands (PING, INFO, CLIENT*)
    return (int) ((MurmurHash3.hash(key) & 0xFFFF_FFFF) % numLanes);
}
```

**Dispatch cost:** O(key length) MurmurHash3 (~50-200ns depending on key size)  
**Distribution:** Uniform (for uniform key distribution)

**Why it helps:**
- **Key isolation:** Slow `HGETALL user:1234` cannot block `GET session:5678` (different keys → different lanes)
- **Transaction safety:** `WATCH key` + `MULTI` + `EXEC` always hit same lane (no ThreadLocal needed)
- **Hot key predictability:** All traffic for `hot:key` concentrates on one lane

**Best for:** Key-isolated workloads, multi-tenant systems, transactional operations

**Implementation challenges:**
- Key extraction requires Lettuce command hierarchy analysis (`KeyCommand`, `MultiKeyCommand`, etc.)
- Multi-key commands (`MGET k1 k2 k3`) routing decision (first key? hash all? fallback?)
- Keyless commands must fallback to round-robin

**Note:** Uses MurmurHash3 (uniform distribution). Does NOT align with Redis Cluster CRC16 slots.

---

### `RANDOM`

**Status:** 📋 Planned (future release)

`ThreadLocalRandom.current().nextInt(N)` — zero contention alternative to round-robin.

```java
private int selectLane(int numLanes) {
    return ThreadLocalRandom.current().nextInt(numLanes);
}
```

**Dispatch cost:** O(1), zero CAS operations (~5-10ns)  
**Contention:** None (per-thread random state)  
**Distribution:** Uniform in expectation, slightly higher variance than round-robin

**Why it helps:** Round-robin's atomic counter can become bottleneck under extreme concurrency (10K+ threads/sec). `RANDOM` eliminates CAS contention entirely.

**Best for:** Extreme concurrency workloads where atomic counter is measured bottleneck

---

### `ADAPTIVE` (Latency-Weighted)

**Status:** 📋 Planned (future release)

Weighted random selection based on EMA latency per lane. Fast lanes get more traffic.

```
Lane 0: EMA latency = 18ms  (slow)  → weight 0.06  → 6% traffic
Lane 1: EMA latency = 0.4ms         → weight 0.71  → 71% traffic
Lane 2: EMA latency = 0.3ms (fast)  → weight 0.77  → 77% traffic
Lane 3: EMA latency = 0.5ms         → weight 0.67  → 67% traffic
```

**Dispatch cost:** O(N) weighted random selection  
**Distribution:** Latency-aware, self-healing

**Why it helps:** `LEAST_USED` sees queue depth. `ADAPTIVE` sees actual latency — more direct HOL signal. Lane with slow in-flight command looks idle to `LEAST_USED`, slow to `ADAPTIVE`.

**Best for:** Long-running mixed-SLO workloads

**Implementation complexity:** Highest of all strategies (EMA tracking, weighted selection, decay tuning).

---

### Strategy Comparison

| Strategy         | Status | Dispatch Cost            | Contention | Best For                             |
|------------------|--------|--------------------------|------------|--------------------------------------|
| `ROUND_ROBIN`    | ✅ v1.0 | O(1), 1 CAS (~20ns)      | Low        | Default, uniform workloads           |
| `LEAST_USED`     | ✅ v1.0 | O(N) scan (~50-100ns)    | None       | Mixed fast/slow commands             |
| `THREAD_BASED`   | ✅ v1.0 | O(1), hash (~30ns)       | None       | Transactional, thread-per-request    |
| `KEY_AFFINITY`   | 📋 Planned | O(key len) (~50-200ns)   | None       | Key-isolated, multi-tenant           |
| `RANDOM`         | 📋 Planned | O(1), no CAS (~10ns)     | None       | Extreme concurrency (10K+ threads)   |
| `ADAPTIVE`       | 📋 Planned | O(N) weighted (~200ns)   | None       | Mixed SLO, self-optimizing           |
| `THREAD_STICKY` | O(1) ThreadLocal           | None         | Thread-per-request, low thread count   |

---

## ⚠️ Transaction Safety (MULTI/EXEC)

**RESP stores transaction state per-connection (`client->flags`, `client->mstate`), not per-request. Concurrent MULTI on shared connection clobbers state. ThreadAffinity maps thread→lane via MurmurHash3 but doesn't prevent collision (pigeonhole: n threads, m lanes, n>m). Birthday paradox: n=m=2500 → 63% ≥1 collision → 34% threads share connection → MULTI/EXEC fails (`EXEC without MULTI`, cross-thread command execution).** Lettuce explicitly forbids MULTI/EXEC on shared connections. Not a bug—RESP protocol constraint. **Solution:** dedicated pool (`shareNativeConnection: false`) or Lua scripts (atomic, no MULTI needed). See [Transaction Safety Deep Dive](docs/TRANSACTION_SAFETY_DEEP_DIVE.md) (Redis `multi.c` analysis, Netty internals, collision math) and [Lane Selection Strategies](docs/LANE_SELECTION_STRATEGIES.md) (production configs, `shareNativeConnection` behavior, architectural trade-offs).

---

*Created by Christian Schnapka, Principal+ Engineer · [Macstab GmbH](https://macstab.com)*
*Research: Lettuce `CommandHandler.java`, `DefaultEndpoint.java` (Redis Ltd.); Redis `ae.c`,*
*`networking.c`, `server.h` (Redis Ltd.); hiredis `read.c` (Salvatore Sanfilippo, Pieter Noordhuis);*
*StackExchange.Redis `PhysicalConnection.cs` (Stack Exchange); go-redis `pool.go` (Redis Ltd.);*
*redis-py `connection.py` (Redis Ltd.); RFC 793 (TCP).*
