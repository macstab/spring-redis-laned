/* (C)2026 Macstab GmbH */

/**
 * Pure Lettuce core library for laned Redis connections (NO Spring dependencies).
 *
 * <h2>Purpose</h2>
 *
 * <p>Provides N fixed multiplexed Redis connections (lanes) to mitigate head-of-line (HOL) blocking
 * in RESP protocol. Framework-independent - works with raw Lettuce {@code RedisClient}, no Spring
 * required.
 *
 * <h2>Core Problem: RESP Head-of-Line Blocking</h2>
 *
 * <p>Redis RESP protocol (RESP2/RESP3) has NO request IDs. Response matching is positional (FIFO
 * queue). Example:
 *
 * <pre>{@code
 * Client sends:
 * → GET fast-key (responds in 0.1ms)
 * → HGETALL slow-key (responds in 50ms - 500KB payload)
 * → GET another-fast-key (responds in 0.1ms)
 *
 * Server responses arrive in FIFO order:
 * ← "value-1" (0.1ms later)
 * ← {...500KB...} (50ms later) ← BLOCKS until fully read from TCP socket
 * ← "value-2" (50.1ms later) ← DELAYED by 50ms waiting for HGETALL
 * }</pre>
 *
 * <p>Lettuce {@code CommandHandler.decode()} in {@code io.lettuce.core.protocol.CommandHandler}
 * reads sequentially from Netty {@code ByteBuf}. Cannot skip incomplete responses - TCP byte stream
 * does not allow it.
 *
 * <p><strong>Result:</strong> One slow command blocks ALL subsequent responses until fully
 * received.
 *
 * <h2>Solution: N Independent Lanes</h2>
 *
 * <p>N lanes = N separate TCP connections, each with isolated {@code CommandHandler.stack}.
 * Commands distributed across lanes via configurable {@link
 * com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy}:
 *
 * <ul>
 *   <li><strong>Round-robin:</strong> {@code (counter++ % N)} - uniform distribution, 1/N blocking
 *       probability
 *   <li><strong>Thread affinity:</strong> {@code hash(threadId) % N} - same thread → same lane
 *       (transaction safety)
 *   <li><strong>Least-used:</strong> Route to emptiest queue (future: adaptive load balancing)
 * </ul>
 *
 * <p><strong>Mathematics:</strong> P(blocked | N lanes) ≈ P(blocked | single) / N. With N=8: 87.5%
 * HOL reduction.
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * ┌────────────────────────────────────────────────────────┐
 * │ Application Code (multiple threads)                    │
 * └────────────────┬───────────────────────────────────────┘
 *                  ↓
 * ┌────────────────────────────────────────────────────────┐
 * │ LanedConnectionManager                                 │
 * │   ├─→ LaneSelectionStrategy (round-robin/affinity)     │
 * │   │     selectLane() → lane index                      │
 * │   ├─→ ConnectionLane[0]                                │
 * │   │     └─→ StatefulRedisConnection (Netty Channel 0)  │
 * │   ├─→ ConnectionLane[1]                                │
 * │   │     └─→ StatefulRedisConnection (Netty Channel 1)  │
 * │   └─→ ConnectionLane[N-1]                              │
 * │         └─→ StatefulRedisConnection (Netty Channel N-1)│
 * └────────────────┬───────────────────────────────────────┘
 *                  ↓ (N independent TCP connections)
 * ┌────────────────────────────────────────────────────────┐
 * │ Redis Server (single-threaded event loop)              │
 * │   ├─→ ae.c (epoll_wait → readQueryFromClient)          │
 * │   └─→ N client connections (N isolated FIFO queues)    │
 * └────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Key Components</h2>
 *
 * <dl>
 *   <dt>{@link com.macstab.oss.redis.laned.LanedConnectionManager}
 *   <dd>Main entry point. Creates N lanes, delegates to strategy for selection. Thread-safe.
 *   <dt>{@link com.macstab.oss.redis.laned.ConnectionLane}
 *   <dd>Single lane wrapper. Holds {@code StatefulRedisConnection} + in-flight count tracking.
 *   <dt>{@link com.macstab.oss.redis.laned.LanedConnectionWrapper}
 *   <dd>Wrapper that delegates to lane connection. Tracks {@code close()} for usage accounting.
 *   <dt>{@link com.macstab.oss.redis.laned.PubSubConnectionTracker}
 *   <dd>Manages dedicated Pub/Sub connections (separate from command lanes).
 *   <dt>{@link com.macstab.oss.redis.laned.strategy} package
 *   <dd>Lane selection strategies: {@code RoundRobinStrategy}, {@code ThreadAffinityStrategy},
 *       {@code LeastUsedStrategy}.
 * </dl>
 *
 * <h2>Usage Example (Pure Lettuce)</h2>
 *
 * <pre>{@code
 * // Create client
 * RedisClient client = RedisClient.create("redis://localhost:6379");
 *
 * // Create manager with 8 lanes (round-robin)
 * LanedConnectionManager manager = new LanedConnectionManager(
 *     client,
 *     StringCodec.UTF8,
 *     8  // number of lanes
 * );
 *
 * // Get connection (round-robin selection)
 * StatefulRedisConnection<String, String> conn = manager.getConnection();
 * conn.sync().set("key", "value");
 *
 * // Cleanup
 * manager.destroy();
 * client.shutdown();
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All classes in this package are thread-safe unless explicitly documented otherwise:
 *
 * <ul>
 *   <li>{@code LanedConnectionManager}: Thread-safe. Multiple threads can call {@code
 *       getConnection()} concurrently.
 *   <li>{@code ConnectionLane}: Immutable after construction (final fields). Thread-safe.
 *   <li>{@code LanedConnectionWrapper}: Thread-safe. Delegates to thread-safe {@code
 *       StatefulRedisConnection}.
 *   <li>{@code PubSubConnectionTracker}: Thread-safe. Uses {@code CopyOnWriteArrayList} for
 *       connections.
 *   <li>Strategy implementations: Must be thread-safe (contract enforced by interface).
 * </ul>
 *
 * <h2>Performance</h2>
 *
 * <p><strong>Lane selection overhead:</strong>
 *
 * <ul>
 *   <li>{@code RoundRobinStrategy}: ~10-20ns (AtomicLong increment + modulo)
 *   <li>{@code ThreadAffinityStrategy}: ~30-50ns (MurmurHash3 + modulo)
 *   <li>{@code LeastUsedStrategy}: ~100-200ns (AtomicIntegerArray scan)
 * </ul>
 *
 * <p><strong>Memory overhead per lane:</strong>
 *
 * <ul>
 *   <li>TCP connection: ~200KB (send buffer + receive buffer)
 *   <li>Netty buffers: ~16KB (pooled ByteBuf allocator)
 *   <li>Lettuce objects: ~1-2KB (CommandHandler, stack, futures)
 *   <li><strong>Total: ~220KB per lane</strong>
 * </ul>
 *
 * <p>With 8 lanes: ~1.8MB per {@code LanedConnectionManager} instance.
 *
 * <h2>Trade-offs</h2>
 *
 * <table>
 *   <caption>Lane Strategy Comparison</caption>
 *   <thead>
 *     <tr>
 *       <th>Metric</th>
 *       <th>Single Connection</th>
 *       <th>Connection Pool</th>
 *       <th>Laned (N=8)</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>HOL Blocking</td>
 *       <td>100%</td>
 *       <td>0% (isolated)</td>
 *       <td>~12.5% (1/N)</td>
 *     </tr>
 *     <tr>
 *       <td>Connections (30 pods)</td>
 *       <td>30</td>
 *       <td>1,500 (50-pool)</td>
 *       <td>240 (8 lanes)</td>
 *     </tr>
 *     <tr>
 *       <td>Memory per pod</td>
 *       <td>~200KB</td>
 *       <td>~10MB</td>
 *       <td>~1.8MB</td>
 *     </tr>
 *     <tr>
 *       <td>Selection latency</td>
 *       <td>0ns (no selection)</td>
 *       <td>~1-10μs (borrow)</td>
 *       <td>~10-50ns (CAS)</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <h2>Design Decisions</h2>
 *
 * <p><strong>Why fixed lanes (not dynamic pool):</strong>
 *
 * <ul>
 *   <li>Fixed lanes: Predictable connection count (N × pods)
 *   <li>Dynamic pool: Unpredictable (min-idle to max-active, thundering herd on shard failure)
 *   <li>Fixed lanes: Zero borrow contention (lock-free CAS selection)
 *   <li>Dynamic pool: Borrow contention (synchronized block in {@code
 *       GenericObjectPool.borrowObject()})
 * </ul>
 *
 * <p><strong>Why round-robin default (not least-used):</strong>
 *
 * <ul>
 *   <li>Round-robin: 10-20ns overhead (single AtomicLong increment)
 *   <li>Least-used: 100-200ns overhead (scan AtomicIntegerArray[N])
 *   <li>Round-robin sufficient for most workloads (probabilistic HOL reduction works)
 * </ul>
 *
 * <p><strong>Why ThreadLocal for transaction pinning:</strong>
 *
 * <ul>
 *   <li>Redis {@code WATCH/MULTI/EXEC} state stored per-connection (Redis {@code client->mstate})
 *   <li>Same thread must stay on same lane from {@code WATCH} through {@code EXEC/DISCARD}
 *   <li>ThreadLocal: {@code Thread.currentThread()} → stable lane assignment (imperative code)
 *   <li><strong>Limitation:</strong> Reactive (Project Reactor) suspends/resumes on different
 *       threads → ThreadLocal doesn't propagate → NOT SUPPORTED
 * </ul>
 *
 * <h2>Related Documentation</h2>
 *
 * <ul>
 *   <li><a href="https://redis.io/docs/reference/protocol-spec/">RESP Protocol Specification</a>
 *   <li><a href="https://netty.io/4.1/api/io/netty/channel/Channel.html">Netty Channel API</a>
 *   <li><a href="https://github.com/lettuce-io/lettuce-core">Lettuce Core GitHub</a>
 * </ul>
 *
 * @since 1.0.0
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.oss.redis.laned.LanedConnectionManager
 * @see com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy
 */
package com.macstab.oss.redis.laned;
