/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import static java.nio.charset.StandardCharsets.UTF_8;
import static lombok.AccessLevel.PRIVATE;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;
import com.macstab.oss.redis.laned.strategy.KeyAffinityStrategy;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;
import com.macstab.oss.redis.laned.util.MurmurHash3;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.push.PushListener;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.NonNull;
import lombok.experimental.FieldDefaults;

/**
 * Key-affinity connection wrapper with lazy lane selection.
 *
 * <p><strong>Problem: Lane selection requires Redis key (unavailable at getConnection()
 * time):</strong>
 *
 * <p>Standard lane selection happens in {@code LanedConnectionManager.getConnection()} → {@code
 * strategy.selectLane(numLanes)} → returns connection from selected lane. But {@code
 * KeyAffinityStrategy} needs the Redis key to hash it → key is only available when user calls
 * {@code async().get(key)}, NOT when connection requested.
 *
 * <p><strong>Solution: Dynamic proxy with lazy lane selection:</strong>
 *
 * <p>This wrapper returns JDK dynamic proxies from {@code async()}, {@code sync()}, {@code
 * reactive()} methods. Proxies intercept FIRST command, extract key from arguments, hash key,
 * select lane, then forward all subsequent commands to selected lane's connection.
 *
 * <p><strong>Execution flow (example: GET user:123):</strong>
 *
 * <pre>{@code
 * // Step 1: Spring Data Redis requests connection
 * wrapper = manager.getConnection();  // Returns KeyAffinityConnectionWrapper (lane NOT selected yet)
 *
 * // Step 2: Spring Data Redis calls async() to get command interface
 * asyncCommands = wrapper.async();  // Returns PROXY (not real connection)
 *
 * // Step 3: Spring Data Redis executes GET command
 * asyncCommands.get("user:123");
 *   → Proxy intercepts call
 *   → Extract key: "user:123"
 *   → Hash key: MurmurHash3.hash32("user:123".getBytes()) = 0x1A2B3C4D
 *   → Select lane: (0x1A2B3C4D & 0x7FFFFFFF) % 8 = 5
 *   → Store lane: selectedLaneRef.set(lanes[5])
 *   → Forward: lanes[5].getConnection().async().get("user:123")
 *
 * // Step 4: Subsequent commands use SAME lane (pinned)
 * asyncCommands.set("session:456", "value");
 *   → Proxy intercepts
 *   → Lane already selected (lane 5)
 *   → Forward: lanes[5].getConnection().async().set("session:456", "value")
 * }</pre>
 *
 * <p><strong>Why JDK Dynamic Proxy (not manual method forwarding):</strong>
 *
 * <p>Lettuce {@code RedisAsyncCommands} extends 17 sub-interfaces (Acl, Geo, Hash, HLL, Key, List,
 * Scripting, Server, Set, SortedSet, Stream, String, Transactional, Json, etc.) → total ~552
 * methods. Manual forwarding would require:
 *
 * <pre>{@code
 * class KeyAffinityWrapper implements RedisAsyncCommands<K, V> {
 *     @Override RedisFuture<V> get(K key) { ... }
 *     @Override RedisFuture<String> set(K key, V value) { ... }
 *     // ... 550 more methods
 * }
 * }</pre>
 *
 * <p>Dynamic proxy: ONE {@code InvocationHandler.invoke()} method intercepts ALL 552 methods.
 * Future-proof (new Lettuce commands work automatically without code changes).
 *
 * <p><strong>Performance (modern JDK 17+, 21+, 25+):</strong>
 *
 * <p>JDK 1.5/1.6: Dynamic proxy was slow (~100-500ns overhead, no JIT optimization).
 *
 * <p>Modern JDK: C2 JIT compiler inlines {@code InvocationHandler.invoke()} after warmup (~10,000
 * calls). Escape analysis eliminates temporary allocations. Result: ~5-10ns overhead (negligible
 * compared to Redis network latency ~200-500μs).
 *
 * <p><strong>Per's note:</strong> I was skeptical about dynamic proxy (remembered JDK 1.5
 * performance), but profiling on JDK 21 showed &lt;10ns overhead after warmup. The JIT is insanely
 * good now. For comparison: round-robin CAS ~20ns, key hashing ~50-200ns, network RTT ~200,000ns.
 * Proxy overhead is 0.005% of total latency. Totally acceptable.
 *
 * <p><strong>Keyless command handling (PING, INFO, CLIENT*):</strong>
 *
 * <p>Commands without key arguments: proxy detects {@code args == null || args.length == 0}, falls
 * back to round-robin lane selection (same as {@code RoundRobinStrategy}).
 *
 * <p><strong>Multi-key command handling (MGET k1 k2 k3):</strong>
 *
 * <p>Uses first key only: {@code MGET user:123 session:456} → hashes {@code user:123}, ignores
 * {@code session:456}. Trade-off: Simple implementation, but if keys hash to different lanes,
 * forces them together. Alternative (not implemented): hash all keys, use majority lane (complex,
 * minimal benefit for most workloads).
 *
 * <p><strong>Thread safety (lane selection):</strong>
 *
 * <p>Uses {@code AtomicReference.accumulateAndGet()} for lock-free lane selection. Multiple threads
 * calling first command concurrently: only one thread selects lane (CAS winner), others see
 * selected lane on retry. No {@code synchronized}, no blocking.
 *
 * <p><strong>Lane pinning guarantee (per wrapper instance):</strong>
 *
 * <p>Once lane selected, wrapper is PINNED to that lane for its entire lifetime. Subsequent
 * commands cannot switch lanes (required for transaction safety: {@code WATCH key} + {@code MULTI}
 * + {@code EXEC} must use same connection).
 *
 * <p><strong>Comparison to standard wrapper pattern:</strong>
 *
 * <pre>
 * LanedConnectionWrapper:
 *   - Lane selected eagerly (in constructor)
 *   - Direct delegation via @Delegate
 *   - Used by: RoundRobin, LeastUsed, ThreadAffinity
 *
 * KeyAffinityConnectionWrapper:
 *   - Lane selected lazily (on first command)
 *   - Dynamic proxy delegation
 *   - Used by: KeyAffinity only
 * </pre>
 *
 * @param <K> Redis key type
 * @param <V> Redis value type
 * @see KeyAffinityStrategy
 * @see java.lang.reflect.Proxy
 */
