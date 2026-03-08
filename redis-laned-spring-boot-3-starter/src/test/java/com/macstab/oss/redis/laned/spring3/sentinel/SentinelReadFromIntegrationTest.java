/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.macstab.oss.redis.laned.spring3.testconfig.TestApplication;
import com.macstab.oss.redis.laned.test.annotation.RedisSentinel;
import com.macstab.oss.redis.laned.test.util.RedisCommandTracker;

/**
 * Integration test for Sentinel read-from-replica routing.
 *
 * <p><strong>Uses:</strong> {@code @RedisSentinel} annotation from {@code redis-laned-test-utils}
 * to auto-start a full Sentinel cluster (1 master + 2 replicas + 3 sentinels).
 *
 * <p><strong>What We Verify:</strong>
 *
 * <ul>
 *   <li>ReadFrom.REPLICA_PREFERRED routes reads to replicas (not master)
 *   <li>Writes always go to master (never replicas)
 * </ul>
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <p>Uses Redis MONITOR command to capture real-time command execution on each node:
 *
 * <ol>
 *   <li>Start MONITOR on master + replicas (separate threads)
 *   <li>Execute reads + writes from Spring
 *   <li>Stop MONITOR
 *   <li>Parse logs to count GET/SET commands per node (filtering replication traffic)
 *   <li>Assert 80%+ reads went to replicas
 * </ol>
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@SpringBootTest(
    classes = TestApplication.class,
    properties = {
      "spring.data.redis.connection.strategy=LANED",
      "spring.data.redis.connection.lanes=8",
      "spring.data.redis.sentinel.master=mymaster",
      "spring.data.redis.sentinel.nodes=${sentinel.nodes}",
      "spring.data.redis.lettuce.read-from=REPLICA_PREFERRED"
    })
@DisplayName("Sentinel ReadFrom Integration Test")
@RedisSentinel(masterName = "mymaster", replicas = 2, sentinels = 3)
class SentinelReadFromIntegrationTest {

  private static com.macstab.oss.redis.laned.test.extension.SentinelContainerExtension
          .SentinelCluster
      cluster;

  @Autowired private RedisTemplate<String, String> redisTemplate;

  @BeforeAll
  static void captureCluster(
      final com.macstab.oss.redis.laned.test.extension.SentinelContainerExtension.SentinelCluster
          injectedCluster) {
    cluster = injectedCluster;
  }

  @Test
  @DisplayName("Should route reads to replicas with REPLICA_PREFERRED")
  void replicaPreferred_routesToReplicas() throws Exception {
    // Given: Write data to master
    for (int i = 0; i < 100; i++) {
      redisTemplate.opsForValue().set("key:" + i, "value:" + i);
    }

    // Wait for replication
    await()
        .atMost(10, TimeUnit.SECONDS)
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              final var value = redisTemplate.opsForValue().get("key:99");
              assertThat(value).isEqualTo("value:99");
            });

    // When: Start command tracking on all nodes
    final var masterMonitor = new RedisCommandTracker(cluster.getMasterContainer());
    final var replica1Monitor = new RedisCommandTracker(cluster.getReplicaContainers().get(0));
    final var replica2Monitor = new RedisCommandTracker(cluster.getReplicaContainers().get(1));

    masterMonitor.start();
    replica1Monitor.start();
    replica2Monitor.start();

    // Execute reads
    for (int i = 0; i < 1000; i++) {
      redisTemplate.opsForValue().get("key:" + (i % 100));
    }

    Thread.sleep(1000); // Let MONITOR capture all commands

    // Stop MONITOR
    masterMonitor.stop();
    replica1Monitor.stop();
    replica2Monitor.stop();

    // Then: Parse command counts
    final long masterGets = masterMonitor.countCommand("GET");
    final long replica1Gets = replica1Monitor.countCommand("GET");
    final long replica2Gets = replica2Monitor.countCommand("GET");
    final long totalGets = masterGets + replica1Gets + replica2Gets;
    final long replicaGets = replica1Gets + replica2Gets;

    System.out.println("=== READ ROUTING DISTRIBUTION ===");
    System.out.printf(
        "  Master:   %,d GETs (%.1f%%)%n", masterGets, (masterGets * 100.0 / totalGets));
    System.out.printf(
        "  Replica1: %,d GETs (%.1f%%)%n", replica1Gets, (replica1Gets * 100.0 / totalGets));
    System.out.printf(
        "  Replica2: %,d GETs (%.1f%%)%n", replica2Gets, (replica2Gets * 100.0 / totalGets));
    System.out.printf(
        "  Total:    %,d GETs (%,d to replicas = %.1f%%)%n",
        totalGets, replicaGets, (replicaGets * 100.0 / totalGets));

    assertThat(totalGets).as("Should capture most GET commands").isGreaterThan(900);

    assertThat(replicaGets)
        .as("With REPLICA_PREFERRED, 80%+ reads should go to replicas")
        .isGreaterThan((long) (totalGets * 0.8));
  }

  @Test
  @DisplayName("Should route all writes to master")
  void writesGoToMaster() throws Exception {
    // When: Start command tracking
    final var masterMonitor = new RedisCommandTracker(cluster.getMasterContainer());
    final var replica1Monitor = new RedisCommandTracker(cluster.getReplicaContainers().get(0));
    final var replica2Monitor = new RedisCommandTracker(cluster.getReplicaContainers().get(1));

    masterMonitor.start();
    replica1Monitor.start();
    replica2Monitor.start();

    // Execute writes
    for (int i = 0; i < 100; i++) {
      redisTemplate.opsForValue().set("write-key:" + i, "value:" + i);
    }

    Thread.sleep(500);

    masterMonitor.stop();
    replica1Monitor.stop();
    replica2Monitor.stop();

    // Then: All writes to master
    final long masterSets = masterMonitor.countCommand("SET");
    final long replica1Sets = replica1Monitor.countCommand("SET");
    final long replica2Sets = replica2Monitor.countCommand("SET");

    System.out.println("=== WRITE ROUTING DISTRIBUTION ===");
    System.out.printf("  Master:   %,d SETs%n", masterSets);
    System.out.printf("  Replica1: %,d SETs%n", replica1Sets);
    System.out.printf("  Replica2: %,d SETs%n", replica2Sets);

    assertThat(masterSets).as("Should capture all SET commands on master").isGreaterThan(90);

    assertThat(replica1Sets + replica2Sets)
        .as("Replicas should NEVER receive SET commands (read-only)")
        .isEqualTo(0);
  }

  /** Test configuration. */
  @Configuration
  @org.springframework.boot.autoconfigure.EnableAutoConfiguration
  static class TestConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(
        final org.springframework.data.redis.connection.RedisConnectionFactory connectionFactory) {
      return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public org.springframework.boot.autoconfigure.data.redis
            .LettuceClientConfigurationBuilderCustomizer
        readFromReplicaCustomizer() {
      return clientConfigurationBuilder ->
          clientConfigurationBuilder.readFrom(io.lettuce.core.ReadFrom.REPLICA_PREFERRED);
    }
  }
}
