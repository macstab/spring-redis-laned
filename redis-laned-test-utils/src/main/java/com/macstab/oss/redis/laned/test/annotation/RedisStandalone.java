/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.test.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import com.macstab.oss.redis.laned.test.extension.RedisContainerExtension;

/**
 * Starts a standalone Redis container for integration tests using Testcontainers.
 *
 * <p>The container is automatically started before all tests in the class and stopped after all
 * tests complete. Connection details are made available via {@link RedisContainerExtension.Store}.
 *
 * <p><strong>Basic Usage:</strong>
 *
 * <pre>{@code
 * @RedisStandalone
 * class MyRedisTest {
 *
 *   @Test
 *   void test(RedisConnectionInfo info) {
 *     // Redis running on info.getHost() : info.getPort()
 *   }i
 * }
 * }</pre>
 *
 * <p><strong>Custom Configuration:</strong>
 *
 * <pre>{@code
 * @RedisStandalone(
 *   version = "7.4",
 *   args = {"--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"}
 * )
 * class CacheEvictionTest {
 *   // Redis 7.4 with custom memory config
 * }
 * }</pre>
 *
 * <p><strong>Container Lifecycle:</strong>
 *
 * <ul>
 *   <li>Scope: {@code @BeforeAll} / {@code @AfterAll} (class-level, singleton)
 *   <li>Reuse: Same container for all tests in class
 *   <li>Cleanup: Automatic (Testcontainers handles shutdown)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 * @see RedisContainerExtension
 * @see RedisSentinel
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(RedisContainerExtension.class)
public @interface RedisStandalone {

  /**
   * Redis Docker image version tag.
   *
   * <p>Default: {@code "7.4"} (latest stable as of 2026)
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "7.4"} (latest 7.x)
   *   <li>{@code "7.2-alpine"} (Alpine Linux variant)
   *   <li>{@code "6.2"} (older version)
   * </ul>
   *
   * @return Docker image tag
   */
  String version() default "7.4";

  /**
   * Exposed host port for Redis.
   *
   * <p>Default: {@code 0} (random available port, recommended for CI)
   *
   * <p>Use fixed port only for debugging:
   *
   * <pre>{@code
   * @RedisStandalone(port = 6379) // Always localhost:6379
   * }</pre>
   *
   * @return host port (0 = random)
   */
  int port() default 0;

  /**
   * Additional Redis server command-line arguments.
   *
   * <p>Default: {@code []} (no extra args)
   *
   * <p>Example:
   *
   * <pre>{@code
   * @RedisStandalone(args = {
   *   "--requirepass", "secret",
   *   "--maxclients", "100"
   * })
   * }</pre>
   *
   * @return Redis CLI arguments
   */
  String[] args() default {};
}
