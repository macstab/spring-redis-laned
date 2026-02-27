/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics.micrometer;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tests for {@link MetricCache}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("MetricCache")
class MetricCacheTest {

  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
  }

  @Nested
  @DisplayName("Constructor")
  class Constructor {

    @Test
    @DisplayName("should create cache with valid parameters")
    void shouldCreateCacheWithValidParameters() {
      // Arrange + Act
      final var cache = new MetricCache(registry, 1000);

      // Assert
      assertThat(cache.getMaxCacheSize()).isEqualTo(1000);
      assertThat(cache.getCacheSize()).isZero();
    }

    @Test
    @DisplayName("should reject null registry")
    void shouldRejectNullRegistry() {
      // Arrange + Act + Assert
      assertThatNullPointerException()
          .isThrownBy(() -> new MetricCache(null, 1000))
          .withMessageContaining("MeterRegistry must not be null");
    }

    @Test
    @DisplayName("should reject zero max cache size")
    void shouldRejectZeroMaxCacheSize() {
      // Arrange + Act + Assert
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new MetricCache(registry, 0))
          .withMessageContaining("maxCacheSize must be > 0");
    }

    @Test
    @DisplayName("should reject negative max cache size")
    void shouldRejectNegativeMaxCacheSize() {
      // Arrange + Act + Assert
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new MetricCache(registry, -1))
          .withMessageContaining("maxCacheSize must be > 0");
    }
  }

  @Nested
  @DisplayName("Counter Caching")
  class CounterCaching {

    @Test
    @DisplayName("should cache counter instance on first access")
    void shouldCacheCounterInstanceOnFirstAccess() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      // Act
      final var counter1 =
          cache.getOrCreateCounter(
              "test.counter", "Test counter", "connection.name", "primary", "lane.index", "3");

      final var counter2 =
          cache.getOrCreateCounter(
              "test.counter", "Test counter", "connection.name", "primary", "lane.index", "3");

      // Assert
      assertThat(counter1).isSameAs(counter2); // Same instance (cached)
      assertThat(cache.getCacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("should create separate counters for different tags")
    void shouldCreateSeparateCountersForDifferentTags() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      // Act
      final var counter1 =
          cache.getOrCreateCounter(
              "test.counter", "Test counter", "connection.name", "primary", "lane.index", "3");

      final var counter2 =
          cache.getOrCreateCounter(
              "test.counter", "Test counter", "connection.name", "cache", "lane.index", "3");

      // Assert
      assertThat(counter1).isNotSameAs(counter2); // Different instances
      assertThat(cache.getCacheSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("should reject odd-length tag pairs")
    void shouldRejectOddLengthTagPairs() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      // Act + Assert
      assertThatIllegalArgumentException()
          .isThrownBy(
              () ->
                  cache.getOrCreateCounter(
                      "test.counter",
                      "Test counter",
                      "connection.name",
                      "primary",
                      "lane.index" // Missing value
                      ))
          .withMessageContaining("Tag pairs must have even length");
    }

    @Test
    @DisplayName("should increment counter correctly")
    void shouldIncrementCounterCorrectly() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      // Act
      final var counter =
          cache.getOrCreateCounter("test.counter", "Test counter", "connection.name", "primary");

      counter.increment();
      counter.increment();

      // Assert
      assertThat(counter.count()).isEqualTo(2.0);
    }
  }

  @Nested
  @DisplayName("Gauge Caching")
  class GaugeCaching {

    @Test
    @DisplayName("should cache gauge value on first access")
    void shouldCacheGaugeValueOnFirstAccess() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      // Act
      final var gauge1 =
          cache.getOrCreateGaugeValue(
              "test.gauge", "Test gauge", "connection.name", "primary", "lane.index", "3");

      final var gauge2 =
          cache.getOrCreateGaugeValue(
              "test.gauge", "Test gauge", "connection.name", "primary", "lane.index", "3");

      // Assert
      assertThat(gauge1).isSameAs(gauge2); // Same instance (cached)
      assertThat(cache.getCacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("should update gauge value correctly")
    void shouldUpdateGaugeValueCorrectly() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      // Act
      final var gaugeValue =
          cache.getOrCreateGaugeValue(
              "test.gauge", "Test gauge", "connection.name", "primary", "lane.index", "3");

      gaugeValue.set(42);

      // Assert
      assertThat(gaugeValue.get()).isEqualTo(42);

      // Verify gauge registered in Micrometer
      final var meters = registry.find("test.gauge").tag("connection.name", "primary").meters();

      assertThat(meters).hasSize(1);
      final var meter = meters.stream().findFirst().orElseThrow();
      assertThat(meter.getId().getTag("connection.name")).isEqualTo("primary");
      assertThat(meter.getId().getTag("lane.index")).isEqualTo("3");
    }
  }

  @Nested
  @DisplayName("Cache Size Management")
  class CacheSizeManagement {

    @Test
    @DisplayName("should respect max cache size (graceful degradation)")
    void shouldRespectMaxCacheSize() {
      // Arrange
      final var cache = new MetricCache(registry, 2); // Small cache

      // Act
      final var counter1 = cache.getOrCreateCounter("test.counter", "Test", "tag1", "value1");
      final var counter2 = cache.getOrCreateCounter("test.counter", "Test", "tag1", "value2");
      final var counter3 =
          cache.getOrCreateCounter("test.counter", "Test", "tag1", "value3"); // Beyond limit

      // Assert
      assertThat(cache.getCacheSize()).isEqualTo(2); // Capped at max
      assertThat(counter1).isNotNull();
      assertThat(counter2).isNotNull();
      assertThat(counter3).isNotNull(); // Still works (direct registry)
    }

    @Test
    @DisplayName("should track cache size correctly with mixed metrics")
    void shouldTrackCacheSizeCorrectlyWithMixedMetrics() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      // Act
      cache.getOrCreateCounter("test.counter", "Test", "tag", "1");
      cache.getOrCreateCounter("test.counter", "Test", "tag", "2");
      cache.getOrCreateGaugeValue("test.gauge", "Test", "tag", "1");
      cache.getOrCreateGaugeValue("test.gauge", "Test", "tag", "2");

      // Assert
      assertThat(cache.getCacheSize()).isEqualTo(4); // 2 counters + 2 gauges
    }
  }

  @Nested
  @DisplayName("Gauge Cleanup")
  class GaugeCleanup {

    @Test
    @DisplayName("should remove gauges for specific connection")
    void shouldRemoveGaugesForSpecificConnection() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      cache.getOrCreateGaugeValue(
          "test.gauge", "Test gauge", "connection.name", "primary", "lane.index", "1");

      cache.getOrCreateGaugeValue(
          "test.gauge", "Test gauge", "connection.name", "primary", "lane.index", "2");

      cache.getOrCreateGaugeValue(
          "test.gauge", "Test gauge", "connection.name", "cache", "lane.index", "1");

      assertThat(cache.getCacheSize()).isEqualTo(3);

      // Act
      cache.removeGaugesForConnection("primary");

      // Assert
      assertThat(cache.getCacheSize()).isEqualTo(1); // Only cache connection remains
    }

    @Test
    @DisplayName("should not remove gauges for other connections")
    void shouldNotRemoveGaugesForOtherConnections() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      cache.getOrCreateGaugeValue(
          "test.gauge", "Test gauge", "connection.name", "primary", "lane.index", "1");

      cache.getOrCreateGaugeValue(
          "test.gauge", "Test gauge", "connection.name", "cache", "lane.index", "1");

      // Act
      cache.removeGaugesForConnection("primary");

      // Assert - verify cache gauge still in registry
      final var meters = registry.find("test.gauge").tag("connection.name", "cache").meters();

      assertThat(meters).hasSize(1);
    }
  }

  @Nested
  @DisplayName("Key Generation")
  class KeyGeneration {

    @Test
    @DisplayName("should generate consistent keys for same tags")
    void shouldGenerateConsistentKeysForSameTags() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      // Act
      final var counter1 =
          cache.getOrCreateCounter(
              "test.counter", "Test", "connection.name", "primary", "lane.index", "3");

      final var counter2 =
          cache.getOrCreateCounter(
              "test.counter", "Test", "connection.name", "primary", "lane.index", "3");

      // Assert
      assertThat(counter1).isSameAs(counter2); // Same key → same instance
    }

    @Test
    @DisplayName("should generate different keys for different tag values")
    void shouldGenerateDifferentKeysForDifferentTagValues() {
      // Arrange
      final var cache = new MetricCache(registry, 1000);

      // Act
      final var counter1 =
          cache.getOrCreateCounter("test.counter", "Test", "connection.name", "primary");

      final var counter2 =
          cache.getOrCreateCounter("test.counter", "Test", "connection.name", "cache");

      // Assert
      assertThat(counter1).isNotSameAs(counter2); // Different keys
    }
  }

  @Nested
  @DisplayName("Thread Safety")
  class ThreadSafety {

    @Test
    @DisplayName("should handle concurrent counter creation")
    void shouldHandleConcurrentCounterCreation() throws InterruptedException {
      // Arrange
      final var cache = new MetricCache(registry, 1000);
      final var numThreads = 10;
      final var threads = new Thread[numThreads];

      // Act
      for (int i = 0; i < numThreads; i++) {
        threads[i] =
            new Thread(
                () -> {
                  final var counter =
                      cache.getOrCreateCounter(
                          "test.counter", "Test", "connection.name", "primary");
                  counter.increment();
                });
        threads[i].start();
      }

      for (final var thread : threads) {
        thread.join();
      }

      // Assert
      final var counter =
          cache.getOrCreateCounter("test.counter", "Test", "connection.name", "primary");

      assertThat(counter.count()).isEqualTo(numThreads);
      assertThat(cache.getCacheSize()).isEqualTo(1); // Single cached counter
    }
  }
}