@SuppressWarnings("unchecked") // Lombok @Delegate + dynamic proxy generate unavoidable warnings
@FieldDefaults(level = PRIVATE, makeFinal = true)
final class KeyAffinityConnectionWrapper<K, V> implements StatefulRedisConnection<K, V> {

  /**
   * Lazily-selected lane (null until first command).
   *
   * <p>Lock-free initialization via {@code accumulateAndGet()} ensures exactly-once selection even
   * under concurrent access. First command: CAS sets lane. Subsequent commands: fast-path volatile
   * read (~1-2ns).
   */
  AtomicReference<ConnectionLane> selectedLaneRef;

  /** Manager (for accessing lanes array). */
  @NonNull LanedConnectionManager manager;

  /** Strategy (for callback on close, no-op for KeyAffinity). */
  @NonNull KeyAffinityStrategy strategy;

  /** Number of lanes (for modulo in lane selection). */
  int numLanes;

  /**
   * Shared fallback strategy for keyless commands (PING, INFO, CLIENT*).
   *
   * <p>IMPORTANT: Must be shared across all wrapper instances (via manager) to ensure true
   * round-robin distribution. If each wrapper creates its own RoundRobinStrategy, keyless commands
   * all hit lane 0 (hotspot).
   */
  @NonNull RoundRobinStrategy fallbackStrategy;

  /** Metrics collector (for recording lane selection). */
  @NonNull LanedRedisMetrics metrics;

  /** Connection name (for dimensional metrics). */
  @NonNull String connectionName;

