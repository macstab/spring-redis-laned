/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisURI;

/**
 * Unit tests for {@link SentinelTopology}.
 *
 * <p>Validates:
 *
 * <ul>
 *   <li>Factory method validation (null/blank master, empty nodes)
 *   <li>Immutability (defensive copy, unmodifiable list)
 *   <li>Sentinel URI conversion (master name, auth, SSL, database)
 * </ul>
 */
@DisplayName("SentinelTopology")
class SentinelTopologyTest {

  /**
   * Helper to create a proper Sentinel URI.
   *
   * @param host Sentinel host
   * @param port Sentinel port
   * @return RedisURI configured for Sentinel
   */
  private static RedisURI sentinelUri(final String host, final int port) {
    return RedisURI.builder().withSentinel(host, port).build();
  }

  @Nested
  @DisplayName("Factory method validation")
  class FactoryMethodValidation {

    @Test
    @DisplayName("Should create topology with valid inputs")
    void validInputs_createsTopology() {
      // Given
      final var masterName = "mymaster";
      final var nodes = List.of(sentinelUri("sentinel1", 26379), sentinelUri("sentinel2", 26379));

      // When
      final var topology = SentinelTopology.of(masterName, nodes);

      // Then
      assertThat(topology).isNotNull();
      assertThat(topology.getMasterName()).isEqualTo("mymaster");
      assertThat(topology.getSentinelNodes()).hasSize(2);
      // Sentinel URIs store host/port in sentinels list
      assertThat(topology.getSentinelNodes().get(0).getSentinels().get(0).getHost())
          .isEqualTo("sentinel1");
      assertThat(topology.getSentinelNodes().get(0).getSentinels().get(0).getPort())
          .isEqualTo(26379);
      assertThat(topology.getSentinelNodes().get(1).getSentinels().get(0).getHost())
          .isEqualTo("sentinel2");
      assertThat(topology.getSentinelNodes().get(1).getSentinels().get(0).getPort())
          .isEqualTo(26379);
    }

    @Test
    @DisplayName("Should reject null master name")
    void nullMasterName_throwsException() {
      // Given
      final var nodes = List.of(sentinelUri("sentinel1", 26379));

      // When/Then
      assertThatThrownBy(() -> SentinelTopology.of(null, nodes))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("masterName cannot be null");
    }

    @Test
    @DisplayName("Should reject blank master name")
    void blankMasterName_throwsException() {
      // Given
      final var nodes = List.of(sentinelUri("sentinel1", 26379));

      // When/Then
      assertThatThrownBy(() -> SentinelTopology.of("  ", nodes))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("masterName cannot be blank");
    }

    @Test
    @DisplayName("Should reject empty master name")
    void emptyMasterName_throwsException() {
      // Given
      final var nodes = List.of(sentinelUri("sentinel1", 26379));

      // When/Then
      assertThatThrownBy(() -> SentinelTopology.of("", nodes))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("masterName cannot be blank");
    }

