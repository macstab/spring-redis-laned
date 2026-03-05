# Redis Sentinel Configuration with spring-redis-laned

**Author:** Christian Schnapka / Macstab GmbH  
**Version:** 1.0 (2026-03-02)  
**Applies to:** spring-redis-laned 1.0.0+

---

## Overview

Redis Sentinel provides high availability through automatic master failover. When combined with `spring-redis-laned`, you get:

1. **Automatic failover** - Sentinel promotes a replica to master when the master fails
2. **Read scaling** - Distribute read operations across replicas using `ReadFrom` routing
3. **Reduced HOL blocking** - Multiple lanes prevent head-of-line blocking

**Benefits of combining Sentinel + Lanes:**
- **Availability:** Automatic failover ensures uptime during master failures
- **Performance:** Read scaling + lane multiplexing for maximum throughput
- **Simplicity:** Spring Boot autoconfiguration handles Lettuce `MasterReplica` setup

---

## Quick Start

### Minimal Sentinel Configuration (Master-Only Reads)

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379,sentinel2:26379,sentinel3:26379
      connection:
        strategy: LANED
        lanes: 8
```

**Result:** 8 lanes to master, automatic failover enabled, all reads/writes to master.

---

### Sentinel with Read-From-Replica

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379,sentinel2:26379,sentinel3:26379
      lettuce:
        read-from: REPLICA_PREFERRED  # Route reads to replicas
      connection:
        strategy: LANED
        lanes: 8
```

**Result:** 8 lanes with read/write splitting:
- **Writes:** Always go to master
- **Reads:** Prefer replicas, fall back to master if replicas unavailable

---

## ReadFrom Topology Options

Lettuce provides several read routing strategies. Choose based on your requirements:

### `MASTER` (Default)

```yaml
spring.data.redis.lettuce.read-from: MASTER
```

- **Reads:** Master only
- **Writes:** Master only
- **Failover:** Automatic (via MasterReplica)
- **Use case:** Consistency-critical applications, no read scaling needed

### `REPLICA_PREFERRED` (Recommended)

```yaml
spring.data.redis.lettuce.read-from: REPLICA_PREFERRED
```

- **Reads:** Prefer replicas, fall back to master if all replicas down
- **Writes:** Master only
- **Failover:** Automatic
- **Use case:** **Most common** - read scaling with high availability

### `REPLICA`

```yaml
spring.data.redis.lettuce.read-from: REPLICA
```

- **Reads:** Replicas only (fails if no replicas available)
- **Writes:** Master only
- **Failover:** Automatic
- **Use case:** Strict read-replica separation (rare)

### `MASTER_PREFERRED`

```yaml
spring.data.redis.lettuce.read-from: MASTER_PREFERRED
```

- **Reads:** Master first, fall back to replicas if master unavailable
- **Writes:** Master only
- **Failover:** Automatic
- **Use case:** Minimize replication lag (eventual consistency acceptable)

### `LOWEST_LATENCY`

```yaml
spring.data.redis.lettuce.read-from: LOWEST_LATENCY
```

- **Reads:** Route to node with lowest latency (dynamic)
- **Writes:** Master only
- **Failover:** Automatic
- **Use case:** Geographically distributed clusters

### `ANY`

```yaml
spring.data.redis.lettuce.read-from: ANY
```

- **Reads:** Any available node (master or replica)
- **Writes:** Master only
- **Failover:** Automatic
- **Use case:** Maximum read scaling, eventual consistency acceptable

---

## Authentication

### Master Authentication (ACL)

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379,sentinel2:26379
      username: redisuser        # ACL username
      password: securepassword   # ACL password
      connection:
        strategy: LANED
        lanes: 8
```

### Master Authentication (Legacy)

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379
      password: securepassword   # Legacy (no username)
      connection:
        strategy: LANED
        lanes: 8
```

### Sentinel Authentication

If your Sentinel nodes require authentication (separate from master/replicas):

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379
        username: sentineluser     # Sentinel ACL username (optional)
        password: sentinelpass     # Sentinel password
      username: redisuser          # Master/replica username
      password: redispass          # Master/replica password
      connection:
        strategy: LANED
        lanes: 8
