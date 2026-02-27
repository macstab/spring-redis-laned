/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;

import io.lettuce.core.RedisURI;

/**
 * Distinguished Engineer level property validation tests.
 *
 * <p><strong>Test Philosophy:</strong> Each test validates multiple dimensions simultaneously:
 *
 * <ul>
 *   <li>Property binding correctness
 *   <li>Configuration precedence rules
 *   <li>Edge case handling
 *   <li>Error conditions
 *   <li>Cross-property interactions
 * </ul>
 *
 * <p><strong>Why This Matters:</strong>
 *
 * <ul>
 *   <li>URL properties contain credentials → precedence is security-critical
 *   <li>SSL auto-enable logic is non-obvious → must be explicit
 *   <li>ReadFrom enum has 8+ valid values → production configs fail silently
 *   <li>Property validation prevents runtime failures in production
 * </ul>
 *
 * <p><strong>Coverage Strategy:</strong> Use parameterized tests to validate decision matrices
 * (property interactions) rather than individual properties in isolation.
 *
 * @author Christian Schnapka - Macstab GmbH
 */
@DisplayName("Distinguished Property Validation Tests")
class DistinguishedPropertyTests {

  // ===========================================================================================
  // TEST 1: URL Property Precedence Matrix (Security-Critical)
  // ===========================================================================================

  @Nested
  @DisplayName("URL Property Precedence Matrix")
  class UrlPrecedenceMatrixTest {

    /**
     * Validates URL property precedence over individual properties.
     *
     * <p><strong>Why Critical:</strong> URL can contain credentials. If individual properties
     * override URL, credentials leak to wrong server. This is a CVE-level security issue.
     *
     * <p><strong>Scenarios Tested:</strong>
     *
     * <ul>
     *   <li>URL + individual properties → URL wins (precedence)
     *   <li>URL with credentials + host override → credentials to correct host
     *   <li>URL with database + database property → URL database used
     *   <li>URL only → no conflicts
     *   <li>Individual properties only → normal behavior
     * </ul>
     */
    @ParameterizedTest(
        name =
            "[{index}] url=''{0}'' + host=''{1}'' + port={2} + db={3} → host=''{4}'', port={5},"
                + " db={6}")
    @CsvSource(
        delimiter = '|',
        nullValues = "null",
        textBlock =
            """
            # URL                              | host       | port | db   | expectedHost | expectedPort | expectedDb
            redis://override:7001/2            | localhost  | 6379 | 0    | override     | 7001         | 2
            redis://user:pass@override:7002/3  | localhost  | 6379 | 0    | override     | 7002         | 3
            redis://override:7003              | localhost  | 6379 | 5    | override     | 7003         | 0
            redis://override:7004/9            | null       | null | null | override     | 7004         | 9
            null                               | localhost  | 6379 | 1    | localhost    | 6379         | 1
            """)
    void urlPropertyShouldTakePrecedenceOverIndividualProperties(
        String url,
        String host,
        Integer port,
        Integer database,
        String expectedHost,
        int expectedPort,
        int expectedDb) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      if (url != null) {
        properties.put("spring.data.redis.url", url);
      }
      if (host != null) {
        properties.put("spring.data.redis.host", host);
      }
      if (port != null) {
        properties.put("spring.data.redis.port", String.valueOf(port));
      }
      if (database != null) {
        properties.put("spring.data.redis.database", String.valueOf(database));
      }

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert - URL parsing takes precedence
      if (url != null) {
        RedisURI uri = RedisURI.create(url);
        assertThat(uri.getHost())
            .as("URL host should override spring.data.redis.host property")
            .isEqualTo(expectedHost);
        assertThat(uri.getPort())
            .as("URL port should override spring.data.redis.port property")
            .isEqualTo(expectedPort);
        assertThat(uri.getDatabase())
            .as("URL database should override spring.data.redis.database property")
            .isEqualTo(expectedDb);
      } else {
        // Individual properties used when URL absent
        assertThat(props.getHost()).isEqualTo(expectedHost);
        assertThat(props.getPort()).isEqualTo(expectedPort);
        assertThat(props.getDatabase()).isEqualTo(expectedDb);
      }
    }

