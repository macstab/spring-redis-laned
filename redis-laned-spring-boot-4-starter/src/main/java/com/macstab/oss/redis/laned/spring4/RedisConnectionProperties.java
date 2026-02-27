/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Redis connection strategy configuration properties.
 *
 * <pre>{@code
 * spring:
 *   data:
 *     redis:
 *       connection:
 *         strategy: CLASSIC   # CLASSIC (default) | POOLED | LANED
 *         lanes: 8            # only for LANED strategy (1-64)
 * }</pre>
 *
 * <p><strong>Lane count sizing guidance:</strong>
 *
 * <ul>
 *   <li><strong>4 lanes:</strong> Light workloads, memory-constrained environments
 *   <li><strong>8 lanes (default):</strong> Balanced for most workloads
 *   <li><strong>16 lanes:</strong> High-concurrency, mixed fast/slow commands
 *   <li><strong>32+ lanes:</strong> Fan-out workloads, many concurrent blocking commands ({@code
 *       BLPOP}, {@code BRPOP})
 * </ul>
 *
 * <p><strong>Connection budget:</strong> Each lane = 1 TCP connection. With 8 lanes and 30 pods:
 * 240 total connections to Redis (vs 1,500 for a 50-connection pool).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "spring.data.redis.connection")
public class RedisConnectionProperties {

  public static final int MIN_LANES = 1;
  public static final int MAX_LANES = 64;
  public static final int DEFAULT_LANES = 8;

  /**
   * Connection strategy: CLASSIC (single shared connection), POOLED (commons-pool), or LANED (fixed
   * multiplexed connections).
   */
  private RedisConnectionStrategy strategy = RedisConnectionStrategy.CLASSIC;

  /**
   * Number of lanes for LANED strategy. Ignored for CLASSIC and POOLED.
   *
   * <p>Valid range: 1-64. Values outside this range are clamped.
   */
  private int lanes = DEFAULT_LANES;

  /**
   * Sets the number of lanes, clamping to valid range [MIN_LANES, MAX_LANES].
   *
   * @param lanes requested lane count
   */
  public void setLanes(int lanes) {
    this.lanes = Math.max(MIN_LANES, Math.min(lanes, MAX_LANES));
  }
}
