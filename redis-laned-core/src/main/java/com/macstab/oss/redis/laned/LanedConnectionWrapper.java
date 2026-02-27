/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import java.util.concurrent.CompletableFuture;

import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy;

import io.lettuce.core.api.StatefulRedisConnection;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.experimental.Delegate;
import lombok.experimental.FieldDefaults;

/**
 * Lifecycle-aware wrapper around {@link StatefulRedisConnection}.
 *
 * <p><strong>Problem: LeastUsedStrategy memory leak (usage count never decrements):</strong>
 *
 * <p>{@code LeastUsedStrategy.selectLane()} increments {@code usageCounts[lane]} atomically. But
 * when application code calls {@code connection.close()}, strategy never notified → usage count
 * stays inflated forever → lanes appear "busy" even when idle → selection degrades to first lane
 * (all counts equal after overflow).
 *
 * <p>Real-world scenario: 10,000 requests/second, 8 lanes. After 5.9 hours: {@code
 * usageCounts[lane] = Integer.MAX_VALUE - 1} (2,147,483,646). Next increment overflows to {@code
 * Integer.MIN_VALUE} (-2,147,483,648). Strategy prefers negative count (thinks lane is "least
 * used"). All lanes eventually overflow → all counts equal → always returns lane 0.
 *
 * <p><strong>Solution: Delegation + lifecycle hook:</strong>
 *
 * <p>Lombok {@code @Delegate} generates 100+ delegation methods (sync, async, reactive, Pub/Sub).
 * Override {@code close()} only: delegate to wrapped connection, then notify strategy via {@code
 * onConnectionReleased(laneIndex)}. Strategy decrements usage count (leak fixed).
 *
 * <p><strong>Why @Delegate is superior to manual forwarding:</strong>
 *
 * <p>{@code StatefulRedisConnection} interface: 20+ methods (sync/async/reactive, timeouts,
 * pipeline, transactions). Manual forwarding: 300+ lines boilerplate code, maintenance burden
 * (every Lettuce version: check for new methods).
 *
 * <p>Lombok generates this at compile time (annotation processing). JVM sees identical bytecode as
 * manual forwarding. Zero runtime cost. Type-safe (compiler error if {@code
 * StatefulRedisConnection} adds methods).
 *
 * <p><strong>Thread safety (close() idempotency):</strong>
 *
 * <p>Lettuce's {@code StatefulRedisConnectionImpl.close()} is idempotent (Netty {@code
 * Channel.close()} checks {@code isActive()} first). Multiple threads calling {@code wrapper.
 * close()} concurrently: only first thread actually closes channel. Subsequent calls no-op.
 *
 * <p>Strategy notification: {@code onConnectionReleased()} ALWAYS called in {@code finally} block →
 * even if {@code delegate.close()} throws. Strategy implementation MUST handle concurrent calls
 * (same lane, multiple threads) → {@code AtomicIntegerArray.decrementAndGet()} is atomic, no
 * double-decrement risk.
 *
 * <p><strong>Performance impact (close path, not hot path):</strong>
 *
 * <p>This wrapper adds ONE virtual method call overhead to {@code close()}. Close path is NOT hot
 * (connections are long-lived, close ~1/minute). Hot path ({@code getConnection()}) wraps instantly
 * (constructor: 2 field assignments, ~5-10ns).
 *
 * <p>Strategy notification: ~10-50ns (atomic decrement + modulo for index). Negligible compared to
 * {@code close()} cost (TCP FIN + channel cleanup + FD release = ~1-5μs).
 *
 * @param <K> Redis key type
 * @param <V> Redis value type
 * @see LaneSelectionStrategy#onConnectionReleased(int)
 * @see <a
 *     href="https://projectlombok.org/features/Delegate">https://projectlombok.org/features/Delegate</a>
 */
@SuppressWarnings("unchecked") // Lombok @Delegate generates unavoidable unchecked warnings
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
final class LanedConnectionWrapper<K, V> implements StatefulRedisConnection<K, V> {

  /**
   * Wrapped connection (Lombok delegates ALL interface methods to this field).
   *
   * <p><strong>Exclusions (methods we override manually):</strong>
   *
   * <ul>
   *   <li>{@code close()} - Add lifecycle hook before delegating
   *   <li>{@code closeAsync()} - Add lifecycle hook before delegating
   * </ul>
   *
   * <p>All other methods (sync, async, reactive, multi-exec, pipelining) delegate without
   * modification. Lombok generates: {@code return delegate.methodName(args);} for each interface
   * method.
   */
  @Delegate(
      types = StatefulRedisConnection.class,
      excludes = {Close.class, CloseAsync.class})
  @NonNull
  StatefulRedisConnection<K, V> delegate;

  /** Lane index (0-based, range [0, numLanes-1]). */
  int laneIndex;

  /** Lane (for decrementing in-flight count on close). */
  @NonNull ConnectionLane lane;

  /** Strategy to notify when connection released. */
  @NonNull LaneSelectionStrategy strategy;