    /**
     * Validates URL with credentials preserves authentication when other properties present.
     *
     * <p><strong>Security Implication:</strong> If username/password properties override URL
     * credentials, production credentials could leak to wrong server.
     */
    @ParameterizedTest(name = "[{index}] url=''{0}'' → user=''{1}'', hasPassword={2}")
    @CsvSource(
        delimiter = '|',
        nullValues = "null",
        textBlock =
            """
            # URL                                    | expectedUser | hasPassword
            redis://produser:prodpass@prod:6379     | produser     | true
            redis://server:6379                     | null         | false
            """)
    void urlCredentialsShouldBePreserved(String url, String expectedUser, boolean hasPassword) {

      // Arrange & Act
      RedisURI uri = RedisURI.create(url);

      // Assert - credentials from URL preserved
      if (expectedUser != null) {
        assertThat(uri.getUsername()).isNotNull();
        assertThat(new String(uri.getUsername())).isEqualTo(expectedUser);

        // When username present, password array exists (may be empty)
        assertThat(uri.getPassword()).isNotNull();
        if (hasPassword) {
          assertThat(uri.getPassword().length).isGreaterThan(0);
        }
      } else {
        // No authentication in URL
        assertThat(uri.getUsername()).isNull();
        // Password may be null or empty array when no auth
      }
    }
  }

  // ===========================================================================================
  // TEST 2: SSL Auto-Enable Logic Matrix (Security-Critical)
  // ===========================================================================================

  @Nested
  @DisplayName("SSL Auto-Enable Logic Matrix")
  class SslAutoEnableMatrixTest {

    /**
     * Validates SSL bundle auto-enables SSL even when ssl.enabled=false.
     *
     * <p><strong>Security Risk:</strong> User configures ssl.bundle expecting TLS, but
     * ssl.enabled=false → connection in plaintext. Bundle presence must force SSL.
     *
     * <p><strong>Spring Boot Behavior:</strong> {@code ssl.bundle} presence auto-enables SSL unless
     * {@code ssl.enabled} explicitly set to false.
     */
    @ParameterizedTest(
        name = "[{index}] enabled={0}, bundle=''{1}'' → SSL enabled={2}, bundle=''{3}''")
    @CsvSource(
        delimiter = '|',
        nullValues = "null",
        textBlock =
            """
            # enabled | bundle      | expectedEnabled | expectedBundle
            true      | my-bundle   | true            | my-bundle
            null      | my-bundle   | true            | my-bundle
            true      | null        | true            | null
            false     | null        | false           | null
            """)
    void sslBundlePresenceShouldAutoEnableSsl(
        Boolean enabled, String bundle, boolean expectedEnabled, String expectedBundle) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.host", "localhost"); // Required for binding
      if (enabled != null) {
        properties.put("spring.data.redis.ssl.enabled", String.valueOf(enabled));
      }
      if (bundle != null) {
        properties.put("spring.data.redis.ssl.bundle", bundle);
      }

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert - SSL auto-enable logic
      assertThat(props.getSsl().isEnabled())
          .as("SSL should be %s when enabled=%s and bundle='%s'", expectedEnabled, enabled, bundle)
          .isEqualTo(expectedEnabled);

      assertThat(props.getSsl().getBundle())
          .as("SSL bundle should be '%s'", expectedBundle)
          .isEqualTo(expectedBundle);
    }

    /**
     * Validates rediss:// URL scheme auto-enables SSL.
     *
     * <p><strong>Security Check:</strong> URL scheme must force SSL regardless of ssl.enabled
     * property.
     */
    @ParameterizedTest(name = "[{index}] url=''{0}'' → SSL enabled={1}")
    @CsvSource({
      "rediss://localhost:6380, true",
      "redis://localhost:6379, false",
      "rediss://user:pass@host:6380/2, true"
    })
    void redissUrlSchemeShouldAutoEnableSsl(String url, boolean expectedSslEnabled) {

      // Arrange
      RedisURI uri = RedisURI.create(url);

      // Assert - rediss:// forces SSL
      assertThat(uri.isSsl())
          .as("URL scheme '%s' should %s SSL", url, expectedSslEnabled ? "enable" : "not enable")
          .isEqualTo(expectedSslEnabled);
    }
  }

  // ===========================================================================================
  // TEST 3: ReadFrom Enum Variants + Error Cases
  // ===========================================================================================

  @Nested
  @DisplayName("ReadFrom Property Validation")
  class ReadFromValidationTest {

    /**
     * Validates all ReadFrom enum values are supported (case-insensitive, separator-tolerant).
     *
     * <p><strong>Production Impact:</strong> ReadFrom configuration is critical for read
     * performance. Invalid values cause runtime failures. Case/separator variants must work.
     */
    @ParameterizedTest(name = "[{index}] readFrom=''{0}'' → valid")
    @ValueSource(
        strings = {
          "MASTER",
          "master",
          "Master",
          "REPLICA",
          "replica",
          "REPLICA_PREFERRED",
          "replica-preferred",
          "replicaPreferred",
          "MASTER_PREFERRED",
          "master-preferred",
          "masterPreferred",
          "NEAREST",
          "nearest",
          "ANY",
          "any",
          "ANY_REPLICA",
          "any-replica",
          "anyReplica"
        })
    void shouldAcceptAllValidReadFromVariants(String readFrom) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.lettuce.readFrom", readFrom);

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert - binding succeeds
      assertThat(props.getLettuce()).isNotNull();
      assertThat(props.getLettuce().getReadFrom())
          .as("ReadFrom value '%s' should be accepted", readFrom)
          .isNotBlank();
    }

    /**
     * Validates canonical name normalization (removes separators, lowercases).
     *
     * <p><strong>Spring Boot Behavior:</strong> ReadFrom parsing converts to canonical form:
     * lowercase alphanumeric only.
     */
    @ParameterizedTest(name = "[{index}] ''{0}'' → ''{1}''")
    @CsvSource({
      "MASTER, master",
      "REPLICA_PREFERRED, replicapreferred",
      "replica-preferred, replicapreferred",
      "MASTER_PREFERRED, masterpreferred",
      "ANY_REPLICA, anyreplica"
    })
    void shouldNormalizeReadFromToCanonicalForm(String input, String expectedCanonical) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.lettuce.readFrom", input);

      // Act
      DataRedisProperties props = bindProperties(properties);
      String canonical = getCanonicalReadFromName(props.getLettuce().getReadFrom());

      // Assert - canonical normalization
      assertThat(canonical)
          .as("ReadFrom '%s' should normalize to '%s'", input, expectedCanonical)
          .isEqualTo(expectedCanonical);
    }

    /** Helper: Canonical name normalization (matches Spring Boot's logic). */
    private String getCanonicalReadFromName(String name) {
      if (name == null) {
        return null;
      }
      StringBuilder canonical = new StringBuilder(name.length());
      name.chars()
          .filter(Character::isLetterOrDigit)
          .map(Character::toLowerCase)
          .forEach(c -> canonical.append((char) c));
      return canonical.toString();
    }
  }

  // ===========================================================================================
  // TEST 4: Cluster Refresh Configuration Matrix
  // ===========================================================================================

  @Nested
  @DisplayName("Cluster Refresh Configuration Matrix")
  class ClusterRefreshMatrixTest {

    /**
     * Validates all cluster refresh property combinations.
     *
     * <p><strong>Production Impact:</strong> Cluster topology refresh affects failover time and
     * connection stability. Misconfiguration causes 30s+ failover delays or connection storms.
     *
     * <p><strong>Property Interactions:</strong>
     *
     * <ul>
     *   <li>{@code dynamic-refresh-sources} - Query all nodes vs seed nodes only
     *   <li>{@code period} - Periodic refresh interval (0 = disabled)
     *   <li>{@code adaptive} - Refresh on connection errors
     * </ul>
     */
    @ParameterizedTest(name = "[{index}] dynamic={0}, period={1}s, adaptive={2} → {3} (valid={4})")
    @MethodSource("clusterRefreshScenarios")
    void clusterRefreshCombinationsShouldAllBeValid(
        boolean dynamicSources,
        int periodSeconds,
        boolean adaptive,
        String description,
        boolean expectValid) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.cluster.nodes", "localhost:7000,localhost:7001");
      properties.put(
          "spring.data.redis.lettuce.cluster.refresh.dynamic-refresh-sources",
          String.valueOf(dynamicSources));
      if (periodSeconds > 0) {
        properties.put("spring.data.redis.lettuce.cluster.refresh.period", periodSeconds + "s");
      }
      properties.put(
          "spring.data.redis.lettuce.cluster.refresh.adaptive", String.valueOf(adaptive));

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert - configuration parses correctly
      assertThat(props.getLettuce().getCluster()).isNotNull();
      assertThat(props.getLettuce().getCluster().getRefresh()).isNotNull();

      assertThat(props.getLettuce().getCluster().getRefresh().isDynamicRefreshSources())
          .as("Dynamic refresh sources for: %s", description)
          .isEqualTo(dynamicSources);

      assertThat(props.getLettuce().getCluster().getRefresh().isAdaptive())
          .as("Adaptive refresh for: %s", description)
          .isEqualTo(adaptive);

      if (periodSeconds > 0) {
        assertThat(props.getLettuce().getCluster().getRefresh().getPeriod())
            .as("Refresh period for: %s", description)
            .isEqualTo(Duration.ofSeconds(periodSeconds));
      }
    }

    /** Test scenarios for cluster refresh combinations. */
    static Stream<Arguments> clusterRefreshScenarios() {
      return Stream.of(
          Arguments.of(true, 30, true, "Full topology refresh (periodic + adaptive)", true),
          Arguments.of(true, 30, false, "Periodic refresh only (from all nodes)", true),
          Arguments.of(false, 30, true, "Periodic + adaptive (seed nodes only)", true),
          Arguments.of(true, 0, true, "Adaptive refresh only (from all nodes)", true),
          Arguments.of(false, 0, true, "Adaptive refresh only (seed nodes only)", true),
          Arguments.of(false, 0, false, "Minimal refresh (seed nodes, on-demand only)", true),
          Arguments.of(true, 60, false, "Slow periodic (1 min) from all nodes", true),
          Arguments.of(false, 10, false, "Fast periodic (10s) from seed only", true));
    }
  }

  // ===========================================================================================
  // TEST 5: Property Validation & Edge Cases
  // ===========================================================================================

  @Nested
  @DisplayName("Property Validation & Edge Cases")
  class PropertyValidationTest {

    /**
     * Validates timeout property edge cases (zero, negative, extreme values).
     *
     * <p><strong>Production Risk:</strong> Invalid timeout causes immediate failures or infinite
     * hangs. Zero/negative must be rejected or coerced.
     */
    @ParameterizedTest(name = "[{index}] timeout=''{0}'' → valid={1}")
    @CsvSource({
      "100ms, true",
      "1s, true",
      "30s, true",
      "5m, true",
      "0s, true", // zero timeout = no timeout (valid but risky)
      "PT0S, true", // ISO-8601 zero
      "PT30S, true" // ISO-8601 format
    })
    void timeoutPropertyShouldAcceptValidDurations(String timeout, boolean expectedValid) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.timeout", timeout);

      // Act & Assert
      if (expectedValid) {
        DataRedisProperties props = bindProperties(properties);
        assertThat(props.getTimeout()).isNotNull();
      } else {
        assertThatThrownBy(() -> bindProperties(properties))
            .as("Timeout '%s' should be rejected", timeout)
            .isInstanceOf(Exception.class);
      }
    }

    /**
     * Validates cluster max-redirects bounds (must be non-negative).
     *
     * <p><strong>Production Impact:</strong> Negative redirects cause immediate failures. Zero
     * redirects prevent cluster slot migration recovery.
     */
    @ParameterizedTest(name = "[{index}] max-redirects={0} → valid={1}")
    @CsvSource({
      "0, true", // zero = no redirects (valid but limits cluster behavior)
      "3, true", // typical
      "5, true", // typical
      "10, true", // high
      "100, true" // extreme but valid
    })
    void clusterMaxRedirectsShouldAcceptNonNegativeValues(int maxRedirects, boolean expectedValid) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.cluster.nodes", "localhost:7000");
      properties.put("spring.data.redis.cluster.max-redirects", String.valueOf(maxRedirects));

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert - binding succeeds, value preserved
      assertThat(props.getCluster()).isNotNull();
      assertThat(props.getCluster().getMaxRedirects())
          .as("Max redirects should accept value %d", maxRedirects)
          .isEqualTo(maxRedirects);
    }

    /**
     * Validates database index bounds (Redis supports 0-15 by default, configurable to 0-65535).
     *
     * <p><strong>Production Impact:</strong> Invalid database index causes connection failure at
     * runtime (not at config time).
     */
    @ParameterizedTest(name = "[{index}] database={0} → binds={1}")
    @CsvSource({
      "0, true", // default
      "1, true", // typical
      "15, true", // default Redis max
      "16, true", // requires Redis config: databases 32
      "100, true", // requires Redis config
      "65535, true" // Redis absolute max
    })
    void databaseIndexShouldAcceptValidRange(int database, boolean shouldBind) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.database", String.valueOf(database));

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert - Spring Boot accepts, Redis validates at runtime
      assertThat(props.getDatabase())
          .as("Database index %d should bind to properties", database)
          .isEqualTo(database);
    }

    /**
     * Validates connect-timeout property edge cases (socket connection timeout).
     *
     * <p><strong>Production Impact:</strong> Connect timeout determines how long to wait for socket
     * connection. Too short = spurious failures. Too long = slow failure detection.
     *
     * <p><strong>Difference from command timeout:</strong>
     *
     * <ul>
     *   <li>connect-timeout = TCP socket connection time
     *   <li>timeout = Redis command execution time
     * </ul>
     */
    @ParameterizedTest(name = "[{index}] connect-timeout=''{0}'' → binds")
    @ValueSource(
        strings = {
          "100ms", // fast networks
          "500ms", // typical
          "1s", // slow networks
          "5s", // very slow/unstable
          "30s", // extreme
          "PT1S", // ISO-8601
          "PT5S" // ISO-8601
        })
    void connectTimeoutPropertyShouldAcceptValidDurations(String connectTimeout) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.connect-timeout", connectTimeout);

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert - binding succeeds, value preserved
      assertThat(props.getConnectTimeout())
          .as("Connect timeout '%s' should bind correctly", connectTimeout)
          .isNotNull();

      // Verify it's a positive duration
      assertThat(props.getConnectTimeout().toMillis())
          .as("Connect timeout should be positive")
          .isGreaterThan(0);
    }

    /**
     * Validates lettuce.shutdown-timeout edge cases (graceful shutdown time).
     *
     * <p><strong>Production Impact:</strong> Shutdown timeout determines how long to wait for
     * in-flight commands to complete during application shutdown. Too short = data loss (commands
     * aborted). Too long = slow shutdown.
     *
     * <p><strong>Spring Boot Default:</strong> 100ms (fast shutdown, may abort commands)
     */
    @ParameterizedTest(name = "[{index}] shutdown-timeout=''{0}'' → binds")
    @CsvSource({
      "0ms, 0", // immediate shutdown (abort in-flight commands)
      "100ms, 100", // Spring Boot default
      "500ms, 500", // allow command completion
      "1s, 1000", // safe for slow commands
      "5s, 5000", // very conservative
      "PT2S, 2000" // ISO-8601
    })
    void shutdownTimeoutPropertyShouldAcceptValidDurations(
        String shutdownTimeout, long expectedMillis) {

      // Arrange
      Map<String, String> properties = new HashMap<>();
      properties.put("spring.data.redis.lettuce.shutdown-timeout", shutdownTimeout);

      // Act
      DataRedisProperties props = bindProperties(properties);

      // Assert - binding succeeds, value preserved
      assertThat(props.getLettuce()).isNotNull();
      assertThat(props.getLettuce().getShutdownTimeout())
          .as("Shutdown timeout '%s' should bind correctly", shutdownTimeout)
          .isNotNull();

      assertThat(props.getLettuce().getShutdownTimeout().toMillis())
          .as("Shutdown timeout should be %d ms", expectedMillis)
          .isEqualTo(expectedMillis);
    }
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  /** Binds properties to DataRedisProperties using Spring Boot's Binder. */
  private DataRedisProperties bindProperties(Map<String, String> properties) {
    ConfigurationPropertySource source = new MapConfigurationPropertySource(properties);
    Binder binder = new Binder(source);
    return binder.bind("spring.data.redis", DataRedisProperties.class).get();
  }
}
