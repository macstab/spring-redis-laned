/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;

/**
 * Configures the Redis Laned connection manager with explicit lanes and selection strategy.
 *
 * <p>This annotation provides a declarative way to configure {@code LanedConnectionManager}
 * directly on your Spring Boot application class or configuration class, <strong>overriding any
 * YAML/properties file configuration</strong>.
 *
 * <p><strong>Configuration Precedence (highest to lowest):</strong>
 *
 * <ol>
 *   <li><strong>{@code @LanedRedisConnection} annotation</strong> (this annotation)
 *   <li>YAML/properties file ({@code spring.redis.laned.*})
 *   <li>Defaults (lanes=4, strategy=ROUND_ROBIN)
 * </ol>
 *
 * <p><strong>Basic Usage:</strong>
 *
 * <pre>{@code
 * @SpringBootApplication
 * @LanedRedisConnection(lanes = 8, strategy = THREAD_AFFINITY)
 * public class MyApplication {
 *   public static void main(String[] args) {
 *     SpringApplication.run(MyApplication.class, args);
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Configuration Class:</strong>
 *
 * <pre>{@code
 * @Configuration
 * @LanedRedisConnection(lanes = 16, strategy = LEAST_USED)
 * public class RedisConfig {
 *   // LanedConnectionManager auto-configured with these settings
 * }
 * }</pre>
 *
 * <p><strong>Override Behavior:</strong>
 *
 * <p>Given this YAML:
 *
 * <pre>{@code
 * spring:
 *   redis:
 *     laned:
 *       lanes: 4
 *       strategy: ROUND_ROBIN
 * }</pre>
 *
 * <p>This annotation will override it:
 *
 * <pre>{@code
 * @LanedRedisConnection(lanes = 8, strategy = THREAD_AFFINITY)
 * // Result: lanes=8, strategy=THREAD_AFFINITY (annotation wins)
 * }</pre>
 *
 * <p><strong>When to Use:</strong>
 *
 * <ul>
 *   <li>Explicit configuration in code (self-documenting)
 *   <li>Per-environment configuration classes (dev/staging/prod)
 *   <li>Override defaults without touching YAML files
 *   <li>Type-safe configuration (compile-time validation)
 * </ul>
 *
 * <p><strong>When to Use YAML Instead:</strong>
 *
 * <ul>
 *   <li>Configuration changes without recompilation
 *   <li>Externalized config (Docker, Kubernetes ConfigMaps)
 *   <li>Multiple deployment targets with same JAR
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see LaneSelectionStrategyType
 * @see com.macstab.oss.redis.laned.LanedConnectionManager
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LanedRedisConnection {

  /**
   * Number of connection lanes (pools).
   *
   * <p>Each lane maintains an independent Redis connection. Higher lane count reduces contention
   * under concurrent load but increases resource usage (connections, memory).
   *
   * <p><strong>Default:</strong> 4 (balanced for most applications)
   *
   * <p><strong>Tuning Guide:</strong>
   *
   * <ul>
   *   <li><strong>Low concurrency (&lt;10 threads):</strong> 2-4 lanes
   *   <li><strong>Medium concurrency (10-50 threads):</strong> 4-8 lanes
   *   <li><strong>High concurrency (50-200 threads):</strong> 8-16 lanes
   *   <li><strong>Very high concurrency (&gt;200 threads):</strong> 16-32 lanes
   * </ul>
   *
   * <p><strong>Note:</strong> More lanes ≠ always faster. Tune based on actual thread count and
   * Redis server capacity.
   *
   * @return number of lanes (must be &gt; 0)
   */
  int lanes() default 4;

  /**
   * Lane selection strategy.
   *
   * <p><strong>Available Strategies:</strong>
   *
   * <ul>
   *   <li><strong>ROUND_ROBIN</strong> (default): Cycle through lanes sequentially
   *       <ul>
   *         <li>✅ Best for: Uniform workloads, stateless operations
   *         <li>✅ Fair distribution across lanes
   *         <li>❌ May cause thread contention on shared counter
   *       </ul>
   *   <li><strong>THREAD_AFFINITY</strong>: Sticky lane per thread
   *       <ul>
   *         <li>✅ Best for: Thread-local caching, transaction-heavy workloads
   *         <li>✅ Zero contention (thread-local)
   *         <li>❌ Unbalanced if thread count ≠ lane count
   *       </ul>
   *   <li><strong>LEAST_USED</strong>: Select lane with lowest active connection count
   *       <ul>
   *         <li>✅ Best for: Variable workloads, mixed operation types
   *         <li>✅ Dynamic load balancing
   *         <li>❌ Slight overhead (atomic counter reads)
   *       </ul>
   * </ul>
   *
   * <p><strong>Default:</strong> {@code ROUND_ROBIN} (good general-purpose choice)
   *
   * @return selection strategy
   */
  LaneSelectionStrategyType strategy() default LaneSelectionStrategyType.ROUND_ROBIN;

  /**
   * Enable detailed metrics for lane selection and connection usage.
   *
   * <p>When enabled, the following metrics are exposed:
   *
   * <ul>
   *   <li>{@code redis.laned.lane.selection} - Lane selection distribution
   *   <li>{@code redis.laned.lane.active_connections} - Active connections per lane
   *   <li>{@code redis.laned.lane.wait_time} - Time waiting for available connection
   * </ul>
   *
   * <p><strong>Default:</strong> {@code true} (enabled)
   *
   * <p><strong>Disable for:</strong>
   *
   * <ul>
   *   <li>Ultra-low latency requirements (metrics add ~10-50μs overhead)
   *   <li>Very high throughput (&gt;100k ops/sec)
   * </ul>
   *
   * @return {@code true} to enable metrics, {@code false} to disable
   */
  boolean metricsEnabled() default true;
}
