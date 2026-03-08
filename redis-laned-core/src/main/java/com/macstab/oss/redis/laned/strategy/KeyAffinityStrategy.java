/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.strategy;

/**
 * Key-based lane selection (marker strategy, logic in wrapper).
 *
 * <p><strong>Why logic is NOT in this class (architectural decision):</strong>
 *
 * <p>Unlike other strategies ({@code RoundRobinStrategy}, {@code ThreadAffinityStrategy}), {@code
 * KeyAffinityStrategy} CANNOT select lane in {@code selectLane(int)} method. Reason: Redis key is
 * only available when user calls {@code async().get(key)}, NOT when {@code
 * LanedConnectionManager.getConnection()} is called.
 *
 * <p><strong>Execution flow (key-based selection requires lazy initialization):</strong>
 *
 * <pre>{@code
 * // Step 1: Application requests connection
 * connection = manager.getConnection();
 *   → Detects KeyAffinityStrategy
 *   → Returns KeyAffinityConnectionWrapper (lane NOT selected yet)
 *
 * // Step 2: Application calls async() to get command interface
 * asyncCommands = connection.async();
 *   → Returns dynamic proxy (not real connection)
 *
 * // Step 3: Application executes command
 * asyncCommands.get("user:123");
 *   → Proxy intercepts call
 *   → Extract key: "user:123"
 *   → Hash key: MurmurHash3.hash32("user:123".getBytes()) = 0x1A2B3C4D
 *   → Select lane: (0x1A2B3C4D & 0x7FFFFFFF) % 8 = 5
 *   → Forward to: lanes[5].getConnection().async().get("user:123")
 * }</pre>
 *
 * <p><strong>Implementation location:</strong>
 *
 * <p>All key extraction, hashing, and lane selection logic is in {@code
 * KeyAffinityConnectionWrapper.CommandInterceptor.invoke()}. This class exists only as a marker to
 * trigger wrapper creation in {@code LanedConnectionManager.getConnection()}.
 *
 * <p><strong>Why marker pattern (not interface extension):</strong>
 *
 * <p>We could create {@code KeyBasedStrategy extends LaneSelectionStrategy} with {@code
 * selectLane(byte[] key, int numLanes)} method. But:
 *
 * <ul>
 *   <li>Violates ISP (Interface Segregation Principle) - other strategies don't need key parameter
 *   <li>Complicates {@code LanedConnectionManager} (needs to detect interface type)
 *   <li>No benefit (wrapper already has key extraction logic)
 * </ul>
 *
 * <p>Marker pattern: Clean separation, {@code instanceof} check is explicit, no interface
 * pollution.
 *
 * <p><strong>Comparison to other strategies:</strong>
 *
 * <pre>
 * RoundRobinStrategy:
 *   - Logic in strategy class (AtomicInteger.getAndIncrement())
 *   - Selects lane eagerly (in getConnection())
 *   - Uses standard wrapper (LanedConnectionWrapper)
 *
 * ThreadAffinityStrategy:
 *   - Logic in strategy class (MurmurHash3.hash64(threadId))
 *   - Selects lane eagerly (in getConnection())
 *   - Uses standard wrapper (LanedConnectionWrapper)
 *
 * KeyAffinityStrategy:
 *   - Logic in wrapper class (KeyAffinityConnectionWrapper)
 *   - Selects lane lazily (on first command)
 *   - Uses custom wrapper (with dynamic proxy)
 * </pre>
 *
 * <p><strong>Christian's note:</strong> I initially put the hashing logic here (in strategy), but
 * it didn't make sense. The strategy is called from {@code getConnection()}, where the key doesn't
 * exist yet. Moving logic to the wrapper (where we have access to method arguments via dynamic
 * proxy) is the only clean solution. This class is now just a type marker, which feels weird but is
 * architecturally correct.
 *
 * @see com.macstab.oss.redis.laned.KeyAffinityConnectionWrapper
 * @see java.lang.reflect.Proxy
 */
public final class KeyAffinityStrategy implements LaneSelectionStrategy {

  /** Creates key affinity strategy (stateless, no initialization needed). */
  public KeyAffinityStrategy() {
    // Stateless marker - no fields, no state
  }

  /**
   * NOT used for key affinity (lane selection happens in wrapper).
   *
   * <p>This method is never called for {@code KeyAffinityStrategy} because {@code
   * LanedConnectionManager.getConnection()} detects {@code instanceof KeyAffinityStrategy} and
   * returns {@code KeyAffinityConnectionWrapper} directly (bypasses strategy.selectLane() call).
   *
   * <p>If somehow called (programming error): throws {@code UnsupportedOperationException} with
   * clear message.
   *
   * @param numLanes ignored
   * @return never returns
   * @throws UnsupportedOperationException always (method should never be called)
   */
  @Override
  public int selectLane(final int numLanes) {
    throw new UnsupportedOperationException(
        "KeyAffinityStrategy.selectLane() should never be called. "
            + "Lane selection happens in KeyAffinityConnectionWrapper (dynamic proxy intercepts commands). "
            + "If you see this exception, there's a bug in LanedConnectionManager.getConnection().");
  }

  @Override
  public String getName() {
    return "key-affinity";
  }

  /**
   * No-op for key affinity (stateless strategy).
   *
   * <p>Key affinity doesn't track per-lane state (hash-based mapping, no in-flight count needed in
   * strategy). Lifecycle tracking handled by {@code ConnectionLane.recordAcquire()} / {@code
   * recordRelease()}.
   *
   * @param laneIndex lane index (ignored)
   */
  @Override
  public void onConnectionReleased(final int laneIndex) {
    // No-op: key affinity is stateless, no per-lane tracking
  }
}
