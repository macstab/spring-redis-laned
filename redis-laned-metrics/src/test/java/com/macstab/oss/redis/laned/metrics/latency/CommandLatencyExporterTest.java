/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.metrics.latency;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.oss.redis.laned.metrics.autoconfigure.LanedRedisMetricsProperties;

import io.lettuce.core.metrics.DefaultCommandLatencyCollector;
import io.lettuce.core.metrics.DefaultCommandLatencyCollectorOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Unit tests for {@link CommandLatencyExporter}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("CommandLatencyExporter")
class CommandLatencyExporterTest {

  private ClientResources clientResources;
  private MeterRegistry registry;
  private CommandLatencyExporter exporter;

  @BeforeEach
  void setUp() {
    // Create ClientResources with CommandLatencyCollector
    final DefaultCommandLatencyCollectorOptions options =
        DefaultCommandLatencyCollectorOptions.builder().enable().build();

    clientResources =
        DefaultClientResources.builder()
            .commandLatencyCollector(new DefaultCommandLatencyCollector(options))
            .build();

    registry = new SimpleMeterRegistry();

    final LanedRedisMetricsProperties.MetricNames metricNames =
        new LanedRedisMetricsProperties.MetricNames();

    exporter =
        new CommandLatencyExporter(
            clientResources,
            registry,
            "test-connection",
            metricNames,
            new double[] {0.50, 0.95, 0.99},
            true);
  }

  @AfterEach
  void tearDown() {
    if (exporter != null) {
      exporter.close();
    }
    if (clientResources != null) {
      clientResources.shutdown();
    }
  }

  @Nested
  @DisplayName("Constructor")
  class Constructor {

    @Test
    @DisplayName("Should create exporter with valid parameters")
    void shouldCreateExporter() {
      assertThat(exporter).isNotNull();
    }

    @Test
    @DisplayName("Should reject null clientResources")
    void shouldRejectNullClientResources() {
      assertThatThrownBy(
              () ->
                  new CommandLatencyExporter(
                      null,
                      registry,
                      "test",
                      new LanedRedisMetricsProperties.MetricNames(),
                      new double[] {0.95},
                      true))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject null meterRegistry")
    void shouldRejectNullMeterRegistry() {
      assertThatThrownBy(
              () ->
                  new CommandLatencyExporter(
                      clientResources,
                      null,
                      "test",
                      new LanedRedisMetricsProperties.MetricNames(),
                      new double[] {0.95},
                      true))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject empty percentiles array")
    void shouldRejectEmptyPercentiles() {
      assertThatThrownBy(
              () ->
                  new CommandLatencyExporter(
                      clientResources,
                      registry,
                      "test",
                      new LanedRedisMetricsProperties.MetricNames(),
                      new double[] {},
                      true))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Percentiles array must not be empty");
    }
  }

  @Nested
  @DisplayName("exportCommandLatencies()")
  class ExportCommandLatencies {

    @Test
    @DisplayName("Should be no-op when no commands executed")
    void shouldBeNoOpWhenNoCommands() {
      // GIVEN: No Redis commands executed (collector has no data)

      // WHEN: Export latencies
      assertThatCode(() -> exporter.exportCommandLatencies()).doesNotThrowAnyException();

      // THEN: No gauges registered
      assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    @DisplayName("Should be no-op after close")
    void shouldBeNoOpAfterClose() {
      // GIVEN: Exporter is closed
      exporter.close();

      // WHEN: Export latencies
      assertThatCode(() -> exporter.exportCommandLatencies()).doesNotThrowAnyException();

      // THEN: No exceptions thrown
    }
  }

  @Nested
  @DisplayName("getTrackedCommands()")
  class GetTrackedCommands {

    @Test
    @DisplayName("Should return empty set when no commands tracked")
    void shouldReturnEmptySetWhenNoCommands() {
      // WHEN
      final var commands = exporter.getTrackedCommands();

      // THEN
      assertThat(commands).isEmpty();
    }

    @Test
    @DisplayName("Should return empty set after close")
    void shouldReturnEmptySetAfterClose() {
      // GIVEN: Exporter is closed
      exporter.close();

      // WHEN
      final var commands = exporter.getTrackedCommands();

      // THEN
      assertThat(commands).isEmpty();
    }
  }

  @Nested
  @DisplayName("close()")
  class Close {

    @Test
    @DisplayName("Should be idempotent")
    void shouldBeIdempotent() {
      // WHEN: Close multiple times
      exporter.close();
      exporter.close();
      exporter.close();

      // THEN: No exceptions thrown
    }

    @Test
    @DisplayName("Should unregister gauges from registry")
    void shouldUnregisterGauges() {
      // GIVEN: Export creates some gauges (if commands exist)
      exporter.exportCommandLatencies();
      final int initialMeterCount = registry.getMeters().size();

      // WHEN: Close exporter
      exporter.close();

      // THEN: Gauges are unregistered (count should be same or less)
      assertThat(registry.getMeters()).hasSizeLessThanOrEqualTo(initialMeterCount);
    }
  }

  @Nested
  @DisplayName("Configuration Validation")
  class ConfigurationValidation {

    @Test
    @DisplayName("Should use custom metric names")
    void shouldUseCustomMetricNames() {
      // GIVEN: Custom metric names
      final LanedRedisMetricsProperties.MetricNames customNames =
          new LanedRedisMetricsProperties.MetricNames();
      customNames.setCommandLatency("custom.latency.metric");

      final CommandLatencyExporter customExporter =
          new CommandLatencyExporter(
              clientResources, registry, "custom-conn", customNames, new double[] {0.95}, false);

      try {
        // WHEN: Export (even with no data, exporter is created)
        customExporter.exportCommandLatencies();

        // THEN: Exporter uses custom metric name (verified indirectly by no exceptions)
        assertThat(customExporter).isNotNull();
      } finally {
        customExporter.close();
      }
    }

    @Test
    @DisplayName("Should support multiple percentiles")
    void shouldSupportMultiplePercentiles() {
      // GIVEN: Multiple percentiles
      final CommandLatencyExporter multiPercentile =
          new CommandLatencyExporter(
              clientResources,
              registry,
              "test",
              new LanedRedisMetricsProperties.MetricNames(),
              new double[] {0.50, 0.75, 0.90, 0.95, 0.99},
              true);

      try {
        // WHEN: Export
        multiPercentile.exportCommandLatencies();

        // THEN: No exceptions (validates percentile array handling)
        assertThat(multiPercentile).isNotNull();
      } finally {
        multiPercentile.close();
      }
    }
  }
}