```

**Note:** Sentinel authentication is handled by Lettuce `MasterReplica` internally. No additional configuration needed in `spring-redis-laned`.

---

## SSL/TLS

### Enable SSL for Master and Replicas

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379
      ssl:
        enabled: true
      connection:
        strategy: LANED
        lanes: 8
```

### SSL with Custom Trust Store

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379
      ssl:
        enabled: true
        bundle: redis-ssl
  ssl:
    bundle:
      jks:
        redis-ssl:
          truststore:
            location: classpath:truststore.jks
            password: changeit
```

---

## Multiple Sentinel Nodes (High Availability)

**Best practice:** Configure at least 3 Sentinel nodes for quorum-based failover.

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - sentinel1.example.com:26379
          - sentinel2.example.com:26379
          - sentinel3.example.com:26379
      connection:
        strategy: LANED
        lanes: 8
```

**Behavior:**
- `spring-redis-laned` connects to **first available Sentinel** from list
- Lettuce `MasterReplica` discovers master + replicas via Sentinel protocol
- Sentinel monitors master health and triggers failover when needed

---

## Database Selection

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes: sentinel1:26379
      database: 5  # Use database 5 instead of default 0
      connection:
        strategy: LANED
        lanes: 8
```

---

## Troubleshooting

### Warning: "ReadFrom configured but no Sentinel topology"

**Message:**
```
WARN ReadFrom=REPLICA_PREFERRED configured but no Sentinel topology provided.
ReadFrom will be ignored (no replicas available).
This is expected for standalone Redis or Enterprise proxy mode.
```

**Cause:** `spring.data.redis.lettuce.read-from` is set, but:
- No Sentinel configuration found (`spring.data.redis.sentinel.master` missing)
- OR using Redis Standalone
- OR using Redis Enterprise proxy mode

**Solution:**
- **If using Sentinel:** Add `spring.data.redis.sentinel.master` and `spring.data.redis.sentinel.nodes`
- **If using Standalone/Enterprise:** Remove `spring.data.redis.lettuce.read-from` (no replicas available)

---

### Replicas Not Receiving Reads

**Symptom:** All traffic goes to master, replicas idle.

**Diagnosis:**
```bash
# Check replica command stats
docker exec redis-replica-1 redis-cli INFO commandstats | grep cmdstat_get

# Expected: cmdstat_get:calls=10000,...
# Actual: cmdstat_get:calls=0  ← No reads!
```

**Possible causes:**

1. **ReadFrom not configured:**
   ```yaml
   # Missing:
   spring.data.redis.lettuce.read-from: REPLICA_PREFERRED
   ```

2. **Replicas not connected to Sentinel:**
   ```bash
   docker exec sentinel redis-cli -p 26379 SENTINEL replicas mymaster
   # Should show 2+ replicas with status=online
   ```

3. **Replication broken:**
   ```bash
   docker exec redis-master redis-cli INFO replication
   # Should show connected_slaves:2
   ```

4. **Using Spring Data Redis Repositories instead of RedisTemplate:**
   ```java
   // ❌ Doesn't work with lanes:
   @Autowired UserRepository repository;
   repository.findById(id);

   // ✅ Works with lanes:
   @Autowired RedisTemplate<String, User> redisTemplate;
   redisTemplate.opsForValue().get(id);
   ```

---

### Failover Not Working

**Symptom:** Master fails, application throws connection errors instead of reconnecting to new master.

**Diagnosis:**
```bash
# Trigger failover
docker exec sentinel redis-cli -p 26379 SENTINEL failover mymaster

# Check new master
docker exec sentinel redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster
```

**Possible causes:**

1. **Not using Sentinel configuration:**
   ```yaml
   # ❌ Standalone mode (no failover):
   spring.data.redis.host: redis-master
   spring.data.redis.port: 6379

   # ✅ Sentinel mode (automatic failover):
   spring.data.redis.sentinel.master: mymaster
   spring.data.redis.sentinel.nodes: sentinel1:26379
   ```

2. **ReadFrom not set (using direct connection instead of MasterReplica):**
   - Even with Sentinel configured, if `ReadFrom` is missing, `spring-redis-laned` uses `client.connect()` instead of `MasterReplica.connect()`
   - Set `spring.data.redis.lettuce.read-from: MASTER` to enable failover (even if not using replicas for reads)

---

## Architecture: How It Works

### Without spring-redis-laned (Standard Spring Boot)

```
Spring Boot ──> LettuceConnectionFactory
                └─> Single Lettuce connection to master
                    └─> All commands serialized (HOL blocking)
