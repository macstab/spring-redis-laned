/* (C)2026 Macstab GmbH */

/**
 * Micrometer metrics integration for laned Redis connections.
 *
 * <h2>Purpose</h2>
 *
 * <p>Provides observability for laned Redis connections via Micrometer metrics. Validates
 * performance claims (87.5% HOL reduction, 84% connection savings) and enables operational
 * monitoring.
 *
 * <h2>Quick Start</h2>
 *
 * <p><strong>1. Add dependency (Maven):</strong>
 *
 * <pre>{@code
 * <dependency>
 *   <groupId>com.macstab.oss.redis</groupId>
 *   <artifactId>redis-laned-metrics</artifactId>
 *   <version>1.0.0</version>
 * </dependency>
 * }</pre>
 *
 * <p><strong>2. Metrics auto-activate (if Micrometer present):</strong>
 *
 * <pre>{@code
 * # application.yml
 * management:
 *   endpoints:
 *     web:
 *       exposure:
 *         include: metrics, prometheus
 *   metrics:
 *     laned-redis:
 *       enabled: true  # default
 * }</pre>
 *
 * <p><strong>3. View metrics:</strong>
 *
 * <pre>
 * # Prometheus endpoint
 * GET http://localhost:8080/actuator/prometheus
 *
 * # Metrics endpoint
 * GET http://localhost:8080/actuator/metrics/redis.lettuce.laned.lane.selections
 * </pre>
 *
 * <h2>Metrics Published</h2>
 *
 * <table>
 *   <caption>Metric Catalog</caption>
 *   <thead>
 *     <tr><th>Metric</th><th>Type</th><th>Purpose</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@code redis.lettuce.laned.lane.selections}</td>
 *       <td>Counter</td>
 *       <td>Validate uniform lane distribution (round-robin working)</td>
 *     </tr>
 *     <tr>
 *       <td>{@code redis.lettuce.laned.lane.in_flight}</td>
 *       <td>Gauge</td>
 *       <td>Detect lane bottlenecks (one lane much higher)</td>
 *     </tr>
 *     <tr>
 *       <td>{@code redis.lettuce.laned.hol.blocking.estimated}</td>
 *       <td>Gauge</td>
 *       <td>Prove HOL reduction (12.5% with 8 lanes)</td>
 *     </tr>
 *     <tr>
 *       <td>{@code redis.lettuce.laned.strategy.cas.retries}</td>
 *       <td>Counter</td>
 *       <td>Detect excessive contention (high retries)</td>
 *     </tr>
 *     <tr>
 *       <td>{@code redis.lettuce.laned.connections.total}</td>
 *       <td>Gauge</td>
 *       <td>Show connection count (8 lanes vs 50-pool)</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <h2>Prometheus Output Example</h2>
 *
 * <pre>
 * # Lane selection frequency (proves round-robin works)
 * redis_lettuce_laned_lane_selections_total{lane="0",strategy="round-robin"} 125432
 * redis_lettuce_laned_lane_selections_total{lane="1",strategy="round-robin"} 125401
 * # ... all lanes should have ~equal counts
 *
 * # Current in-flight operations per lane
 * redis_lettuce_laned_lane_in_flight{lane="0"} 42
 * redis_lettuce_laned_lane_in_flight{lane="1"} 38
 *
 * # HOL blocking percentage (proves 87.5% reduction with 8 lanes)
 * redis_lettuce_laned_hol_blocking_estimated 12.5
 *
 * # Total connections (proves 84% reduction: 8 vs 50-pool)
 * redis_lettuce_laned_connections_total 8
 * redis_lettuce_laned_connections_open 8
 * </pre>
 *
 * <h2>Grafana Dashboard</h2>
 *
 * <p><strong>Reference dashboard (copy to Grafana):</strong>
 *
 * <pre>{@code
 * {
 *   "title": "Laned Redis - Connection Distribution",
 *   "panels": [
 *     {
 *       "title": "Lane Selection Rate (Should be Uniform)",
 *       "targets": [
 *         {"expr": "rate(redis_lettuce_laned_lane_selections_total[5m])"}
 *       ]
 *     },
 *     {
 *       "title": "In-Flight Operations per Lane",
 *       "targets": [
 *         {"expr": "redis_lettuce_laned_lane_in_flight"}
 *       ]
 *     },
 *     {
 *       "title": "HOL Blocking % (Lower = Better)",
 *       "targets": [
 *         {"expr": "redis_lettuce_laned_hol_blocking_estimated"}
 *       ],
 *       "thresholds": "25,50"  // Green <25%, Yellow <50%, Red >=50%
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────────┐
 * │ Spring Boot Application                                  │
 * │   └─→ LanedConnectionManager                             │
 * │         └─→ @Nullable LanedRedisMetrics (defaults to NOOP) │
 * └────────────────┬─────────────────────────────────────────┘
 *                  ↓
 * ┌──────────────────────────────────────────────────────────┐
 * │ LanedRedisMetricsAutoConfiguration                       │
 * │   ├─→ @ConditionalOnClass(MeterRegistry.class)           │
 * │   ├─→ @ConditionalOnProperty(enabled=true)               │
 * │   └─→ Creates MicrometerLanedRedisMetrics bean           │
 * └────────────────┬─────────────────────────────────────────┘
 *                  ↓
 * ┌──────────────────────────────────────────────────────────┐
 * │ MicrometerLanedRedisMetrics                              │
 * │   ├─→ Counter.builder("redis.lettuce.laned.lane.selections") │
 * │   ├─→ Gauge.builder("redis.lettuce.laned.lane.in_flight")    │
 * │   └─→ Registers with MeterRegistry                       │
 * └────────────────┬─────────────────────────────────────────┘
 *                  ↓
 * ┌──────────────────────────────────────────────────────────┐
 * │ Micrometer MeterRegistry                                 │
 * │   └─→ Exports to Prometheus/Graphite/InfluxDB/StatsD    │
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Key Components</h2>
 *
 * <dl>
 *   <dt>{@link com.macstab.oss.redis.laned.metrics.LanedRedisMetrics}
 *   <dd>Core interface (framework-agnostic). Defines metrics recording contract.
 *   <dt>{@link com.macstab.oss.redis.laned.metrics.NoOpLanedRedisMetrics}
 *   <dd>Zero-overhead no-op implementation. Used when metrics disabled or Micrometer absent.
 *   <dt>{@link com.macstab.oss.redis.laned.metrics.micrometer.MicrometerLanedRedisMetrics}
 *   <dd>Micrometer implementation. Publishes metrics to {@code MeterRegistry}.
 *   <dt>{@link
 *       com.macstab.oss.redis.laned.metrics.autoconfigure.LanedRedisMetricsAutoConfiguration}
 *   <dd>Spring Boot auto-configuration. Activates when Micrometer present.
 * </dl>
 *
 * <h2>Configuration Properties</h2>
 *
 * <pre>{@code
 * management:
 *   metrics:
 *     laned-redis:
 *       enabled: true              # Enable/disable metrics (default: true)
 *       slow-command-threshold: 10ms  # Slow command detection (future)
 * }</pre>
 *
 * <h2>Spring Boot 3+4 Compatibility</h2>
 *
 * <p>Micrometer API stable across Spring Boot versions. Single module works for both:
 *
 * <ul>
 *   <li>Spring Boot 3.x: Micrometer 1.12.x
 *   <li>Spring Boot 4.x: Micrometer 1.14.x
 *   <li><strong>API changes:</strong> NONE (stable since Micrometer 1.0.0)
 * </ul>
 *
 * <h2>Performance</h2>
 *
 * <p><strong>Overhead per Redis command:</strong>
 *
 * <ul>
 *   <li>NoOp implementation: &lt;1ns (JIT eliminates)
 *   <li>Micrometer implementation: ~10-20ns (counter increment)
 *   <li>Acceptable: &lt;0.1% overhead (10ns / 10μs Redis RTT)
 * </ul>
 *
 * <p><strong>Memory:</strong>
 *
 * <ul>
 *   <li>Per lane: ~1KB (gauge + counter objects)
 *   <li>8 lanes: ~8KB total
 *   <li>Cleanup on {@code close()}: prevents leak
 * </ul>
 *
 * <h2>Operational Alerts</h2>
 *
 * <p><strong>Recommended Prometheus alerts:</strong>
 *
 * <pre>
 * # Uneven lane distribution (>20% variance)
 * - alert: LanedRedisUnevenDistribution
 *   expr: |
 *     stddev(rate(redis_lettuce_laned_lane_selections_total[5m])) /
 *     avg(rate(redis_lettuce_laned_lane_selections_total[5m])) > 0.2
 *   for: 5m
 *   annotations:
 *     summary: "Lane selection not uniform (check round-robin strategy)"
 *
 * # High in-flight on single lane (bottleneck)
 * - alert: LanedRedisBottleneck
 *   expr: |
 *     max(redis_lettuce_laned_lane_in_flight) /
 *     avg(redis_lettuce_laned_lane_in_flight) > 3
 *   for: 2m
 *   annotations:
 *     summary: "One lane has 3x more load (possible HOL blocking)"
 * </pre>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All classes in this package are thread-safe:
 *
 * <ul>
 *   <li>{@code LanedRedisMetrics}: Interface contract requires thread-safety
 *   <li>{@code NoOpLanedRedisMetrics}: Immutable singleton (enum)
 *   <li>{@code MicrometerLanedRedisMetrics}: Uses {@code ConcurrentHashMap}, {@code AtomicInteger},
 *       {@code CopyOnWriteArrayList}
 * </ul>
 *
 * <h2>Testing</h2>
 *
 * <p><strong>Unit tests:</strong>
 *
 * <pre>{@code
 * @Test
 * void shouldRecordLaneSelection() {
 *     SimpleMeterRegistry registry = new SimpleMeterRegistry();
 *     MicrometerLanedRedisMetrics metrics = new MicrometerLanedRedisMetrics(registry);
 *
 *     metrics.recordLaneSelection(0, "round-robin");
 *     metrics.recordLaneSelection(0, "round-robin");
 *
 *     assertThat(registry.counter("redis.lettuce.laned.lane.selections",
 *         "lane", "0", "strategy", "round-robin").count())
 *         .isEqualTo(2.0);
 * }
 * }</pre>
 *
 * <h2>Related Documentation</h2>
 *
 * <ul>
 *   <li><a href="https://micrometer.io/docs">Micrometer Documentation</a>
 *   <li><a
 *       href="https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html">Spring
 *       Boot Actuator</a>
 *   <li><a href="https://prometheus.io/docs/prometheus/latest/querying/basics/">Prometheus
 *       Querying</a>
 * </ul>
 *
 * @since 1.0.0
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.oss.redis.laned.metrics.LanedRedisMetrics
 * @see com.macstab.oss.redis.laned.metrics.micrometer.MicrometerLanedRedisMetrics
 */
package com.macstab.oss.redis.laned.metrics;
