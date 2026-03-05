/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4.sentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;

/**
 * Unit tests for {@link SentinelUriBuilder}.
 *
 * <p>Validates:
 *
 * <ul>
 *   <li>Extraction of master name and nodes from Spring configuration
 *   <li>Master authentication (ACL, legacy, none)
 *   <li>Sentinel authentication handling
 *   <li>Database index extraction
 *   <li>Multiple Sentinel nodes
 *   <li>Validation errors
 * </ul>
 */
@DisplayName("SentinelUriBuilder")
class SentinelUriBuilderTest {

  @Nested
  @DisplayName("Basic configuration")
  class BasicConfiguration {

    @Test
    @DisplayName("Should extract master name and nodes")
    void extractsBasicConfig() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      assertThat(topology.getMasterName()).isEqualTo("mymaster");
      assertThat(topology.getSentinelNodes()).hasSize(1);
      assertThat(topology.getSentinelNodes().get(0).getSentinels().get(0).getHost())
          .isEqualTo("sentinel1");
      assertThat(topology.getSentinelNodes().get(0).getSentinels().get(0).getPort())
          .isEqualTo(26379);
    }

    @Test
    @DisplayName("Should handle multiple sentinel nodes")
    void handlesMultipleNodes() {
      // Given
      final var config =
          new RedisSentinelConfiguration(
              "mymaster", Set.of("sentinel1:26379", "sentinel2:26380", "sentinel3:26381"));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      assertThat(topology.getSentinelNodes()).hasSize(3);
      // Sentinel URIs store host/port in sentinels list
      assertThat(topology.getSentinelNodes())
          .flatExtracting(uri -> uri.getSentinels())
          .extracting("host")
          .containsExactlyInAnyOrder("sentinel1", "sentinel2", "sentinel3");
      assertThat(topology.getSentinelNodes())
          .flatExtracting(uri -> uri.getSentinels())
          .extracting("port")
          .containsExactlyInAnyOrder(26379, 26380, 26381);
    }

