/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring3;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.resource.ClientResources;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration for LANED Redis connection strategy.
 *
 * <p>Activated when {@code spring.data.redis.connection.strategy=LANED}. Provides a fallback
 * connection factory when no other {@link RedisConnectionFactory} bean exists.
 *
 * <p><strong>Supported topologies:</strong>
 *
 * <ul>
 *   <li>Redis Standalone
 *   <li>Redis Sentinel
 *   <li>Redis Enterprise (proxy mode)
 *   <li>Redis Cluster (per-shard laning planned for future release)
 * </ul>
 *
 * <p><strong>Configuration example:</strong>
 *
 * <pre>{@code
 * spring:
 *   data:
 *     redis:
 *       host: localhost
 *       port: 6379
 *       connection:
 *         strategy: LANED
 *         lanes: 8
 * }</pre>
 *
 * @see LanedLettuceConnectionFactory
 * @see RedisConnectionProperties
 */
@Slf4j
@AutoConfiguration(before = RedisAutoConfiguration.class)
@ConditionalOnClass({LettuceConnectionFactory.class, RedisConnectionFactory.class})
@ConditionalOnProperty(name = "spring.data.redis.connection.strategy", havingValue = "LANED")
@EnableConfigurationProperties({RedisProperties.class, RedisConnectionProperties.class})
public class LanedRedisAutoConfiguration {

  /**
   * Creates a laned Redis connection factory.
   *
   * <p>Only created if no other {@link RedisConnectionFactory} bean exists. Supports standalone,
   * sentinel, enterprise, and cluster modes.
   *
   * <p>Builds {@link LettuceClientConfiguration} from Spring Boot beans:
   *
   * <ul>
   *   <li>{@link SslBundles} - SSL/TLS support (certificates, verify-peer)
   *   <li>{@link ClientResources} - Thread pools, DNS resolver, metrics
   *   <li>{@link LettuceClientConfigurationBuilderCustomizer} - User customizations (cluster
   *       refresh, read-from replicas, etc.)
   * </ul>
   *
   * @param redisProperties Spring Boot Redis properties
   * @param connectionProperties laned connection properties
   * @param sslBundles SSL bundles (optional, for TLS)
   * @param clientResources client resources (optional, for thread pools)
   * @param customizers configuration customizers (optional, for user extensions)
   * @param metricsProvider metrics collector provider (optional, for observability)
   * @return configured connection factory
   */
  @Bean
  @ConditionalOnMissingBean(RedisConnectionFactory.class)
  public RedisConnectionFactory lanedRedisConnectionFactory(
      final RedisProperties redisProperties,
      final RedisConnectionProperties connectionProperties,
      final ObjectProvider<SslBundles> sslBundles,
      final ObjectProvider<ClientResources> clientResources,
      final ObjectProvider<LettuceClientConfigurationBuilderCustomizer> customizers,
      final ObjectProvider<com.macstab.oss.redis.laned.metrics.LanedRedisMetrics> metricsProvider) {

    final var clientConfig =
        buildClientConfiguration(redisProperties, sslBundles, clientResources, customizers);
    final var lanes = connectionProperties.getLanes();
    final var metrics = Optional.ofNullable(metricsProvider.getIfAvailable());
    final var factory = createFactory(redisProperties, clientConfig, lanes, metrics);

    factory.setShareNativeConnection(true);
    factory.afterPropertiesSet();

    return factory;
  }

  private LanedLettuceConnectionFactory createFactory(
      final RedisProperties redisProperties,
      final LettuceClientConfiguration clientConfig,
      final int lanes,
      final Optional<com.macstab.oss.redis.laned.metrics.LanedRedisMetrics> metrics) {

    if (isSentinelConfigured(redisProperties)) {
      return createSentinelFactory(redisProperties, clientConfig, lanes, metrics);
    }

    if (isClusterConfigured(redisProperties)) {
      return createClusterFactory(redisProperties, clientConfig, lanes, metrics);
    }

    return createStandaloneFactory(redisProperties, clientConfig, lanes, metrics);
  }

  private LanedLettuceConnectionFactory createSentinelFactory(
      final RedisProperties redisProperties,
      final LettuceClientConfiguration clientConfig,
      final int lanes,
      final Optional<com.macstab.oss.redis.laned.metrics.LanedRedisMetrics> metrics) {

    final var sentinelConfig = buildSentinelConfig(redisProperties);
    final var factory =
        new LanedLettuceConnectionFactory(sentinelConfig, clientConfig, lanes, metrics);

    if (log.isInfoEnabled()) {
      log.info(
          "Redis LANED strategy (Sentinel): {} lanes, master={}, metrics={}",
          Integer.valueOf(lanes),
          redisProperties.getSentinel().getMaster(),
          metrics.isPresent() ? "enabled" : "disabled");
    }

    return factory;
  }

