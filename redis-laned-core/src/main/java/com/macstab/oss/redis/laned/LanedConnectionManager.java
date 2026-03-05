/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import java.util.Optional;

import com.macstab.oss.redis.laned.connection.SentinelTopology;
import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Pure Lettuce connection manager with N fixed multiplexed lanes.
 *
 * <p><strong>RESP protocol constraint (why HOL blocking is fundamental):</strong>
 *
 * <p>RESP has no request IDs. Response matching is positional (FIFO queue). A 500KB {@code HGETALL}
 * response blocks all subsequent responses until fully read from TCP socket. Lettuce's {@code
 * CommandHandler.decode()} in {@code io.lettuce.core.protocol.CommandHandler.java} reads
 * sequentially from Netty {@code ByteBuf} and CANNOT skip incomplete responses (missing bytes). TCP
 * stream does not allow it.
 *
 * <p>RESP3 does NOT change this. No request IDs added. Design philosophy: minimal overhead (8-12B
 * per command) vs HTTP/2 (30-50B). Trade-off: FIFO mandatory, server must be fast enough that HOL
 * is rare.
 *
 * <p>N lanes: slow command on lane K blocks only 1/N probability (strategy-dependent). N=8 with
 * round-robin → 87.5% HOL reduction.
 *
 * <p>Per's note: I spent 3 days profiling this at Macstab before I understood the root cause.
 * Everyone assumes it's Redis being slow, or the network, or Spring's pooling. But the profiler
 * showed threads parked in CompletableFuture.get() while Redis reported 3% CPU. The smoking gun was
 * CommandHandler.stack - 200+ commands queued behind a single slow HGETALL. Once you see it, the
 * fix is obvious: more connections. But not a pool—fixed lanes.
 *
 * <p><strong>JMM guarantees (final field publication):</strong>
 *
 * <p>{@code lanes} array: {@code final} → happens-before edge (JLS §17.5). Array elements assigned
 * once during {@code initializeLanes()} before constructor returns. All threads see fully
 * initialized array without {@code volatile} or barriers. CPU cache coherence (MESI) ensures cache
 * lines invalidated in other cores' L1/L2 when constructor writes.
 *
 * <p>{@code destroyed} flag: {@code volatile} required (mutates after construction). Without it:
 * Thread A writes {@code destroyed = true}, Thread B reads from stale L1 cache (sees {@code
 * false}). {@code volatile} forces cache flush + MFENCE (x86_64) or DMB (ARM).
 *
 * @see <a href="https://redis.io/docs/reference/protocol-spec/">RESP Protocol</a>
 * @see <a href="https://docs.oracle.com/javase/specs/jls/se17/html/jls-17.html#jls-17.4.5">JLS
 *     §17.4.5</a>
 */
@Slf4j
public final class LanedConnectionManager {

  private final RedisClient client;
  private final RedisCodec<?, ?> codec;
  @Getter private final int numLanes;
  final ConnectionLane[] lanes; // Package-private for testing
  private final LaneSelectionStrategy strategy;
  private final PubSubConnectionTracker pubSubTracker;
  private final LanedRedisMetrics metrics;
  private final String connectionName;
  private final Optional<ReadFrom> readFrom;
  private final Optional<SentinelTopology> sentinelTopology;
  private volatile boolean destroyed;

