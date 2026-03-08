# Testing Guide - redis-laned-test-utils

> **Test Infrastructure** for Redis integration testing

---

## Table of Contents

1. [Overview](#overview)
2. [Quick Start](#quick-start)
3. [Annotations](#annotations)
4. [Manual Factory Usage](#manual-factory-usage)
5. [Command Tracking](#command-tracking)
6. [Platform Requirements](#platform-requirements)
7. [Advanced Patterns](#advanced-patterns)
8. [Troubleshooting](#troubleshooting)

---

## Overview

### Architecture

```
redis-laned-test-utils/
├── annotation/           # User API (@RedisStandalone, @RedisSentinel)
├── extension/            # JUnit 5 lifecycle management
├── factory/              # Low-level container creation
├── util/                 # Command tracking, monitoring
└── condition/            # Platform checks (@DisabledOnNonLinuxHost)
```

### Design Principles

1. **Annotation-Driven**: Prefer `@RedisStandalone`/`@RedisSentinel` over manual setup
2. **Zero Duplication**: Centralized in `redis-laned-test-utils`
3. **Production Quality**: Thread-safe, properly documented
4. **Best Practices**: JUnit 5, Testcontainers, AAA pattern

---

## Quick Start

### Standalone Redis

```java
@SpringBootTest
@RedisStandalone
class MyIntegrationTest {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    @Test
    void testRedisOperations() {
        redisTemplate.opsForValue().set("key", "value");
        assertThat(redisTemplate.opsForValue().get("key"))
            .isEqualTo("value");
    }
}
```

**That's it.** The annotation auto-starts a Redis container and configures Spring Boot.

---

### Sentinel Cluster

```java
@SpringBootTest(properties = {
    "spring.data.redis.sentinel.master=mymaster",
    "spring.data.redis.sentinel.nodes=${sentinel.nodes}",
    "spring.data.redis.lettuce.read-from=REPLICA_PREFERRED"
})
@RedisSentinel(masterName = "mymaster", replicas = 2, sentinels = 3)
class SentinelTest {
    
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    
    // Cluster auto-injected via JUnit parameter resolver
    private static SentinelCluster cluster;
    
    @BeforeAll
    static void captureCluster(SentinelContainerExtension.SentinelCluster injectedCluster) {
        cluster = injectedCluster;
    }
    
    @Test
    void testReplicaReads() {
        // Reads automatically route to replicas
        redisTemplate.opsForValue().get("key");
    }
}
```

**Topology:**
- 1 master (writes)
- 2 replicas (reads)
- 3 sentinels (high availability)

---

## Annotations

### `@RedisStandalone`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RedisContainerExtension.class)
public @interface RedisStandalone {
    String version() default "7-alpine";
}
```

**Features:**
- Auto-starts Redis container
- Auto-configures Spring Boot connection
- Auto-stops after tests
- No manual cleanup needed

**Example:**

```java
@SpringBootTest
@RedisStandalone
class SimpleTest {
    // Redis is ready, just inject and use
}
```

---

### `@RedisSentinel`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(SentinelContainerExtension.class)
public @interface RedisSentinel {
    String masterName() default "mymaster";
    int replicas() default 2;
    int sentinels() default 3;
    int quorum() default 2;
    String version() default "7-alpine";
}
```

**Features:**
- Full Sentinel cluster (master + replicas + sentinels)
- Auto-configures Sentinel nodes property
- Supports parameter injection (access containers directly)
- Platform-aware (auto-skips on macOS/Windows hosts)

**Example:**

```java
@RedisSentinel(masterName = "mymaster", replicas = 3, sentinels = 5, quorum = 3)
class HighAvailabilityTest {
    // Cluster with 3 replicas + 5 sentinels (production-like)
}
```

---

### `@DisabledOnNonLinuxHost`

```java
@RedisSentinel
@DisabledOnNonLinuxHost("Sentinel requires Docker native networking")
class SentinelTest {
    // Auto-skips on macOS/Windows, runs on Linux/dev containers
}
```

**Why needed:** Sentinel returns Docker internal IPs (not routable from macOS/Windows host).

**Workaround:** Use dev container (Docker-in-Docker).

---

## Manual Factory Usage

For advanced scenarios where annotations don't fit:

### Standalone

```java
@Testcontainers
class ManualTest {
    
    @Container
    static GenericContainer<?> redis = RedisContainerFactory.createStandalone();
    
    @Test
    void test() {
        String host = redis.getHost();
        Integer port = redis.getFirstMappedPort();
        // Manual connection setup
    }
}
```

### Standalone with SSL

```java
@Container
static GenericContainer<?> redisSSL = RedisContainerFactory.createStandaloneWithSSL();
```

**Requirements:**
- Certificates in `src/test/resources/certs/`:
  - `ca.crt` (Certificate Authority)
  - `server.crt` / `server.key` (server)
  - `client.crt` / `client.key` (client)

### Sentinel Cluster

```java
static SentinelCluster cluster = RedisContainerFactory.createSentinelCluster();

@AfterAll
static void cleanup() {
    cluster.stop();
}

@Test
void test() {
    GenericContainer<?> sentinel = cluster.firstSentinel();
    String host = sentinel.getHost();
    Integer port = sentinel.getMappedPort(26379);
}
```

---

## Command Tracking

### Purpose

Verify **command routing** in integration tests (e.g., reads go to replicas, writes to master).

### Basic Usage

```java
@Test
void testReplicaRouting() {
    // Start tracking on replica
    RedisCommandTracker tracker = new RedisCommandTracker(replicaContainer);
    tracker.start();
    
    // Execute reads
    for (int i = 0; i < 1000; i++) {
        redisTemplate.opsForValue().get("key:" + i);
    }
    
    tracker.stop();
    
    // Verify replica handled reads
    long getCount = tracker.countCommand("GET");
    assertThat(getCount).isGreaterThan(900); // 90%+ to replica
}
```

### How It Works

Uses Redis `MONITOR` command to capture real-time command stream:

```
1234567890.123456 [0 172.17.0.1:54321] "GET" "key"  ✅ Tracked
1234567890.123457 [0 172.18.0.2:6379] "SET" "key"   ❌ Filtered (replication)
```

**Filtering:** Replication traffic (source port `:6379`) is automatically excluded.

### Advanced Configuration

```java
// Custom commands + disable replication filter
RedisCommandTracker tracker = RedisCommandTracker.builder()
    .container(masterContainer)
    .trackCommands(Set.of("HGETALL", "HSET", "DEL"))
    .filterReplication(false) // Include all traffic
    .build();

tracker.start();
// ... execute commands
tracker.stop();

long hgetallCount = tracker.countCommand("HGETALL");
```

### Use Cases

| Scenario | What to Verify |
|----------|----------------|
| **Sentinel ReadFrom** | Reads go to replicas (not master) |
| **Writes** | All writes go to master (never replicas) |
| **Connection pooling** | Commands distributed across lanes |
| **Circuit breaking** | Failed commands not retried on same node |

---

## Platform Requirements

### Linux Host

✅ **Fully supported** - All tests run natively

```bash
./gradlew test
```

### macOS / Windows

⚠️ **Sentinel tests require dev container** (Docker networking limitation)

**Option 1: Dev Container**

```bash
# VS Code: Command Palette -> "Dev Containers: Reopen in Container"
# Inside container:
./gradlew test
```

**Option 2: Skip Sentinel Tests**

```bash
./gradlew test -x :redis-laned-spring-boot-4-starter:test
```

Tests annotated with `@DisabledOnNonLinuxHost` auto-skip.

---

## Advanced Patterns

### Multi-Node Verification

```java
@RedisSentinel
class MultiNodeTest {
    
    @BeforeAll
    static void captureCluster(SentinelCluster cluster) {
        // Access all nodes
    }
    
    @Test
    void verifyDistribution() {
        var masterTracker = new RedisCommandTracker(cluster.getMaster());
        var replica1Tracker = new RedisCommandTracker(cluster.getReplicas().get(0));
        var replica2Tracker = new RedisCommandTracker(cluster.getReplicas().get(1));
        
        masterTracker.start();
        replica1Tracker.start();
        replica2Tracker.start();
        
        // Execute mixed workload
        for (int i = 0; i < 1000; i++) {
            redisTemplate.opsForValue().set("key:" + i, "value");
            redisTemplate.opsForValue().get("key:" + i);
        }
        
        masterTracker.stop();
        replica1Tracker.stop();
        replica2Tracker.stop();
        
        // Verify routing
        assertThat(masterTracker.countCommand("SET")).isGreaterThan(900);
        assertThat(replica1Tracker.countCommand("GET") + replica2Tracker.countCommand("GET"))
            .isGreaterThan(900);
    }
}
```

### Custom Sentinel Configuration

```java
// Create cluster manually for custom config
SentinelCluster cluster = RedisContainerFactory.createSentinelCluster();

// Get Sentinel connection details
GenericContainer<?> sentinel = cluster.firstSentinel();
String nodes = sentinel.getHost() + ":" + sentinel.getMappedPort(26379);

// Use in Spring Boot test
@DynamicPropertySource
static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.sentinel.nodes", () -> nodes);
}
```

---

## Troubleshooting

### Tests Hang on Startup

**Cause:** Testcontainers waiting for container ready check

**Fix:** Check Docker is running:

```bash
docker ps  # Should list running containers
```

### Port Conflicts

**Cause:** Previous test containers still running

**Fix:**

```bash
docker ps -a | grep redis | awk '{print $1}' | xargs docker rm -f
docker network prune -f
```

### Sentinel Tests Skipped on macOS

**Expected behavior** - Sentinel requires Linux host or dev container.

**Fix:** Use dev container (see Platform Requirements).

### "Connection refused" Errors

**Cause:** Container not fully started

**Fix:** Increase startup timeout:

```java
@RedisSentinel
class SlowStartTest {
    // Custom factory with longer timeout
    static {
        // Use manual setup with custom timeout
    }
}
```

### Test Isolation Issues

**Cause:** Data from previous tests still in Redis

**Fix:** Flush between tests:

```java
@BeforeEach
void flushRedis() {
    redisTemplate.getConnectionFactory()
        .getConnection()
        .serverCommands()
        .flushAll();
}
```

---

## Best Practices

### ✅ DO

1. **Use annotations** (`@RedisStandalone`, `@RedisSentinel`) instead of manual setup
2. **Flush data between tests** for isolation
3. **Use Awaitility** for async behavior (replication lag, etc.)
4. **Add `@DisabledOnNonLinuxHost`** to Sentinel tests
5. **Use descriptive test names** (`@DisplayName`)

### ❌ DON'T

1. **Don't reuse containers** across test classes (test isolation)
2. **Don't use fixed ports** (use mapped ports)
3. **Don't assume instant replication** (use `await().until()`)
4. **Don't commit test data** (flush in `@AfterEach`)
5. **Don't mix manual + annotation setup** (choose one)

---

## Examples

### Complete Sentinel ReadFrom Test

See: [`SentinelReadFromIntegrationTest.java`](../redis-laned-spring-boot-4-starter/src/test/java/com/macstab/oss/redis/laned/spring4/sentinel/SentinelReadFromIntegrationTest.java)

**What it tests:**
- Reads route to replicas (80%+ threshold)
- Writes route to master (100%)
- Uses `RedisCommandTracker` for verification
- Filters replication traffic
- Production-ready example

### SSL/TLS Test

See: [`SSLIntegrationTest.java`](../redis-laned-spring-boot-4-starter/src/test/java/com/macstab/oss/redis/laned/spring4/integration/SSLIntegrationTest.java)

**What it tests:**
- Mutual TLS (client certificates)
- Trust store configuration
- Connection over TLS port (6380)

---

## CI/CD Integration

### GitHub Actions

```yaml
name: Integration Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest  # Linux required for Sentinel tests
    
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Run Tests
        run: ./gradlew test --info
      
      - name: Upload Test Reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: '**/build/reports/tests/**'
```

---

## Performance

### Startup Times

| Test Type | Containers | Startup Time |
|-----------|------------|--------------|
| `@RedisStandalone` | 1 | ~2-3s |
| `@RedisSentinel` (default) | 6 | ~20-30s |
| SSL Redis | 1 | ~2-3s |

### Optimization Tips

1. **Reuse containers** within test class (static `@Container`)
2. **Use `@Nested` classes** for logical grouping (share container)
3. **Run Sentinel tests separately** (slower, run in CI only)

---

## Summary

| Feature | Annotation | Manual Factory |
|---------|------------|----------------|
| **Ease of use** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **Flexibility** | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Test isolation** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **Spring Boot integration** | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| **Best for** | Most tests | Custom scenarios |

**Recommendation:** Start with annotations, use manual factory only when needed.

---

## Further Reading

- [Testcontainers Enhancements](TESTCONTAINERS_ENHANCEMENTS.md) - Advanced patterns (resource limits, Kubernetes, Toxiproxy)
- [Sentinel ReadFrom Testing](TESTING_SENTINEL_READFROM.md) - Deep dive into replica routing verification
- [Main README](../README.md) - Library overview and configuration

---

_Last Updated: 2026-03-06 | Author: Christian Schnapka / Embedded Principal+ Engineer - Macstab GmbH_
