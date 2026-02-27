/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.stress;

import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategy;

/**
 * Direct modulo thread affinity strategy (for testing only).
 *
 * <p><strong>Why this exists (test-specific strategy):</strong>
 *
 * <p>Production {@code ThreadAffinityStrategy} uses MurmurHash3 for pseudo-random distribution
 * (avoids pathological patterns in production). MurmurHash3 creates random collisions:
 *
 * <pre>
 * Thread 42  → MurmurHash3(42)  % 8 = 7
 * Thread 137 → MurmurHash3(137) % 8 = 7  ← Collision! (unpredictable)
 * </pre>
 *
 * <p>For transaction safety tests, we need DETERMINISTIC distribution to prove correctness. Direct
 * modulo achieves this:
 *
 * <pre>
 * Thread 42  → 42  % 8 = 2
 * Thread 137 → 137 % 8 = 1  ← No collision (deterministic)
 * </pre>
 *
 * <p><strong>When virtual threads have sequential IDs:</strong>
 *
 * <pre>
 * 2500 threads created in loop:
 *   Thread 1  → ID 100 → 100  % 2500 = 100  (Lane 100)
 *   Thread 2  → ID 101 → 101  % 2500 = 101  (Lane 101)
 *   Thread 3  → ID 102 → 102  % 2500 = 102  (Lane 102)
 *   ...
 *   Thread 2500 → ID 2599 → 2599 % 2500 = 99 (Lane 99)
 *
 * Result: Near-perfect 1:1 thread-to-lane mapping (minimal collisions)
 * </pre>
 *
 * <p><strong>Why NOT use this in production:</strong>
 *
 * <ul>
 *   <li>Thread IDs may NOT be sequential (thread pool reuse, non-contiguous allocation)
 *   <li>Direct modulo creates patterns (e.g., all even thread IDs → even lanes)
 *   <li>MurmurHash3 breaks patterns (better load distribution in pathological cases)
 * </ul>
 *
 * <p><strong>Test vs Production:</strong>
 *
 * <ul>
 *   <li><strong>Test:</strong> Sequential thread creation → direct modulo → perfect distribution
 *   <li><strong>Production:</strong> Unpredictable thread IDs → MurmurHash3 → uniform distribution
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
final class DirectModuloThreadAffinityStrategy implements LaneSelectionStrategy {

  /**
   * Selects lane using direct modulo of thread ID (no hashing).
   *
   * <p><strong>Why direct modulo works for tests:</strong>
   *
   * <p>Virtual threads created in a tight loop typically get sequential IDs:
   *
   * <pre>{@code
   * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   *     for (int i = 0; i < 2500; i++) {
   *         executor.submit(() -> {
   *             long id = Thread.currentThread().threadId();  // Sequential: 100, 101, 102, ...
   *             int lane = (int)(id % 2500);                   // Perfect distribution!
   *         });
   *     }
   * }
   * }</pre>
   *
   * <p>With 2500 threads and 2500 lanes, collision probability ≈ 0 (if IDs sequential).
   *
   * <p><strong>Performance:</strong>
   *
   * <pre>
   * Direct modulo: ~1-2ns (single division)
   * MurmurHash3:    ~10-12ns (3 operations: XOR, multiply, XOR)
   *
   * ~1ns vs ~10ns, only marginally faster, but only matters for millions of operations (tests run once)
   * </pre>
   *
   * @param numLanes total number of lanes
   * @return lane index (deterministic per thread)
   */
  @Override
  public int selectLane(final int numLanes) {
    final long threadId = Thread.currentThread().threadId();

    // Cast to int is safe: modulo result is always < numLanes (< Integer.MAX_VALUE)
    // Negative thread IDs handled correctly: Java modulo preserves sign, abs() fixes
    return Math.abs((int) (threadId % numLanes));
  }

  @Override
  public String getName() {
    return "DirectModuloThreadAffinity";
  }

  // No lifecycle tracking needed (stateless strategy)
  @Override
  public void initialize(com.macstab.oss.redis.laned.ConnectionLane[] lanes) {
    // No-op: stateless strategy
  }

  @Override
  public void onConnectionAcquired(final int laneIndex) {
    // No-op: stateless strategy (no counting)
  }

  @Override
  public void onConnectionReleased(final int laneIndex) {
    // No-op: stateless strategy (no counting)
  }

  @Override
  public int getInFlightCount(final int laneIndex) {
    return 0; // Stateless strategy (no tracking)
  }
}
