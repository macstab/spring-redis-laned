/* (C)2026 Macstab GmbH */

/**
 * Spring Boot 3.x auto-configuration for laned Redis connections.
 *
 * <h2>Purpose</h2>
 *
 * <p>Provides zero-code Spring Boot 3.x integration for {@link
 * com.macstab.oss.redis.laned.LanedConnectionManager}. Drop-in replacement for standard {@code
 * LettuceConnectionFactory} with laned connection strategy support.
 *
 * <h2>Quick Start</h2>
 *
 * <p><strong>1. Add dependency (Maven):</strong>
 *
 * <pre>{@code
 * <dependency>
 *   <groupId>com.macstab.oss.redis</groupId>
 *   <artifactId>redis-laned-spring-boot-3-starter</artifactId>
 *   <version>1.0.0</version>
 * </dependency>
 * }</pre>
 *
 * <p><strong>2. Configure (application.yml):</strong>
 *
 * <pre>{@code
 * spring:
 *   data:
 *     redis:
 *       host: redis.example.com
 *       port: 6379
 *       connection:
 *         strategy: LANED   # CLASSIC (default) | POOLED | LANED
 *         lanes: 8          # 1-64, default 8
 * }</pre>
 *
 * <p><strong>3. Use (standard Spring Data Redis API):</strong>
 *
 * <pre>{@code
 * @Service
 * public class MyService {
 *     @Autowired
 *     private RedisTemplate<String, String> redisTemplate;
 *
 *     public void doWork() {
 *         redisTemplate.opsForValue().set("key", "value");
 *     }
 * }
 * }</pre>
 *
 * <h2>Architecture</h2>
 *
 * <pre>
 * ┌────────────────────────────────────────────────────────────┐
 * │ Spring Boot 3.x Application                                │
 * │   ├─→ @Cacheable (Spring Cache)                            │
 * │   ├─→ RedisTemplate (Spring Data Redis)                    │
 * │   └─→ StringRedisTemplate                                  │
 * └────────────────┬───────────────────────────────────────────┘
 *                  ↓
 * ┌────────────────────────────────────────────────────────────┐
 * │ LanedRedisAutoConfiguration (this package)                 │
 * │   ├─→ @ConditionalOnProperty (strategy = LANED)            │
 * │   ├─→ RedisConnectionProperties (property binding)         │
 * │   └─→ Creates LanedLettuceConnectionFactory                │
 * └────────────────┬───────────────────────────────────────────┘
 *                  ↓
 * ┌────────────────────────────────────────────────────────────┐
 * │ LanedLettuceConnectionFactory (adapter)                    │
 * │   └─→ Adapts Spring Boot 3.x RedisConnectionFactory        │
 * │         to LanedConnectionManager                          │
 * └────────────────┬───────────────────────────────────────────┘
 *                  ↓
 * ┌────────────────────────────────────────────────────────────┐
 * │ LanedLettuceConnectionProvider (Spring Data Redis bridge)  │
 * │   └─→ Adapts LettuceConnectionProvider interface           │
 * │         to LanedConnectionManager                          │
 * └────────────────┬───────────────────────────────────────────┘
 *                  ↓
 * ┌────────────────────────────────────────────────────────────┐
 * │ LanedConnectionManager (core, framework-independent)        │
 * │   └─→ Pure Lettuce implementation (no Spring dependencies) │
 * └────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>Key Components</h2>
 *
 * <dl>
 *   <dt>{@link com.macstab.oss.redis.laned.spring3.LanedRedisAutoConfiguration}
 *   <dd>Spring Boot auto-configuration. Activated when {@code
 *       spring.data.redis.connection.strategy=LANED}. Creates {@code LanedLettuceConnectionFactory}
 *       bean.
 *   <dt>{@link com.macstab.oss.redis.laned.spring3.LanedLettuceConnectionFactory}
 *   <dd>Adapter implementing Spring Data Redis {@code RedisConnectionFactory}. Delegates to {@code
 *       LanedConnectionManager} for connection lifecycle.
 *   <dt>{@link com.macstab.oss.redis.laned.spring3.LanedLettuceConnectionProvider}
 *   <dd>Adapter implementing Spring Data Redis {@code LettuceConnectionProvider}. Thin bridge to
 *       {@code LanedConnectionManager}.
 *   <dt>{@link com.macstab.oss.redis.laned.spring3.RedisConnectionProperties}
 *   <dd>Configuration properties ({@code @ConfigurationProperties}). Binds {@code
 *       spring.data.redis.connection.*} to Java objects.
 *   <dt>{@link com.macstab.oss.redis.laned.spring3.RedisConnectionStrategy}
 *   <dd>Enum defining connection strategies: {@code CLASSIC} (single), {@code POOLED}
 *       (commons-pool), {@code LANED} (fixed lanes).
 * </dl>
 *
 * <h2>Configuration Reference</h2>
 *
 * <p><strong>All standard Spring Boot Redis properties supported:</strong>
 *
 * <pre>{@code
 * spring:
 *   data:
 *     redis:
 *       # Connection
 *       host: localhost
 *       port: 6379
 *       database: 0
 *       username: default
 *       password: secret
 *       url: redis://user:pass@host:port/db  # Overrides host/port/username/password/database
 *       client-name: my-app
 *       timeout: 60s
 *       connect-timeout: 10s
 *
 *       # SSL/TLS (Spring Boot 3.1+ SSL Bundles)
 *       ssl:
 *         enabled: true
 *         bundle: my-redis-tls  # References spring.ssl.bundle.pem.my-redis-tls
 *
 *       # Sentinel (High Availability)
 *       sentinel:
 *         master: mymaster
 *         nodes: sentinel1:26379,sentinel2:26379
 *         username: sentinel-user
 *         password: sentinel-pass
 *
 *       # Cluster (OSS Cluster Protocol)
 *       cluster:
 *         nodes: node1:6379,node2:6379,node3:6379
 *         max-redirects: 3
 *
 *       # Lettuce-specific
 *       lettuce:
 *         shutdown-timeout: 100ms
 *         readFrom: REPLICA_PREFERRED  # MASTER, REPLICA, REPLICA_PREFERRED, etc.
 *         cluster:
 *           refresh:
 *             dynamic-refresh-sources: true
 *             period: 60s
 *             adaptive: true
 *
 *       # LANED strategy (this library)
 *       connection:
 *         strategy: LANED   # CLASSIC | POOLED | LANED
 *         lanes: 8          # 1-64, default 8
 * }</pre>
 *
 * <h2>SSL/TLS Configuration</h2>
 *
 * <p><strong>Server-only TLS (verify Redis server certificate):</strong>
 *
 * <pre>{@code
 * spring:
 *   data:
 *     redis:
 *       ssl:
 *         enabled: true
 *         bundle: my-redis-tls
 *   ssl:
 *     bundle:
 *       pem:
 *         my-redis-tls:
 *           truststore:
 *             certificate: file:/etc/certs/ca-cert.pem  # CA certificate
 * }</pre>
 *
 * <p><strong>Mutual TLS (client certificate + server verification):</strong>
 *
 * <pre>{@code
 * spring:
 *   data:
 *     redis:
 *       ssl:
 *         enabled: true
 *         bundle: my-redis-mtls
 *   ssl:
 *     bundle:
 *       pem:
 *         my-redis-mtls:
 *           keystore:
 *             certificate: file:/etc/certs/client-cert.pem  # Client certificate
 *             private-key: file:/etc/certs/client-key.pem    # Client private key
 *           truststore:
 *             certificate: file:/etc/certs/ca-cert.pem       # CA certificate
 * }</pre>
 *
 * <h2>Connection Strategy Comparison</h2>
 *
 * <table>
 *   <caption>Strategy Selection Guide</caption>
 *   <thead>
 *     <tr>
 *       <th>Strategy</th>
 *       <th>Use When</th>
 *       <th>Connections (30 pods)</th>
 *       <th>HOL Blocking</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@code CLASSIC}</td>
 *       <td>Low concurrency, uniform fast commands</td>
 *       <td>30 (1 per pod)</td>
 *       <td>100%</td>
 *     </tr>
 *     <tr>
 *       <td>{@code POOLED}</td>
 *       <td>Need connection isolation, willing to pay connection cost</td>
 *       <td>1,500 (50-pool × 30)</td>
 *       <td>0% (isolated)</td>
 *     </tr>
 *     <tr>
 *       <td>{@code LANED}</td>
 *       <td>High concurrency, mixed fast/slow commands</td>
 *       <td>240 (8 lanes × 30)</td>
 *       <td>~12.5% (1/8)</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <h2>Lane Count Sizing Guide</h2>
 *
 * <ul>
 *   <li><strong>4 lanes:</strong> Light workloads (&lt; 100 req/sec), memory-constrained
 *   <li><strong>8 lanes (default):</strong> Balanced for most workloads (100-1000 req/sec)
 *   <li><strong>16 lanes:</strong> High concurrency (1000-10000 req/sec), mixed latency
 *   <li><strong>32+ lanes:</strong> Fan-out workloads, many blocking commands ({@code BLPOP},
 *       {@code BRPOP})
 * </ul>
 *
 * <h2>Auto-Configuration Conditions</h2>
 *
 * <p>{@code LanedRedisAutoConfiguration} activates when ALL of:
 *
 * <ol>
 *   <li>{@code RedisOperations.class} on classpath (Spring Data Redis present)
 *   <li>{@code spring.data.redis.connection.strategy = LANED} in properties
 *   <li>No user-defined {@code RedisConnectionFactory} bean exists (can be overridden)
 * </ol>
 *
 * <h2>Metrics Integration</h2>
 *
 * <p>Micrometer metrics exposed (compatible with existing Redis dashboards):
 *
 * <ul>
 *   <li>{@code lettuce.connections.active} - Active connections per lane
 *   <li>{@code lettuce.connections.idle} - Always 0 (lanes never idle)
 *   <li>{@code lettuce.connections.usage} - Connection usage time
 *   <li>{@code lettuce.pool.pending} - Always 0 (no borrow blocking)
 * </ul>
 *
 * <h2>Transaction Safety (WATCH/MULTI/EXEC)</h2>
 *
 * <p><strong>Thread affinity ensures transaction correctness:</strong>
 *
 * <p>Redis stores transaction state per-connection (Redis {@code client->mstate} in {@code
 * networking.c}). If connection switches mid-transaction, {@code EXEC} fails (commands went to
 * different connection).
 *
 * <p>This library uses {@code ThreadLocal} to pin threads to lanes during transactions:
 *
 * <pre>{@code
 * // Same thread → same lane → same connection
 * redisTemplate.watch("key");            // Thread pinned to lane 3
 * redisTemplate.multi();                 // Still lane 3
 * redisTemplate.opsForValue().set(...);  // Still lane 3
 * redisTemplate.exec();                  // Still lane 3, transaction succeeds
 * }</pre>
 *
 * <p><strong>Limitation: Reactive (Project Reactor) NOT SUPPORTED</strong>
 *
 * <p>Reactor suspends/resumes on different threads → ThreadLocal doesn't propagate → transactions
 * may break. Use imperative {@code RedisTemplate} for transactions, not reactive {@code
 * ReactiveRedisTemplate}.
 *
 * <h2>Migration from Standard Spring Boot Redis</h2>
 *
 * <p><strong>Zero code changes required:</strong>
 *
 * <ol>
 *   <li>Add dependency: {@code redis-laned-spring-boot-3-starter}
 *   <li>Change config: {@code spring.data.redis.connection.strategy: LANED}
 *   <li>Restart application
 * </ol>
 *
 * <p>All existing {@code RedisTemplate}, {@code @Cacheable}, {@code @RedisHash} code works
 * unchanged.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All beans created by this auto-configuration are thread-safe:
 *
 * <ul>
 *   <li>{@code LanedLettuceConnectionFactory}: Thread-safe. Multiple threads can call {@code
 *       getConnection()} concurrently.
 *   <li>{@code LanedLettuceConnectionProvider}: Thread-safe. Delegates to thread-safe {@code
 *       LanedConnectionManager}.
 *   <li>{@code RedisTemplate} beans: Thread-safe (Spring Data Redis contract).
 * </ul>
 *
 * <h2>Testing</h2>
 *
 * <p><strong>Unit tests (mock Redis):</strong>
 *
 * <pre>{@code
 * @SpringBootTest
 * @TestPropertySource(properties = {
 *     "spring.data.redis.connection.strategy=LANED",
 *     "spring.data.redis.connection.lanes=4"
 * })
 * class MyServiceTest {
 *     @Autowired
 *     private RedisTemplate<String, String> redisTemplate;
 *
 *     @Test
 *     void testRedis() {
 *         redisTemplate.opsForValue().set("key", "value");
 *         assertThat(redisTemplate.opsForValue().get("key")).isEqualTo("value");
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Integration tests (Testcontainers):</strong>
 *
 * <pre>{@code
 * @Testcontainers
 * @SpringBootTest
 * class MyServiceIntegrationTest {
 *     @Container
 *     static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
 *         .withExposedPorts(6379);
 *
 *     @DynamicPropertySource
 *     static void redisProperties(DynamicPropertyRegistry registry) {
 *         registry.add("spring.data.redis.host", redis::getHost);
 *         registry.add("spring.data.redis.port", redis::getFirstMappedPort);
 *         registry.add("spring.data.redis.connection.strategy", () -> "LANED");
 *     }
 * }
 * }</pre>
 *
 * <h2>Troubleshooting</h2>
 *
 * <p><strong>Auto-configuration not activating:</strong>
 *
 * <ul>
 *   <li>Check {@code spring.data.redis.connection.strategy=LANED} in properties
 *   <li>Verify no custom {@code @Bean RedisConnectionFactory} (disables auto-config)
 *   <li>Enable debug logging: {@code logging.level.com.macstab.oss.redis.laned=DEBUG}
 * </ul>
 *
 * <p><strong>High connection count to Redis:</strong>
 *
 * <ul>
 *   <li>Each pod creates N lanes (connections)
 *   <li>Total connections = (lanes × pods)
 *   <li>Default 8 lanes × 30 pods = 240 connections
 *   <li>Reduce lanes if hitting Redis {@code maxclients} limit
 * </ul>
 *
 * <p><strong>Transaction failures (MULTI/EXEC):</strong>
 *
 * <ul>
 *   <li>Verify using imperative {@code RedisTemplate} (NOT reactive {@code ReactiveRedisTemplate})
 *   <li>Check thread affinity: same thread from {@code WATCH} to {@code EXEC}
 *   <li>Reactive code suspends/resumes on different threads → breaks affinity
 * </ul>
 *
 * <h2>Related Documentation</h2>
 *
 * <ul>
 *   <li><a href="file:../../../../../docs/TECHNICAL_REFERENCE.md">Complete Technical Reference</a>
 *   <li><a href="file:../../../../../README.md">README with configuration examples</a>
 *   <li><a href="file:../../../../../docs/TRANSACTION_SAFETY_DEEP_DIVE.md">Transaction Safety Deep
 *       Dive</a>
 * </ul>
 *
 * @since 1.0.0
 * @author Christian Schnapka - Macstab GmbH
 * @see com.macstab.oss.redis.laned.spring3.LanedRedisAutoConfiguration
 * @see com.macstab.oss.redis.laned.spring3.LanedLettuceConnectionFactory
 * @see com.macstab.oss.redis.laned.LanedConnectionManager
 */
package com.macstab.oss.redis.laned.spring3;
