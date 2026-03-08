/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.test.annotation;

import java.util.function.Function;

/**
 * Generic manager for Redis test containers (standalone or Sentinel).
 *
 * <p>Provides programmatic access to containers started by {@link RedisStandalone} or {@link
 * RedisSentinel} annotations.
 *
 * <p><strong>Standalone Usage:</strong>
 *
 * <pre>{@code
 * @RedisStandalone(id = "master")
 * class MyTest {
 *   @BeforeEach
 *   void setUp() {
 *     final var redis = RedisStandalone.INSTANCE.get("master");
 *     // redis.getHost(), redis.getPort()
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Sentinel Usage:</strong>
 *
 * <pre>{@code
 * @RedisSentinel(id = "ha-cluster")
 * class MyTest {
 *   @BeforeEach
 *   void setUp() {
 *     final var cluster = RedisSentinel.INSTANCE.get("ha-cluster");
 *     // cluster.getMasterHost(), cluster.getMasterPort()
 *   }
 * }
 * }</pre>
 *
 * <p><strong>Thread Safety:</strong> This class delegates to extension-managed ThreadLocal
 * contexts. Safe for parallel test execution.
 *
 * @param <T> container info type (RedisConnectionInfo or SentinelCluster)
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisManager<T> {

  private final Function<String, T> containerAccessor;

  /**
   * Package-private constructor (only called from annotations).
   *
   * @param containerAccessor function to retrieve container by ID
   */
  RedisManager(final Function<String, T> containerAccessor) {
    this.containerAccessor = containerAccessor;
  }

  /**
   * Get default container (ID = "default").
   *
   * <p>Convenience for {@code get("default")}.
   *
   * @return container info
   * @throws IllegalStateException if no container started
   * @throws IllegalArgumentException if default ID not found
   */
  public T get() {
    return get("default");
  }

  /**
   * Get container by ID.
   *
   * @param id container ID from annotation (e.g., {@code @RedisStandalone(id = "master")})
   * @return container info
   * @throws IllegalArgumentException if ID not found
   * @throws IllegalStateException if called outside test context
   */
  public T get(final String id) {
    return containerAccessor.apply(id);
  }
}