    @Test
    @DisplayName("Should reject null sentinel nodes")
    void nullNodes_throwsException() {
      // When/Then
      assertThatThrownBy(() -> SentinelTopology.of("mymaster", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("sentinelNodes cannot be null");
    }

    @Test
    @DisplayName("Should reject empty sentinel nodes")
    void emptyNodes_throwsException() {
      // When/Then
      assertThatThrownBy(() -> SentinelTopology.of("mymaster", List.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sentinelNodes cannot be empty");
    }

    @Test
    @DisplayName("Should accept single sentinel node")
    void singleNode_accepted() {
      // Given
      final var nodes = List.of(sentinelUri("sentinel1", 26379));

      // When
      final var topology = SentinelTopology.of("mymaster", nodes);

      // Then
      assertThat(topology.getSentinelNodes()).hasSize(1);
    }

    @Test
    @DisplayName("Should accept multiple sentinel nodes")
    void multipleNodes_accepted() {
      // Given
      final var nodes =
          List.of(
              sentinelUri("sentinel1", 26379),
              sentinelUri("sentinel2", 26380),
              sentinelUri("sentinel3", 26381));

      // When
      final var topology = SentinelTopology.of("mymaster", nodes);

      // Then
      assertThat(topology.getSentinelNodes()).hasSize(3);
    }
  }

  @Nested
  @DisplayName("Immutability")
  class ImmutabilityTests {

    @Test
    @DisplayName("Should return defensive copy of sentinel nodes")
    void defensiveCopy_preventsExternalModification() {
      // Given
      final var mutableList = new ArrayList<>(List.of(sentinelUri("sentinel1", 26379)));
      final var topology = SentinelTopology.of("mymaster", mutableList);

      // When: modify original list
      mutableList.add(sentinelUri("sentinel2", 26379));

      // Then: topology unchanged
      assertThat(topology.getSentinelNodes()).hasSize(1);
    }

    @Test
    @DisplayName("Should return unmodifiable sentinel nodes list")
    void unmodifiableList_throwsOnModification() {
      // Given
      final var topology =
          SentinelTopology.of("mymaster", List.of(sentinelUri("sentinel1", 26379)));

      // When/Then
      assertThatThrownBy(() -> topology.getSentinelNodes().add(sentinelUri("sentinel2", 26379)))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Should return unmodifiable list on clear attempt")
    void unmodifiableList_throwsOnClear() {
      // Given
      final var topology =
          SentinelTopology.of("mymaster", List.of(sentinelUri("sentinel1", 26379)));

      // When/Then
      assertThatThrownBy(() -> topology.getSentinelNodes().clear())
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Nested
  @DisplayName("Sentinel URI conversion")
  class SentinelUriConversion {

    @Test
    @DisplayName("Should convert to Sentinel URI with master name")
    void convertsToSentinelUri_withMasterName() {
      // Given
      final var topology =
          SentinelTopology.of(
              "mymaster",
              List.of(sentinelUri("sentinel1", 26379), sentinelUri("sentinel2", 26379)));

      // When
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.getSentinelMasterId()).isEqualTo("mymaster");
      // toSentinelUri() includes ALL sentinel nodes
      assertThat(sentinelUri.getSentinels()).hasSize(2);
      assertThat(sentinelUri.getSentinels().get(0).getHost()).isEqualTo("sentinel1");
      assertThat(sentinelUri.getSentinels().get(0).getPort()).isEqualTo(26379);
      assertThat(sentinelUri.getSentinels().get(1).getHost()).isEqualTo("sentinel2");
      assertThat(sentinelUri.getSentinels().get(1).getPort()).isEqualTo(26379);
    }

    @Test
    @DisplayName("Should use first sentinel node as base")
    void usesFirstNodeAsBase() {
      // Given
      final var topology =
          SentinelTopology.of(
              "mymaster",
              List.of(sentinelUri("sentinel1", 26379), sentinelUri("sentinel2", 26380)));

      // When
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.getSentinels().get(0).getHost()).isEqualTo("sentinel1");
      assertThat(sentinelUri.getSentinels().get(0).getPort()).isEqualTo(26379);
    }

    @Test
    @DisplayName("Should preserve authentication with credentials provider")
    void preservesAuthentication_withCredentialsProvider() {
      // Given
      final var credentials = RedisCredentials.just("user", "pass");
      final var nodeWithAuth =
          RedisURI.builder()
              .withSentinel("sentinel1", 26379)
              .withAuthentication(() -> reactor.core.publisher.Mono.just(credentials))
              .build();
      final var topology = SentinelTopology.of("mymaster", List.of(nodeWithAuth));

      // When
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.getCredentialsProvider()).isNotNull();
    }

    @Test
    @DisplayName("Should preserve SSL settings")
    void preservesSslSettings() {
      // Given
      final var nodeWithSsl =
          RedisURI.builder()
              .withSentinel("sentinel1", 26379)
              .withSsl(true)
              .withVerifyPeer(true)
              .build();
      final var topology = SentinelTopology.of("mymaster", List.of(nodeWithSsl));

      // When
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.isSsl()).isTrue();
      assertThat(sentinelUri.isVerifyPeer()).isTrue();
    }

    @Test
    @DisplayName("Should preserve SSL enabled without verify peer")
    void preservesSslWithoutVerifyPeer() {
      // Given
      final var nodeWithSsl =
          RedisURI.builder()
              .withSentinel("sentinel1", 26379)
              .withSsl(true)
              .withVerifyPeer(false)
              .build();
      final var topology = SentinelTopology.of("mymaster", List.of(nodeWithSsl));

      // When
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.isSsl()).isTrue();
      assertThat(sentinelUri.isVerifyPeer()).isFalse();
    }

    @Test
    @DisplayName("Should preserve database index")
    void preservesDatabaseIndex() {
      // Given
      final var nodeWithDb =
          RedisURI.builder().withSentinel("sentinel1", 26379).withDatabase(5).build();
      final var topology = SentinelTopology.of("mymaster", List.of(nodeWithDb));

      // When
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.getDatabase()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should default to database 0")
    void defaultsToDatabase0() {
      // Given
      final var topology =
          SentinelTopology.of("mymaster", List.of(sentinelUri("sentinel1", 26379)));

      // When
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.getDatabase()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should handle no authentication")
    void handlesNoAuthentication() {
      // Given
      final var topology =
          SentinelTopology.of("mymaster", List.of(sentinelUri("sentinel1", 26379)));

      // When
      final var sentinelUri = topology.toSentinelUri();

      // Then
      // Lettuce may provide empty credentials provider, verify no actual credentials
      if (sentinelUri.getCredentialsProvider() != null) {
        final var credentials = sentinelUri.getCredentialsProvider().resolveCredentials().block();
        assertThat(credentials)
            .satisfiesAnyOf(
                c -> assertThat(c).isNull(),
                c -> assertThat(c.getUsername()).isNull(),
                c -> assertThat(c.getPassword()).isEmpty());
      }
    }

    @Test
    @DisplayName("Should handle no SSL")
    void handlesNoSsl() {
      // Given
      final var topology =
          SentinelTopology.of("mymaster", List.of(sentinelUri("sentinel1", 26379)));

      // When
      final var sentinelUri = topology.toSentinelUri();

      // Then
      assertThat(sentinelUri.isSsl()).isFalse();
    }
  }

  @Nested
  @DisplayName("Equals and hashCode")
  class EqualsAndHashCodeTests {

    @Test
    @DisplayName("Should be equal with same master and nodes")
    void equals_withSameMasterAndNodes() {
      // Given
      final var nodes = List.of(sentinelUri("sentinel1", 26379));
      final var topology1 = SentinelTopology.of("mymaster", nodes);
      final var topology2 = SentinelTopology.of("mymaster", nodes);

      // When/Then
      assertThat(topology1).isEqualTo(topology2);
      assertThat(topology1.hashCode()).isEqualTo(topology2.hashCode());
    }

    @Test
    @DisplayName("Should not be equal with different master")
    void notEquals_withDifferentMaster() {
      // Given
      final var nodes = List.of(sentinelUri("sentinel1", 26379));
      final var topology1 = SentinelTopology.of("master1", nodes);
      final var topology2 = SentinelTopology.of("master2", nodes);

      // When/Then
      assertThat(topology1).isNotEqualTo(topology2);
    }

    @Test
    @DisplayName("Should not be equal with different nodes")
    void notEquals_withDifferentNodes() {
      // Given
      final var nodes1 = List.of(sentinelUri("sentinel1", 26379));
      final var nodes2 = List.of(sentinelUri("sentinel2", 26379));
      final var topology1 = SentinelTopology.of("mymaster", nodes1);
      final var topology2 = SentinelTopology.of("mymaster", nodes2);

      // When/Then
      assertThat(topology1).isNotEqualTo(topology2);
    }
  }

  @Nested
  @DisplayName("toString")
  class ToStringTests {

    @Test
    @DisplayName("Should include master name and node count")
    void toString_includesMasterAndNodeCount() {
      // Given
      final var topology =
          SentinelTopology.of(
              "mymaster",
              List.of(sentinelUri("sentinel1", 26379), sentinelUri("sentinel2", 26379)));

      // When
      final var string = topology.toString();

      // Then
      assertThat(string).contains("mymaster");
      assertThat(string).contains("sentinelNodes");
    }
  }
}