  private LanedLettuceConnectionFactory createClusterFactory(
      final RedisProperties redisProperties,
      final LettuceClientConfiguration clientConfig,
      final int lanes,
      final Optional<com.macstab.oss.redis.laned.metrics.LanedRedisMetrics> metrics) {

    final var clusterConfig = buildClusterConfig(redisProperties);
    final var factory =
        new LanedLettuceConnectionFactory(clusterConfig, clientConfig, lanes, metrics);

    if (log.isInfoEnabled()) {
      log.info(
          "Redis LANED strategy (Cluster): {} lanes, metrics={}",
          Integer.valueOf(lanes),
          metrics.isPresent() ? "enabled" : "disabled");
    }

    return factory;
  }

  private LanedLettuceConnectionFactory createStandaloneFactory(
      final RedisProperties redisProperties,
      final LettuceClientConfiguration clientConfig,
      final int lanes,
      final Optional<com.macstab.oss.redis.laned.metrics.LanedRedisMetrics> metrics) {

    final var standaloneConfig = buildStandaloneConfig(redisProperties);
    final var factory =
        new LanedLettuceConnectionFactory(standaloneConfig, clientConfig, lanes, metrics);

    if (log.isInfoEnabled()) {
      log.info(
          "Redis LANED strategy (Standalone/Enterprise): {} lanes, host={}:{}, metrics={}",
          Integer.valueOf(lanes),
          redisProperties.getHost(),
          Integer.valueOf(redisProperties.getPort()),
          metrics.isPresent() ? "enabled" : "disabled");
    }

    return factory;
  }

  private boolean isSentinelConfigured(final RedisProperties props) {
    final var sentinel = props.getSentinel();
    return sentinel != null
        && StringUtils.hasText(sentinel.getMaster())
        && sentinel.getNodes() != null
        && !sentinel.getNodes().isEmpty();
  }

  private boolean isClusterConfigured(final RedisProperties props) {
    final var cluster = props.getCluster();
    return cluster != null && cluster.getNodes() != null && !cluster.getNodes().isEmpty();
  }

  private RedisStandaloneConfiguration buildStandaloneConfig(final RedisProperties props) {
    final var config = new RedisStandaloneConfiguration();

    // URL overrides all individual properties
    if (StringUtils.hasText(props.getUrl())) {
      applyUrlToConfig(config, props.getUrl());
    } else {
      config.setHostName(props.getHost());
      config.setPort(props.getPort());
      config.setDatabase(props.getDatabase());

      if (StringUtils.hasText(props.getUsername())) {
        config.setUsername(props.getUsername());
      }
      if (StringUtils.hasText(props.getPassword())) {
        config.setPassword(props.getPassword());
      }
    }

    return config;
  }

  /**
   * Applies connection URL to standalone configuration.
   *
   * <p>Parses {@code redis://[user:password@]host[:port][/database]} and overrides all individual
   * properties.
   *
   * @param config standalone configuration to update
   * @param url connection URL
   */
  @SuppressWarnings("deprecation") // Lettuce RedisURI API - migration pending
  private void applyUrlToConfig(final RedisStandaloneConfiguration config, final String url) {
    final RedisURI uri = RedisURI.create(url);

    config.setHostName(uri.getHost());
    config.setPort(uri.getPort());
    config.setDatabase(uri.getDatabase());

    if (uri.getUsername() != null) {
      config.setUsername(new String(uri.getUsername()));
    }
    if (uri.getPassword() != null && uri.getPassword().length > 0) {
      config.setPassword(new String(uri.getPassword()));
    }
  }

  private RedisSentinelConfiguration buildSentinelConfig(final RedisProperties props) {
    final var sentinel = props.getSentinel();
    final var config =
        new RedisSentinelConfiguration(sentinel.getMaster(), new HashSet<>(sentinel.getNodes()));

    config.setDatabase(props.getDatabase());

    if (StringUtils.hasText(props.getUsername())) {
      config.setUsername(props.getUsername());
    }
    if (StringUtils.hasText(props.getPassword())) {
      config.setPassword(props.getPassword());
    }
    if (StringUtils.hasText(sentinel.getUsername())) {
      config.setSentinelUsername(sentinel.getUsername());
    }
    if (StringUtils.hasText(sentinel.getPassword())) {
      config.setSentinelPassword(sentinel.getPassword());
    }

    return config;
  }

