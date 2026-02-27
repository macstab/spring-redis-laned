/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3;

/**
 * Redis connection strategy modes.
 *
 * <pre>{@code
 * spring:
 *   data:
 *     redis:
 *       connection:
 *         strategy: CLASSIC  # CLASSIC, POOLED, or LANED
 *         lanes: 8           # only used when strategy=LANED
 * }</pre>
 */
public enum RedisConnectionStrategy {

  /**
   * Classic Spring Data Redis behavior. Single shared native connection for non-blocking
   * operations. Simple, low overhead, good for low-to-medium concurrency.
   *
   * <p><strong>Use when:</strong> Low concurrency, uniform fast commands (&lt; 1ms), simple setup.
   *
   * <p><strong>Limitation:</strong> Single FIFO queue - one slow command blocks all subsequent
   * commands.
   */
  CLASSIC,

  /**
   * Traditional commons-pool based connection pooling. Borrows/returns connections; blocks when
   * pool exhausted. Requires {@code spring.data.redis.lettuce.pool.enabled=true}.
   *
   * <p><strong>Use when:</strong> Need connection isolation per request, willing to pay connection
   * count cost.
   *
   * <p><strong>Limitation:</strong> Connection count explosion at scale (N pods × M threads = N×M
   * connections). Can overload Redis with connection churn.
   */
  POOLED,

  /**
   * Fixed N multiplexed connections (lanes). Round-robin selection; no blocking; always
   * multiplexes. Reduces head-of-line blocking while keeping connection count low.
   *
   * <p><strong>Use when:</strong> High concurrency, mixed fast/slow commands, Redis connection
   * limit is a concern.
   *
   * <p><strong>Benefit:</strong> 84-87% reduction in HOL-blocked commands (N=8) vs single
   * connection. 84% reduction in connection count vs pooled (8 lanes vs 50-pool connections per
   * pod).
   *
   * <p><strong>Limitation:</strong> Round-robin is probabilistic, not guaranteed isolation. No
   * priority queuing (critical vs background commands share lanes).
   */
  LANED
}
