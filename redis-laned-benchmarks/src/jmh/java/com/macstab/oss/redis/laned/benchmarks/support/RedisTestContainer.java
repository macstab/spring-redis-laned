/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.benchmarks.support;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;

/**
 * Singleton Redis container manager for JMH benchmarks.
 *
 * <p><strong>Problem:</strong> Testcontainers startup is slow (5-10 seconds). Starting container
 * per benchmark would add 5-10s × N benchmarks overhead.
 *
 * <p><strong>Solution:</strong> Singleton pattern + Testcontainers reuse. Container started ONCE
 * for entire benchmark suite, stopped ONCE at end.
 *
 * <p><strong>Thread Safety:</strong> Singleton instance created via double-checked locking. Once
 * initialized, container reference is immutable (final field).
 *
 * <p><strong>Lifecycle:</strong>
 *
 * <pre>
 * First benchmark calls start()
 *   → Container starts (5-10s, ONE-TIME cost)
 *   → Pre-populate test data (1000 keys, large hash)
 *   → Return connection details
 *
 * Subsequent benchmarks call start()
 *   → Return existing container (0ms cost)
 *
 * JVM shutdown hook
 *   → stop() called
 *   → Container stopped, resources released
 * </pre>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
public final class RedisTestContainer {

  private static final Logger log = LoggerFactory.getLogger(RedisTestContainer.class);

  private static volatile RedisTestContainer instance;

  private final GenericContainer<?> container;
  private final String host;
  private final int port;

  private RedisTestContainer() {
    log.info("Starting Redis container (one-time startup, ~5-10s)...");

    this.container =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withStartupTimeout(Duration.ofSeconds(60))
            .withReuse(true); // Reuse across Gradle runs (if Testcontainers daemon enabled)

    try {
      container.start();
    } catch (final Exception e) {
      throw new RuntimeException(
          "Failed to start Redis container. Ensure Docker is running and port 6379 is free.", e);
    }

    this.host = container.getHost();
    this.port = container.getFirstMappedPort();

    log.info("Redis container started: {}:{}", host, port);

    // Pre-populate test data (shared across all benchmarks)
    prepopulateTestData();

    // Register shutdown hook (cleanup on JVM exit)
    Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "redis-container-shutdown"));
  }

  /**
   * Returns singleton container instance.
   *
   * <p><strong>Thread Safety:</strong> Double-checked locking with volatile. First thread creates
   * instance, subsequent threads return cached instance.
   *
   * @return singleton container instance
   */
  public static RedisTestContainer getInstance() {
    if (instance == null) {
      synchronized (RedisTestContainer.class) {
        if (instance == null) {
          instance = new RedisTestContainer();
        }
      }
    }
    return instance;
  }

  /**
   * Returns Redis host (e.g., "localhost" or Docker bridge IP).
   *
   * @return Redis host
   */
  public String getHost() {
    return host;
  }

  /**
   * Returns Redis port (mapped by Testcontainers, typically random port 32768+).
   *
   * @return Redis port
   */
  public int getPort() {
    return port;
  }

  /**
   * Creates RedisURI for connecting to container.
   *
   * @return RedisURI pointing to container
   */
  public RedisURI getRedisURI() {
    return RedisURI.builder().withHost(host).withPort(port).build();
  }

  /**
   * Pre-populates test data (shared across all benchmarks).
   *
   * <p><strong>Data seeded:</strong>
   *
   * <ul>
   *   <li>1000 string keys: {@code key-0} through {@code key-999}, values {@code value-0} through
   *       {@code value-999}
   *   <li>1 large hash: {@code large-hash}, 10,000 fields × 50 bytes each ≈ 500KB (simulates slow
   *       HGETALL)
   * </ul>
   *
   * <p><strong>Why pre-populate:</strong> Benchmarks measure GET/HGETALL latency. Pre-populating
   * ensures Redis cache is warm (no misses) and HGETALL has realistic data size.
   */
  private void prepopulateTestData() {
    log.info("Pre-populating test data (1000 keys + 500KB hash)...");

    final RedisClient client = RedisClient.create(getRedisURI());

    try (StatefulRedisConnection<String, String> conn = client.connect(StringCodec.UTF8)) {
      final var sync = conn.sync();

      // 1000 string keys
      for (int i = 0; i < 1000; i++) {
        sync.set("key-" + i, "value-" + i);
      }

      // Large hash (500KB for slow HGETALL simulation)
      final Map<String, String> largeHash = new HashMap<>(10000);
      for (int i = 0; i < 10000; i++) {
        largeHash.put("field-" + i, "x".repeat(50)); // 50 bytes per field
      }
      sync.hset("large-hash", largeHash);

      log.info("Test data pre-populated successfully");
    } finally {
      client.shutdown();
    }
  }

  /**
   * Stops container (called by shutdown hook).
   *
   * <p><strong>Idempotent:</strong> Safe to call multiple times.
   */
  private void stop() {
    if (container != null && container.isRunning()) {
      log.info("Stopping Redis container...");
      container.stop();
      log.info("Redis container stopped");
    }
  }
}
