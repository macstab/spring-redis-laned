/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import static lombok.AccessLevel.PRIVATE;

import java.util.concurrent.atomic.AtomicInteger;

import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;

import io.lettuce.core.api.StatefulRedisConnection;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

/**
 * Single connection lane wrapper.
 *
 * <p><strong>Why {@code StatefulRedisConnection} is thread-safe (Netty internals):</strong>
 *
 * <p>Lettuce {@code StatefulRedisConnection} → Netty {@code Channel}. Multiple threads write via
 * {@code DefaultEndpoint.write()}: {@code SharedLock.incrementWriters()} (lock-free CAS counter,
 * multiple concurrent writers allowed), write to {@code ChannelOutboundBuffer} ({@code
 * MpscLinkedQueue}: multi-producer, single-consumer), {@code decrementWriters()}. Netty event loop
 * thread (single consumer) dequeues FIFO, flushes to TCP socket.
 *
 * <p>Read side: {@code CommandHandler.decode()} runs exclusively on event loop thread. Parses RESP
 * from {@code ByteBuf}, polls {@code stack} (FIFO {@code ArrayDeque}, NOT thread-safe, but only
 * event loop accesses), completes {@code CompletableFuture}s.
 *
 * <p><strong>Netty pipeline (Lettuce default):</strong>
 *
 * <pre>{@code
 * [head] → [ConnectionWatchdog] → [CommandEncoder] → [CommandHandler] → [tail]
 *          (auto-reconnect)       (RESP serialize)   (RESP deserialize + FIFO match)
 * }</pre>
 *
 * <p><strong>JMM guarantee (final field publication):</strong>
 *
 * <p>{@code index} and {@code connection} are {@code final} → happens-before edge (JLS §17.5).
 * Constructor writes complete BEFORE {@code this} reference published. All threads see fully
 * initialized fields without {@code volatile}. x86_64: implicit (TSO), ARM: {@code StoreStore}
 * barrier at constructor end.
 *
 * @see <a href="https://netty.io/4.1/api/io/netty/channel/Channel.html">Netty Channel</a>
 * @see <a
 *     href="https://github.com/lettuce-io/lettuce-core/blob/main/src/main/java/io/lettuce/core/protocol/CommandHandler.java">Lettuce
 *     CommandHandler</a>
 */
@FieldDefaults(level = PRIVATE, makeFinal = true)
public class ConnectionLane {

  @Getter int index;
  StatefulRedisConnection<?, ?> connection;

  /**
   * In-flight command count (for load-aware lane selection).
   *
   * <p>Tracks how many connections are currently "borrowed" from this lane. Incremented when {@link
   * com.macstab.oss.redis.laned.LanedConnectionManager#getConnection()} returns connection from
   * this lane. Decremented when application calls {@code close()} on returned connection (which
   * doesn't actually close the shared connection, just releases the accounting reference).
   *
   * <p>Used by {@link com.macstab.oss.redis.laned.strategy.LeastUsedStrategy} to select lane with
   * minimum in-flight count (real-time load measurement, not historical selection count).
   */
  @Getter AtomicInteger inFlightCount;

  /**
   * Metrics collector (optional, may be NOOP).
   *
   * <p>Tracks lane-level metrics (in-flight gauge). NoOp when metrics module not on classpath or
   * disabled.
   */
  LanedRedisMetrics metrics;

  /**
   * Connection name for dimensional metrics.
   *
   * <p>Distinguishes multiple Redis connections (primary, cache, session) in Grafana queries.
   */
  String connectionName;

  public ConnectionLane(final int index, @NonNull final StatefulRedisConnection<?, ?> connection) {
    this(index, connection, LanedRedisMetrics.NOOP, "default");
  }

  public ConnectionLane(
      final int index,
      @NonNull final StatefulRedisConnection<?, ?> connection,
      @NonNull final LanedRedisMetrics metrics,
      @NonNull final String connectionName) {
    if (index < 0) {
      throw new IllegalArgumentException("Lane index must be >= 0, got: " + index);
    }
    this.index = index;
    this.connection = connection;
    this.inFlightCount = new AtomicInteger(0);
    this.metrics = metrics;
    this.connectionName = connectionName;
  }

  /**
   * Records connection acquisition (wrapper created).
   *
   * <p>Increments in-flight count + records metrics. Called by {@link LanedConnectionWrapper}
   * constructor when wrapper created (connection borrowed from lane).
   *
   * <p><strong>Thread safety:</strong> {@code AtomicInteger.incrementAndGet()} is atomic. Multiple
   * threads can concurrently acquire connections from same lane.
   *
   * <p><strong>Metrics:</strong> Tracks {@code redis.lettuce.laned.lane.in_flight} gauge (current
   * load per lane).
   */
  public void recordAcquire() {
    final var count = inFlightCount.incrementAndGet();
    metrics.setInFlightOperations(connectionName, index, count);
  }

  /**
   * Records connection release (wrapper closed).
   *
   * <p>Decrements in-flight count + records metrics. Called by {@link
   * LanedConnectionWrapper#close()} when application code closes wrapper (connection returned to
   * lane).
   *
   * <p><strong>Thread safety:</strong> {@code AtomicInteger.updateAndGet()} is atomic. Multiple
   * threads can concurrently release connections to same lane.
   *
   * <p><strong>Idempotency:</strong> Prevents negative count (wrapper close may be called multiple
   * times). Uses {@code Math.max(0, count - 1)} to clamp at zero.
   *
   * <p><strong>Metrics:</strong> Tracks {@code redis.lettuce.laned.lane.in_flight} gauge (current
   * load per lane).
   */
  public void recordRelease() {
    final var count = inFlightCount.updateAndGet(c -> Math.max(0, c - 1));
    metrics.setInFlightOperations(connectionName, index, count);
  }

  /**
   * Returns Lettuce connection.
   *
   * <p><strong>DO NOT CLOSE:</strong> Breaks all threads using this lane. Closes TCP socket, fails
   * all in-flight commands, rejects future commands. Only {@code LanedConnectionManager.destroy()}
   * should close.
   */
  public StatefulRedisConnection<?, ?> getConnection() {
    return connection;
  }

  /**
   * Checks if connection is open.
   *
   * <p>Delegates to Lettuce {@code StatefulRedisConnectionImpl.isOpen()} → Netty {@code
   * Channel.isOpen()}:
   *
   * <pre>{@code
   * // AbstractChannel.java (Netty)
   * volatile int state;  // 0=INIT, 1=REGISTERED, 2=ACTIVE, 3=CLOSED
   *
   * public boolean isOpen() {
   *     return state != ST_CLOSED;  // Volatile read (memory barrier)
   * }
   * }</pre>
   *
   * <p>Volatile read: ensures visibility of writes from other threads (cache flush + MESI). x86_64:
   * volatile read = normal {@code mov} (TSO guarantees visibility).
   */
  public boolean isOpen() {
    return connection.isOpen();
  }

  /**
   * Closes connection.
   *
   * <p>Lettuce: {@code QUIT} command → Netty {@code channel.close()} (volatile write {@code state =
   * CLOSED}, TCP FIN) → event loop deregister (no more epoll) → ByteBuf release to {@code
   * PooledByteBufAllocator} → FD close. Idempotent (Lettuce checks {@code isOpen()}).
   */
  public void close() {
    connection.close();
  }

  @Override
  public String toString() {
    return String.format("Lane[%d, open=%s]", index, isOpen());
  }
}
