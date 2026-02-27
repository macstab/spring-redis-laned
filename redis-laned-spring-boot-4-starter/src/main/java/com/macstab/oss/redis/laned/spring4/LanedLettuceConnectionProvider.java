/* (C)2026 Christian Schnapka / Macstab GmbH */
package com.macstab.oss.redis.laned.spring4;

import java.util.concurrent.CompletableFuture;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionProvider;

import com.macstab.oss.redis.laned.LanedConnectionManager;

import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Spring Boot 3.x adapter for {@link LanedConnectionManager}.
 *
 * <p>This is a thin adapter layer that delegates to the pure Lettuce core implementation. All
 * connection management logic is in {@link LanedConnectionManager} (framework-independent).
 *
 * <p><strong>Design Pattern:</strong> Adapter Pattern - adapts pure Lettuce core to Spring Data
 * Redis 3.x {@link LettuceConnectionProvider} interface.
 *
 * @see LanedConnectionManager
 * @see LanedLettuceConnectionFactory
 */
@Slf4j
public final class LanedLettuceConnectionProvider implements LettuceConnectionProvider {

  private final LanedConnectionManager manager;

  /**
   * Creates a new Spring Boot 3.x connection provider adapter.
   *
   * @param manager core connection manager
   * @throws IllegalArgumentException if manager is null
   */
  public LanedLettuceConnectionProvider(final LanedConnectionManager manager) {
    if (manager == null) {
      throw new IllegalArgumentException("LanedConnectionManager cannot be null");
    }
    this.manager = manager;
  }

  /**
   * Gets a connection of the specified type.
   *
   * <p>Delegates to {@link LanedConnectionManager}:
   *
   * <ul>
   *   <li>For {@link StatefulRedisPubSubConnection}: Creates dedicated Pub/Sub connection
   *   <li>For regular connections: Returns lane connection (round-robin)
   * </ul>
   *
   * @param connectionType connection type
   * @return connection from core manager
   */
  @Override
  @SuppressWarnings("unchecked")
  public <T extends StatefulConnection<?, ?>> T getConnection(final Class<T> connectionType) {
    if (StatefulRedisPubSubConnection.class.isAssignableFrom(connectionType)) {
      return (T) manager.getPubSubConnection();
    }
    return (T) manager.getConnection();
  }

  @Override
  public <T extends StatefulConnection<?, ?>> CompletableFuture<T> getConnectionAsync(
      final Class<T> connectionType) {
    return CompletableFuture.completedFuture(getConnection(connectionType));
  }

  /**
   * Releases a connection.
   *
   * <p>Delegates to {@link LanedConnectionManager#releaseConnection(StatefulConnection)}.
   *
   * @param connection connection to release
   */
  @Override
  public void release(final StatefulConnection<?, ?> connection) {
    manager.releaseConnection(connection);
  }

  /**
   * Returns the underlying core connection manager.
   *
   * <p>Exposed for advanced use cases (e.g., accessing Pub/Sub connection count).
   *
   * @return core manager
   */
  public LanedConnectionManager getManager() {
    return manager;
  }
}
