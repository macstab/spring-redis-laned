/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics.micrometer;

import static com.macstab.oss.redis.laned.metrics.micrometer.MetricsConfiguration.*;
import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tests for {@link MicrometerLanedRedisMetrics} (dimensional version).
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <ul>
 *   <li>AAA pattern strictly enforced
 *   <li>{@link SimpleMeterRegistry} for isolation
 *   <li>Verify dimensional tags (connection.name, lane.index, strategy.name)
 *   <li>Verify counter/gauge values directly
 *   <li>Edge cases (negative lane index, null params, concurrent access, cleanup)
 * </ul>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("MicrometerLanedRedisMetrics (Dimensional)")
class MicrometerLanedRedisMetricsTest {

  private SimpleMeterRegistry registry;
  private MicrometerLanedRedisMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new MicrometerLanedRedisMetrics(registry, "primary", 1000);
  }

  @Nested
  @DisplayName("Constructor")
  class ConstructorTests {

    @Test
    @DisplayName("should reject null MeterRegistry")
    void shouldRejectNullMeterRegistry() {
      // Act & Assert
      assertThatNullPointerException()
          .isThrownBy(() -> new MicrometerLanedRedisMetrics(null, "primary", 1000))
          .withMessageContaining("MeterRegistry must not be null");
    }

    @Test
    @DisplayName("should reject null connection name")
    void shouldRejectNullConnectionName() {
      // Act & Assert
      assertThatNullPointerException()
          .isThrownBy(() -> new MicrometerLanedRedisMetrics(new SimpleMeterRegistry(), null, 1000))
          .withMessageContaining("connectionName must not be null");
    }

    @Test
    @DisplayName("should reject invalid max cache size")
    void shouldRejectInvalidMaxCacheSize() {
      // Act & Assert
      assertThatIllegalArgumentException()
          .isThrownBy(
              () -> new MicrometerLanedRedisMetrics(new SimpleMeterRegistry(), "primary", 0))
          .withMessageContaining("maxCacheSize must be > 0");
    }

    @Test
    @DisplayName("should create with valid parameters")
    void shouldCreateWithValidParameters() {
      // Act & Assert
      assertThatCode(
              () -> new MicrometerLanedRedisMetrics(new SimpleMeterRegistry(), "primary", 1000))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should use default max cache size")
    void shouldUseDefaultMaxCacheSize() {
      // Act
      final var metricsWithDefault = new MicrometerLanedRedisMetrics(registry, "primary");

      // Assert
      assertThat(metricsWithDefault.getCacheSize()).isZero();
    }
  }

  @Nested
  @DisplayName("Lane Selection Counter (Dimensional)")
  class LaneSelectionTests {

    @Test
    @DisplayName("should record lane selection with dimensional tags")
    void shouldRecordLaneSelectionWithDimensionalTags() {
      // Act
      metrics.recordLaneSelection("primary", 0, "round-robin");
      metrics.recordLaneSelection("primary", 0, "round-robin");
      metrics.recordLaneSelection("primary", 1, "round-robin");

      // Assert - verify dimensional tags
      assertThat(
              registry
                  .counter(
                      LANE_SELECTIONS,
                      TAG_CONNECTION_NAME,
                      "primary",
                      TAG_LANE_INDEX,
                      "0",
                      TAG_STRATEGY_NAME,
                      "round-robin")
                  .count())
          .isEqualTo(2.0);

      assertThat(
              registry
                  .counter(
                      LANE_SELECTIONS,
                      TAG_CONNECTION_NAME,
                      "primary",
                      TAG_LANE_INDEX,
                      "1",
                      TAG_STRATEGY_NAME,
                      "round-robin")
                  .count())
          .isEqualTo(1.0);
    }

    @Test
    @DisplayName("should separate metrics by connection name")
    void shouldSeparateMetricsByConnectionName() {
      // Arrange
      final var cacheMetrics = new MicrometerLanedRedisMetrics(registry, "cache", 1000);

      // Act
      metrics.recordLaneSelection("primary", 0, "round-robin");
      cacheMetrics.recordLaneSelection("cache", 0, "round-robin");

      // Assert - separate counters per connection
      assertThat(
              registry
                  .counter(
                      LANE_SELECTIONS,
                      TAG_CONNECTION_NAME,
                      "primary",
                      TAG_LANE_INDEX,
                      "0",
                      TAG_STRATEGY_NAME,
                      "round-robin")
                  .count())
          .isEqualTo(1.0);

      assertThat(
              registry
                  .counter(
                      LANE_SELECTIONS,
                      TAG_CONNECTION_NAME,
                      "cache",
                      TAG_LANE_INDEX,
                      "0",
                      TAG_STRATEGY_NAME,
                      "round-robin")
                  .count())
          .isEqualTo(1.0);
    }

    @Test
    @DisplayName("should handle multiple strategies separately")
    void shouldHandleMultipleStrategiesSeparately() {
      // Act
      metrics.recordLaneSelection("primary", 0, "round-robin");
      metrics.recordLaneSelection("primary", 0, "thread-affinity");
      metrics.recordLaneSelection("primary", 0, "least-used");

      // Assert - separate counters per strategy
      assertThat(
              registry
                  .counter(
                      LANE_SELECTIONS,
                      TAG_CONNECTION_NAME,
                      "primary",
                      TAG_LANE_INDEX,
                      "0",
                      TAG_STRATEGY_NAME,
                      "round-robin")
                  .count())
          .isEqualTo(1.0);

      assertThat(
              registry
                  .counter(
                      LANE_SELECTIONS,
                      TAG_CONNECTION_NAME,
                      "primary",
                      TAG_LANE_INDEX,
                      "0",
                      TAG_STRATEGY_NAME,
                      "thread-affinity")
                  .count())
          .isEqualTo(1.0);

      assertThat(
              registry
                  .counter(
                      LANE_SELECTIONS,
                      TAG_CONNECTION_NAME,
                      "primary",
                      TAG_LANE_INDEX,
                      "0",
                      TAG_STRATEGY_NAME,
                      "least-used")
                  .count())
          .isEqualTo(1.0);
    }

    @Test
    @DisplayName("should skip negative lane index")
    void shouldSkipNegativeLaneIndex() {
      // Act
      metrics.recordLaneSelection("primary", -1, "round-robin");

      // Assert - no counter registered
      assertThat(registry.find(LANE_SELECTIONS).counters()).isEmpty();
    }
  }

  @Nested
  @DisplayName("In-Flight Gauge (Dimensional)")
  class InFlightGaugeTests {

    @Test
    @DisplayName("should increment in-flight gauge with dimensional tags")
    void shouldIncrementInFlightGaugeWithDimensionalTags() {
      // Act
      metrics.recordInFlightIncrement("primary", 0);
      metrics.recordInFlightIncrement("primary", 0);
      metrics.recordInFlightIncrement("primary", 0);

      // Assert
      assertThat(
              registry
                  .find(LANE_IN_FLIGHT)
                  .tag(TAG_CONNECTION_NAME, "primary")
                  .tag(TAG_LANE_INDEX, "0")
                  .gauge()
                  .value())
          .isEqualTo(3.0);
    }

    @Test
    @DisplayName("should decrement in-flight gauge")
    void shouldDecrementInFlightGauge() {
      // Arrange
      metrics.recordInFlightIncrement("primary", 0);
      metrics.recordInFlightIncrement("primary", 0);
      metrics.recordInFlightIncrement("primary", 0);

      // Act
      metrics.recordInFlightDecrement("primary", 0);

      // Assert
      assertThat(
              registry
                  .find(LANE_IN_FLIGHT)
                  .tag(TAG_CONNECTION_NAME, "primary")
                  .tag(TAG_LANE_INDEX, "0")
                  .gauge()
                  .value())
          .isEqualTo(2.0);
    }

    @Test
    @DisplayName("should prevent negative in-flight count")
    void shouldPreventNegativeInFlightCount() {
      // Act - decrement when already 0
      metrics.recordInFlightDecrement("primary", 0);
      metrics.recordInFlightDecrement("primary", 0);

      // Assert - stays at 0 (never negative)
      assertThat(
              registry
                  .find(LANE_IN_FLIGHT)
                  .tag(TAG_CONNECTION_NAME, "primary")
                  .tag(TAG_LANE_INDEX, "0")
                  .gauge()
                  .value())
          .isEqualTo(0.0);
    }

    @Test
    @DisplayName("should separate metrics by connection name")
    void shouldSeparateMetricsByConnectionName() {
      // Arrange
      final var cacheMetrics = new MicrometerLanedRedisMetrics(registry, "cache", 1000);

      // Act
      metrics.recordInFlightIncrement("primary", 0);
      metrics.recordInFlightIncrement("primary", 0);
      cacheMetrics.recordInFlightIncrement("cache", 0);

      // Assert
      assertThat(
              registry
                  .find(LANE_IN_FLIGHT)
                  .tag(TAG_CONNECTION_NAME, "primary")
                  .tag(TAG_LANE_INDEX, "0")
                  .gauge()
                  .value())
          .isEqualTo(2.0);

      assertThat(
              registry
                  .find(LANE_IN_FLIGHT)
                  .tag(TAG_CONNECTION_NAME, "cache")
                  .tag(TAG_LANE_INDEX, "0")
                  .gauge()
                  .value())
          .isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("CAS Retry Counter (Dimensional)")
  class CASRetryTests {

    @Test
    @DisplayName("should record CAS retry with dimensional tags")
    void shouldRecordCASRetryWithDimensionalTags() {
      // Act
      metrics.recordCASRetry("primary", "least-used");
      metrics.recordCASRetry("primary", "least-used");
      metrics.recordCASRetry("primary", "least-used");

      // Assert
      assertThat(
              registry
                  .counter(
                      CAS_RETRIES, TAG_CONNECTION_NAME, "primary", TAG_STRATEGY_NAME, "least-used")
                  .count())
          .isEqualTo(3.0);
    }

    @Test
    @DisplayName("should separate metrics by connection name")
    void shouldSeparateMetricsByConnectionName() {
      // Arrange
      final var cacheMetrics = new MicrometerLanedRedisMetrics(registry, "cache", 1000);

      // Act
      metrics.recordCASRetry("primary", "least-used");
      cacheMetrics.recordCASRetry("cache", "least-used");

      // Assert
      assertThat(
              registry
                  .counter(
                      CAS_RETRIES, TAG_CONNECTION_NAME, "primary", TAG_STRATEGY_NAME, "least-used")
                  .count())
          .isEqualTo(1.0);

      assertThat(
              registry
                  .counter(
                      CAS_RETRIES, TAG_CONNECTION_NAME, "cache", TAG_STRATEGY_NAME, "least-used")
                  .count())
          .isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("Slow Command Counter (Dimensional)")
  class SlowCommandTests {

    @Test
    @DisplayName("should record slow command with dimensional tags")
    void shouldRecordSlowCommandWithDimensionalTags() {
      // Act
      metrics.recordSlowCommand("primary", "SMEMBERS", 150);
      metrics.recordSlowCommand("primary", "SMEMBERS", 200);
      metrics.recordSlowCommand("primary", "GET", 100);

      // Assert
      assertThat(
              registry
                  .counter(SLOW_COMMANDS, TAG_CONNECTION_NAME, "primary", TAG_COMMAND, "SMEMBERS")
                  .count())
          .isEqualTo(2.0);

      assertThat(
              registry
                  .counter(SLOW_COMMANDS, TAG_CONNECTION_NAME, "primary", TAG_COMMAND, "GET")
                  .count())
          .isEqualTo(1.0);
    }

    @Test
    @DisplayName("should separate metrics by connection name")
    void shouldSeparateMetricsByConnectionName() {
      // Arrange
      final var cacheMetrics = new MicrometerLanedRedisMetrics(registry, "cache", 1000);

      // Act
      metrics.recordSlowCommand("primary", "SMEMBERS", 150);
      cacheMetrics.recordSlowCommand("cache", "SMEMBERS", 150);

      // Assert
      assertThat(
              registry
                  .counter(SLOW_COMMANDS, TAG_CONNECTION_NAME, "primary", TAG_COMMAND, "SMEMBERS")
                  .count())
          .isEqualTo(1.0);

      assertThat(
              registry
                  .counter(SLOW_COMMANDS, TAG_CONNECTION_NAME, "cache", TAG_COMMAND, "SMEMBERS")
                  .count())
          .isEqualTo(1.0);
    }
  }

  @Nested
  @DisplayName("HOL Blocking Gauge")
  class HOLBlockingGaugeTests {

    @Test
    @DisplayName("should register HOL blocking gauge with connection tag")
    void shouldRegisterHOLBlockingGaugeWithConnectionTag() {
      // Act
      metrics.registerHOLBlockingGauge(8);

      // Assert
      assertThat(
              registry
                  .find(HOL_BLOCKING_ESTIMATED)
                  .tag(TAG_CONNECTION_NAME, "primary")
                  .gauge()
                  .value())
          .isEqualTo(12.5); // 100 / 8
    }

    @Test
    @DisplayName("should separate by connection name")
    void shouldSeparateByConnectionName() {
      // Arrange
      final var cacheMetrics = new MicrometerLanedRedisMetrics(registry, "cache", 1000);

      // Act
      metrics.registerHOLBlockingGauge(8);
      cacheMetrics.registerHOLBlockingGauge(4);

      // Assert
      assertThat(
              registry
                  .find(HOL_BLOCKING_ESTIMATED)
                  .tag(TAG_CONNECTION_NAME, "primary")
                  .gauge()
                  .value())
          .isEqualTo(12.5);

      assertThat(
              registry
                  .find(HOL_BLOCKING_ESTIMATED)
                  .tag(TAG_CONNECTION_NAME, "cache")
                  .gauge()
                  .value())
          .isEqualTo(25.0); // 100 / 4
    }
  }

  @Nested
  @DisplayName("Cleanup / Close (Dimensional)")
  class CleanupTests {

    @Test
    @DisplayName("should remove gauges only for specific connection")
    void shouldRemoveGaugesOnlyForSpecificConnection() {
      // Arrange
      final var cacheMetrics = new MicrometerLanedRedisMetrics(registry, "cache", 1000);

      metrics.recordInFlightIncrement("primary", 0);
      cacheMetrics.recordInFlightIncrement("cache", 0);

      metrics.registerHOLBlockingGauge(8);
      cacheMetrics.registerHOLBlockingGauge(4);

      // Act
      metrics.close("primary");

      // Assert - primary gauges removed, cache gauges remain
      assertThat(registry.find(LANE_IN_FLIGHT).tag(TAG_CONNECTION_NAME, "primary").gauges())
          .isEmpty();

      assertThat(registry.find(LANE_IN_FLIGHT).tag(TAG_CONNECTION_NAME, "cache").gauges())
          .hasSize(1);

      assertThat(registry.find(HOL_BLOCKING_ESTIMATED).tag(TAG_CONNECTION_NAME, "primary").gauges())
          .isEmpty();

      assertThat(registry.find(HOL_BLOCKING_ESTIMATED).tag(TAG_CONNECTION_NAME, "cache").gauges())
          .hasSize(1);
    }

    @Test
    @DisplayName("should be idempotent")
    void shouldBeIdempotent() {
      // Arrange
      metrics.recordInFlightIncrement("primary", 0);
      metrics.registerHOLBlockingGauge(8);

      // Act
      metrics.close("primary");
      metrics.close("primary");
      metrics.close("primary");

      // Assert - no exceptions
      assertThatCode(() -> metrics.close("primary")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should skip metric recording after close")
    void shouldSkipMetricRecordingAfterClose() {
      // Arrange
      metrics.close("primary");

      // Act
      metrics.recordLaneSelection("primary", 0, "round-robin");
      metrics.recordInFlightIncrement("primary", 0);
      metrics.recordCASRetry("primary", "test");

      // Assert - no new metrics registered
      assertThat(registry.find(LANE_SELECTIONS).counters()).isEmpty();
      assertThat(registry.find(LANE_IN_FLIGHT).gauges()).isEmpty();
      assertThat(registry.find(CAS_RETRIES).counters()).isEmpty();
    }

    @Test
    @DisplayName("should ignore close for different connection")
    void shouldIgnoreCloseForDifferentConnection() {
      // Arrange
      metrics.recordInFlightIncrement("primary", 0);

      // Act
      metrics.close("cache"); // Wrong connection name

      // Assert - gauge still present
      assertThat(registry.find(LANE_IN_FLIGHT).tag(TAG_CONNECTION_NAME, "primary").gauges())
          .hasSize(1);
    }
  }

  @Nested
  @DisplayName("Cache Size Management")
  class CacheSizeTests {

    @Test
    @DisplayName("should track cache size correctly")
    void shouldTrackCacheSizeCorrectly() {
      // Act
      metrics.recordLaneSelection("primary", 0, "round-robin");
      metrics.recordLaneSelection("primary", 1, "round-robin");
      metrics.recordInFlightIncrement("primary", 0);
      metrics.recordCASRetry("primary", "least-used");

      // Assert - 4 cached metrics
      assertThat(metrics.getCacheSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("should respect max cache size")
    void shouldRespectMaxCacheSize() {
      // Arrange
      final var smallCacheMetrics = new MicrometerLanedRedisMetrics(registry, "primary", 2);

      // Act - exceed cache limit
      smallCacheMetrics.recordLaneSelection("primary", 0, "round-robin");
      smallCacheMetrics.recordLaneSelection("primary", 1, "round-robin");
      smallCacheMetrics.recordLaneSelection("primary", 2, "round-robin"); // Beyond limit

      // Assert - cache capped, metrics still work
      assertThat(smallCacheMetrics.getCacheSize()).isEqualTo(2);

      assertThat(
              registry
                  .counter(
                      LANE_SELECTIONS,
                      TAG_CONNECTION_NAME,
                      "primary",
                      TAG_LANE_INDEX,
                      "2",
                      TAG_STRATEGY_NAME,
                      "round-robin")
                  .count())
          .isEqualTo(1.0); // Still recorded (direct registry)
    }
  }
}