```

### With spring-redis-laned (8 Lanes)

```
Spring Boot ──> LanedLettuceConnectionFactory
                └─> LanedConnectionManager
                    ├─> Lane 0: MasterReplica.connect() → Round-robin to replicas
                    ├─> Lane 1: MasterReplica.connect() → Round-robin to replicas
                    ├─> Lane 2: MasterReplica.connect() → Round-robin to replicas
                    ├─> Lane 3: MasterReplica.connect() → Round-robin to replicas
                    ├─> Lane 4: MasterReplica.connect() → Round-robin to replicas
                    ├─> Lane 5: MasterReplica.connect() → Round-robin to replicas
                    ├─> Lane 6: MasterReplica.connect() → Round-robin to replicas
                    └─> Lane 7: MasterReplica.connect() → Round-robin to replicas
```

**Key points:**
- Each lane is a separate Lettuce `MasterReplica` connection
- Lettuce handles Sentinel discovery, failover, and read routing
- `spring-redis-laned` provides multiplexing (lanes) to reduce HOL blocking
- Round-robin strategy distributes commands across lanes

---

## Java Configuration (Alternative to YAML)

```java
@Configuration
public class RedisConfig {

  @Bean
  public RedisConnectionFactory redisConnectionFactory() {
    final var sentinelConfig = new RedisSentinelConfiguration("mymaster", 
        Set.of("sentinel1:26379", "sentinel2:26379"));
    sentinelConfig.setPassword(RedisPassword.of("password"));

    final var clientConfig = LettuceClientConfiguration.builder()
        .readFrom(ReadFrom.REPLICA_PREFERRED)
        .build();

    final var factory = new LanedLettuceConnectionFactory(
        sentinelConfig, 
        clientConfig, 
        8,  // lanes
        Optional.empty()  // metrics
    );
    
    factory.afterPropertiesSet();
    return factory;
  }

  @Bean
  public RedisTemplate<String, String> redisTemplate(
      RedisConnectionFactory connectionFactory) {
    final var template = new RedisTemplate<String, String>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    template.afterPropertiesSet();
    return template;
  }
}
```

---

## Best Practices

1. **Use at least 3 Sentinel nodes** for quorum-based failover
2. **Set `ReadFrom.REPLICA_PREFERRED`** for read scaling (most common use case)
3. **Use RedisTemplate instead of Repositories** (repositories bypass lanes)
4. **Monitor replica lag** with `INFO replication` on master
5. **Test failover** before production (manual `SENTINEL failover mymaster`)
6. **Enable SSL** for production deployments
7. **Use ACL authentication** (username + password) instead of legacy password-only

---

## Performance Expectations

### Baseline (Single Connection, Master-Only)

```
Throughput: 50,000-60,000 ops/sec
P99 latency: 5-10ms (HOL blocking on large commands)
```

### With 8 Lanes (Master-Only, No ReadFrom)

```
Throughput: 65,000-75,000 ops/sec (+30%)
P99 latency: 3-5ms (reduced HOL blocking)
```

### With 8 Lanes + ReadFrom.REPLICA_PREFERRED (2 Replicas)

```
Read throughput: 100,000-150,000 ops/sec (+2-3×)
Write throughput: 65,000-75,000 ops/sec (same as master-only)
P99 latency: 2-4ms (read distribution + reduced HOL)
```

**Note:** Actual performance depends on:
- Network latency between client/Redis
- Command complexity (GET vs HGETALL)
- Object sizes
- CPU limits on Redis containers
- Number of replicas

---

## See Also

- [Lettuce MasterReplica Documentation](https://lettuce.io/core/release/reference/index.html#master-replica)
- [Redis Sentinel Documentation](https://redis.io/docs/management/sentinel/)
- [Spring Data Redis Reference](https://docs.spring.io/spring-data/redis/docs/current/reference/html/)
- [spring-redis-laned README](../README.md)

---

**Last updated:** 2026-03-02  
**Maintained by:** Macstab GmbH  
**License:** Apache 2.0
