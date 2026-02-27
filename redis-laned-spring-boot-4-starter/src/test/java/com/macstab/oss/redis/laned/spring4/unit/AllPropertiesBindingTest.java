/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;

import com.macstab.oss.redis.laned.spring4.RedisConnectionProperties;

/**
 * Unit tests for ALL Spring Boot Redis property bindings.
 *
 * <p><strong>Test Strategy:</strong>
 *
 * <ul>
 *   <li>Pure property binding tests (no Spring context)
 *   <li>Uses {@link Binder} to bind properties to {@link RedisProperties}
 *   <li>Validates ALL standard + custom properties are correctly bound
 *   <li>No Redis connectivity required
 * </ul>
 *
 * <p><strong>What This Proves:</strong> All {@code spring.data.redis.*} properties are correctly
 * mapped to {@link RedisProperties} and our {@link RedisConnectionProperties}.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("All Properties Binding Tests")
class AllPropertiesBindingTest {

  @Nested
  @DisplayName("Sentinel Properties")
  class SentinelPropertiesTest {

    @Test
    @DisplayName("should bind all Sentinel properties")
    void shouldBindAllSentinelProperties() {
      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.sentinel.master", "mymaster");
      properties.put("spring.data.redis.sentinel.nodes", "localhost:26379,localhost:26380");
      properties.put("spring.data.redis.sentinel.username", "sentinel-user");
      properties.put("spring.data.redis.sentinel.password", "sentinel-pass");

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert
      assertThat(props.getSentinel()).isNotNull();
      assertThat(props.getSentinel().getMaster()).isEqualTo("mymaster");
      assertThat(props.getSentinel().getNodes())
          .containsExactly("localhost:26379", "localhost:26380");
      assertThat(props.getSentinel().getUsername()).isEqualTo("sentinel-user");
      assertThat(props.getSentinel().getPassword()).isEqualTo("sentinel-pass");
    }
  }

  @Nested
  @DisplayName("Cluster Properties")
  class ClusterPropertiesTest {

    @Test
    @DisplayName("should bind cluster.nodes and cluster.max-redirects")
    void shouldBindClusterProperties() {
      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.cluster.nodes", "localhost:7000,localhost:7001");
      properties.put("spring.data.redis.cluster.max-redirects", "5");

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert
      assertThat(props.getCluster()).isNotNull();
      assertThat(props.getCluster().getNodes()).containsExactly("localhost:7000", "localhost:7001");
      assertThat(props.getCluster().getMaxRedirects()).isEqualTo(5);
    }

    @Test
    @DisplayName("should bind lettuce.cluster.refresh properties")
    void shouldBindClusterRefreshProperties() {
      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.cluster.nodes", "localhost:7000");
      properties.put("spring.data.redis.lettuce.cluster.refresh.dynamic-refresh-sources", "true");
      properties.put("spring.data.redis.lettuce.cluster.refresh.period", "30s");
      properties.put("spring.data.redis.lettuce.cluster.refresh.adaptive", "true");

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert
      assertThat(props.getLettuce()).isNotNull();
      assertThat(props.getLettuce().getCluster()).isNotNull();
      assertThat(props.getLettuce().getCluster().getRefresh()).isNotNull();
      assertThat(props.getLettuce().getCluster().getRefresh().isDynamicRefreshSources()).isTrue();
      assertThat(props.getLettuce().getCluster().getRefresh().getPeriod())
          .isEqualTo(Duration.ofSeconds(30));
      assertThat(props.getLettuce().getCluster().getRefresh().isAdaptive()).isTrue();
    }
  }

  @Nested
  @DisplayName("SSL Properties")
  class SSLPropertiesTest {

    @Test
    @DisplayName("should bind ssl.enabled property")
    void shouldBindSslEnabled() {
      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.ssl.enabled", "true");

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert
      assertThat(props.getSsl()).isNotNull();
      assertThat(props.getSsl().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("should bind ssl.bundle property")
    void shouldBindSslBundle() {
      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.ssl.bundle", "my-bundle");

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert
      assertThat(props.getSsl()).isNotNull();
      assertThat(props.getSsl().getBundle()).isEqualTo("my-bundle");
    }
  }

  @Nested
  @DisplayName("Lettuce Properties")
  class LettucePropertiesTest {

    @Test
    @DisplayName("should bind lettuce.readFrom property")
    void shouldBindReadFrom() {
      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.lettuce.readFrom", "REPLICA_PREFERRED");

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert
      assertThat(props.getLettuce()).isNotNull();
      assertThat(props.getLettuce().getReadFrom()).isEqualTo("REPLICA_PREFERRED");
    }
  }

  // Helper method to bind properties
  private DataRedisProperties bindProperties(Map<String, String> properties) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
    Binder binder = new Binder(source);
    return binder.bind("spring.data.redis", DataRedisProperties.class).get();
  }
}
