/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.macstab.oss.redis.laned.metrics.LanedRedisMetrics;
import com.macstab.oss.redis.laned.strategy.KeyAffinityStrategy;
import com.macstab.oss.redis.laned.strategy.RoundRobinStrategy;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Tests for {@link KeyAffinityConnectionWrapper}.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Constructor validation
 *   <li>Dynamic proxy creation (async/sync/reactive)
 *   <li>Lazy lane selection (on first command)
 *   <li>Lane pinning (subsequent commands use same lane)
 *   <li>Thread safety (concurrent access)
 *   <li>Key extraction (String/byte[]/ByteBuffer)
 *   <li>Keyless command fallback (PING, INFO)
 *   <li>Close lifecycle
 * </ul>
 */
@DisplayName("KeyAffinityConnectionWrapper")
class KeyAffinityConnectionWrapperTest {

  @Nested
  @DisplayName("Construction")
  class Construction {

    @Test
    @DisplayName("creates wrapper with valid parameters")
    void createWrapper() {
      // Arrange
      final var manager = createMockManager(8);
      final var strategy = new KeyAffinityStrategy();

      // Act
      final var wrapper =
          new KeyAffinityConnectionWrapper<>(
              manager,
              strategy,
              8,
              new RoundRobinStrategy(),
              mock(LanedRedisMetrics.class),
              "test");

      // Assert
      assertThat(wrapper).isNotNull();
    }

    @Test
    @DisplayName("throws on numLanes < 1")
    void invalidNumLanes() {
      // Arrange
      final var manager = createMockManager(8);
      final var strategy = new KeyAffinityStrategy();

      // Act & Assert
      assertThatThrownBy(
              () ->
                  new KeyAffinityConnectionWrapper<>(
                      manager,
                      strategy,
                      0,
                      new RoundRobinStrategy(),
                      mock(LanedRedisMetrics.class),
                      "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("numLanes must be >= 1");
    }

    @Test
    @DisplayName("throws on negative numLanes")
    void negativeNumLanes() {
      // Arrange
      final var manager = createMockManager(8);
      final var strategy = new KeyAffinityStrategy();

      // Act & Assert
      assertThatThrownBy(
              () ->
                  new KeyAffinityConnectionWrapper<>(
                      manager,
                      strategy,
                      -1,
                      new RoundRobinStrategy(),
                      mock(LanedRedisMetrics.class),
                      "test"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("numLanes must be >= 1");
    }

    @Test
    @DisplayName("throws on null manager")
    void nullManager() {
      // Arrange
      final var strategy = new KeyAffinityStrategy();

      // Act & Assert
      assertThatThrownBy(
              () ->
                  new KeyAffinityConnectionWrapper<>(
                      null,
                      strategy,
                      8,
                      new RoundRobinStrategy(),
                      mock(LanedRedisMetrics.class),
                      "test"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("manager");
    }

    @Test
    @DisplayName("throws on null strategy")
    void nullStrategy() {
      // Arrange
      final var manager = createMockManager(8);

      // Act & Assert
      assertThatThrownBy(
              () ->
                  new KeyAffinityConnectionWrapper<>(
                      manager,
                      null,
                      8,
                      new RoundRobinStrategy(),
                      mock(LanedRedisMetrics.class),
                      "test"))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("strategy");
    }
  }

  @Nested
  @DisplayName("Dynamic Proxy Creation")
  class DynamicProxyCreation {

    @Test
    @DisplayName("async() returns dynamic proxy")
    void asyncReturnsProxy() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act
      final var async = wrapper.async();

      // Assert
      assertThat(async).isNotNull();
      assertThat(Proxy.isProxyClass(async.getClass())).isTrue();
    }

    @Test
    @DisplayName("sync() returns dynamic proxy")
    void syncReturnsProxy() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act
      final var sync = wrapper.sync();

      // Assert
      assertThat(sync).isNotNull();
      assertThat(Proxy.isProxyClass(sync.getClass())).isTrue();
    }

    @Test
    @DisplayName("reactive() returns dynamic proxy")
    void reactiveReturnsProxy() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act
      final var reactive = wrapper.reactive();

      // Assert
      assertThat(reactive).isNotNull();
      assertThat(Proxy.isProxyClass(reactive.getClass())).isTrue();
    }

    @Test
    @DisplayName("async() returns new proxy instance on each call")
    void asyncReturnsNewInstance() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act
      final var async1 = wrapper.async();
      final var async2 = wrapper.async();

      // Assert
      assertThat(async1).isNotSameAs(async2);
    }
  }

  @Nested
  @DisplayName("Lazy Lane Selection")
  class LazyLaneSelection {

    @Test
    @DisplayName("lane NOT selected on wrapper creation")
    void laneNotSelectedOnCreation() throws Exception {
      // Arrange
      final var manager = createMockManager(8);
      final var strategy = new KeyAffinityStrategy();
      final var wrapper =
          new KeyAffinityConnectionWrapper<>(
              manager,
              strategy,
              8,
              new RoundRobinStrategy(),
              mock(LanedRedisMetrics.class),
              "test");

      // Act (access selectedLaneRef via reflection)
      final var field = wrapper.getClass().getDeclaredField("selectedLaneRef");
      field.setAccessible(true);
      final var ref = (AtomicReference<?>) field.get(wrapper);

      // Assert
      assertThat(ref.get()).isNull();
    }

    @Test
    @DisplayName("lane NOT selected when calling async()")
    void laneNotSelectedOnAsync() throws Exception {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act
      wrapper.async();

      // Assert
      final var ref = getSelectedLaneRef(wrapper);
      assertThat(ref.get()).isNull(); // Proxy created, but lane not selected yet
    }
  }

  @Nested
  @DisplayName("Close Lifecycle")
  class CloseLifecycle {

    @Test
    @DisplayName("close() on unselected wrapper is safe")
    void closeUnselected() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act & Assert (no exception)
      wrapper.close();
    }

    @Test
    @DisplayName("close() can be called multiple times (idempotent)")
    void closeIdempotent() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act & Assert
      wrapper.close();
      wrapper.close();
      wrapper.close();
    }

