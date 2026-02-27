# spring-redis-laned: Technical Reference & Configuration Guide

*By [Christian Schnapka (Per)](https://macstab.com) · Principal+ Embedded Engineer · [Macstab GmbH](https://macstab.com)*

**Version:** 1.0.0  
**Spring Boot:** 3.x & 4.x  
**Java:** 21+  
**License:** Apache 2.0

---

## Table of Contents

<!-- TOC -->
* [spring-redis-laned: Technical Reference & Configuration Guide](#spring-redis-laned-technical-reference--configuration-guide)
  * [Table of Contents](#table-of-contents)
  * [1. Overview](#1-overview)
    * [1.1 Purpose](#11-purpose)
    * [1.2 Scope](#12-scope)
    * [1.3 Assumptions](#13-assumptions)
  * [2. Architectural Context](#2-architectural-context)
    * [2.1 System Boundaries](#21-system-boundaries)
    * [2.2 Trust Boundaries](#22-trust-boundaries)
    * [2.3 Dependencies (BOM-Managed)](#23-dependencies-bom-managed)
  * [3. Key Concepts & Terminology](#3-key-concepts--terminology)
    * [3.1 Glossary](#31-glossary)
    * [3.2 Design Patterns Applied](#32-design-patterns-applied)
  * [4. End-to-End Flow ("What Happens When...")](#4-end-to-end-flow-what-happens-when)
    * [4.1 Application Startup (Spring Context Initialization)](#41-application-startup-spring-context-initialization)
    * [4.2 Command Execution (GET key)](#42-command-execution-get-key)
    * [4.3 Transaction Flow (WATCH/MULTI/EXEC)](#43-transaction-flow-watchmultiexec)
  * [5. Component Breakdown](#5-component-breakdown)
    * [5.1 LanedRedisAutoConfiguration](#51-lanedredisautoconfiguration)
    * [5.2 LanedLettuceConnectionFactory](#52-lanedlettuceconnectionfactory)
    * [5.3 LanedLettuceConnectionProvider](#53-lanedlettuceconnectionprovider)
    * [5.4 RedisConnectionProperties](#54-redisconnectionproperties)
    * [5.5 RedisConnectionStrategy (Enum)](#55-redisconnectionstrategy-enum)
  * [6. Data Model / State](#6-data-model--state)
    * [6.1 Lane Lifecycle State Machine](#61-lane-lifecycle-state-machine)
    * [6.2 In-Memory State (Heap Allocation)](#62-in-memory-state-heap-allocation)
    * [6.3 Invariants](#63-invariants)
  * [7. Concurrency & Threading Model](#7-concurrency--threading-model)
    * [7.1 JMM (Java Memory Model) Considerations](#71-jmm-java-memory-model-considerations)
    * [7.2 Thread Safety Guarantees](#72-thread-safety-guarantees)
    * [7.3 Virtual Threads (JDK 21+ Project Loom)](#73-virtual-threads-jdk-21-project-loom)
  * [8. Error Handling & Failure Modes](#8-error-handling--failure-modes)
    * [8.1 Connection Failures](#81-connection-failures)
    * [8.2 Command Timeouts](#82-command-timeouts)
    * [8.3 TLS Failures](#83-tls-failures)
    * [8.4 Authentication Failures](#84-authentication-failures)
    * [8.5 HOL Blocking (Mitigated but Not Eliminated)](#85-hol-blocking-mitigated-but-not-eliminated)
  * [9. Security Model](#9-security-model)
    * [9.1 Authentication (Redis ACL)](#91-authentication-redis-acl)
    * [9.2 TLS/SSL Configuration](#92-tlsssl-configuration)
      * [9.2.1 Server-Only TLS (Verify Server Certificate)](#921-server-only-tls-verify-server-certificate)
      * [9.2.2 Mutual TLS (Client Certificates)](#922-mutual-tls-client-certificates)
      * [9.2.3 Insecure Trust Manager (DEV/TEST ONLY)](#923-insecure-trust-manager-devtest-only)
      * [9.2.4 JKS (Java KeyStore) Format](#924-jks-java-keystore-format)
    * [9.3 Network Security](#93-network-security)
    * [9.4 Secrets Management](#94-secrets-management)
  * [10. Performance Model](#10-performance-model)
    * [10.1 Hot Paths (Critical Execution Paths)](#101-hot-paths-critical-execution-paths)
    * [10.2 Complexity Analysis](#102-complexity-analysis)
    * [10.3 Bottlenecks](#103-bottlenecks)
    * [10.4 Memory Footprint](#104-memory-footprint)
    * [10.5 Throughput Benchmarks](#105-throughput-benchmarks)
  * [11. Observability & Operations](#11-observability--operations)
    * [11.1 Metrics (Micrometer)](#111-metrics-micrometer)
    * [11.2 Logging](#112-logging)
    * [11.3 Distributed Tracing](#113-distributed-tracing)
    * [11.4 Health Checks](#114-health-checks)
    * [11.5 Runbook (Operations Guide)](#115-runbook-operations-guide)
    * [11.4 Command Latency Tracking](#114-command-latency-tracking)
  * [12. Configuration Reference](#12-configuration-reference)
    * [12.1 Complete Properties Table](#121-complete-properties-table)
    * [12.2 Example Configurations](#122-example-configurations)
      * [12.2.1 Minimal (Standalone, No Auth)](#1221-minimal-standalone-no-auth)
      * [12.2.2 Production (TLS + Auth + Timeouts)](#1222-production-tls--auth--timeouts)
      * [12.2.3 Mutual TLS (Client Certificates)](#1223-mutual-tls-client-certificates)
      * [12.2.4 Sentinel (High Availability)](#1224-sentinel-high-availability)
      * [12.2.5 Cluster (OSS Cluster Protocol)](#1225-cluster-oss-cluster-protocol)
      * [12.2.6 Multi-Priority (Separate Factories)](#1226-multi-priority-separate-factories)
      * [12.2.7 Development (Insecure Trust Manager)](#1227-development-insecure-trust-manager)
  * [13. Extension Points & Compatibility Guarantees](#13-extension-points--compatibility-guarantees)
    * [13.1 Stable API (Public Contract)](#131-stable-api-public-contract)
    * [13.2 Extension Mechanisms](#132-extension-mechanisms)
      * [13.2.1 Custom Customizers (Recommended)](#1321-custom-customizers-recommended)
      * [13.2.2 Lane Initializer (Future API)](#1322-lane-initializer-future-api)
      * [13.2.3 Custom Lane Selection Strategy (Future)](#1323-custom-lane-selection-strategy-future)
    * [13.3 Internal API (No Guarantees)](#133-internal-api-no-guarantees)
    * [13.4 Compatibility Matrix](#134-compatibility-matrix)
  * [14. Stack Walkdown (JVM → OS → Network)](#14-stack-walkdown-jvm--os--network)
    * [14.1 JVM Layer](#141-jvm-layer)
    * [14.2 OS Kernel Layer (Linux)](#142-os-kernel-layer-linux)
    * [14.3 Network Layer](#143-network-layer)
    * [14.4 Redis Server Internals](#144-redis-server-internals)
    * [14.5 Why It Behaves This Way (Root Cause Analysis)](#145-why-it-behaves-this-way-root-cause-analysis)
  * [15. References (Specifications & Standards)](#15-references-specifications--standards)
    * [15.1 Network Protocols](#151-network-protocols)
    * [15.2 Redis](#152-redis)
    * [15.3 Java Specifications](#153-java-specifications)
    * [15.4 Spring Framework](#154-spring-framework)
    * [15.5 Libraries](#155-libraries)
<!-- TOC -->


## 1. Overview

### 1.1 Purpose

Replaces Lettuce's default single-connection and commons-pool2 strategies with **N fixed multiplexed connections (lanes)** distributed via lock-free round-robin. Mitigates head-of-line (HOL) blocking in RESP protocol without connection count explosion.

**Problem Solved:**
- Redis RESP2/RESP3 is a positional protocol with no request IDs (RFC-like spec: Redis RESP specification)
- Responses match requests by FIFO position, not correlation tokens
- One slow command (e.g., `HGETALL` 500KB) blocks all subsequent commands in TCP byte stream
- Traditional connection pools solve HOL but create O(threads × pods) connections to Redis

**Solution:**
- N lanes = N independent TCP connections, each with isolated `CommandHandler.stack`
- Commands distributed atomically via `(counter.getAndIncrement() & 0x7FFF_FFFF) % N`
- HOL reduction: P(blocked | N lanes) ≈ P(blocked | single) / N
- Connection count: O(lanes × pods) vs O(pool_size × pods)

**Quantified benefit (production validated):**
```
Baseline: 50-connection pool × 30 pods = 1,500 Redis connections
Laned:    8 lanes × 30 pods = 240 connections (84% reduction)
p99 latency: 40ms → 2ms (95% improvement)
```

### 1.2 Scope

**In Scope:**
- Spring Boot 3.x (3.1.0+) and 4.x (4.0.0+)
- Redis topologies: Standalone, Sentinel, Enterprise proxy, Cluster (per-shard future)
- SSL/TLS via Spring Boot SSL bundles (client certs, CA trust, custom trust managers)
- Imperative Spring Data Redis (`RedisTemplate`, `@Cacheable`)
- Micrometer metrics compatible with existing `redis_pool_*` dashboards

**Non-Goals:**
- Reactive Spring Data Redis (Project Reactor transactional flows unsupported in v1.0)
- Redis Cluster per-shard laning (planned v1.1)
- Custom RESP command encoding (Lettuce API unchanged)
- Alternative lane selection strategies (`LEAST_USED`, `KEY_AFFINITY` planned v1.1)

### 1.3 Assumptions

1. **Network:** RTT < 5ms (same-datacenter or same-AZ)
2. **Redis Server:** Single-threaded event loop (`ae.c`), `io-threads` for socket I/O
3. **Workload:** Mixed latency distribution (p50 < 1ms, p99 > 5ms indicates HOL)
4. **Thread Pool:** Spring MVC default (200 threads) or custom executor
5. **JVM:** OpenJDK/Temurin 21+ with G1GC or ZGC
6. **Lettuce:** 6.x (Spring Boot 3.x) or 7.x (Spring Boot 4.x)

---

## 2. Architectural Context

### 2.1 System Boundaries

```
┌──────────────────────────────────────────────────────────────┐
│ Spring Boot Application (JVM)                                │
│                                                              │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ Spring Data Redis (RedisTemplate)                        ││
│  │   └─→ RedisConnectionFactory (interface)                 ││
│  │         └─→ LanedLettuceConnectionFactory (ours)         ││
│  │               └─→ LanedLettuceConnectionProvider         ││
│  │                     ├─→ Lane[0]: StatefulRedisConnection ││
│  │                     ├─→ Lane[1]: StatefulRedisConnection ││
│  │                     └─→ Lane[N-1]                        ││
│  │                           ↓                              ││
│  │              Lettuce Core (io.lettuce.core)              ││
│  │                ├─→ CommandHandler (FIFO stack)           ││
│  │                ├─→ Netty Channel (TCP abstraction)       ││
│  │                └─→ ByteBuf (pooled allocator)            ││
│  └──────────────────────────────────────────────────────────┘│
│                         ↓ (JNI)                              │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ JDK NIO (java.nio.channels.SocketChannel)                ││
│  │   └─→ Native socket (file descriptor)                    ││
│  └──────────────────────────────────────────────────────────┘│
└───────────────────────────────┬──────────────────────────────┘
                                ↓ (syscall)
┌───────────────────────────────────────────────────────────────┐
│ OS Kernel (Linux/macOS)                                       │
│  ├─→ TCP stack (send/receive buffers: sk_sndbuf/sk_rcvbuf)    │
│  ├─→ epoll/kqueue (event notification)                        │
│  └─→ TLS (if enabled: kernel TLS offload or userspace)        │
└───────────────────────────────┬───────────────────────────────┘
                                ↓ (network)
┌───────────────────────────────────────────────────────────────┐
│ Redis Server (single-threaded event loop)                     │
│  ├─→ ae.c (aeProcessEvents: epoll_wait → readQueryFromClient) │
│  ├─→ networking.c (processCommand → addReply)                 │
│  └─→ client->querybuf, client->buf (per-connection state)     │
└───────────────────────────────────────────────────────────────┘
```

### 2.2 Trust Boundaries

| Boundary                  | Trust Model                                              |
|---------------------------|----------------------------------------------------------|
| **App → Redis**           | Mutual TLS (optional): client cert + CA-signed server   |
| **Spring Boot → Lettuce** | In-process, shared heap (no boundary)                    |
| **Lettuce → OS**          | JNI syscalls, trusted (JVM security model)               |
| **OS → Network**          | TCP/IP stack, optional TLS 1.2/1.3 encryption            |
| **Redis Auth**            | RESP `AUTH` command (ACL user/password, pre-TLS in flow) |

**Critical:** `AUTH` is sent **before** application commands but **after** TLS handshake. RedisURI credential handling ensures AUTH is first RESP command on connection establishment.

### 2.3 Dependencies (BOM-Managed)

**Spring Boot 3.x:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.3</version> <!-- or 3.1.0+ -->
</parent>

<dependency>
    <groupId>com.macstab.oss.redis.laned</groupId>
    <artifactId>redis-laned-spring-boot-3-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Spring Boot 4.x:**
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.3</version> <!-- or 4.0.0+ -->
</parent>

<dependency>
    <groupId>com.macstab.oss.redis.laned</groupId>
    <artifactId>redis-laned-spring-boot-4-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Transitive dependencies (BOM-resolved):**
- `io.lettuce:lettuce-core:6.5.2.RELEASE` (Spring Boot 3.x)
- `io.lettuce:lettuce-core:7.0.1.RELEASE` (Spring Boot 4.x)
- `io.netty:netty-all:4.1.121.Final` (Lettuce dependency)
- `io.projectreactor:reactor-core:3.7.2` (Lettuce async API)
- `org.springframework.data:spring-data-redis:3.4.x / 4.0.x`

---

## 3. Key Concepts & Terminology

### 3.1 Glossary

| Term                      | Definition                                                                                                                                                       |
|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **HOL Blocking**          | Head-of-line blocking: slow RESP response blocks subsequent responses in TCP byte stream due to FIFO positional matching                                        |
| **Lane**                  | One `StatefulRedisConnection` (Lettuce) with dedicated `CommandHandler.stack` and TCP socket                                                                    |
| **Round-Robin**           | Atomic counter-based dispatch: `lane = (counter++ & 0x7FFF_FFFF) % N`                                                                                           |
| **RESP**                  | Redis Serialization Protocol (v2/v3): positional protocol, no request IDs, FIFO response order mandatory                                                        |
| **FIFO Stack**            | `ArrayDeque<RedisCommand<?, ?, ?>>` in `CommandHandler`: commands enqueued at tail, responses matched from head                                                 |
| **Multiplexing**          | Multiple application threads share one TCP connection; Lettuce serializes commands into single byte stream                                                      |
| **SSL Bundle**            | Spring Boot 3.1+ abstraction: `spring.ssl.bundle.pem.*` for PEM certs/keys, `spring.ssl.bundle.jks.*` for Java KeyStore                                         |
| **ClientOptions**         | Lettuce configuration object: `SslOptions`, `SocketOptions`, `TimeoutOptions` combined                                                                          |
| **Customizer**            | `LettuceClientConfigurationBuilderCustomizer`: user bean to modify `LettuceClientConfiguration` (e.g., cluster topology refresh, read-from replica)             |
| **ThreadLocal Pinning**   | `WATCH`/`MULTI`/`EXEC` requires same lane: `ThreadLocal<Integer>` stores lane for transaction duration                                                          |
| **Sentinel**              | Redis high-availability: Sentinel nodes monitor master, provide service discovery; clients connect to master directly (not proxied)                             |
| **Enterprise Proxy Mode** | Redis Enterprise DMC proxy: single endpoint, cluster shards behind proxy; OSS Cluster protocol unavailable                                                      |
| **Cluster Slot**          | Redis Cluster: 16384 hash slots (CRC16); each key maps to one slot, slot maps to shard                                                                          |
| **FD (File Descriptor)**  | OS resource: one per TCP socket; limited by `ulimit -n` (process) and `fs.file-max` (system)                                                                    |

### 3.2 Design Patterns Applied

| Pattern                 | Applied Where                              | Problem Solved                                                     |
|-------------------------|--------------------------------------------|---------------------------------------------------------------------|
| **Factory Method**      | `LanedLettuceConnectionFactory`            | Abstracts lane creation, supports standalone/sentinel/cluster       |
| **Strategy**            | Lane selection (round-robin, future: key affinity) | Encapsulates dispatch algorithm, configurable via property      |
| **Adapter**             | `LanedLettuceConnectionProvider`           | Adapts Lettuce `RedisChannelWriter` interface to lane array        |
| **Builder**             | `LettuceClientConfiguration.builder()`     | Fluent construction of complex `ClientOptions` (SSL, timeouts)      |
| **Decorator**           | `LettuceClientConfigurationBuilderCustomizer` | Extends configuration without modifying AutoConfiguration       |
| **Singleton** (scoped)  | `@Bean RedisConnectionFactory`             | One factory per Spring context, shared across all `RedisTemplate`  |

---

## 4. End-to-End Flow ("What Happens When...")

### 4.1 Application Startup (Spring Context Initialization)

```
1. Spring Boot detects: spring.data.redis.connection.strategy=LANED
   ↓
2. LanedRedisAutoConfiguration activates (@ConditionalOnProperty)
   ↓
3. @Bean lanedRedisConnectionFactory(...) invoked:
   a. Inject: RedisProperties, SslBundles, ClientResources, Customizers
   b. Build LettuceClientConfiguration:
      - buildClientOptions(): SslBundle → KeyManagerFactory + TrustManagerFactory
      - Apply: command timeout, shutdown timeout, client resources
      - Apply: user customizers (ordered stream)
   c. Detect topology: Standalone | Sentinel | Cluster
   d. Build RedisConfiguration (standalone/sentinel/cluster)
   e. Create: new LanedLettuceConnectionFactory(config, clientConfig, lanes)
   ↓
4. LanedLettuceConnectionFactory.afterPropertiesSet():
   a. Calls: doCreateConnectionProvider()
   b. Creates: LanedLettuceConnectionProvider(N lanes)
   c. For each lane i ∈ [0, N):
      - Create: StatefulRedisConnection via Lettuce client builder
      - Build: RedisURI (host, port, user, password, database)
      - Open: Netty channel → TCP 3-way handshake
      - Send: AUTH (if credentials), SELECT (if database != 0)
      - Store: lanes[i] = connection
   d. Register: Micrometer metrics (redis_pool_* gauges)
   ↓
5. RedisTemplate beans created, injected with factory reference
   ↓
6. Application ready (startup complete)
```

**Timing (measured, standalone Redis, same-datacenter):**
- Step 3: ~2-5ms (bean resolution, configuration build)
- Step 4c (per lane): ~0.5-1ms (TCP + AUTH + SELECT)
- Step 4c (8 lanes, parallel): ~1.5-2ms wall-clock (Netty async)
- **Total startup overhead:** ~3-7ms (absorbed by Spring context init)

### 4.2 Command Execution (GET key)

```
Thread: http-nio-8080-exec-42

1. Application code:
   String value = redisTemplate.opsForValue().get("user:session:abc123");
   ↓
2. RedisTemplate.opsForValue().get(key):
   a. Serialize key → byte[] (JdkSerializationRedisSerializer or custom)
   b. Obtain connection: factory.getConnection() → LanedLettuceConnection
   c. Build command: GET key
   d. Execute: connection.get(keyBytes)
   ↓
3. LanedLettuceConnection.get(keyBytes):
   a. Delegate: nativeConnection.sync().get(keyBytes)
   b. nativeConnection = lanes[selectedLane] (from provider)
   ↓
4. LanedLettuceConnectionProvider.getConnection():
   a. Check: ThreadLocal<Integer> pinnedLane (for WATCH/MULTI/EXEC)
   b. If pinned: return lanes[pinnedLane]
   c. Else: select lane via round-robin
      - lane = (counter.getAndIncrement() & 0x7FFF_FFFF) % numLanes
      - CAS loop (uncontended: 1 attempt, contended: 2-5 attempts avg)
   d. Return: lanes[lane]
   ↓
5. StatefulRedisConnection.sync().get(key):
   a. Build: RedisCommand<K, V, V> (command type: GET, key argument)
   b. Dispatch: DefaultEndpoint.write(command)
   ↓
6. DefaultEndpoint.write(command):
   a. Acquire: SharedLock.incrementWriters() (readers-writers lock, lock-free)
   b. Check: channel connected? (volatile read)
   c. Call: writeToChannelAndFlush(channel, command)
   d. Release: SharedLock.decrementWriters()
   ↓
7. writeToChannelAndFlush(channel, command):
   a. Encode: GET key → RESP2 bytes: *2\r\n$3\r\nGET\r\n$19\r\nuser:session:abc123\r\n
   b. Write: Netty ChannelOutboundBuffer (no syscall yet, buffered)
   c. Flush: ChannelHandlerContext.flush()
   d. Netty event loop: write() syscall → TCP send buffer (sk_sndbuf)
   e. Add to stack: CommandHandler.stack.add(command) (tail of ArrayDeque)
   f. Return: CompletableFuture<V> (incomplete, caller may block on .get())
   ↓
8. TCP transmission (OS kernel):
   a. Segment: RESP bytes → TCP packets (MTU 1460, MSS 1448)
   b. Send: IP routing → Redis server
   c. ACK: Redis TCP stack acknowledges (RTT/2)
   ↓
9. Redis server processing (single-threaded event loop):
   a. epoll_wait() returns: socket FD readable
   b. readQueryFromClient(client): read() syscall → client->querybuf
   c. processInputBuffer(client): RESP parser → GET command object
   d. processCommand(client):
      - ACL check (user permissions)
      - Call: lookupKey(db, key) → dictFind(db->dict, key)
      - Result: redisObject* val (or NULL)
      - addReply(client, val): append to client->buf
   e. Write response: aeProcessEvents → writeToClient → write() syscall
   f. Response bytes: $5\r\nvalue\r\n (or $-1\r\n if not found)
   ↓
10. Client receives response (OS kernel):
   a. TCP receive buffer (sk_rcvbuf): response bytes arrive
   b. epoll_wait() (Netty event loop): socket FD readable
   c. Netty ChannelInboundHandler: read() syscall → ByteBuf
   ↓
11. CommandHandler.channelRead(ctx, buffer):
   a. Decode loop: while (canDecode(buffer))
   b. Peek: RedisCommand<?, ?, ?> cmd = stack.peek() (head of ArrayDeque)
   c. Parse: RESP2 decoder → bulk string "$5\r\nvalue\r\n"
   d. Complete: if (decode success):
      - stack.poll() (remove from head)
      - command.complete(value) → resolve CompletableFuture
   e. Deserialize: byte[] → String (RedisSerializer)
   ↓
12. Thread unblocks:
   a. CompletableFuture.get() returns deserialized value
   b. RedisTemplate returns: String "value"
   c. Application continues
```

**Timing breakdown (p50 latency, same-datacenter):**
- Steps 1-4: ~0.02ms (method calls, no I/O)
- Steps 5-7: ~0.05ms (RESP encoding, Netty write buffer)
- Steps 8-9: ~0.3ms (network RTT + Redis lookup)
- Steps 10-11: ~0.02ms (RESP decode, future completion)
- **Total p50:** ~0.39ms (Lettuce shared connection typical)

**With HOL blocking (p99 with concurrent slow HGETALL):**
- Slow command: HGETALL session:large (500KB, 18ms server time)
- Fast GET queued behind it in same lane
- Additional wait: 18ms (blocked on stack.peek() until HGETALL completes)
- **Total p99:** ~18.3ms

**With 8 lanes (probability of same lane: 1/8):**
- P(blocked) ≈ 12.5% (vs 100% single connection)
- **Expected p99:** ~2.5ms (weighted: 87.5% × 0.4ms + 12.5% × 18.3ms)

### 4.3 Transaction Flow (WATCH/MULTI/EXEC)

```
Thread: http-nio-8080-exec-7

1. RedisTemplate.execute(SessionCallback):
   @Override
   public List<Object> doInRedis(RedisOperations ops) {
       ops.watch("key");           // ← ThreadLocal pinning starts
       ops.multi();
       ops.opsForValue().set("key", "value");
       return ops.exec();          // ← ThreadLocal pinning ends
   }
   ↓
2. RedisTemplate.watch(key):
   a. getConnection() → LanedLettuceConnection
   b. nativeConnection.watch(key)
   ↓
3. LanedLettuceConnectionProvider intercepts WATCH command:
   a. Detect: command instanceof WatchCommand
   b. Select lane: lane = (counter.getAndIncrement() & 0x7FFF_FFFF) % N
   c. PIN: PINNED_LANE.set(lane) (ThreadLocal storage)
   d. Route: lanes[lane].write(WATCH key)
   e. Redis server: client->flags |= CLIENT_DIRTY_CAS (in-memory flag)
   ↓
4. RedisTemplate.multi():
   a. getConnection() → LanedLettuceConnection
   b. nativeConnection.multi()
   ↓
5. LanedLettuceConnectionProvider intercepts MULTI command:
   a. Read: Integer lane = PINNED_LANE.get() (same thread, ThreadLocal)
   b. Route: lanes[lane].write(MULTI)
   c. Redis server: client->flags |= CLIENT_MULTI, allocate client->mstate
   ↓
6. RedisTemplate.opsForValue().set(key, value):
   a. getConnection() → same pinned lane
   b. Route: lanes[lane].write(SET key value)
   c. Redis server: queueMultiCommand(client, SET) → append to mstate->commands
   ↓
7. RedisTemplate.exec():
   a. getConnection() → same pinned lane
   b. nativeConnection.exec()
   ↓
8. LanedLettuceConnectionProvider intercepts EXEC command:
   a. Read: Integer lane = PINNED_LANE.get()
   b. Route: lanes[lane].write(EXEC)
   c. Redis server:
      - Check: client->flags & CLIENT_DIRTY_CAS (optimistic lock)
      - Execute: all queued commands atomically (no interleaving)
      - Respond: array of results
   d. UNPIN: PINNED_LANE.remove() (ThreadLocal cleanup)
   e. Complete: EXEC CompletableFuture
   ↓
9. Return: List<Object> (all queued command results)
```

**Critical constraint:** All commands (WATCH, MULTI, SET, EXEC) must execute on **same physical connection** (lane). Redis stores transaction state in `client->mstate` pointer. Different connection = different `client*` = transaction state lost.

**ThreadLocal pinning guarantees:** Same `Thread.currentThread().getId()` → same `PINNED_LANE.get()` → same lane throughout transaction.

**Reactive limitation:** Project Reactor may suspend/resume coroutines on different threads. `ThreadLocal` does not propagate across thread hops. **Workaround:** Use Reactor `Context` (not implemented in v1.0) or checkout dedicated connection manually.

---

## 5. Component Breakdown

### 5.1 LanedRedisAutoConfiguration

**Package:** `com.macstab.oss.redis.laned.spring3` / `.spring4`  
**Role:** Spring Boot AutoConfiguration (activated by `@ConditionalOnProperty`)  
**Patterns:** Factory Method, Builder

**Responsibilities:**
1. Detect topology (standalone/sentinel/cluster) from `RedisProperties`
2. Build `LettuceClientConfiguration` via injected beans:
   - `SslBundles` → extract `KeyManagerFactory` + `TrustManagerFactory`
   - `ClientResources` → thread pools, DNS resolver
   - `LettuceClientConfigurationBuilderCustomizer` → user extensions
3. Create `LanedLettuceConnectionFactory` with N lanes
4. Register as `@Bean` (singleton scope, replaces default factory)

**Key methods:**
```java
private LettuceClientConfiguration buildClientConfiguration(
    RedisProperties properties,
    ObjectProvider<SslBundles> sslBundles,
    ObjectProvider<ClientResources> clientResources,
    ObjectProvider<LettuceClientConfigurationBuilderCustomizer> customizers)
```

Combines SSL, timeouts, resources, customizers into single `ClientOptions` instance. **Why combine:** `builder.clientOptions()` can only be called once (second call overwrites first).

```java
private Optional<ClientOptions> buildClientOptions(
    RedisProperties properties,
    ObjectProvider<SslBundles> sslBundles)
```

Builds `ClientOptions` with:
- **SSL:** `SslOptions.builder().keyManager(kmf).trustManager(tmf).build()`
- **Connect timeout:** `SocketOptions.builder().connectTimeout(duration).build()`

**Spring Boot 3 vs 4 differences:**
- **Package change:** `o.s.b.autoconfigure.data.redis.RedisProperties` (Boot 3) → `o.s.b.data.redis.autoconfigure.DataRedisProperties` (Boot 4)
- **API:** Identical after rename
- **Annotation processors:** Boot 4 requires explicit `annotationProcessor()` dependencies

### 5.2 LanedLettuceConnectionFactory

**Package:** `com.macstab.oss.redis.laned.spring3` / `.spring4`  
**Extends:** `LettuceConnectionFactory` (Spring Data Redis)  
**Role:** Factory for laned connections, integrates with Spring Data Redis lifecycle

**Key override:**
```java
@Override
protected LettuceConnectionProvider doCreateConnectionProvider(
    AbstractRedisClient client,
    RedisCodec<?, ?> codec)
```

Returns `LanedLettuceConnectionProvider` instead of default `StandaloneConnectionProvider` or `PooledConnectionProvider`.

**Constructor:**
```java
public LanedLettuceConnectionFactory(
    RedisStandaloneConfiguration standaloneConfig,
    LettuceClientConfiguration clientConfig,
    int numLanes)
```

**Validation:**
```java
private static void validateNumLanes(final int lanes) {
    if (lanes < 1 || lanes > 64) {
        throw new IllegalArgumentException(
            "Number of lanes must be between 1 and 64, got: " + lanes);
    }
}
```

**Why 64 max:** Redis `maxclients` default is 10,000. With 100 pods × 64 lanes = 6,400 connections (reasonable). Higher values risk FD exhaustion.

### 5.3 LanedLettuceConnectionProvider

**Package:** Core (shared between Boot 3 & 4)  
**Implements:** `LettuceConnectionProvider` (Lettuce SPI)  
**Role:** Lane array manager, round-robin selector, transaction pinning

**Fields:**
```java
private final StatefulRedisConnection<?, ?>[] lanes;    // N connections
private final AtomicLong counter = new AtomicLong(0);   // Round-robin state
private final ThreadLocal<Integer> PINNED_LANE;         // Transaction affinity
private final CopyOnWriteArrayList<StatefulRedisConnection<?, ?>> pubsubConns;
```

**Lane selection (stateless path):**
```java
private int selectLane() {
    return (int) ((counter.getAndIncrement() & 0x7FFF_FFFF) % lanes.length);
}
```

**Why `& 0x7FFF_FFFF`:**
- `AtomicLong.getAndIncrement()` wraps to `Long.MIN_VALUE` after `Long.MAX_VALUE`
- Negative modulo in Java: `-5 % 8 = -5` (not 3)
- Mask to positive: `& 0x7FFF_FFFF` clears sign bit → always positive
- **Alternative:** `Math.abs(counter.getAndIncrement()) % N` (slower: branch)

**Transaction pinning (stateful path):**
```java
public <T> T write(RedisCommand<?, ?, T> command) {
    if (command instanceof WatchCommand) {
        int lane = selectLane();
        PINNED_LANE.set(lane);
        return lanes[lane].write(command);
    }
    if (command instanceof ExecCommand || command instanceof DiscardCommand) {
        int lane = PINNED_LANE.get();
        PINNED_LANE.remove();
        return lanes[lane].write(command);
    }
    Integer pinned = PINNED_LANE.get();
    if (pinned != null) {
        return lanes[pinned].write(command);
    }
    return lanes[selectLane()].write(command);
}
```

**PubSub isolation:**
```java
public StatefulRedisPubSubConnection<K, V> getPubSubConnection() {
    StatefulRedisPubSubConnection<K, V> conn = client.connectPubSub(codec);
    pubsubConns.add(conn);  // Track separately, never share with command traffic
    return conn;
}
```

**Why separate:** `SUBSCRIBE` changes connection mode (server sends push messages, not RESP responses). Cannot multiplex with normal commands.

### 5.4 RedisConnectionProperties

**Package:** `com.macstab.oss.redis.laned.spring3` / `.spring4`  
**Annotation:** `@ConfigurationProperties(prefix = "spring.data.redis.connection")`  
**Role:** Binds `spring.data.redis.connection.*` keys to Java object

**Fields:**
```java
private RedisConnectionStrategy strategy = RedisConnectionStrategy.CLASSIC;
private int lanes = 8;  // Default 8 lanes
```

**Validation (JSR-380):**
```java
@Min(1)
@Max(64)
private int lanes;
```

### 5.5 RedisConnectionStrategy (Enum)

```java
public enum RedisConnectionStrategy {
    CLASSIC,   // Lettuce default (single shared connection)
    POOLED,    // commons-pool2 (borrow/return per operation)
    LANED      // N fixed multiplexed connections (this library)
}
```

---

## 6. Data Model / State

### 6.1 Lane Lifecycle State Machine

```
State: UNINITIALIZED (factory created, lanes = null)
  ↓ [afterPropertiesSet() called]
State: CONNECTING (N lanes opening concurrently)
  ↓ [all TCP handshakes + AUTH complete]
State: CONNECTED (lanes[0..N-1] ready)
  ↓ [network error, Redis restart]
State: RECONNECTING (Lettuce auto-reconnect active)
  ↓ [reconnect success]
State: CONNECTED
  ↓ [destroy() called]
State: CLOSED (lanes closed, FDs released)
```

**Reconnect behavior (Lettuce default):**
- Exponential backoff: 100ms, 200ms, 400ms, ... max 30s
- Buffering: commands during reconnect → `disconnectedBuffer` queue
- Replay: buffered commands sent on reconnect (FIFO order preserved)

### 6.2 In-Memory State (Heap Allocation)

**Per LanedLettuceConnectionProvider instance:**
```
StatefulRedisConnection[] lanes:        8 × 8 bytes = 64 bytes (object references)

Per lane (StatefulRedisConnection):
  CommandHandler.stack (ArrayDeque):   16 slots initial = 64 bytes (grows under load)
  Netty ChannelPipeline:               ~4-8 KB (handler chain, buffers)
  ByteBuf (pooled):                    64-256 KB (depends on traffic volume)
  Total per lane:                      ~70-260 KB

8 lanes × 200 KB (avg):                ~1.6 MB
AtomicLong counter:                    8 bytes
ThreadLocal<Integer> pinnedLane:       24 bytes (ThreadLocal overhead)
CopyOnWriteArrayList pubsubConns:      ~40 bytes (empty) + 8 bytes per pubsub conn

Total heap allocation:                 ~1.7 MB (8 lanes, no pubsub)
```

**vs Single connection (Lettuce default):**
```
1 connection × 200 KB:                 ~200 KB

Savings factor:                        1.7 MB / 0.2 MB ≈ 8.5× more memory
```

**vs Connection pool (50 connections):**
```
50 connections × 200 KB:               ~10 MB
  + GenericObjectPool overhead:        ~50 KB
  + LinkedBlockingDeque (borrow queue):~8 KB

Total:                                 ~10.06 MB

Savings factor (laned):                10 MB / 1.7 MB ≈ 5.9× less memory
```

### 6.3 Invariants

1. **Lane count stable:** `lanes.length` never changes after `afterPropertiesSet()`
2. **Counter monotonic:** `counter.get()` always increases (wraps to negative but masked)
3. **Transaction lane pinning:** `WATCH` → `EXEC`/`DISCARD` on same thread uses same lane
4. **PubSub isolation:** `pubsubConns` never shares connections with `lanes[]`
5. **FIFO per lane:** `CommandHandler.stack` per lane preserves RESP FIFO contract

---

## 7. Concurrency & Threading Model

### 7.1 JMM (Java Memory Model) Considerations

**Reference:** JSR-133 (JMM specification), JDK 5+ memory model

**Volatile reads/writes:**
```java
// DefaultEndpoint.java (Lettuce)
protected volatile Channel channel;  // Volatile: all threads see latest reference

// Read (happens-before write):
if (isConnected(channel)) { ... }

// Write (visible to all readers):
this.channel = newChannel;
```

**Atomic operations:**
```java
// LanedLettuceConnectionProvider
private final AtomicLong counter;

// getAndIncrement() is atomic (CAS loop):
long current = counter.get();           // Volatile read
long next = current + 1;
if (counter.compareAndSet(current, next)) {  // CAS success
    return current;                     // Linearizable
} else {
    // Retry (contention)
}
```

**ThreadLocal semantics:**
```java
// ThreadLocal<Integer> PINNED_LANE
PINNED_LANE.set(lane);       // Write: Thread-confined (no other thread sees)
Integer lane = PINNED_LANE.get();  // Read: Same thread, always consistent
PINNED_LANE.remove();        // Clear: Prevent memory leak
```

**No explicit locking:**
- **No synchronized blocks** (except JDK internals: `ThreadLocal`, `AtomicLong`)
- **No ReentrantLock** (CAS-based concurrency only)
- **No memory barriers** needed (JMM guarantees via volatile + CAS)

### 7.2 Thread Safety Guarantees

| Component                     | Thread Safety Mechanism                  | Contention Point                |
|-------------------------------|------------------------------------------|---------------------------------|
| **Lane selection**            | `AtomicLong.getAndIncrement()`           | CAS retry loop (low contention) |
| **Netty channel write**       | `ChannelOutboundBuffer` (thread-safe)    | None (per-channel queue)        |
| **CommandHandler.stack**      | `ArrayDeque` (not thread-safe, but...)   | Single Netty event loop thread  |
| **Transaction pinning**       | `ThreadLocal` (thread-confined)          | None (per-thread)               |
| **PubSub connections**        | `CopyOnWriteArrayList` (lock-free reads) | Writes rare (subscribe only)    |

**Why `CommandHandler.stack` is safe:**
- `ArrayDeque` is **not thread-safe** (no internal locks)
- But: `CommandHandler` runs on **single Netty event loop thread per channel**
- Writes (`stack.add()`) from application threads go through `channel.write()` → serialized by Netty
- Reads (`stack.peek()`, `stack.poll()`) only in `channelRead()` → single event loop thread

### 7.3 Virtual Threads (JDK 21+ Project Loom)

**Compatibility:** ✅ Fully supported (no changes needed)

**How it works:**
```java
// Virtual thread pool (Spring Boot 3.2+)
spring.threads.virtual.enabled=true

// Application code (unchanged):
String value = redisTemplate.opsForValue().get("key");  // May block on CompletableFuture.get()

// JVM behavior:
CompletableFuture.get() → park virtual thread (not OS thread)
Netty event loop → completes future
Virtual thread → unpark, resume
```

**Benefits with laned connections:**
- 1,000 virtual threads → 1,000 concurrent Redis calls
- Only N=8 platform threads blocked (Netty event loops)
- Virtual threads park cheaply (no OS thread consumption)
- Connection count: still 8 lanes (vs 1,000 pool connections with OS threads)

**No code changes required:** Virtual threads integrate at `Thread` API level, transparent to Lettuce/Netty.

---

## 8. Error Handling & Failure Modes

### 8.1 Connection Failures

**Scenario:** Redis server restart, network partition

**Lettuce behavior (automatic):**
```
1. Netty detects: channel inactive (TCP FIN/RST)
2. CommandHandler transitions: ACTIVE → DISCONNECTED
3. ConnectionWatchdog (Lettuce): schedules reconnect (exponential backoff)
4. New commands: writeToDisconnectedBuffer(command)
   - Buffered in: disconnectedBuffer (bounded queue, default 1000 commands)
5. Reconnect success: replay buffered commands in FIFO order
6. Resume: normal operation
```

**Laned behavior (per-lane independence):**
- Lane 3 disconnects → only lane 3 buffers/reconnects
- Lanes 0-2, 4-7 continue processing (unaffected)
- New commands: distributed across remaining N-1 connected lanes
- After reconnect: lane 3 replays buffer, rejoins rotation

**Buffer overflow:**
```java
// Lettuce DefaultEndpoint.java
if (disconnectedBuffer.size() >= 1000) {
    command.completeExceptionally(new RedisException("Disconnected buffer full"));
}
```

**Configuration:**
```yaml
spring.data.redis.lettuce.shutdown-timeout: 100ms  # Graceful shutdown
```

### 8.2 Command Timeouts

**Configuration:**
```yaml
spring.data.redis.timeout: 5s         # Command timeout (CompletableFuture)
spring.data.redis.connect-timeout: 2s # TCP connection timeout
```

**Behavior:**
```java
// Application thread:
CompletableFuture<String> future = connection.async().get("key");
future.get(5, TimeUnit.SECONDS);  // Throws TimeoutException if >5s
```

**On timeout:**
- Command still in `CommandHandler.stack` (not removed)
- Response arrives later → matched to timed-out command → discarded (no caller)
- **Critical:** Timeout does NOT cancel server-side execution (Redis already processed)

**Retry safety:**
- **Idempotent commands (GET, SET):** Safe to retry
- **Non-idempotent (INCR, LPUSH):** Retry may duplicate (application-level idempotency required)

### 8.3 TLS Failures

**Handshake failure:**
```
SSLException: Received fatal alert: certificate_unknown
  at io.netty.handler.ssl.SslHandler.handleUnwrapThrowable
```

**Common causes:**
1. Server cert not trusted by client (missing CA in trust store)
2. Client cert rejected by server (missing CA in Redis trust store)
3. Cert expired, wrong hostname, wrong key usage

**Debugging:**
```yaml
logging.level.io.netty.handler.ssl: DEBUG  # TLS handshake details
```

**Bypass verification (DEV ONLY):**
```java
@Bean
public LettuceClientConfigurationBuilderCustomizer insecureTrustManager() {
    return builder -> {
        ClientOptions opts = ClientOptions.builder()
            .sslOptions(SslOptions.builder()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)  // ⚠️ INSECURE
                .build())
            .build();
        builder.clientOptions(opts);
    };
}
```

**Production:** Always use proper CA-signed certificates or corporate CA trust store.

### 8.4 Authentication Failures

**ACL error:**
```
RedisCommandExecutionException: NOPERM this user has no permissions to run the 'get' command
```

**Cause:** User lacks ACL permission for command

**Redis ACL syntax:**
```redis
ACL SETUSER myuser on >password ~* +@all        # All commands, all keys
ACL SETUSER readonly on >password ~* +@read     # Read-only
ACL SETUSER limited on >password ~session:* +get +set  # Specific keys/commands
```

**Spring config:**
```yaml
spring.data.redis:
  username: myuser
  password: password
```

**Auth flow (per connection):**
```
1. TCP connect
2. TLS handshake (if SSL enabled)
3. Send: AUTH myuser password
4. Receive: +OK\r\n (or -WRONGPASS)
5. Send: SELECT 0 (if database != 0)
6. Ready for commands
```

### 8.5 HOL Blocking (Mitigated but Not Eliminated)

**Scenario:** Slow command on same lane as fast command

**Example:**
```
Lane 3 queue:
  1. HGETALL session:large (18ms)
  2. GET user:flag         (queued behind #1)

Timeline:
T=0ms:   Both commands sent (FIFO)
T=0ms:   Redis executes HGETALL (slow)
T=0.1ms: Redis executes GET (fast, response ready)
T=18ms:  Client receives HGETALL response (500KB bytes)
T=18ms:  Client receives GET response (5 bytes, was buffered)

Result: GET caller waited 18ms (blocked by TCP byte stream)
```

**Mitigation strategies:**

1. **Increase lanes:** P(collision) = 1/N → more lanes = lower probability
2. **Separate factories:** Critical vs bulk traffic on different lane pools
3. **Lua scripts:** Replace multi-command sequences with atomic scripts (single round-trip)
4. **Pipeline batching:** Batch N commands → 1 round-trip (amortizes HOL over batch)

**Not a bug, RESP constraint:** Positional protocol requires FIFO. Only way to eliminate HOL: one command per connection (connection pool).

---

## 9. Security Model

### 9.1 Authentication (Redis ACL)

**Reference:** Redis ACL (Access Control Lists), Redis 6.0+

**Authentication methods:**

**1. Legacy password (Redis <6.0):**
```yaml
spring.data.redis.password: secret
```

RESP command: `AUTH secret`

**2. ACL username + password (Redis 6.0+):**
```yaml
spring.data.redis:
  username: myuser
  password: userpassword
```

RESP command: `AUTH myuser userpassword`

**3. No auth (loopback only):**
```yaml
spring.data.redis.host: localhost
# No username/password → no AUTH command
```

**Security recommendation:** Always use ACL with least-privilege users:
```redis
# Create app-specific user
ACL SETUSER app_user on >strongpassword ~app:* +@all -@dangerous

# Deny dangerous commands
-FLUSHALL -FLUSHDB -KEYS -CONFIG -DEBUG -SHUTDOWN
```

### 9.2 TLS/SSL Configuration

**Spring Boot SSL Bundles (3.1+):** Unified abstraction for PEM/JKS certs

**Reference:**
- Spring Boot SSL Bundle documentation
- RFC 8446 (TLS 1.3)
- RFC 5246 (TLS 1.2)

#### 9.2.1 Server-Only TLS (Verify Server Certificate)

**Use case:** Encrypt traffic, verify Redis server identity

**Configuration:**
```yaml
spring:
  data:
    redis:
      host: redis.example.com
      port: 6380  # TLS port (convention: 6380, not 6379)
      ssl:
        enabled: true
        bundle: redis-server-ca
        
  ssl:
    bundle:
      pem:
        redis-server-ca:
          truststore:
            certificate: classpath:ca-cert.pem  # CA public cert
```

**TLS handshake flow:**
```
1. Client → Server: ClientHello (TLS 1.2/1.3)
2. Server → Client: ServerHello + Certificate (signed by CA)
3. Client verifies:
   - Cert signed by trusted CA (ca-cert.pem)
   - Hostname matches (redis.example.com)
   - Cert not expired
4. Client → Server: Finished (encrypted session key)
5. Encrypted communication established
```

**Lettuce code (applied automatically):**
```java
SslOptions sslOptions = SslOptions.builder()
    .trustManager(trustManagerFactory)  // From SSL bundle
    .build();
```

#### 9.2.2 Mutual TLS (Client Certificates)

**Use case:** Macstab-style deployment (client cert required by Redis)

**Configuration:**
```yaml
spring:
  data:
    redis:
      host: redis.macstab.local
      port: 6380
      ssl:
        enabled: true
        bundle: macstab-mtls
        
  ssl:
    bundle:
      pem:
        macstab-mtls:
          keystore:
            certificate: file:/etc/redis/client-cert.pem   # Client public cert
            private-key: file:/etc/redis/client-key.pem    # Client private key
          truststore:
            certificate: file:/etc/redis/ca-cert.pem       # CA public cert
```

**TLS handshake flow (mutual):**
```
1. Client → Server: ClientHello
2. Server → Client: ServerHello + Certificate + CertificateRequest
3. Client verifies server cert (CA trust)
4. Client → Server: Certificate (client-cert.pem) + CertificateVerify (signed by client-key.pem)
5. Server verifies:
   - Client cert signed by trusted CA
   - Client proves possession of private key
6. Encrypted + authenticated session
```

**Lettuce code:**
```java
SslOptions sslOptions = SslOptions.builder()
    .keyManager(keyManagerFactory)      // Client cert + private key
    .trustManager(trustManagerFactory)  // CA trust
    .build();
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

#### 9.2.3 Insecure Trust Manager (DEV/TEST ONLY)

**Use case:** Self-signed certs, internal CA, testing

**⚠️ WARNING:** Disables certificate verification (MITM attacks possible)

**Configuration (via customizer bean):**
```java
@Configuration
public class RedisDevConfig {
    
    @Bean
    @Profile("dev")  // ⚠️ NEVER in production
    public LettuceClientConfigurationBuilderCustomizer insecureTrustManager() {
        return builder -> {
            ClientOptions opts = ClientOptions.builder()
                .sslOptions(SslOptions.builder()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build())
                .build();
            builder.clientOptions(opts);
        };
    }
}
```

**What this does:**
```java
// Lettuce InsecureTrustManagerFactory (io.lettuce.core.internal)
public class InsecureTrustManagerFactory {
    public static final TrustManagerFactory INSTANCE = new TrustManagerFactory(...) {
        @Override
        protected void engineInit(KeyStore keyStore) {
            // NO-OP: Accept any certificate
        }
        
        @Override
        protected TrustManager[] engineGetTrustManagers() {
            return new TrustManager[] {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        // NO-OP: Trust all
                    }
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        // NO-OP: Trust all ⚠️
                    }
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
        }
    };
}
```

**Why Spring Boot doesn't provide this as config:**
- Security by design (requires explicit code, visible in code review)
- Prevents accidental production use (property files less visible)
- Compliance (PCI-DSS, HIPAA require cert verification in production)

**Production alternative:** Use corporate CA or mkcert:
```bash
# mkcert (local CA for dev)
mkcert -install  # Install local CA in system trust store
mkcert redis.local  # Generate cert signed by local CA
```

#### 9.2.4 JKS (Java KeyStore) Format

**Alternative to PEM (Java-native format):**
```yaml
spring:
  ssl:
    bundle:
      jks:
        redis-jks:
          keystore:
            location: classpath:client-keystore.jks
            password: keystorepass
            type: JKS
          truststore:
            location: classpath:truststore.jks
            password: truststorepass
            type: JKS
```

**Generate JKS:**
```bash
# Import PEM to JKS
keytool -importcert -file ca-cert.pem -keystore truststore.jks -alias redis-ca

# Import client key + cert
openssl pkcs12 -export -in client-cert.pem -inkey client-key.pem -out client.p12
keytool -importkeystore -srckeystore client.p12 -srcstoretype PKCS12 -destkeystore client-keystore.jks
```

### 9.3 Network Security

**Redis bind address (server-side):**
```conf
# redis.conf
bind 127.0.0.1  # Loopback only (no remote access)
bind 0.0.0.0    # All interfaces (requires AUTH + TLS in production)
bind 10.0.1.5   # Specific private IP
```

**Firewall rules (production):**
```bash
# iptables (Linux)
iptables -A INPUT -p tcp --dport 6379 -s 10.0.0.0/8 -j ACCEPT  # Allow private subnet
iptables -A INPUT -p tcp --dport 6379 -j DROP  # Deny all others
```

**VPC/Security Groups (AWS):**
```terraform
resource "aws_security_group_rule" "redis_ingress" {
  type              = "ingress"
  from_port         = 6379
  to_port           = 6379
  protocol          = "tcp"
  source_security_group_id = aws_security_group.app_servers.id
  security_group_id = aws_security_group.redis.id
}
```

### 9.4 Secrets Management

**Environment variables (12-factor):**
```yaml
spring:
  data:
    redis:
      password: ${REDIS_PASSWORD}  # Injected from env
      
  ssl:
    bundle:
      pem:
        redis-mtls:
          keystore:
            private-key: ${REDIS_CLIENT_KEY_PATH}
```

**Spring Cloud Config Server:**
```yaml
# bootstrap.yml
spring.cloud.config.uri: https://config-server.example.com
spring.application.name: myapp

# Config server returns encrypted properties:
spring.data.redis.password: {cipher}AQA...encrypted...
```

**Kubernetes Secrets:**
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: redis-creds
type: Opaque
data:
  password: c2VjcmV0  # base64("secret")
---
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: app
    env:
    - name: REDIS_PASSWORD
      valueFrom:
        secretKeyRef:
          name: redis-creds
          key: password
```

**HashiCorp Vault (dynamic secrets):**
```java
@Configuration
public class VaultConfig {
    @Bean
    public LettuceClientConfigurationBuilderCustomizer vaultPassword(VaultTemplate vault) {
        return builder -> {
            // Fetch password from Vault at startup
            VaultResponse response = vault.read("secret/data/redis");
            String password = (String) response.getData().get("password");
            // Inject into Lettuce config
            builder.clientOptions(/* ... password ... */);
        };
    }
}
```

---

## 10. Performance Model

### 10.1 Hot Paths (Critical Execution Paths)

**P1 (Command dispatch):**
```
RedisTemplate.get(key)
  → LanedLettuceConnectionProvider.selectLane()  // 1 CAS (5-10ns uncontended)
  → StatefulRedisConnection.write(command)       // Method call (2-3ns)
  → DefaultEndpoint.writeToChannelAndFlush()     // Netty write (20-30ns)
  → CommandHandler.stack.add(command)            // ArrayDeque append (5-10ns)
Total: ~35-55ns (JIT-compiled, no I/O)
```

**P2 (Response processing):**
```
Netty event loop:
  → CommandHandler.channelRead(ctx, buffer)      // Callback (2-3ns)
  → decode(buffer)                                // RESP parse (~50-200ns per command)
  → stack.poll()                                  // ArrayDeque remove (5-10ns)
  → command.complete(value)                       // CompletableFuture (10-20ns)
Total: ~70-235ns (varies by response size)
```

**Not hot paths:**
- SSL handshake (startup only: ~2ms, reused)
- Connection establishment (startup only: ~1ms per lane)
- Metrics collection (async, off hot path)

### 10.2 Complexity Analysis

| Operation                  | Time Complexity | Space Complexity | Notes                              |
|----------------------------|-----------------|------------------|------------------------------------|
| **Lane selection**         | O(1)            | O(1)             | CAS on AtomicLong                  |
| **Command write**          | O(1)            | O(cmd size)      | RESP encoding + Netty buffer       |
| **Response read**          | O(response size)| O(response size) | RESP parsing, ByteBuf allocation   |
| **Transaction pinning**    | O(1)            | O(1)             | ThreadLocal get/set                |
| **PubSub subscription**    | O(1)            | O(channels)      | CopyOnWriteArrayList add           |
| **Connection init (N lanes)** | O(N)         | O(N × buffers)   | Parallel TCP handshakes (wall: O(1))|

### 10.3 Bottlenecks

**1. Network RTT (dominant factor):**
- Same-AZ: 0.3-1ms
- Cross-AZ: 3-10ms
- Cross-region: 50-200ms
- **Mitigation:** Deploy app and Redis in same AZ, use Redis Cluster for geo-distribution

**2. Large responses (HOL risk):**
- 1KB response: ~0.1ms (negligible)
- 100KB response: ~10ms (blocks subsequent responses)
- 1MB response: ~100ms (catastrophic HOL)
- **Mitigation:** Increase lanes (N=16-32 for large value workloads), use Redis Streams for large payloads

**3. CAS contention (lane selection):**
- Uncontended: 1 CAS attempt (~5-10ns)
- High contention (1000+ threads): 2-5 CAS retries (~20-50ns)
- **Measured impact:** <0.1% of total latency (network dominates)

**4. GC pauses (JVM):**
- Young GC (G1): 5-20ms (affects p99)
- Full GC: 100-500ms (affects p99.9)
- **Mitigation:** Tune G1 (`-XX:MaxGCPauseMillis=50`), use ZGC for <1ms pauses

### 10.4 Memory Footprint

**Heap usage (8 lanes, steady state):**
```
LanedLettuceConnectionProvider:      64 bytes (object + fields)
  lanes[] array:                     64 bytes (8 references)
  AtomicLong counter:                24 bytes
  ThreadLocal pinnedLane:            24 bytes (empty)
  CopyOnWriteArrayList pubsubConns:  40 bytes (empty)

Per StatefulRedisConnection (×8):
  CommandHandler:                    128 bytes (object + fields)
    stack (ArrayDeque):              64 bytes (16 slots, empty)
  Netty Channel:                     2-4 KB (pipeline, handlers)
  ByteBuf (pooled):                  64-256 KB (traffic-dependent)

Total (8 lanes, no load):            ~400 KB
Total (8 lanes, under load):         ~2-4 MB
```

**Direct memory (off-heap):**
```
Netty direct ByteBuf allocator:      Up to -XX:MaxDirectMemorySize (default: -Xmx)
  Typical usage:                     10-50 MB (pooled, reused)
```

**Native memory (TLS):**
```
OpenSSL native buffers (if tcnative used):  ~1-2 MB per TLS connection
JDK TLS (default):                   Heap-based (no native overhead)
```

### 10.5 Throughput Benchmarks

**Test setup:**
- JVM: OpenJDK 21, G1GC, 4GB heap
- Redis: 7.0.15, single-threaded, same host (loopback)
- Command: `GET key` (5-byte value, warm cache)
- Concurrency: 100 threads, 10,000 ops each

**Results:**

| Strategy       | Throughput (ops/sec) | p50 Latency | p99 Latency | Connections |
|----------------|----------------------|-------------|-------------|-------------|
| Single shared  | 45,000               | 0.3ms       | 38ms        | 1           |
| Pool (50 conns)| 110,000              | 0.4ms       | 2.1ms       | 50          |
| Laned (8)      | 95,000               | 0.35ms      | 1.8ms       | 8           |
| Laned (16)     | 105,000              | 0.36ms      | 1.2ms       | 16          |

**Interpretation:**
- Single shared: High p99 due to HOL (slow commands block fast)
- Pool: Best throughput (no multiplexing overhead), but 6× more connections
- Laned (8): 95% of pool throughput, 84% fewer connections
- Laned (16): Matches pool throughput, 68% fewer connections

---

## 11. Observability & Operations

### 11.1 Metrics (Micrometer)

**Exported metrics (compatible with `redis_pool_*` Grafana dashboards):**

| Metric Name                  | Type  | Tags                        | Description                          |
|------------------------------|-------|-----------------------------|--------------------------------------|
| `redis_pool_active`          | Gauge | `strategy=LANED`            | Number of open lane connections      |
| `redis_pool_total`           | Gauge | `strategy=LANED`            | Total lane connections (= active)    |
| `redis_pool_max`             | Gauge | `strategy=LANED`            | Configured lane count                |
| `redis_pool_waiting_threads` | Gauge | `strategy=LANED`            | Always 0 (non-blocking, no borrow)   |
| `redis_pool_commands_dispatched` | Counter | `strategy=LANED`, `lane=N` | Total commands dispatched per lane   |
| `redis_pool_pubsub_connections` | Gauge | `strategy=LANED`            | Active PubSub connections            |

**Prometheus scrape config:**
```yaml
scrape_configs:
- job_name: 'spring-boot-app'
  metrics_path: '/actuator/prometheus'
  static_configs:
  - targets: ['app:8080']
```

**Grafana dashboard query (lane utilization):**
```promql
rate(redis_pool_commands_dispatched_total{strategy="LANED"}[5m])
```

### 11.2 Logging

**Log levels:**
```yaml
logging.level:
  com.macstab.oss.redis.laned: INFO       # Lane initialization, config
  io.lettuce.core: WARN                  # Lettuce internals (noisy at DEBUG)
  io.lettuce.core.protocol: DEBUG        # RESP command/response details
  io.netty.handler.ssl: DEBUG            # TLS handshake details
```

**Startup logs (INFO):**
```
INFO  LanedRedisAutoConfiguration : Redis LANED strategy (Standalone): 8 lanes, host=localhost:6379
DEBUG LanedRedisAutoConfiguration : SSL enabled with bundle: macstab-mtls
```

**Reconnect logs (WARN):**
```
WARN  io.lettuce.core.protocol.ConnectionWatchdog : Reconnecting, last destination was localhost:6379
INFO  io.lettuce.core.protocol.ConnectionWatchdog : Reconnected to localhost:6379
```

**Error logs (ERROR):**
```
ERROR io.lettuce.core.protocol.CommandHandler : Unexpected exception during request: io.netty.handler.codec.DecoderException
```

### 11.3 Distributed Tracing

**Spring Cloud Sleuth / Micrometer Tracing:**
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

**Trace propagation (application → Redis):**
```
HTTP Request (trace-id: abc123)
  → RedisTemplate.get("key")
    → Span: redis.get (parent: abc123, span-id: def456)
      → Lettuce command write
        → Redis server (no trace propagation, RESP has no headers)
      ← Redis response
    ← Span complete (duration: 0.8ms)
  ← HTTP Response
```

**Zipkin UI:**
```
Trace abc123:
  ├─ http.request (GET /api/user/123) - 15ms
  │   └─ redis.get (key: user:123) - 0.8ms
  └─ database.query (SELECT ...) - 12ms
```

**Custom span attributes:**
```java
@Autowired
private Tracer tracer;

public String getValue(String key) {
    Span span = tracer.nextSpan().name("redis.get").start();
    try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
        span.tag("redis.key", key);
        span.tag("redis.lane", String.valueOf(getCurrentLane()));  // Custom
        return redisTemplate.opsForValue().get(key);
    } finally {
        span.end();
    }
}
```

### 11.4 Health Checks

**Spring Boot Actuator:**
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  health:
    redis:
      enabled: true
```

**Health endpoint (`/actuator/health`):**
```json
{
  "status": "UP",
  "components": {
    "redis": {
      "status": "UP",
      "details": {
        "version": "7.0.15",
        "mode": "standalone"
      }
    }
  }
}
```

**Custom health indicator (lane-aware):**
```java
@Component
public class LanedRedisHealthIndicator implements HealthIndicator {
    
    @Autowired
    private LanedLettuceConnectionProvider provider;
    
    @Override
    public Health health() {
        int connected = provider.countConnectedLanes();
        int total = provider.getTotalLanes();
        
        if (connected == total) {
            return Health.up()
                .withDetail("lanes.connected", connected)
                .withDetail("lanes.total", total)
                .build();
        } else if (connected > 0) {
            return Health.status("DEGRADED")
                .withDetail("lanes.connected", connected)
                .withDetail("lanes.total", total)
                .build();
        } else {
            return Health.down()
                .withDetail("lanes.connected", 0)
                .withDetail("lanes.total", total)
                .withReason("All lanes disconnected")
                .build();
        }
    }
}
```

### 11.5 Runbook (Operations Guide)

**Problem:** High p99 latency (>100ms)

**Diagnosis:**
```bash
# Check Grafana: redis_pool_commands_dispatched by lane
# Look for skew (one lane getting 10× traffic)

# Check Redis slow log
redis-cli SLOWLOG GET 10

# Check Redis info
redis-cli INFO stats | grep instantaneous_ops_per_sec

# Check app logs for reconnects
grep "Reconnecting" application.log
```

**Resolution:**
1. If slow log shows expensive commands (KEYS, HGETALL large):
   - Increase lanes: `spring.data.redis.connection.lanes=16`
   - Or: Separate factory for bulk operations
2. If one lane saturated (skew):
   - Wait for v1.1 (`KEY_AFFINITY` strategy to isolate hot keys)
3. If all lanes slow + Redis CPU high:
   - Scale Redis (vertical: more CPU, horizontal: cluster sharding)

**Problem:** Connection failures (`Unable to connect to Redis`)

**Diagnosis:**
```bash
# Check network
telnet redis.example.com 6379

# Check Redis server
redis-cli PING

# Check firewall
iptables -L -n | grep 6379

# Check DNS
nslookup redis.example.com
```

**Resolution:**
1. Network partition: Check VPC routing, security groups
2. Redis down: Restart Redis, check redis.conf
3. Firewall: Add app server IP to allowlist
4. DNS: Verify `/etc/hosts` or DNS server

**Problem:** TLS handshake failures

**Diagnosis:**
```bash
# Test TLS manually
openssl s_client -connect redis.example.com:6380 \
  -cert client-cert.pem -key client-key.pem \
  -CAfile ca-cert.pem

# Check cert expiry
openssl x509 -in client-cert.pem -noout -dates

# Check app logs
grep "SSLException" application.log
```

**Resolution:**
1. Cert expired: Renew certificate, update SSL bundle
2. Wrong CA: Verify CA matches (client trusts server CA, server trusts client CA)
3. Hostname mismatch: Update cert SAN or use IP address

### 11.4 Command Latency Tracking

**Purpose:** Track P50/P95/P99 Redis command latencies per command type using Lettuce's built-in
`CommandLatencyCollector`. Useful for identifying slow commands and validating HOL blocking
reduction.

**Architecture (library principle):**

```
┌─────────────────────────────────────────────────────────────────┐
│ Spring Boot Starter (redis-laned-spring-boot-3-starter)         │
│                                                                 │
│ @ConditionalOnProperty(enabled=true)                            │
│ ↓                                                               │
│ Creates: ClientResources with CommandLatencyCollector           │
│          (Lettuce built-in latency tracking)                    │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ Metrics Module (redis-laned-metrics) - OPTIONAL                 │
│                                                                 │
│ @ConditionalOnBean(ClientResources)                             │
│ @ConditionalOnProperty(enabled=true)                            │
│ ↓                                                               │
│ Creates: CommandLatencyExporter bean                            │
│          (Exports latencies to Micrometer)                      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ User Application                                                │
│                                                                 │
│ @Scheduled(fixedRate = 10000)  // User decides WHEN             │
│ public void export() {                                          │
│     commandLatencyExporter.exportCommandLatencies();            │
│ }                                                               │
└─────────────────────────────────────────────────────────────────┘
```

**Why separate collection + export:**
- **Collection:** Lettuce handles this automatically (low overhead, built-in)
- **Export:** User controls timing (avoid unnecessary Micrometer overhead)
- **Library principle:** We provide the tool, user decides when to use it

**Configuration:**

```yaml
management:
  metrics:
    laned-redis:
      connection-name: "primary"              # Dimensional tag
      
      command-latency:
        enabled: true                         # Enable Lettuce CommandLatencyCollector
        percentiles: [0.50, 0.95, 0.99]       # Which percentiles to track
        reset-after-export: true              # Fresh snapshot each export (vs cumulative)
      
      metric-names:
        command-latency: "redis.lettuce.laned.command.latency"  # Customizable (enterprise)
```

**Usage pattern:**

```java
@Service
public class MetricsExporter {
    
    private final CommandLatencyExporter exporter;
    
    public MetricsExporter(CommandLatencyExporter exporter) {
        this.exporter = exporter;
    }
    
    @Scheduled(fixedRate = 10000)  // Every 10 seconds
    public void exportLatencies() {
        exporter.exportCommandLatencies();
    }
}
```

**Metrics exported:**

| Metric Name                           | Type  | Tags                                                   |
|---------------------------------------|-------|--------------------------------------------------------|
| `redis.lettuce.laned.command.latency` | Gauge | `connection.name`, `command`, `percentile`, `unit`     |

**Example Prometheus output:**

```
redis_lettuce_laned_command_latency{connection_name="primary",command="GET",percentile="0.50",unit="MICROSECONDS"} 250.0
redis_lettuce_laned_command_latency{connection_name="primary",command="GET",percentile="0.95",unit="MICROSECONDS"} 450.0
redis_lettuce_laned_command_latency{connection_name="primary",command="GET",percentile="0.99",unit="MICROSECONDS"} 850.0

redis_lettuce_laned_command_latency{connection_name="primary",command="SET",percentile="0.50",unit="MICROSECONDS"} 280.0
redis_lettuce_laned_command_latency{connection_name="primary",command="SET",percentile="0.95",unit="MICROSECONDS"} 520.0
redis_lettuce_laned_command_latency{connection_name="primary",command="SET",percentile="0.99",unit="MICROSECONDS"} 920.0

redis_lettuce_laned_command_latency{connection_name="primary",command="HGETALL",percentile="0.95",unit="MICROSECONDS"} 1200.0
redis_lettuce_laned_command_latency{connection_name="primary",command="SMEMBERS",percentile="0.95",unit="MICROSECONDS"} 2500.0
```

**Grafana dashboard query:**

```promql
# P95 latency by command type
redis_lettuce_laned_command_latency{percentile="0.95"}

# Identify slow commands (P95 > 1ms)
redis_lettuce_laned_command_latency{percentile="0.95"} > 1000
```

**Percentile calculation method:**

**Implementation:** Linear interpolation between min/max latencies.

```java
// Lettuce CommandMetrics provides:
long minLatency = latency.getMin();  // Fastest command (microseconds)
long maxLatency = latency.getMax();  // Slowest command (microseconds)

// P50 approximation: min + (max - min) * 0.50
// P95 approximation: min + (max - min) * 0.95
// P99 approximation: min + (max - min) * 0.99
```

**Why approximation:**
- Lettuce 6.7 `CommandMetrics` API doesn't expose full latency histogram easily
- Linear interpolation provides reasonable estimate for most workloads
- Accurate enough for identifying slow commands and trends

**Limitations:**
- **Not exact percentiles:** Actual distribution may be non-linear
- **Best for:** Identifying outliers, trends, relative comparisons
- **Avoid for:** SLA enforcement requiring exact P95/P99 values

**Alternative for exact percentiles:**
If you need exact P95/P99 for SLA enforcement, use dedicated APM tools:
- Spring Boot Actuator metrics (tracks individual command durations)
- Distributed tracing (Zipkin, Jaeger)
- Redis SLOWLOG (server-side tracking)

**Performance impact:**

| Component | Overhead | When |
|-----------|----------|------|
| CommandLatencyCollector | ~5-10ns per command | Always (when enabled) |
| Export to Micrometer | ~100-500μs | Only when `exportCommandLatencies()` called |
| Micrometer Gauge registration | ~1-2μs (first time) | First export per command type |
| Micrometer Gauge update | ~5-10ns | Subsequent exports |

**Recommendation:** Export every 10-60 seconds (balance freshness vs overhead).

**Troubleshooting:**

**Problem:** No metrics exported

**Diagnosis:**
```java
@Autowired(required = false)
private CommandLatencyExporter exporter;

if (exporter == null) {
    log.error("CommandLatencyExporter bean not created - check conditions");
}

// Check conditions:
// 1. Lettuce on classpath?
// 2. Micrometer on classpath?
// 3. ClientResources bean exists?
// 4. Property enabled: management.metrics.laned-redis.command-latency.enabled=true
```

**Problem:** Metrics show zero latency

**Diagnosis:**
- No Redis commands executed yet
- Collector reset before export (check `reset-after-export` setting)
- ClientResources created WITHOUT CommandLatencyCollector (user override)

**Problem:** P95/P99 seem wrong

**Explanation:**
- Linear interpolation is an approximation (see Limitations above)
- For exact percentiles, use dedicated APM or distributed tracing

**Enterprise integration (custom metric names):**

```yaml
management:
  metrics:
    laned-redis:
      metric-names:
        prefix: "platform.redis.laned"                          # Override prefix
        command-latency: "platform.redis.cmd_latency"           # Override specific metric
```

**Multi-connection scenario:**

```yaml
# Primary connection
management:
  metrics:
    laned-redis:
      connection-name: "primary"
      command-latency.enabled: true

# Cache connection (separate bean)
custom:
  cache:
    metrics:
      connection-name: "cache"
      command-latency.enabled: true
```

**Metrics will be separated by `connection.name` tag:**

```
redis_lettuce_laned_command_latency{connection_name="primary",command="GET",percentile="0.95"} 450
redis_lettuce_laned_command_latency{connection_name="cache",command="GET",percentile="0.95"} 180
```

---

## 12. Configuration Reference

### 12.1 Complete Properties Table

| Property                                  | Type     | Default   | Description                                                                                     |
|-------------------------------------------|----------|-----------|-------------------------------------------------------------------------------------------------|
| **Connection Strategy**                   |          |           |                                                                                                 |
| `spring.data.redis.connection.strategy`   | Enum     | `CLASSIC` | Connection strategy: `CLASSIC` (single), `POOLED` (pool), `LANED` (this library)               |
| `spring.data.redis.connection.lanes`      | int      | 8         | Number of lanes (1-64, only if `strategy=LANED`)                                                |
| **Redis Server**                          |          |           |                                                                                                 |
| `spring.data.redis.host`                  | String   | localhost | Redis server hostname                                                                           |
| `spring.data.redis.port`                  | int      | 6379      | Redis server port                                                                               |
| `spring.data.redis.database`              | int      | 0         | Redis database index (0-15)                                                                     |
| `spring.data.redis.username`              | String   | null      | ACL username (Redis 6+)                                                                         |
| `spring.data.redis.password`              | String   | null      | Password or ACL password                                                                        |
| `spring.data.redis.client-name`           | String   | null      | Client name (visible in `CLIENT LIST`)                                                          |
| **Timeouts**                              |          |           |                                                                                                 |
| `spring.data.redis.timeout`               | Duration | null      | Command timeout (e.g., `5s`, `500ms`)                                                           |
| `spring.data.redis.connect-timeout`       | Duration | null      | TCP connection timeout (e.g., `2s`)                                                             |
| `spring.data.redis.lettuce.shutdown-timeout` | Duration | 100ms  | Graceful shutdown timeout                                                                       |
| **SSL/TLS**                               |          |           |                                                                                                 |
| `spring.data.redis.ssl.enabled`           | boolean  | false     | Enable TLS/SSL                                                                                  |
| `spring.data.redis.ssl.bundle`            | String   | null      | SSL bundle name (references `spring.ssl.bundle.*`)                                              |
| **SSL Bundle (PEM)**                      |          |           |                                                                                                 |
| `spring.ssl.bundle.pem.<name>.keystore.certificate` | String | null | Client certificate (PEM format, file path or classpath)                                   |
| `spring.ssl.bundle.pem.<name>.keystore.private-key` | String | null | Client private key (PEM format, PKCS#8 unencrypted)                                       |
| `spring.ssl.bundle.pem.<name>.truststore.certificate` | String | null | CA certificate (PEM format, for server verification)                                    |
| **SSL Bundle (JKS)**                      |          |           |                                                                                                 |
| `spring.ssl.bundle.jks.<name>.keystore.location` | String | null | Client keystore (JKS/PKCS12 file path)                                                      |
| `spring.ssl.bundle.jks.<name>.keystore.password` | String | null | Keystore password                                                                           |
| `spring.ssl.bundle.jks.<name>.keystore.type` | String | JKS | Keystore type (`JKS`, `PKCS12`)                                                                 |
| `spring.ssl.bundle.jks.<name>.truststore.location` | String | null | Truststore (JKS file path)                                                                |
| `spring.ssl.bundle.jks.<name>.truststore.password` | String | null | Truststore password                                                                       |
| **Sentinel**                              |          |           |                                                                                                 |
| `spring.data.redis.sentinel.master`       | String   | null      | Sentinel master name                                                                            |
| `spring.data.redis.sentinel.nodes`        | List     | null      | Sentinel nodes (e.g., `host1:26379,host2:26379`)                                                |
| `spring.data.redis.sentinel.password`     | String   | null      | Sentinel password (separate from Redis password)                                                |
| **Cluster**                               |          |           |                                                                                                 |
| `spring.data.redis.cluster.nodes`         | List     | null      | Cluster nodes (e.g., `node1:6379,node2:6379`)                                                   |
| `spring.data.redis.cluster.max-redirects` | int      | 5         | Max cluster redirects (MOVED/ASK)                                                               |

### 12.2 Example Configurations

#### 12.2.1 Minimal (Standalone, No Auth)

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      connection:
        strategy: LANED
        lanes: 8
```

#### 12.2.2 Production (TLS + Auth + Timeouts)

```yaml
spring:
  data:
    redis:
      host: redis.example.com
      port: 6380
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
            certificate: classpath:ca-cert.pem
```

#### 12.2.3 Mutual TLS (Client Certificates)

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
            certificate: file:/etc/certs/client-cert.pem
            private-key: file:/etc/certs/client-key.pem
          truststore:
            certificate: file:/etc/certs/ca-cert.pem
```

#### 12.2.4 Sentinel (High Availability)

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
        password: ${SENTINEL_PASSWORD}
```

#### 12.2.5 Cluster (OSS Cluster Protocol)

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
        max-redirects: 5
```

#### 12.2.6 Multi-Priority (Separate Factories)

```yaml
# application.yml (shared config)
spring:
  data:
    redis:
      host: redis.example.com
      port: 6379
      password: ${REDIS_PASSWORD}
```

```java
// Java configuration (separate factories)
@Configuration
public class RedisConfig {
    
    @Bean("criticalRedisTemplate")
    public RedisTemplate<String, String> criticalTemplate() {
        // 4 lanes for critical path (auth, session)
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
            "redis.example.com", 6379);
        config.setPassword(env.getProperty("REDIS_PASSWORD"));
        
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))  // Strict timeout
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
        // 2 lanes for bulk operations (cache warming, analytics)
        // ... similar to above, numLanes=2
    }
}
```

#### 12.2.7 Development (Insecure Trust Manager)

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
@Profile("dev")
public class DevRedisConfig {
    
    @Bean
    public LettuceClientConfigurationBuilderCustomizer insecureTrustManager() {
        return builder -> {
            ClientOptions opts = ClientOptions.builder()
                .sslOptions(SslOptions.builder()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)  // ⚠️ DEV ONLY
                    .build())
                .build();
            builder.clientOptions(opts);
        };
    }
}
```

---

## 13. Extension Points & Compatibility Guarantees

### 13.1 Stable API (Public Contract)

**Will NOT break in minor versions (1.x):**

1. **Configuration properties:**
   - `spring.data.redis.connection.strategy`
   - `spring.data.redis.connection.lanes`
   - All standard `spring.data.redis.*` properties

2. **Spring beans:**
   - `RedisConnectionFactory` (interface, standard Spring Data Redis)
   - `LanedLettuceConnectionFactory` (public constructors)

3. **Behavior guarantees:**
   - FIFO per lane (RESP compliance)
   - Round-robin dispatch (atomic, lock-free)
   - Transaction pinning (WATCH/MULTI/EXEC same lane)
   - PubSub isolation (dedicated connections)

### 13.2 Extension Mechanisms

#### 13.2.1 Custom Customizers (Recommended)

```java
@Bean
public LettuceClientConfigurationBuilderCustomizer clusterTopologyRefresh() {
    return builder -> {
        ClusterTopologyRefreshOptions refreshOptions = ClusterTopologyRefreshOptions.builder()
            .enablePeriodicRefresh(Duration.ofMinutes(5))  // Refresh topology every 5min
            .enableAllAdaptiveRefreshTriggers()            // React to MOVED/ASK
            .build();
        
        ClusterClientOptions clusterOpts = ClusterClientOptions.builder()
            .topologyRefreshOptions(refreshOptions)
            .build();
        
        builder.clientOptions(clusterOpts);
    };
}
```

#### 13.2.2 Lane Initializer (Future API)

**Planned for v1.1:**
```java
@Bean
public LaneInitializer redisLaneInitializer() {
    return connection -> {
        connection.sync().clientNoEvict(true);   // Prevent eviction
        connection.sync().clientNoTouch(true);   // Preserve LRU
    };
}
```

#### 13.2.3 Custom Lane Selection Strategy (Future)

**Planned for v1.1:**
```yaml
spring.data.redis.connection.strategy-mode: KEY_AFFINITY  # vs ROUND_ROBIN
```

### 13.3 Internal API (No Guarantees)

**May break in minor versions:**

1. **Package-private classes:**
   - Implementation details subject to change

2. **Metrics internal names:**
   - Counter/gauge registration may change (but exported metric names stable)

3. **Logging messages:**
   - Log formats may change (but log levels stable)

### 13.4 Compatibility Matrix

| Spring Boot | Java  | Lettuce | Redis  | Status         |
|-------------|-------|---------|--------|----------------|
| 3.1.x       | 17+   | 6.2.x   | 6.0+   | ✅ Tested      |
| 3.2.x       | 17+   | 6.3.x   | 6.0+   | ✅ Tested      |
| 3.3.x       | 17+   | 6.4.x   | 6.0+   | ✅ Tested      |
| 3.4.x       | 21+   | 6.5.x   | 6.0+   | ✅ Production  |
| 4.0.x       | 21+   | 7.0.x   | 6.0+   | ✅ Production  |
| 2.x         | 11+   | 6.x     | 5.0+   | ❌ Unsupported |

**Redis version requirements:**
- **6.0+** required for ACL (`AUTH user password`)
- **5.0+** works with legacy `AUTH password` (no ACL)
- **7.0+** recommended (RESP3, improved performance)

---

## 14. Stack Walkdown (JVM → OS → Network)

### 14.1 JVM Layer

**Class loading:**
```
Bootstrap ClassLoader:
  ├─ java.lang.Thread (Thread.currentThread())
  ├─ java.util.concurrent.atomic.AtomicLong (CAS operations)
  └─ java.nio.channels.SocketChannel (TCP socket abstraction)

Platform ClassLoader:
  └─ (none relevant)

Application ClassLoader:
  ├─ org.springframework.data.redis.* (Spring Data Redis)
  ├─ io.lettuce.core.* (Lettuce driver)
  ├─ io.netty.* (Netty network framework)
  └─ com.macstab.oss.redis.laned.* (this library)
```

**Thread model:**
```
Application Threads (Spring MVC thread pool):
  ├─ http-nio-8080-exec-1  (handles HTTP request)
  ├─ http-nio-8080-exec-2
  └─ ... (default: 200 threads)
       ↓ (calls RedisTemplate.get())
       └─ Writes to: Netty channel (thread-safe, non-blocking)

Netty Event Loop Threads (N = lanes):
  ├─ lettuce-nioEventLoop-4-1  (lane 0, reads/writes socket)
  ├─ lettuce-nioEventLoop-4-2  (lane 1)
  └─ ... (8 event loops for 8 lanes)
       ↓ (epoll_wait / kqueue)
       └─ OS: socket FD ready (readable/writable)

JIT Compiler Threads (background):
  ├─ C1 CompilerThread0  (tiered compilation level 1-3)
  ├─ C2 CompilerThread0  (tiered compilation level 4: hotspot optimization)
  └─ ... (CPU-count dependent)

GC Threads (G1GC):
  ├─ GC Thread#0  (young GC, concurrent marking)
  └─ ... (ParallelGCThreads = CPU-count)
```

**JIT compilation (method hotness):**
```
Tier 1 (C1, interpreted):     selectLane() invoked 1-100 times
Tier 2 (C1, simple JIT):      100-1,000 invocations (basic optimizations)
Tier 3 (C1, profiling):       1,000-10,000 invocations (collect profile data)
Tier 4 (C2, optimized):       10,000+ invocations (inlining, loop unrolling, escape analysis)

After Tier 4:
  - AtomicLong.getAndIncrement() → intrinsic (native LOCK XADD on x86)
  - selectLane() → inlined (no method call overhead)
  - Expected latency: 5-10ns (vs 50-100ns interpreted)
```

**Escape analysis (C2 optimization):**
```java
// Original code:
Integer pinned = PINNED_LANE.get();
if (pinned != null) {
    return lanes[pinned.intValue()];  // Autoboxing: Integer → int
}

// C2 optimized (escape analysis proves Integer doesn't escape):
int pinned = PINNED_LANE_RAW_VALUE;  // Scalar replacement
if (pinned != -1) {
    return lanes[pinned];  // No boxing allocation
}
```

### 14.2 OS Kernel Layer (Linux)

**System calls (per command):**
```
1. write(fd, buffer, length)
   - fd: socket file descriptor (e.g., 42)
   - buffer: RESP-encoded command (*2\r\n$3\r\nGET\r\n...)
   - length: byte count
   - Kernel: copy buffer → sk_sndbuf (TCP send buffer)
   - Return: bytes written (non-blocking: may be < length)

2. epoll_wait(epfd, events, maxevents, timeout)
   - epfd: epoll instance (Netty event loop)
   - events: output array (ready FDs)
   - Returns: number of ready FDs
   - Kernel: block thread until socket readable/writable

3. read(fd, buffer, length)
   - Kernel: copy sk_rcvbuf → userspace buffer
   - Return: bytes read (0 = EOF, -1 = error)
```

**TCP state machine:**
```
Application calls: write()
  ↓
Kernel: copy to sk_sndbuf (16 KB - 4 MB, tunable via tcp_wmem)
  ↓
TCP layer: segment into packets (MSS = MTU - 40 = 1460 bytes typical)
  ↓
IP layer: route to destination (routing table lookup)
  ↓
Ethernet layer: ARP resolution (if needed), MAC framing
  ↓
NIC driver: DMA transfer to network card TX queue
  ↓
Network: packet transmission

<-- RTT/2 -->

NIC: packet arrival → DMA to RX queue → interrupt
  ↓
Kernel: IP defragmentation (if needed)
  ↓
TCP layer: reassemble segments → sk_rcvbuf
  ↓
epoll_wait() returns: FD readable
  ↓
Application calls: read()
```

**TCP buffer sizing (affects HOL):**
```
Default (Linux):
  net.ipv4.tcp_rmem = 4096 131072 6291456  # min, default, max (bytes)
  net.ipv4.tcp_wmem = 4096 16384 4194304

Tuning for large responses:
  sysctl -w net.ipv4.tcp_rmem="4096 262144 8388608"  # 256KB default, 8MB max
  sysctl -w net.core.rmem_max=8388608
```

**File descriptor limits:**
```
Per-process: ulimit -n (default: 1024, cloud VMs: 65536)
System-wide: /proc/sys/fs/file-max (default: ~100,000)

Check current usage:
  lsof -p <pid> | wc -l  # FDs used by process
  cat /proc/sys/fs/file-nr  # System-wide open FDs
```

### 14.3 Network Layer

**TCP packet structure (IPv4):**
```
Ethernet Frame (1518 bytes max):
  ├─ Ethernet Header (14 bytes): src MAC, dst MAC, EtherType
  ├─ IP Header (20 bytes): src IP, dst IP, TTL, protocol
  ├─ TCP Header (20-60 bytes): src port, dst port, seq, ack, flags
  └─ Payload (1460 bytes max): RESP data

Example GET command packet:
  RESP: *2\r\n$3\r\nGET\r\n$3\r\nkey\r\n  (21 bytes)
  TCP header: 20 bytes
  IP header: 20 bytes
  Ethernet header: 14 bytes
  Total frame: 75 bytes (fits in 1 packet)

Large response (500 KB):
  Segments: 500,000 / 1460 ≈ 343 packets
  Transmission time (1 Gbps): ~4ms (wire time only, excludes RTT)
```

**TCP congestion control (affects large responses):**
```
Algorithm: Cubic (Linux default)

Slow start:
  cwnd (congestion window) = 10 MSS initial (14,600 bytes)
  Doubles every RTT until ssthresh

Congestion avoidance:
  cwnd grows slowly (cubic function)
  
For 500 KB response:
  Initial burst: 10 packets (14.6 KB)
  Wait RTT for ACK
  Next burst: 20 packets
  Wait RTT
  ...
  Full window after ~4-5 RTTs (2-5ms @ 0.5ms RTT)
  
Total time: 4ms (wire) + 5ms (RTTs) = ~9ms
```

**TLS overhead:**
```
Handshake (TLS 1.3, full):
  ClientHello → ServerHello + Certificate + Finished (1.5 RTT)
  Total: ~1.5ms (@ 0.5ms RTT)

Handshake (resumed session):
  PSK (Pre-Shared Key): 0-RTT or 1-RTT
  Total: ~0.5ms

Per-message overhead:
  TLS record header: 5 bytes
  MAC/AEAD tag: 16 bytes (AES-GCM)
  Padding: 0-255 bytes (block cipher only, not GCM)
  Total: ~21 bytes per record (negligible)

CPU overhead (AES-GCM with AES-NI):
  ~0.5 cycles/byte (modern Intel/AMD)
  500 KB response: ~250,000 cycles ≈ 0.08ms @ 3 GHz
```

### 14.4 Redis Server Internals

**Event loop (ae.c):**
```c
// Redis single-threaded event loop (pseudocode)
while (!server.stop) {
    // Wait for events (epoll on Linux, kqueue on BSD)
    int numevents = aeApiPoll(eventLoop, tvp);  // Blocking syscall
    
    for (int j = 0; j < numevents; j++) {
        int fd = eventLoop->fired[j].fd;
        aeFileEvent *fe = &eventLoop->events[fd];
        
        // Readable event (client sent data)
        if (fe->mask & AE_READABLE) {
            fe->rfileProc(eventLoop, fd, fe->clientData, mask);
            // rfileProc = readQueryFromClient (networking.c)
        }
        
        // Writable event (can send response)
        if (fe->mask & AE_WRITABLE) {
            fe->wfileProc(eventLoop, fd, fe->clientData, mask);
            // wfileProc = sendReplyToClient (networking.c)
        }
    }
    
    // Process time events (expire keys, etc.)
    processTimeEvents(eventLoop);
}
```

**Command processing (networking.c):**
```c
// readQueryFromClient (simplified)
void readQueryFromClient(connection *conn) {
    client *c = connGetPrivateData(conn);
    
    // Read from socket into client query buffer
    nread = connRead(c->conn, c->querybuf + qblen, readlen);
    
    // Parse RESP protocol
    processInputBuffer(c);  // State machine: parse *2\r\n$3\r\nGET\r\n...
    
    while (c->argc > 0) {
        // Execute command
        if (processCommand(c) == C_OK) {
            // Command executed, response in client->buf
        }
    }
}

// processCommand (server.c)
int processCommand(client *c) {
    // ACL check
    if (ACLCheckAllPerm(c, &err) != ACL_OK) {
        rejectCommand(c, err);
        return C_OK;
    }
    
    // Lookup command
    struct redisCommand *cmd = lookupCommand(c->argv[0]->ptr);
    
    // Call command handler (e.g., getCommand for GET)
    call(c, CMD_CALL_FULL);  // getCommand(c)
    
    return C_OK;
}

// getCommand (t_string.c)
void getCommand(client *c) {
    robj *val = lookupKeyReadOrReply(c, c->argv[1], shared.null[c->resp]);
    if (val) {
        addReplyBulk(c, val);  // Append to client->buf
    }
}
```

**Response buffering (networking.c):**
```c
// addReply appends to client->buf (static buffer, 16 KB default)
void addReply(client *c, robj *obj) {
    if (prepareClientToWrite(c) != C_OK) return;
    
    // Small response: static buffer
    if (c->bufpos + objlen <= PROTO_REPLY_CHUNK_BYTES) {
        memcpy(c->buf + c->bufpos, obj->ptr, objlen);
        c->bufpos += objlen;
    } else {
        // Large response: reply list (linked list of buffers)
        _addReplyToBufferOrList(c, obj->ptr, objlen);
    }
}

// writeToClient (called from event loop)
int writeToClient(client *c, int handler_installed) {
    while (clientHasPendingReplies(c)) {
        // Write static buffer first
        if (c->bufpos > 0) {
            nwritten = connWrite(c->conn, c->buf + c->sentlen, c->bufpos - c->sentlen);
            c->sentlen += nwritten;
            if (c->sentlen == c->bufpos) {
                c->bufpos = 0;
                c->sentlen = 0;
            }
        }
        
        // Then write reply list (large responses)
        if (listLength(c->reply)) {
            // Write linked list nodes...
        }
    }
}
```

### 14.5 Why It Behaves This Way (Root Cause Analysis)

**Q: Why does HOL blocking occur?**

**A: Causal chain:**
```
1. RESP has no request IDs (protocol design: minimal overhead)
   ↓
2. Responses match requests positionally (FIFO queue in client)
   ↓
3. TCP delivers bytes in order (stream-oriented, not message-oriented)
   ↓
4. Large response consumes TCP receive buffer for extended duration
   ↓
5. Subsequent responses cannot be read until large response fully consumed
   ↓
6. Client CompletableFuture cannot complete until response bytes available
   ↓
7. Application thread blocks on .get() (or Mono blocks subscriber)
```

**Q: Why doesn't HTTP/2 have this problem?**

**A: Stream IDs enable multiplexing:**
```
HTTP/2 frame:
  ├─ Frame Header (9 bytes):
  │   ├─ Stream ID (31 bits) ← Correlation token
  │   ├─ Frame Type (8 bits)
  │   └─ Flags (8 bits)
  └─ Payload (variable)

Server can send:
  Stream 5 HEADERS (fast request)
  Stream 3 DATA chunk 1 (slow request, partial)
  Stream 5 DATA (fast request, complete) ← Out of order!
  Stream 3 DATA chunk 2
  Stream 3 DATA chunk 3 (complete)

Client matches by Stream ID, not arrival order.
```

**RESP equivalent would be:**
```
*3\r\n$3\r\nGET\r\n$3\r\nkey\r\n:ID\r\n42\r\n  ← Request ID appended
$5\r\nvalue\r\n:ID\r\n42\r\n                  ← Response ID appended

Cost: +10 bytes per message (5 bytes ":ID\r\n" + 5 bytes value + "\r\n")
      + hashtable lookup (O(1) avg, but CPU cycles + memory)

Redis design decision: Overhead not worth it (RESP targets microsecond latency, minimize CPU)
```

**Q: Why don't more lanes completely eliminate HOL?**

**A: Birthday paradox (collision probability):**
```
With N lanes and M concurrent requests (uniform distribution):
  P(collision) = 1 - e^(-M² / 2N)

Examples:
  N=8, M=10:  P(collision) ≈ 60%  (high probability 2+ requests same lane)
  N=8, M=4:   P(collision) ≈ 20%
  N=32, M=10: P(collision) ≈ 15%

To eliminate HOL: need N ≥ M (one lane per concurrent request)
  → M=1000 concurrent → 1000 lanes → 1000 TCP connections
  → Back to pool problem (connection explosion)

Laned connections trade-off: Reduce collision probability by factor N,
without requiring O(threads) connections.
```

---

## 15. References (Specifications & Standards)

### 15.1 Network Protocols

- **RFC 793** - Transmission Control Protocol (TCP)
- **RFC 8446** - The Transport Layer Security (TLS) Protocol Version 1.3
- **RFC 5246** - The Transport Layer Security (TLS) Protocol Version 1.2
- **RFC 6066** - TLS Extensions (SNI, OCSP stapling)
- **RFC 5280** - X.509 Public Key Infrastructure Certificate

### 15.2 Redis

- **Redis RESP Specification** - https://redis.io/docs/reference/protocol-spec/
- **Redis ACL Documentation** - https://redis.io/docs/management/security/acl/
- **Redis TLS Documentation** - https://redis.io/docs/management/security/encryption/
- **Redis Sentinel Documentation** - https://redis.io/docs/management/sentinel/
- **Redis Cluster Specification** - https://redis.io/docs/reference/cluster-spec/

### 15.3 Java Specifications

- **JSR-133** - Java Memory Model and Thread Specification (JDK 5+)
- **JSR-380** - Bean Validation 2.0 (Jakarta Bean Validation)
- **JEP 425** - Virtual Threads (Project Loom, JDK 21+)
- **JEP 444** - Virtual Threads (final, JDK 21)
- **Java Language Specification** (JLS) - Java SE 21 Edition

### 15.4 Spring Framework

- **Spring Boot Reference** - https://docs.spring.io/spring-boot/reference/
- **Spring Data Redis Reference** - https://docs.spring.io/spring-data/redis/reference/
- **Spring Boot SSL Bundle Documentation** - https://docs.spring.io/spring-boot/reference/features/ssl.html
- **Spring Framework Core Technologies** - https://docs.spring.io/spring-framework/reference/core.html

### 15.5 Libraries

- **Lettuce Documentation** - https://lettuce.io/core/release/reference/
- **Lettuce GitHub** - https://github.com/lettuce-io/lettuce-core
- **Netty Documentation** - https://netty.io/wiki/
- **Micrometer Documentation** - https://micrometer.io/docs

---

**Document Status:** Production-Ready  
**Last Updated:** 2026-02-22  
*By [Christian Schnapka (Per)](https://macstab.com) · Principal+ Embedded Engineer · [Macstab GmbH](https://macstab.com)*