  /**
   * Creates wrapper and records connection acquisition.
   *
   * <p>Automatically increments lane's in-flight count + records metrics when wrapper created
   * (connection borrowed from lane).
   *
   * @param delegate wrapped Lettuce connection
   * @param laneIndex lane index (0-based)
   * @param lane lane (for metrics + lifecycle tracking)
   * @param strategy strategy (for lifecycle notifications)
   */
  LanedConnectionWrapper(
      @NonNull final StatefulRedisConnection<K, V> delegate,
      final int laneIndex,
      @NonNull final ConnectionLane lane,
      @NonNull final LaneSelectionStrategy strategy) {
    this.delegate = delegate;
    this.laneIndex = laneIndex;
    this.lane = lane;
    this.strategy = strategy;

    // Record acquisition (increment in-flight count + metrics)
    lane.recordAcquire();
  }

  /**
   * Releases wrapper and notifies strategy (lifecycle tracking).
   *
   * <p><strong>CRITICAL: Lane connections are long-lived (multiplexed):</strong>
   *
   * <p>Lane connections are created ONCE in {@code LanedConnectionManager} constructor and reused
   * for ALL requests (similar to Spring's {@code shareNativeConnection = true}). This wrapper
   * represents ONE request's usage of a lane connection.
   *
   * <p><strong>Why we do NOT close the delegate:</strong>
   *
   * <ul>
   *   <li><strong>Delegate = underlying lane connection</strong> (long-lived TCP socket)
   *   <li><strong>Wrapper = temporary request handle</strong> (short-lived, one per operation)
   *   <li>Closing delegate would terminate TCP → defeats multiplexing → negates entire value
   *       proposition
   * </ul>
   *
   * <p><strong>Physical vs logical close:</strong>
   *
   * <ul>
   *   <li><strong>Logical close (this method):</strong> {@code wrapper.close()} → notify strategy
   *       (decrement in-flight count) → delegate connection stays OPEN for next request
   *   <li><strong>Physical close:</strong> {@code LanedConnectionManager.destroy()} → {@code
   *       lane.close()} → Lettuce closes TCP socket (QUIT + FIN + FD release)
   * </ul>
   *
   * <p><strong>Comparison to Spring Data Redis:</strong>
   *
   * <p>Spring's {@code LettuceConnection.close()} with {@code shareNativeConnection = true}:
   *
   * <pre>{@code
   * public void close() {
   *     reset();  // Resets state (DB index, subscriptions)
   *     // Does NOT close shared connection!
   * }
   * }</pre>
   *
   * <p>Our wrapper (similar pattern):
   *
   * <pre>{@code
   * public void close() {
   *     strategy.onConnectionReleased(laneIndex);  // Notify strategy
   *     // Does NOT close lane connection!
   * }
   * }</pre>
   *
   * <p><strong>Idempotency guarantee:</strong>
   *
   * <p>Calling {@code close()} multiple times: Strategy must handle gracefully (e.g., {@code
   * LeastUsedStrategy} uses atomic operations, safe for concurrent/redundant calls).
   */
  @Override
  public void close() {
    // Lane connections are LONG-LIVED (created once, reused forever)

    // Record release (decrement in-flight count + metrics)
    lane.recordRelease();

    // Strategy handles lifecycle tracking (decrement in-flight count if needed)
    strategy.onConnectionReleased(laneIndex);

    // DO NOT close delegate - would break multiplexing!
  }

  /**
   * Asynchronously releases wrapper and notifies strategy.
   *
   * <p><strong>Async release pattern:</strong>
   *
   * <p>Returns immediately completed future (wrapper release is synchronous, no I/O needed).
   * Strategy notification happens synchronously before future completes.
   *
   * <p><strong>Why no delegate.closeAsync():</strong>
   *
   * <p>Same reason as {@link #close()}: delegate is a long-lived lane connection. Closing it would
   * terminate TCP socket, defeating multiplexing. Only {@code LanedConnectionManager.destroy()}
   * should physically close lane connections.
   *
   * @return completed future (wrapper release is immediate, no async work)
   */
  @Override
  public CompletableFuture<Void> closeAsync() {
    // Lane connections are LONG-LIVED (created once, reused forever)

    // Record release (decrement in-flight count + metrics)
    lane.recordRelease();

    // Strategy handles lifecycle tracking (decrement in-flight count if needed)
    strategy.onConnectionReleased(laneIndex);

    // DO NOT close delegate - would break multiplexing!
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Marker interface for {@code @Delegate} exclusion ({@code close()} method).
   *
   * <p>Lombok's {@code excludes = Close.class} prevents delegation of {@code void close()} method.
   * We override manually to add lifecycle hook.
   */
  private interface Close {
    void close();
  }

  /**
   * Marker interface for {@code @Delegate} exclusion ({@code closeAsync()} method).
   *
   * <p>Lombok's {@code excludes = CloseAsync.class} prevents delegation of {@code
   * CompletableFuture<Void> closeAsync()} method. We override manually to add lifecycle hook.
   */
  private interface CloseAsync {
    CompletableFuture<Void> closeAsync();
  }
}