  private RedisClusterConfiguration buildClusterConfig(final RedisProperties props) {
    final var cluster = props.getCluster();
    final var config = new RedisClusterConfiguration(cluster.getNodes());

    if (cluster.getMaxRedirects() != null) {
      config.setMaxRedirects(cluster.getMaxRedirects());
    }
    if (StringUtils.hasText(props.getUsername())) {
      config.setUsername(props.getUsername());
    }
    if (StringUtils.hasText(props.getPassword())) {
      config.setPassword(props.getPassword());
    }

    return config;
  }

  /**
   * Builds Lettuce client configuration from Spring Boot beans.
   *
   * <p>Reuses Spring Boot's configuration infrastructure:
   *
   * <ul>
   *   <li><strong>SSL/TLS:</strong> {@link SslBundles} for certificates and verification
   *   <li><strong>Timeouts:</strong> {@link RedisProperties#getTimeout()} and {@link
   *       RedisProperties#getConnectTimeout()}
   *   <li><strong>Resources:</strong> {@link ClientResources} for thread pools and DNS
   *   <li><strong>Customizers:</strong> User-provided {@link
   *       LettuceClientConfigurationBuilderCustomizer} beans
   * </ul>
   *
   * <p>This approach:
   *
   * <ul>
   *   <li>Delegates config parsing to Spring Boot (DRY principle)
   *   <li>Supports all Spring Boot Redis features (SSL bundles, timeouts, etc.)
   *   <li>Allows user customizations via customizer beans (Open/Closed principle)
   *   <li>Future-proof (new Spring Boot features work automatically)
   * </ul>
   *
   * @param properties Spring Boot Redis properties
   * @param sslBundles SSL bundles provider
   * @param clientResources client resources provider
   * @param customizers configuration customizers
   * @return configured client configuration
   */
  private LettuceClientConfiguration buildClientConfiguration(
      final RedisProperties properties,
      final ObjectProvider<SslBundles> sslBundles,
      final ObjectProvider<ClientResources> clientResources,
      final ObjectProvider<LettuceClientConfigurationBuilderCustomizer> customizers) {

    final var builder = LettuceClientConfiguration.builder();

    // Build ClientOptions (combines SSL + timeouts + other options)
    final var clientOptions = buildClientOptions(properties, sslBundles);
    if (clientOptions.isPresent()) {
      builder.clientOptions(clientOptions.get());
    }

    // Command timeout (separate from ClientOptions)
    final Duration commandTimeout = properties.getTimeout();
    if (commandTimeout != null) {
      builder.commandTimeout(commandTimeout);
    }

    // Shutdown timeout + read-from strategy
    final var lettuce = properties.getLettuce();
    if (lettuce != null) {
      if (lettuce.getShutdownTimeout() != null) {
        builder.shutdownTimeout(lettuce.getShutdownTimeout());
      }
      // Read-from strategy (master/replica reads)
      if (StringUtils.hasText(lettuce.getReadFrom())) {
        builder.readFrom(parseReadFrom(lettuce.getReadFrom()));
      }
    }

    // Client name (CLIENT SETNAME)
    if (StringUtils.hasText(properties.getClientName())) {
      builder.clientName(properties.getClientName());
    }

    // Enable SSL flag
    if (properties.getSsl() != null && properties.getSsl().isEnabled()) {
      builder.useSsl().and();
    }

    // URL overrides (if set, enables SSL automatically)
    if (StringUtils.hasText(properties.getUrl()) && urlUsesSsl(properties.getUrl())) {
      builder.useSsl().and();
    }

    // Client resources (thread pools, DNS)
    final var resources = clientResources.getIfAvailable();
    if (resources != null) {
      builder.clientResources(resources);
    }

    // Apply user customizers (cluster refresh, read-from, etc.)
    customizers.orderedStream().forEach(customizer -> customizer.customize(builder));

    return builder.build();
  }