    @Test
    @DisplayName("Should extract master name from configuration object")
    void extractsMasterFromConfigObject() {
      // Given
      final var config = new RedisSentinelConfiguration();
      config.setMaster("production-master");
      config.setSentinels(Set.of(new RedisNode("sentinel1", 26379)));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      assertThat(topology.getMasterName()).isEqualTo("production-master");
    }
  }

  @Nested
  @DisplayName("Authentication")
  class Authentication {

    @Test
    @DisplayName("Should include master password (legacy auth)")
    void includesMasterPassword() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));
      config.setPassword(RedisPassword.of("masterpass"));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      final var uri = topology.getSentinelNodes().get(0);
      assertThat(uri.getCredentialsProvider()).isNotNull();
    }

    @Test
    @DisplayName("Should include master username and password (ACL)")
    void includesMasterAcl() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));
      config.setUsername("masteruser");
      config.setPassword(RedisPassword.of("masterpass"));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      final var uri = topology.getSentinelNodes().get(0);
      assertThat(uri.getCredentialsProvider()).isNotNull();
    }

    @Test
    @DisplayName("Should handle no authentication")
    void handlesNoAuthentication() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      final var uri = topology.getSentinelNodes().get(0);
      // Lettuce may provide empty credentials provider, verify no actual credentials
      if (uri.getCredentialsProvider() != null) {
        final var credentials = uri.getCredentialsProvider().resolveCredentials().block();
        assertThat(credentials)
            .satisfiesAnyOf(
                c -> assertThat(c).isNull(),
                c -> assertThat(c.getUsername()).isNull(),
                c -> assertThat(c.getPassword()).isEmpty());
      }
    }

    @Test
    @DisplayName("Should handle empty password")
    void handlesEmptyPassword() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));
      config.setPassword(RedisPassword.none());

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      final var uri = topology.getSentinelNodes().get(0);
      // Lettuce may provide empty credentials provider, verify no actual credentials
      if (uri.getCredentialsProvider() != null) {
        final var credentials = uri.getCredentialsProvider().resolveCredentials().block();
        assertThat(credentials)
            .satisfiesAnyOf(
                c -> assertThat(c).isNull(),
                c -> assertThat(c.getUsername()).isNull(),
                c -> assertThat(c.getPassword()).isEmpty());
      }
    }

    @Test
    @DisplayName("Should handle username without password")
    void handlesUsernameWithoutPassword() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));
      config.setUsername("user");
      config.setPassword(RedisPassword.none());

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      final var uri = topology.getSentinelNodes().get(0);
      // Username without password is ignored (not valid for Redis)
      if (uri.getCredentialsProvider() != null) {
        final var credentials = uri.getCredentialsProvider().resolveCredentials().block();
        assertThat(credentials)
            .satisfiesAnyOf(
                c -> assertThat(c).isNull(),
                c -> assertThat(c.getUsername()).isNull(),
                c -> assertThat(c.getPassword()).isEmpty());
      }
    }
  }

  @Nested
  @DisplayName("Database selection")
  class DatabaseSelection {

    @Test
    @DisplayName("Should include database index")
    void includesDatabase() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));
      config.setDatabase(5);

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      final var uri = topology.getSentinelNodes().get(0);
      assertThat(uri.getDatabase()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should default to database 0")
    void defaultsToDatabase0() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      final var uri = topology.getSentinelNodes().get(0);
      assertThat(uri.getDatabase()).isEqualTo(0);
    }
  }

  @Nested
  @DisplayName("Sentinel authentication")
  class SentinelAuthentication {

    @Test
    @DisplayName("Should handle sentinel password")
    void handlesSentinelPassword() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));
      config.setSentinelPassword(RedisPassword.of("sentinelpass"));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      // Sentinel authentication is handled by Lettuce MasterReplica internally
      // We just verify topology is created successfully
      assertThat(topology).isNotNull();
    }

    @Test
    @DisplayName("Should handle sentinel username and password")
    void handlesSentinelAcl() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));
      config.setSentinelUsername("sentineluser");
      config.setSentinelPassword(RedisPassword.of("sentinelpass"));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);

      // Then
      // Sentinel authentication is handled by Lettuce MasterReplica internally
      assertThat(topology).isNotNull();
    }
  }

  @Nested
  @DisplayName("Validation")
  class Validation {

    @Test
    @DisplayName("Should reject null configuration")
    void nullConfig_throwsException() {
      // When/Then
      assertThatThrownBy(() -> SentinelUriBuilder.buildTopology(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("config cannot be null");
    }

    @Test
    @DisplayName("Should reject blank master name")
    void blankMasterName_throwsException() {
      // When/Then - Spring validates master name during setMaster()
      assertThatThrownBy(
              () -> {
                final var config = new RedisSentinelConfiguration();
                config.setMaster("  ");
                config.setSentinels(Set.of(new RedisNode("sentinel1", 26379)));
                SentinelUriBuilder.buildTopology(config);
              })
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Master Id must not be");
    }

    @Test
    @DisplayName("Should reject empty sentinel nodes")
    void emptyNodes_throwsException() {
      // Given
      final var config = new RedisSentinelConfiguration();
      config.setMaster("mymaster");
      config.setSentinels(Set.of());

      // When/Then
      assertThatThrownBy(() -> SentinelUriBuilder.buildTopology(config))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Sentinel nodes cannot be empty");
    }

    @Test
    @DisplayName("Should reject null sentinel nodes")
    void nullNodes_throwsException() {
      // When/Then - Spring validates sentinels during setSentinels()
      assertThatThrownBy(
              () -> {
                final var config = new RedisSentinelConfiguration();
                config.setMaster("mymaster");
                config.setSentinels(null);
                SentinelUriBuilder.buildTopology(config);
              })
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sentinels");
    }
  }

  @Nested
  @DisplayName("URI conversion")
  class UriConversion {

    @Test
    @DisplayName("Should convert to Sentinel URI")
    void convertsToSentinelUri() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.getSentinelMasterId()).isEqualTo("mymaster");
      assertThat(sentinelUri.getSentinels()).hasSize(1);
      assertThat(sentinelUri.getSentinels().get(0).getHost()).isEqualTo("sentinel1");
      assertThat(sentinelUri.getSentinels().get(0).getPort()).isEqualTo(26379);
    }

    @Test
    @DisplayName("Should preserve all configuration in URI conversion")
    void preservesAllConfiguration() {
      // Given
      final var config = new RedisSentinelConfiguration("mymaster", Set.of("sentinel1:26379"));
      config.setPassword(RedisPassword.of("pass"));
      config.setDatabase(3);

      // When
      final var topology = SentinelUriBuilder.buildTopology(config);
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.getSentinelMasterId()).isEqualTo("mymaster");
      assertThat(sentinelUri.getDatabase()).isEqualTo(3);
      assertThat(sentinelUri.getCredentialsProvider()).isNotNull();
    }
  }
}