  /**
   * Creates manager with default round-robin strategy.
   *
   * @param client Redis client (must not be null)
   * @param codec codec for encoding/decoding (must not be null)
   * @param numLanes number of lanes (must be &gt;= 1, recommended: 8)
   */
  public LanedConnectionManager(
      @NonNull final RedisClient client,
      @NonNull final RedisCodec<?, ?> codec,
      final int numLanes) {
    this(
        client,
        codec,
        numLanes,
        new RoundRobinStrategy(),
        Optional.empty(),
        "default",
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Creates manager with custom selection strategy (no metrics).
   *
   * <p><strong>Strategy injection (dependency inversion principle):</strong>
   *
   * <p>Lane selection is abstracted via {@link LaneSelectionStrategy} interface. This enables:
   *
   * <ul>
   *   <li>Runtime strategy swap (future enhancement: dynamic strategy based on load)
   *   <li>Custom strategies without modifying core (closed for modification, open for extension)
   *   <li>Testing with deterministic strategies (fixed lane for reproducibility)
   * </ul>
   *
   * <p>Strategy is invoked on EVERY {@code getConnection()} call (hot path). Performance
   * requirement: &lt;50ns (target) or &lt;500ns (acceptable). Slower strategies bottleneck Redis
   * command throughput.
   *
   * @param client Redis client (must not be null)
   * @param codec codec for encoding/decoding (must not be null)
   * @param numLanes number of lanes (must be &gt;= 1, recommended: 8)
   * @param strategy lane selection strategy (must not be null)
   */
  public LanedConnectionManager(
      @NonNull final RedisClient client,
      @NonNull final RedisCodec<?, ?> codec,
      final int numLanes,
      @NonNull final LaneSelectionStrategy strategy) {
    this(
        client,
        codec,
        numLanes,
        strategy,
        Optional.empty(),
        "default",
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Creates manager with custom selection strategy and metrics.
   *
   * <p><strong>Metrics integration (optional dependency):</strong>
   *
   * <p>Metrics track lane selection distribution, in-flight operations per lane, and HOL blocking
   * reduction. Used for Grafana dashboards to visualize lane load and prove HOL blocking
   * improvement.
   *
   * <p>If metrics not provided, {@code LanedRedisMetrics.NOOP} singleton used (zero overhead).
   *
   * @param client Redis client (must not be null)
   * @param codec codec for encoding/decoding (must not be null)
   * @param numLanes number of lanes (must be &gt;= 1, recommended: 8)
   * @param strategy lane selection strategy (must not be null)
   * @param metrics metrics collector (optional, defaults to NOOP if not present)
   */
  public LanedConnectionManager(
      @NonNull final RedisClient client,
      @NonNull final RedisCodec<?, ?> codec,
      final int numLanes,
      @NonNull final LaneSelectionStrategy strategy,
      @NonNull final Optional<LanedRedisMetrics> metrics) {
    this(client, codec, numLanes, strategy, metrics, "default", Optional.empty(), Optional.empty());
  }

  /**
   * Creates manager with custom selection strategy, metrics, and connection name.
   *
   * <p><strong>Connection name (dimensional metrics):</strong>
   *
   * <p>Distinguishes multiple Redis connections in Grafana (primary, cache, session). Enables
   * queries like: {@code redis_lettuce_laned_lane_in_flight{connection_name="primary"}}.
   *
   * @param client Redis client (must not be null)
   * @param codec codec for encoding/decoding (must not be null)
   * @param numLanes number of lanes (must be &gt;= 1, recommended: 8)
   * @param strategy lane selection strategy (must not be null)
   * @param metrics metrics collector (optional, defaults to NOOP if not present)
   * @param connectionName connection name for dimensional metrics (default: "default")
   */
  public LanedConnectionManager(
      @NonNull final RedisClient client,
      @NonNull final RedisCodec<?, ?> codec,
      final int numLanes,
      @NonNull final LaneSelectionStrategy strategy,
      @NonNull final Optional<LanedRedisMetrics> metrics,
      final String connectionName) {
    this(
        client,
        codec,
        numLanes,
        strategy,
        metrics,
        connectionName,
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Creates manager with custom selection strategy, metrics, connection name, and Sentinel support.
   *
   * <p><strong>Sentinel failover + read-from-replica routing:</strong>
   *
   * <p>When both {@code sentinelTopology} and {@code readFrom} are provided, uses {@link
   * io.lettuce.core.masterreplica.MasterReplica#connect} for automatic failover and replica read
   * routing.
   *
   * @param client Redis client (must not be null)
   * @param codec codec for encoding/decoding (must not be null)
   * @param numLanes number of lanes (must be &gt;= 1, recommended: 8)
   * @param strategy lane selection strategy (must not be null)
   * @param metrics metrics collector (optional, defaults to NOOP if not present)
   * @param connectionName connection name for dimensional metrics (default: "default")
   * @param readFrom read-from strategy for Sentinel (optional, requires sentinelTopology)
   * @param sentinelTopology Sentinel topology (optional, enables MasterReplica failover)
   */
  public LanedConnectionManager(
      @NonNull final RedisClient client,
      @NonNull final RedisCodec<?, ?> codec,
      final int numLanes,
      @NonNull final LaneSelectionStrategy strategy,
      @NonNull final Optional<LanedRedisMetrics> metrics,
      final String connectionName,
      @NonNull final Optional<ReadFrom> readFrom,
      @NonNull final Optional<SentinelTopology> sentinelTopology) {

    if (numLanes < 1) {
      throw new IllegalArgumentException("numLanes must be >= 1, got: " + numLanes);
    }

    // Warn on potential misconfiguration
    if (readFrom.isPresent() && sentinelTopology.isEmpty()) {
      log.warn(
          "ReadFrom={} configured but no Sentinel topology provided. "
              + "ReadFrom will be ignored (no replicas available). "
              + "This is expected for standalone Redis or Enterprise proxy mode.",
          readFrom.get());
    }

    this.client = client;
    this.codec = codec;
    this.numLanes = numLanes;
    this.lanes = new ConnectionLane[numLanes];
    this.strategy = strategy;
    this.pubSubTracker = new PubSubConnectionTracker(client, codec);
    this.metrics = metrics.orElse(LanedRedisMetrics.NOOP);
    this.connectionName = connectionName != null ? connectionName : "default";
    this.readFrom = readFrom;
    this.sentinelTopology = sentinelTopology;
    this.destroyed = false;

    configureClientOptions();
    initializeLanes();

    // Two-phase initialization: strategy may need lane references (e.g., LeastUsedStrategy)
    strategy.initialize(lanes);

    if (log.isInfoEnabled()) {
      log.info(
          "Created LanedConnectionManager: lanes={}, strategy={}, connection={}, readFrom={}, sentinel={}",
          numLanes,
          strategy.getName(),
          this.connectionName,
          readFrom.map(Object::toString).orElse("none"),
          sentinelTopology.isPresent() ? "enabled" : "none");
    }
  }

  /**
   * Gets connection using configured selection strategy.
   *
   * <p><strong>Lettuce thread safety (Netty internals):</strong>
   *
   * <p>{@code StatefulRedisConnection} → Netty {@code Channel}. {@code DefaultEndpoint.write()}
   * uses {@code SharedLock} (readers-writers, multiple writers allowed). Netty {@code
   * Channel.write()} is thread-safe: {@code MpscLinkedQueue} in {@code ChannelOutboundBuffer}
   * (multi-producer, single-consumer). Event loop thread (single-threaded) dequeues FIFO, flushes
   * to TCP socket.
   *
   * <p>Read side: {@code CommandHandler.decode()} runs exclusively on event loop thread. {@code
   * stack} ({@code ArrayDeque}) is NOT thread-safe, but only event loop thread reads/polls. Write
   * calls ({@code stack.add()}) serialized by Netty's event loop.
   *
   * <p><strong>Hot path optimization (strategy call):</strong>
   *
   * <p>This method is called for EVERY non-Pub/Sub Redis command. The strategy invocation ({@code
   * strategy.selectLane(numLanes)}) is on the hot path. JIT compiler inlines simple strategies
   * (round-robin CAS is ~5-10ns inlined). Complex strategies (e.g., scanning all lanes for
   * least-used) may not inline, adding ~50-500ns overhead.
   *
   * <p>Profiling recommendation: For custom strategies, verify via JMH that {@code getConnection()}
   * completes in &lt;1μs (including strategy selection + array access).
   *
   * @return wrapped connection with lifecycle tracking (close releases wrapper, not underlying
   *     connection)
   */
  public StatefulRedisConnection<?, ?> getConnection() {
    checkNotDestroyed();

    // Strategy selects lane (algorithm-specific logic)
    final var laneIndex = strategy.selectLane(numLanes);

    // Record lane selection (dimensional metrics)
    metrics.recordLaneSelection(connectionName, laneIndex, strategy.getName());

    // Strategy tracks lifecycle (increment in-flight count if needed)
    strategy.onConnectionAcquired(laneIndex);

    // Wrap connection with lifecycle tracking (calls strategy.onConnectionReleased() on close)
    // Wrapper constructor calls lane.recordAcquire() (increments in-flight count + metrics)
    return new LanedConnectionWrapper<>(
        lanes[laneIndex].getConnection(), laneIndex, lanes[laneIndex], strategy);
  }

  /**
   * Dedicated Pub/Sub connection.
   *
   * <p><strong>Why Pub/Sub requires isolation (Redis server source):</strong>
   *
   * <p>From {@code src/pubsub.c}: {@code SUBSCRIBE} sets {@code c->flags |= CLIENT_PUBSUB}. From
   * {@code src/networking.c}: {@code processCommand()} rejects ALL commands except (P|S)SUBSCRIBE,
   * PING, QUIT, RESET when {@code CLIENT_PUBSUB} flag set. Protocol-level constraint enforced by
   * server.
   *
   * <p>RESP push messages (RESP3): {@code >3\r\n$7\r\nmessage\r\n...} (unsolicited, no matching
   * command in FIFO stack). Lettuce's {@code PubSubCommandHandler} intercepts push, dispatches to
   * listeners. Cannot coexist with regular {@code CommandHandler} FIFO matching.
   *
   * @return new Pub/Sub connection (tracked by manager, must be released via {@link
   *     #releaseConnection(StatefulConnection)})
   */
  public StatefulRedisPubSubConnection<?, ?> getPubSubConnection() {
    checkNotDestroyed();
    return pubSubTracker.create();
  }

  /**
   * Releases connection.
   *
   * <p>Lane connections: no-op (long-lived, closed only during {@code destroy()}). Pub/Sub: {@code
   * CopyOnWriteArrayList.remove()} (array copy + volatile write), then {@code close()} (QUIT + TCP
   * FIN + FD release).
   *
   * @param connection connection to release (may be null, may be lane connection or Pub/Sub)
   */
  public void releaseConnection(final StatefulConnection<?, ?> connection) {
    if (connection == null) {
      return;
    }

    if (connection instanceof StatefulRedisPubSubConnection<?, ?> pubSubConn) {
      pubSubTracker.release(pubSubConn);
    }
  }

  /**
   * Returns the number of open lanes (connections with active TCP sockets).
   *
   * <p>Used for health checks and monitoring. A lane is considered open if its underlying Lettuce
   * connection reports {@code isOpen() == true} (Netty channel state != CLOSED).
   *
   * @return count of open lanes (0 to {@code numLanes})
   */
  public int getOpenLaneCount() {
    int count = 0;
    for (final var lane : lanes) {
      if (lane != null && lane.isOpen()) {
        count++;
      }
    }
    return count;
  }

  /**
   * Returns the number of active Pub/Sub connections.
   *
   * <p>Pub/Sub connections are created on-demand via {@link #getPubSubConnection()} and tracked
   * until released via {@link #releaseConnection(StatefulConnection)}. Unlike lane connections
   * (fixed N), Pub/Sub connections grow unbounded if not released.
   *
   * @return count of active Pub/Sub connections (0+)
   */
  public int getPubSubConnectionCount() {
    return pubSubTracker.getConnectionCount();
  }

  /**
   * Closes all connections.
   *
   * <p><strong>Shutdown sequence:</strong> {@code destroyed = true} (volatile write → MFENCE on
   * x86_64, ensures visibility). Close lanes: Lettuce sends {@code QUIT}, Netty closes channel (TCP
   * FIN), event loop deregister (no more epoll), ByteBuf release to {@code PooledByteBufAllocator},
   * FD close (1 per lane). Pub/Sub: iterate {@code CopyOnWriteArrayList} (snapshot-based, safe if
   * concurrent mods), close each, clear list (volatile write of empty array). Metrics: remove all
   * gauges for this connection (prevent memory leak).
   */
  public void destroy() {
    if (destroyed) {
      return;
    }

    destroyed = true;

    try {
      closeLanes();
      pubSubTracker.closeAll();

      // Close metrics (remove all gauges for this connection)
      metrics.close(connectionName);

      if (log.isInfoEnabled()) {
        log.info("Destroyed LanedConnectionManager (connection: {})", connectionName);
      }
    } catch (final Exception e) {
      log.error("Error during LanedConnectionManager destruction", e);
    }
  }

  /**
   * Checks if manager has been destroyed.
   *
   * <p>Once destroyed, all {@code getConnection()} and {@code getPubSubConnection()} calls throw
   * {@code IllegalStateException}. Lanes and Pub/Sub connections are closed, metrics removed.
   *
   * @return {@code true} if {@link #destroy()} has been called, {@code false} otherwise
   */
  public boolean isDestroyed() {
    return destroyed;
  }

  // ==================== Private Methods ====================

  private void checkNotDestroyed() {
    if (destroyed) {
      throw new IllegalStateException("LanedConnectionManager has been destroyed");
    }
  }

  /**
   * Configures Lettuce auto-reconnect.
   *
   * <p>{@code autoReconnect(true)}: {@code ConnectionWatchdog} (exponential backoff: 1ms, 2ms, 4ms,
   * ..., 32s max). {@code REJECT_COMMANDS}: fail-fast (prevents unbounded {@code
   * disconnectedBuffer} growth → OOM).
   */
  private void configureClientOptions() {
    final var existingOptions = client.getOptions();
    final var optionsBuilder =
        existingOptions != null ? existingOptions.mutate() : ClientOptions.builder();

    client.setOptions(
        optionsBuilder
            .autoReconnect(true)
            .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
            .build());
  }

  /**
   * Initializes N lanes.
   *
   * <p><strong>Connection mode:</strong>
   *
   * <ul>
   *   <li>Sentinel + ReadFrom → {@link MasterReplica#connect} with failover + read routing
   *   <li>Otherwise → {@link RedisClient#connect} direct to master
   * </ul>
   *
   * <p>{@code client.connect(codec)}: Netty {@code Bootstrap.connect()} on {@code
   * NioEventLoopGroup}, TCP handshake (SYN/SYN-ACK/ACK = 1.5 RTT), pipeline: {@code
   * ConnectionWatchdog} → {@code CommandEncoder} (RESP serialization) → {@code CommandHandler}
   * (RESP deserialization + FIFO stack). All N connections concurrent (wall-clock ~1.5 RTT, not N ×
   * RTT).
   */
  private void initializeLanes() {
    final boolean useMasterReplica = sentinelTopology.isPresent() && readFrom.isPresent();

    if (useMasterReplica) {
      log.info(
          "Initializing {} lanes with MasterReplica (Sentinel failover + ReadFrom={})",
          numLanes,
          readFrom.get());
    } else {
      log.info("Initializing {} lanes with direct connection (master-only)", numLanes);
    }

    try {
      for (int i = 0; i < numLanes; i++) {
        final var connection = createConnection(i, useMasterReplica);
        lanes[i] = new ConnectionLane(i, connection, metrics, connectionName);
      }
    } catch (final RuntimeException ex) {
      closeLanes();
      throw new IllegalStateException("Failed to initialize lanes", ex);
    }
  }

  /**
   * Creates a single lane connection.
   *
   * @param laneIndex lane index (for logging)
   * @param useMasterReplica whether to use MasterReplica mode
   * @return initialized connection
   */
  @SuppressWarnings("unchecked")
  private StatefulRedisConnection<?, ?> createConnection(
      final int laneIndex, final boolean useMasterReplica) {

    if (useMasterReplica) {
      // Sentinel with ReadFrom → use MasterReplica for failover + read routing
      final var sentinelUri = sentinelTopology.get().toSentinelUri();
      final var connection = MasterReplica.connect(client, codec, sentinelUri);
      connection.setReadFrom(readFrom.get());

      if (log.isDebugEnabled()) {
        log.debug(
            "Lane {} initialized: MasterReplica (Sentinel={}, ReadFrom={})",
            laneIndex,
            sentinelUri.getSentinelMasterId(),
            readFrom.get());
      }

      return connection;
    } else {
      // Direct connection to master (standalone, Enterprise proxy, or Sentinel without ReadFrom)
      final var connection = client.connect(codec);

      if (log.isDebugEnabled()) {
        log.debug("Lane {} initialized: direct connection", laneIndex);
      }

      return connection;
    }
  }

  private void closeLanes() {
    for (final var lane : lanes) {
      if (lane != null) {
        lane.close();
      }
    }
  }
}