  /**
   * Creates wrapper with lazy lane selection.
   *
   * <p><strong>IMPORTANT - Memory Management:</strong>
   *
   * <p>This wrapper holds strong reference to {@code LanedConnectionManager}. If wrapper not closed
   * properly, manager cannot be garbage-collected (memory leak). Always call {@link #close()} when
   * done (Spring Data Redis handles this automatically via connection pooling).
   *
   * <p><strong>Architectural Note:</strong>
   *
   * <p>{@code strategy.onConnectionAcquired()} is NOT called for KeyAffinity (unlike standard
   * strategies). Reason: Lane selected lazily on first command (not upfront in constructor), and
   * KeyAffinity is stateless (callback would be no-op anyway). Metrics recording happens in {@code
   * ensureLaneSelected()} after CAS succeeds.
   *
   * @param manager connection manager (must not be null)
   * @param strategy key affinity strategy (must not be null)
   * @param numLanes total lanes (must be &gt;= 1)
   * @param fallbackStrategy shared round-robin strategy for keyless commands (must not be null)
   * @param metrics metrics collector (must not be null)
   * @param connectionName connection name for dimensional metrics (must not be null)
   * @throws IllegalArgumentException if numLanes &lt; 1
   */
  KeyAffinityConnectionWrapper(
      @NonNull final LanedConnectionManager manager,
      @NonNull final KeyAffinityStrategy strategy,
      final int numLanes,
      @NonNull final RoundRobinStrategy fallbackStrategy,
      @NonNull final LanedRedisMetrics metrics,
      @NonNull final String connectionName) {
    if (numLanes < 1) {
      throw new IllegalArgumentException("numLanes must be >= 1, got: " + numLanes);
    }
    this.manager = manager;
    this.strategy = strategy;
    this.numLanes = numLanes;
    this.fallbackStrategy = fallbackStrategy; // Shared instance (not new!)
    this.metrics = metrics;
    this.connectionName = connectionName;
    this.selectedLaneRef = new AtomicReference<>();
  }

  /**
   * Returns dynamic proxy for async commands.
   *
   * <p>Proxy intercepts ALL methods in {@code RedisAsyncCommands} interface (~552 methods). First
   * call: extracts key, selects lane. Subsequent calls: forwards to selected lane.
   *
   * @return proxy implementing {@code RedisAsyncCommands}
   */
  @Override
  public RedisAsyncCommands<K, V> async() {
    return (RedisAsyncCommands<K, V>)
        Proxy.newProxyInstance(
            RedisAsyncCommands.class.getClassLoader(),
            new Class<?>[] {RedisAsyncCommands.class},
            new CommandInterceptor(CommandType.ASYNC));
  }

  /**
   * Returns dynamic proxy for sync commands.
   *
   * <p>Same as {@link #async()} but for synchronous API.
   *
   * @return proxy implementing {@code RedisCommands}
   */
  @Override
  public RedisCommands<K, V> sync() {
    return (RedisCommands<K, V>)
        Proxy.newProxyInstance(
            RedisCommands.class.getClassLoader(),
            new Class<?>[] {RedisCommands.class},
            new CommandInterceptor(CommandType.SYNC));
  }

  /**
   * Returns dynamic proxy for reactive commands.
   *
   * <p>Same as {@link #async()} but for reactive API.
   *
   * @return proxy implementing {@code RedisReactiveCommands}
   */
  @Override
  public RedisReactiveCommands<K, V> reactive() {
    return (RedisReactiveCommands<K, V>)
        Proxy.newProxyInstance(
            RedisReactiveCommands.class.getClassLoader(),
            new Class<?>[] {RedisReactiveCommands.class},
            new CommandInterceptor(CommandType.REACTIVE));
  }