  /**
   * Builds Lettuce {@link ClientOptions} combining SSL, timeout, and cluster settings.
   *
   * <p>Combines multiple configuration sources into a single ClientOptions instance:
   *
   * <ul>
   *   <li><strong>SSL bundles:</strong> {@code spring.data.redis.ssl.bundle} - Client certificates,
   *       trust stores
   *   <li><strong>Connect timeout:</strong> {@code spring.data.redis.connect-timeout} - Socket
   *       connection timeout
   *   <li><strong>Cluster refresh:</strong> {@code spring.data.redis.lettuce.cluster.refresh.*} -
   *       Topology refresh options
   * </ul>
   *
   * <p><strong>Why combine:</strong> {@code builder.clientOptions()} can only be called once.
   * Multiple calls overwrite previous settings. This method ensures all options coexist in the same
   * ClientOptions instance.
   *
   * @param properties Redis properties
   * @param sslBundles SSL bundles provider (for Spring Boot 3.1+)
   * @return configured ClientOptions if any options are needed, empty otherwise
   */
  private Optional<ClientOptions> buildClientOptions(
      final RedisProperties properties, final ObjectProvider<SslBundles> sslBundles) {

    // Use ClusterClientOptions if cluster is configured
    final ClientOptions.Builder optionsBuilder =
        isClusterConfigured(properties) ? ClusterClientOptions.builder() : ClientOptions.builder();
    boolean hasOptions = false;

    // SSL support (if bundle configured)
    final var ssl = properties.getSsl();
    if (ssl != null && ssl.isEnabled()) {
      final var bundleName = ssl.getBundle();
      if (StringUtils.hasText(bundleName)) {
        final var bundles = sslBundles.getIfAvailable();
        if (bundles != null) {
          final SslBundle bundle = bundles.getBundle(bundleName);
          final var managers = bundle.getManagers();

          final var lettuceSslOptions = io.lettuce.core.SslOptions.builder();
          if (managers.getKeyManagerFactory() != null) {
            lettuceSslOptions.keyManager(managers.getKeyManagerFactory());
          }
          if (managers.getTrustManagerFactory() != null) {
            lettuceSslOptions.trustManager(managers.getTrustManagerFactory());
          }
          optionsBuilder.sslOptions(lettuceSslOptions.build());
          hasOptions = true;

          if (log.isDebugEnabled()) {
            log.debug("SSL enabled with bundle: {}", bundleName);
          }
        }
      } else if (log.isDebugEnabled()) {
        log.debug("SSL enabled (default configuration, no bundle)");
      }
    }

    // Connect timeout
    final Duration connectTimeout = properties.getConnectTimeout();
    if (connectTimeout != null) {
      optionsBuilder.socketOptions(SocketOptions.builder().connectTimeout(connectTimeout).build());
      hasOptions = true;
    }

    // Cluster topology refresh options
    if (isClusterConfigured(properties)) {
      final var lettuce = properties.getLettuce();
      if (lettuce != null && lettuce.getCluster() != null) {
        final var refresh = lettuce.getCluster().getRefresh();
        if (refresh != null) {
          final var refreshBuilder =
              ClusterTopologyRefreshOptions.builder()
                  .dynamicRefreshSources(refresh.isDynamicRefreshSources());

          if (refresh.getPeriod() != null) {
            refreshBuilder.enablePeriodicRefresh(refresh.getPeriod());
          }
          if (refresh.isAdaptive()) {
            refreshBuilder.enableAllAdaptiveRefreshTriggers();
          }

          ((ClusterClientOptions.Builder) optionsBuilder)
              .topologyRefreshOptions(refreshBuilder.build());
          hasOptions = true;
        }
      }
    }

    return hasOptions ? Optional.of(optionsBuilder.build()) : Optional.empty();
  }

  /**
   * Parses {@code spring.data.redis.lettuce.readFrom} property.
   *
   * <p>Supports formats:
   *
   * <ul>
   *   <li>{@code MASTER} - read from master only
   *   <li>{@code REPLICA} - read from replicas only
   *   <li>{@code REPLICA_PREFERRED} - prefer replicas, fall back to master
   *   <li>{@code MASTER_PREFERRED:3} - prefer master, fall back to 3 replicas
   * </ul>
   *
   * @param readFrom readFrom string value
   * @return parsed ReadFrom enum
   */
  private ReadFrom parseReadFrom(final String readFrom) {
    final int colonIndex = readFrom.indexOf(':');
    if (colonIndex == -1) {
      return ReadFrom.valueOf(getCanonicalReadFromName(readFrom));
    }

    final String name = getCanonicalReadFromName(readFrom.substring(0, colonIndex));
    final String value = readFrom.substring(colonIndex + 1);
    return ReadFrom.valueOf(name + ":" + value);
  }

  /**
   * Converts readFrom name to canonical format (lowercase, alphanumeric only).
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code "replica-preferred"} → {@code "replicapreferred"}
   *   <li>{@code "MASTER_PREFERRED"} → {@code "masterpreferred"}
   * </ul>
   *
   * @param name readFrom name (any case, with separators)
   * @return canonical name (lowercase, alphanumeric)
   */
  private String getCanonicalReadFromName(final String name) {
    final StringBuilder canonical = new StringBuilder(name.length());
    name.chars()
        .filter(Character::isLetterOrDigit)
        .map(Character::toLowerCase)
        .forEach(c -> canonical.append((char) c));
    return canonical.toString();
  }

  /**
   * Checks if the connection URL uses SSL/TLS.
   *
   * @param url connection URL
   * @return true if URL scheme is {@code rediss://}
   */
  private boolean urlUsesSsl(final String url) {
    return url != null && url.startsWith("rediss://");
  }
}