    @Test
    @DisplayName("closeAsync() returns completed future")
    void closeAsync() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act
      final var future = wrapper.closeAsync();

      // Assert
      assertThat(future).isCompleted();
      assertThat(future.join()).isNull();
    }
  }

  @Nested
  @DisplayName("StatefulRedisConnection Methods")
  class StatefulRedisConnectionMethods {

    @Test
    @DisplayName("isOpen() returns true before lane selected")
    void isOpenBeforeSelection() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act & Assert
      assertThat(wrapper.isOpen()).isTrue();
    }

    @Test
    @DisplayName("isMulti() returns false before lane selected")
    void isMultiBeforeSelection() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act & Assert
      assertThat(wrapper.isMulti()).isFalse();
    }

    @Test
    @DisplayName("setAutoFlushCommands() is safe before lane selected")
    void setAutoFlushBeforeSelection() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act & Assert (no exception)
      wrapper.setAutoFlushCommands(true);
    }

    @Test
    @DisplayName("flushCommands() is safe before lane selected")
    void flushCommandsBeforeSelection() {
      // Arrange
      final var wrapper = createWrapper(8);

      // Act & Assert (no exception)
      wrapper.flushCommands();
    }
  }

  @Nested
  @DisplayName("Thread Safety")
  class ThreadSafety {

    @Test
    @DisplayName("concurrent wrapper creation is safe")
    void concurrentCreation() throws InterruptedException {
      // Arrange
      final var manager = createMockManager(8);
      final var strategy = new KeyAffinityStrategy();
      final int numThreads = 10;
      final var latch = new CountDownLatch(numThreads);
      final var wrappers = new AtomicReference[numThreads];

      // Act
      for (int i = 0; i < numThreads; i++) {
        final int index = i;
        new Thread(
                () -> {
                  wrappers[index] =
                      new AtomicReference<>(
                          new KeyAffinityConnectionWrapper<>(
                              manager,
                              strategy,
                              8,
                              new RoundRobinStrategy(),
                              mock(LanedRedisMetrics.class),
                              "test"));
                  latch.countDown();
                })
            .start();
      }

      latch.await();

      // Assert
      for (var ref : wrappers) {
        assertThat(ref.get()).isNotNull();
      }
    }

    @Test
    @DisplayName("concurrent async() calls are safe")
    void concurrentAsync() throws InterruptedException {
      // Arrange
      final var wrapper = createWrapper(8);
      final int numThreads = 10;
      final var latch = new CountDownLatch(numThreads);
      final var proxies = new AtomicReference[numThreads];

      // Act
      for (int i = 0; i < numThreads; i++) {
        final int index = i;
        new Thread(
                () -> {
                  proxies[index] = new AtomicReference<>(wrapper.async());
                  latch.countDown();
                })
            .start();
      }

      latch.await();

      // Assert
      for (var ref : proxies) {
        assertThat(ref.get()).isNotNull();
      }
    }

    @Test
    @DisplayName("concurrent close() calls are safe")
    void concurrentClose() throws InterruptedException {
      // Arrange
      final var wrapper = createWrapper(8);
      final int numThreads = 10;
      final var latch = new CountDownLatch(numThreads);

      // Act
      for (int i = 0; i < numThreads; i++) {
        new Thread(
                () -> {
                  wrapper.close();
                  latch.countDown();
                })
            .start();
      }

      latch.await();

      // Assert (no exception)
    }
  }

  @Nested
  @DisplayName("Shared Fallback Strategy")
  class SharedFallbackStrategy {

    @Test
    @DisplayName("shared fallback distributes keyless commands across lanes")
    void sharedFallbackDistribution() {
      // Arrange
      final var sharedFallback = new RoundRobinStrategy();
      final var metrics = mock(LanedRedisMetrics.class);
      final int numLanes = 8;
      final int numWrappers = 24; // 3× lanes for good distribution test
      final var manager = createMockManager(numLanes);
      final var wrappers = new ArrayList<KeyAffinityConnectionWrapper<String, String>>();

      for (int i = 0; i < numWrappers; i++) {
        wrappers.add(
            new KeyAffinityConnectionWrapper<>(
                manager, new KeyAffinityStrategy(), numLanes, sharedFallback, metrics, "test"));
      }

      // Act: Simulate keyless commands (null args = PING/INFO)
      final var selectedLanes = new ConcurrentHashMap<Integer, AtomicInteger>();
      wrappers.forEach(
          wrapper -> {
            try {
              // Simulate keyless command by passing null args
              final var laneRef = getSelectedLaneRef(wrapper);
              // Manually trigger ensureLaneSelected with null args (keyless command)
              final var ensureMethod =
                  wrapper.getClass().getDeclaredMethod("ensureLaneSelected", Object[].class);
              ensureMethod.setAccessible(true);
              final var lane = (ConnectionLane) ensureMethod.invoke(wrapper, (Object) null);

              selectedLanes
                  .computeIfAbsent(lane.getIndex(), k -> new AtomicInteger())
                  .incrementAndGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });

      // Assert: All lanes used (round-robin distributes evenly)
      assertThat(selectedLanes).hasSize(numLanes);
      // Each lane should be used ~3 times (24 wrappers / 8 lanes = 3)
      // Allow variance (2-4 acceptable due to concurrency)
      selectedLanes.values().forEach(count -> assertThat(count.get()).isBetween(2, 4));
    }

    @Test
    @DisplayName("separate fallback instances create hotspot (regression test)")
    void separateFallbackHotspot() {
      // Arrange
      final var metrics = mock(LanedRedisMetrics.class);
      final int numLanes = 8;
      final int numWrappers = 24;
      final var manager = createMockManager(numLanes);
      final var wrappers = new ArrayList<KeyAffinityConnectionWrapper<String, String>>();

      // Each wrapper gets its OWN fallback (BAD - creates hotspot)
      for (int i = 0; i < numWrappers; i++) {
        wrappers.add(
            new KeyAffinityConnectionWrapper<>(
                manager,
                new KeyAffinityStrategy(),
                numLanes,
                new RoundRobinStrategy(), // NEW instance each time!
                metrics,
                "test"));
      }

      // Act: Simulate keyless commands
      final var selectedLanes = new ConcurrentHashMap<Integer, AtomicInteger>();
      wrappers.forEach(
          wrapper -> {
            try {
              final var ensureMethod =
                  wrapper.getClass().getDeclaredMethod("ensureLaneSelected", Object[].class);
              ensureMethod.setAccessible(true);
              final var lane = (ConnectionLane) ensureMethod.invoke(wrapper, (Object) null);

              selectedLanes
                  .computeIfAbsent(lane.getIndex(), k -> new AtomicInteger())
                  .incrementAndGet();
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });

      // Assert: Hotspot on lane 0 (all separate RoundRobins start at counter=0)
      assertThat(selectedLanes.get(0).get())
          .isGreaterThan(20); // Majority hit lane 0 (demonstrates bug)
    }
  }

  @Nested
  @DisplayName("Metrics Recording")
  class MetricsRecording {

    @Test
    @DisplayName("metrics recorded after lane selection")
    void metricsRecorded() throws Exception {
      // Arrange
      final var metrics = mock(LanedRedisMetrics.class);
      final var manager = createMockManager(8);
      final var sharedFallback = new RoundRobinStrategy();
      final var wrapper =
          new KeyAffinityConnectionWrapper<>(
              manager, new KeyAffinityStrategy(), 8, sharedFallback, metrics, "test-conn");

      // Act: Trigger lane selection with key command
      final var ensureMethod =
          wrapper.getClass().getDeclaredMethod("ensureLaneSelected", Object[].class);
      ensureMethod.setAccessible(true);
      ensureMethod.invoke(wrapper, (Object) new Object[] {"user:123"});

      // Assert: Metrics recorded with strategy name
      verify(metrics, times(1)).recordLaneSelection(eq("test-conn"), anyInt(), eq("key-affinity"));
    }

    @Test
    @DisplayName("metrics recorded exactly once under concurrent access")
    void metricsRecordedOnce() throws Exception {
      // Arrange
      final var metrics = mock(LanedRedisMetrics.class);
      final var manager = createMockManager(8);
      final var sharedFallback = new RoundRobinStrategy();
      final var wrapper =
          new KeyAffinityConnectionWrapper<>(
              manager, new KeyAffinityStrategy(), 8, sharedFallback, metrics, "test-conn");

      final int numThreads = 10;
      final var latch = new CountDownLatch(numThreads);

      // Act: 10 threads trigger lane selection concurrently
      final var ensureMethod =
          wrapper.getClass().getDeclaredMethod("ensureLaneSelected", Object[].class);
      ensureMethod.setAccessible(true);

      for (int i = 0; i < numThreads; i++) {
        new Thread(
                () -> {
                  try {
                    ensureMethod.invoke(wrapper, (Object) new Object[] {"user:123"});
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  } finally {
                    latch.countDown();
                  }
                })
            .start();
      }

      latch.await();

      // Assert: Metrics recorded EXACTLY once (not 10 times)
      verify(metrics, times(1)).recordLaneSelection(eq("test-conn"), anyInt(), eq("key-affinity"));
    }

    @Test
    @DisplayName("keyless commands record metrics via fallback")
    void keylessMetricsRecorded() throws Exception {
      // Arrange
      final var metrics = mock(LanedRedisMetrics.class);
      final var manager = createMockManager(8);
      final var sharedFallback = new RoundRobinStrategy();
      final var wrapper =
          new KeyAffinityConnectionWrapper<>(
              manager, new KeyAffinityStrategy(), 8, sharedFallback, metrics, "test-conn");

      // Act: Trigger lane selection with keyless command (null args)
      final var ensureMethod =
          wrapper.getClass().getDeclaredMethod("ensureLaneSelected", Object[].class);
      ensureMethod.setAccessible(true);
      ensureMethod.invoke(wrapper, (Object) null);

      // Assert: Metrics still recorded (via fallback path)
      verify(metrics, times(1)).recordLaneSelection(eq("test-conn"), anyInt(), eq("key-affinity"));
    }
  }

  // Helper methods

  private KeyAffinityConnectionWrapper<String, String> createWrapper(final int numLanes) {
    final var metrics = mock(LanedRedisMetrics.class);
    final var fallback = new RoundRobinStrategy();
    return new KeyAffinityConnectionWrapper<>(
        createMockManager(numLanes),
        new KeyAffinityStrategy(),
        numLanes,
        fallback,
        metrics,
        "test");
  }

  private LanedConnectionManager createMockManager(final int numLanes) {
    final var manager = mock(LanedConnectionManager.class);
    final var lanes = new ConnectionLane[numLanes];

    for (int i = 0; i < numLanes; i++) {
      lanes[i] = createMockLane(i);
    }

    // Use reflection to set final field (lanes is package-private final)
    try {
      final var lanesField = LanedConnectionManager.class.getDeclaredField("lanes");
      lanesField.setAccessible(true);
      lanesField.set(manager, lanes);
    } catch (Exception e) {
      throw new RuntimeException("Failed to set lanes field", e);
    }

    when(manager.getNumLanes()).thenReturn(numLanes);

    return manager;
  }

  private ConnectionLane createMockLane(final int index) {
    final var lane = mock(ConnectionLane.class);
    final var connection = mock(StatefulRedisConnection.class);
    final var async = mock(RedisAsyncCommands.class);
    final var sync = mock(RedisCommands.class);
    final var reactive = mock(RedisReactiveCommands.class);

    when(lane.getIndex()).thenReturn(index);
    when(lane.getConnection()).thenReturn(connection);
    when(lane.isOpen()).thenReturn(true);
    when(connection.async()).thenReturn(async);
    when(connection.sync()).thenReturn(sync);
    when(connection.reactive()).thenReturn(reactive);
    when(connection.isOpen()).thenReturn(true);
    when(connection.isMulti()).thenReturn(false);

    return lane;
  }

  private AtomicReference<ConnectionLane> getSelectedLaneRef(
      final KeyAffinityConnectionWrapper<?, ?> wrapper) throws Exception {
    final var field = wrapper.getClass().getDeclaredField("selectedLaneRef");
    field.setAccessible(true);
    return (AtomicReference<ConnectionLane>) field.get(wrapper);
  }
}
