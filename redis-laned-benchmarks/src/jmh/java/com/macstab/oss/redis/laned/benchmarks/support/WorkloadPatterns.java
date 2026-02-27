/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.benchmarks.support;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Reusable workload patterns for benchmarks.
 *
 * <p><strong>Strategy Pattern:</strong> Benchmarks inject workload behavior via functional
 * interface. Keeps benchmarks DRY (don't repeat command logic). Enables easy workload substitution.
 *
 * <p><strong>Available patterns:</strong>
 *
 * <ul>
 *   <li>{@link #FAST_ONLY} - Only fast GET commands (best-case latency)
 *   <li>{@link #MIXED_95_5} - 95% fast GET, 5% slow HGETALL (realistic production mix)
 *   <li>{@link #SLOW_ONLY} - Only slow HGETALL commands (worst-case latency)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class WorkloadPatterns {

  private WorkloadPatterns() {
    // Static utility class
  }

  /**
   * Fast-only workload (GET commands only).
   *
   * <p><strong>Command:</strong> {@code GET key-<random>} where random ∈ [0, 999]
   *
   * <p><strong>Latency:</strong> ~0.3ms (network RTT + Redis execution)
   *
   * <p><strong>Use case:</strong> Baseline measurement (no HOL blocking)
   */
  public static final WorkloadPattern FAST_ONLY =
      conn -> {
        final int keyIndex = ThreadLocalRandom.current().nextInt(1000);
        conn.sync().get("key-" + keyIndex);
      };

  /**
   * Mixed workload (95% fast, 5% slow).
   *
   * <p><strong>Commands:</strong>
   *
   * <ul>
   *   <li>95%: {@code GET key-0} (~0.3ms)
   *   <li>5%: {@code HGETALL large-hash} (~18ms, 500KB response)
   * </ul>
   *
   * <p><strong>Latency distribution:</strong>
   *
   * <pre>
   * p50: ~0.3ms (fast commands)
   * p95: ~0.5ms (still fast)
   * p99: ~18ms (slow commands visible)
   * </pre>
   *
   * <p><strong>Use case:</strong> Realistic production workload (mostly fast, occasional slow)
   */
  public static final WorkloadPattern MIXED_95_5 =
      new WorkloadPattern() {
        private final Random random = new Random();

        @Override
        public void execute(final StatefulRedisConnection<String, String> conn) {
          if (random.nextDouble() < 0.95) {
            conn.sync().get("key-0"); // Fast: 95%
          } else {
            conn.sync().hgetall("large-hash"); // Slow: 5%
          }
        }
      };

  /**
   * Slow-only workload (HGETALL commands only).
   *
   * <p><strong>Command:</strong> {@code HGETALL large-hash} (10,000 fields × 50 bytes ≈ 500KB)
   *
   * <p><strong>Latency:</strong> ~18ms (500KB ÷ 200 Mbps ≈ 20ms theoretical, ~18ms observed)
   *
   * <p><strong>Use case:</strong> Worst-case HOL blocking scenario
   */
  public static final WorkloadPattern SLOW_ONLY = conn -> conn.sync().hgetall("large-hash");

  /**
   * Functional interface for workload execution.
   *
   * <p><strong>Contract:</strong> Implementations must execute exactly one Redis command per
   * invocation. JMH measures each invocation's latency.
   */
  @FunctionalInterface
  public interface WorkloadPattern {
    /**
     * Executes workload (one Redis command).
     *
     * @param conn Redis connection
     */
    void execute(StatefulRedisConnection<String, String> conn);
  }
}