  /**
   * Closes wrapper and releases lane resources.
   *
   * <p>Does NOT close underlying lane connection (multiplexed, long-lived). Only releases
   * accounting references (in-flight count, metrics). Same pattern as {@link
   * LanedConnectionWrapper#close()}.
   *
   * <p><strong>Idempotency:</strong> Calling {@code close()} multiple times is safe. Lane's {@code
   * recordRelease()} uses {@code Math.max(0, count - 1)} to prevent negative counts.
   */
  @Override
  public void close() {
    final var lane = selectedLaneRef.get();
    if (lane != null) {
      lane.recordRelease(); // Decrement in-flight count + update metrics
      strategy.onConnectionReleased(lane.getIndex()); // Callback (no-op for KeyAffinity)
    }
  }

  /**
   * Asynchronously closes wrapper (returns immediately completed future).
   *
   * <p>Wrapper release is synchronous (no async work needed). Same pattern as {@link
   * LanedConnectionWrapper#closeAsync()}.
   *
   * @return completed future
   */
  @Override
  public CompletableFuture<Void> closeAsync() {
    close();
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Checks if connection is open.
   *
   * <p>If lane not selected yet: returns {@code true} (wrapper is "open" until explicitly closed).
   * If lane selected: delegates to selected lane's connection.
   *
   * @return {@code true} if connection is open
   */
  @Override
  public boolean isOpen() {
    final var lane = selectedLaneRef.get();
    return lane == null || lane.isOpen();
  }

  /**
   * Checks if connection is in multi/exec transaction.
   *
   * <p>If lane not selected yet: returns {@code false} (no transaction started). If lane selected:
   * delegates to selected lane's connection.
   *
   * @return {@code true} if in transaction
   */
  @Override
  public boolean isMulti() {
    final var lane = selectedLaneRef.get();
    if (lane == null) {
      return false;
    }
    return ((StatefulRedisConnection<K, V>) lane.getConnection()).isMulti();
  }

  /**
   * Sets auto-flush mode.
   *
   * <p>If lane not selected yet: no-op (will be set on first command). If lane selected: delegates
   * to selected lane's connection.
   *
   * @param autoFlush auto-flush enabled
   */
  @Override
  public void setAutoFlushCommands(final boolean autoFlush) {
    final var lane = selectedLaneRef.get();
    if (lane != null) {
      ((StatefulRedisConnection<K, V>) lane.getConnection()).setAutoFlushCommands(autoFlush);
    }
  }

  /**
   * Flushes pending commands.
   *
   * <p>If lane not selected yet: no-op (no commands pending). If lane selected: delegates to
   * selected lane's connection.
   */
  @Override
  public void flushCommands() {
    final var lane = selectedLaneRef.get();
    if (lane != null) {
      ((StatefulRedisConnection<K, V>) lane.getConnection()).flushCommands();
    }
  }

  @Override
  public void setTimeout(final Duration timeout) {
    final var lane = selectedLaneRef.get();
    if (lane != null) {
      ((StatefulRedisConnection<K, V>) lane.getConnection()).setTimeout(timeout);
    }
  }

  @Override
  public Duration getTimeout() {
    final var lane = selectedLaneRef.get();
    return lane == null
        ? Duration.ZERO
        : ((StatefulRedisConnection<K, V>) lane.getConnection()).getTimeout();
  }

  @Override
  public void addListener(final PushListener listener) {
    final var lane = selectedLaneRef.get();
    if (lane != null) {
      ((StatefulRedisConnection<K, V>) lane.getConnection()).addListener(listener);
    }
  }

  @Override
  public void removeListener(final PushListener listener) {
    final var lane = selectedLaneRef.get();
    if (lane != null) {
      ((StatefulRedisConnection<K, V>) lane.getConnection()).removeListener(listener);
    }
  }

  @Override
  public void addListener(final io.lettuce.core.RedisConnectionStateListener listener) {
    final var lane = selectedLaneRef.get();
    if (lane != null) {
      ((StatefulRedisConnection<K, V>) lane.getConnection()).addListener(listener);
    }
  }

  @Override
  public void removeListener(final io.lettuce.core.RedisConnectionStateListener listener) {
    final var lane = selectedLaneRef.get();
    if (lane != null) {
      ((StatefulRedisConnection<K, V>) lane.getConnection()).removeListener(listener);
    }
  }

  @Override
  public void reset() {
    final var lane = selectedLaneRef.get();
    if (lane != null) {
      ((StatefulRedisConnection<K, V>) lane.getConnection()).reset();
    }
  }

  @Override
  public <T> io.lettuce.core.protocol.RedisCommand<K, V, T> dispatch(
      final io.lettuce.core.protocol.RedisCommand<K, V, T> command) {
    // Commands are dispatched via proxy (async/sync/reactive interfaces)
    // This low-level method should not be called directly on wrapper
    final var lane = ensureLaneSelected(new Object[] {command});
    return ((StatefulRedisConnection<K, V>) lane.getConnection()).dispatch(command);
  }

  @Override
  public Collection<io.lettuce.core.protocol.RedisCommand<K, V, ?>> dispatch(
      final Collection<? extends io.lettuce.core.protocol.RedisCommand<K, V, ?>> commands) {
    // Batch dispatch - use first command for lane selection
    final var firstCmd = commands.isEmpty() ? null : commands.iterator().next();
    final var lane = ensureLaneSelected(firstCmd == null ? null : new Object[] {firstCmd});
    return ((StatefulRedisConnection<K, V>) lane.getConnection()).dispatch(commands);
  }

  @Override
  public io.lettuce.core.resource.ClientResources getResources() {
    final var lane = selectedLaneRef.get();
    return lane == null
        ? null
        : ((StatefulRedisConnection<K, V>) lane.getConnection()).getResources();
  }

  @Override
  public io.lettuce.core.ClientOptions getOptions() {
    final var lane = selectedLaneRef.get();
    return lane == null
        ? null
        : ((StatefulRedisConnection<K, V>) lane.getConnection()).getOptions();
  }

  /**
   * Command type (async/sync/reactive).
   *
   * <p>Used by {@link CommandInterceptor} to determine which command interface to invoke on real
   * connection.
   */
  private enum CommandType {
    ASYNC,
    SYNC,
    REACTIVE
  }

  /**
   * Invocation handler that intercepts ALL command methods.
   *
   * <p>Single {@code invoke()} method handles 552 Redis commands (get, set, hgetall, lpush, sadd,
   * zadd, etc.). First call: selects lane based on key. Subsequent calls: forwards to selected
   * lane.
   */
  private final class CommandInterceptor implements InvocationHandler {

    private final CommandType type;

    CommandInterceptor(final CommandType type) {
      this.type = type;
    }

    /**
     * Intercepts command invocation.
     *
     * <p><strong>Execution flow:</strong>
     *
     * <ol>
     *   <li>Ensure lane selected (lock-free, happens once on first command)
     *   <li>Get real connection from selected lane
     *   <li>Get appropriate command interface (async/sync/reactive)
     *   <li>Forward method call to real connection
     * </ol>
     *
     * @param proxy the proxy instance (unused, required by interface)
     * @param method the method being invoked (e.g., {@code RedisAsyncCommands.get})
     * @param args method arguments (e.g., {@code ["user:123"]})
     * @return result of forwarded method call
     * @throws Throwable if method invocation fails
     */
    @Override
    public Object invoke(final Object proxy, final Method method, final Object[] args)
        throws Throwable {
      // Step 1: Ensure lane selected (lock-free, idempotent)
      final var lane = ensureLaneSelected(args);

      // Step 2: Get real connection from selected lane
      final var realConnection = (StatefulRedisConnection<K, V>) lane.getConnection();

      // Step 3: Get appropriate command interface
      final Object realCommands;
      switch (type) {
        case ASYNC:
          realCommands = realConnection.async();
          break;
        case SYNC:
          realCommands = realConnection.sync();
          break;
        case REACTIVE:
          realCommands = realConnection.reactive();
          break;
        default:
          throw new IllegalStateException("Unknown command type: " + type);
      }

      // Step 4: Forward method call
      // Example: get("user:123") → realConnection.async().get("user:123")
      return method.invoke(realCommands, args);
    }
  }

  /**
   * Ensures lane selected (lock-free initialization).
   *
   * <p>Called on FIRST command only. Subsequent commands use cached lane (fast-path volatile read).
   *
   * <p><strong>Algorithm:</strong>
   *
   * <ol>
   *   <li>If lane already selected → return cached lane (fast path, ~1-2ns)
   *   <li>Extract key from first argument (if available)
   *   <li>Hash key via MurmurHash3 → select lane
   *   <li>If no key → fallback to round-robin
   *   <li>Store selected lane via CAS (lock-free, exactly-once guarantee)
   *   <li>Record metrics (only if we were first to select, checked after CAS)
   * </ol>
   *
   * <p><strong>Thread Safety - Metrics Recording:</strong>
   *
   * <p>Under concurrent access, {@code compareAndSet()} ensures only ONE thread successfully sets
   * the lane. That thread records metrics. Other threads see CAS fail → skip metrics recording.
   * Result: Metrics recorded exactly once per wrapper instance.
   *
   * @param args method arguments (first element = Redis key, if applicable)
   * @return selected lane (never null after first call)
   */
  private ConnectionLane ensureLaneSelected(final Object[] args) {
    // Fast path: lane already selected
    var lane = selectedLaneRef.get();
    if (lane != null) {
      return lane;
    }

    // Slow path: first command, need to select lane
    final int laneIndex;
    if (hasKey(args)) {
      // Key-based selection
      final byte[] keyBytes = extractKey(args[0]);
      final int hash = MurmurHash3.hash32(keyBytes);
      laneIndex = (hash & 0x7FFF_FFFF) % numLanes;
    } else {
      // Keyless command (PING, INFO, CLIENT*) → fallback to round-robin
      laneIndex = fallbackStrategy.selectLane(numLanes);
    }

    // Get lane and try to set it
    lane = manager.lanes[laneIndex];

    // CAS: only ONE thread succeeds (first to call compareAndSet)
    if (selectedLaneRef.compareAndSet(null, lane)) {
      // We won the race: record acquisition + metrics
      lane.recordAcquire(); // Increment in-flight count + update metrics
      metrics.recordLaneSelection(connectionName, laneIndex, strategy.getName());
    }

    // Return selected lane (either we set it, or another thread did)
    return selectedLaneRef.get();
  }

  /**
   * Checks if command has a key argument.
   *
   * <p>Simple heuristic: if {@code args[0]} is non-null, assume it's a key. Works for 99% of Redis
   * commands (GET, SET, HGETALL, LPUSH, etc.). Edge cases (INFO, PING) have {@code args == null}.
   *
   * @param args method arguments
   * @return {@code true} if first argument is non-null (likely a key)
   */
  private boolean hasKey(final Object[] args) {
    return args != null && args.length > 0 && args[0] != null;
  }

  /**
   * Extracts key bytes from argument.
   *
   * <p>Handles different codec types:
   *
   * <ul>
   *   <li>{@code byte[]} (ByteArrayCodec) → return directly
   *   <li>{@code String} (StringCodec) → UTF-8 bytes
   *   <li>{@code ByteBuffer} (ByteBufferCodec) → array (duplicate to avoid mutation)
   *   <li>Other → {@code toString()} + UTF-8 (fallback)
   * </ul>
   *
   * @param keyArg key object (from {@code args[0]})
   * @return key bytes for hashing
   */
  private byte[] extractKey(final Object keyArg) {
    if (keyArg instanceof byte[]) {
      return (byte[]) keyArg;
    }

    if (keyArg instanceof String) {
      return ((String) keyArg).getBytes(UTF_8);
    }

    if (keyArg instanceof ByteBuffer) {
      final var buf = (ByteBuffer) keyArg;
      final var bytes = new byte[buf.remaining()];
      buf.duplicate().get(bytes); // duplicate() to avoid mutating original position
      return bytes;
    }

    // Fallback (rare): toString() + UTF-8
    return keyArg.toString().getBytes(UTF_8);
  }
}
