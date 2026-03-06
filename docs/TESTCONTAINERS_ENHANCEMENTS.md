# Testcontainers Enhancements for Redis Testing

> **Advanced Testing Strategies** - Resource limits, Kubernetes, network simulation, and more

---

## 📋 Table of Contents

1. [Resource Limits (Memory/CPU)](#1-resource-limits-memorycpu)
2. [Persistent Volumes (PVC Emulation)](#2-persistent-volumes-pvc-emulation)
3. [Kubernetes Testing with Fabric8](#3-kubernetes-testing-with-fabric8)
4. [Network Simulation with Toxiproxy](#4-network-simulation-with-toxiproxy)
5. [Redis Sentinel Cluster Builder](#5-redis-sentinel-cluster-builder)
6. [Performance Benchmarking](#6-performance-benchmarking)

---

## 1. Resource Limits (Memory/CPU)

### Problem

**Without limits:**
- Tests consume excessive host resources
- CI/CD runners OOM (out of memory)
- Unpredictable test duration

### Solution: Docker Resource Constraints

```java
import org.testcontainers.containers.GenericContainer;
import com.github.dockerjava.api.model.HostConfig;

GenericContainer<?> master =
    new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        .withCreateContainerCmdModifier(cmd -> cmd
            .withHostConfig(new HostConfig()
                // Memory limits
                .withMemory(256 * 1024 * 1024L)      // 256 MB RAM (hard limit)
                .withMemorySwap(512 * 1024 * 1024L)  // 512 MB total (RAM + swap)
                .withMemoryReservation(128 * 1024 * 1024L) // 128 MB soft limit
                
                // CPU limits
                .withCpuShares(512)                   // 50% CPU share (relative)
                .withCpuQuota(50000L)                 // 0.5 CPU cores (absolute)
                .withCpuPeriod(100000L)               // 100ms period
                
                // OOM behavior
                .withOomKillDisable(false)            // Kill on OOM (vs letting kernel decide)
            )
        );
```

### Benefits

| Benefit | Impact |
|---------|--------|
| **Predictable CI/CD** | No more flaky tests due to resource starvation |
| **Faster execution** | Less context switching, better CPU cache locality |
| **Cost savings** | Run more tests on smaller CI runners |
| **OOM prevention** | Tests fail fast vs hanging indefinitely |

### Recommended Settings

| Container Type | Memory | CPU | Rationale |
|----------------|--------|-----|-----------|
| **Redis Master** | 256 MB | 0.5 core | Handles writes only, small dataset |
| **Redis Replica** | 128 MB | 0.25 core | Reads only, can be more constrained |
| **Sentinel** | 64 MB | 0.1 core | Minimal logic (monitoring) |

---

## 2. Persistent Volumes (PVC Emulation)

### Problem

**Without persistence:**
- Can't test RDB/AOF save behavior
- Can't test crash recovery
- Can't benchmark persistence overhead

### Solution: tmpfs Volumes

```java
import java.util.Map;

GenericContainer<?> masterWithPersistence =
    new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379)
        // Mount tmpfs for /data (100 MB, in-memory filesystem)
        .withTmpFs(Map.of(
            "/data", "rw,size=100m,mode=0755"
        ))
        .withCommand(
            "redis-server",
            "--dir", "/data",
            
            // RDB persistence (snapshot)
            "--save", "60", "1",              // Save every 60s if 1+ keys changed
            "--dbfilename", "dump.rdb",
            
            // AOF persistence (append-only file)
            "--appendonly", "yes",
            "--appendfilename", "appendonly.aof",
            "--appendfsync", "everysec"       // Fsync every second
        );
```

### Use Cases

#### Test 1: Crash Recovery

```java
@Test
void testCrashRecovery() throws Exception {
    // Given: Write data
    redisTemplate.opsForValue().set("key", "value");
    Thread.sleep(2000);  // Wait for AOF flush
    
    // When: Simulate crash (kill + restart)
    master.execInContainer("kill", "-9", "1");  // Kill Redis process
    master.start();  // Restart (reuses tmpfs volume)
    
    // Then: Data survives
    assertThat(redisTemplate.opsForValue().get("key")).isEqualTo("value");
}
```

#### Test 2: AOF Rewrite Performance

```java
@Test
void testAOFRewritePerformance() throws Exception {
    // Write 100k keys
    for (int i = 0; i < 100_000; i++) {
        redisTemplate.opsForValue().set("key:" + i, "value:" + i);
    }
    
    // Trigger AOF rewrite
    master.execInContainer("redis-cli", "BGREWRITEAOF");
    
    // Measure time
    await().atMost(30, SECONDS)
        .until(() -> {
            String info = master.execInContainer("redis-cli", "INFO", "persistence")
                .getStdout();
            return info.contains("aof_rewrite_in_progress:0");
        });
}
```

### Alternative: Bind Mounts (for large datasets)

```java
Path hostDir = Files.createTempDirectory("redis-data");

GenericContainer<?> master =
    new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withFileSystemBind(
            hostDir.toString(),  // Host path
            "/data",              // Container path
            BindMode.READ_WRITE
        )
        .withCommand("redis-server", "--dir", "/data");
```

**Trade-off:**
- ✅ Supports large datasets (> 1 GB)
- ❌ Slower than tmpfs (disk I/O)
- ❌ Requires cleanup (`Files.deleteIfExists()`)

---

## 3. Kubernetes Testing with Fabric8

### Problem

**Without K8s testing:**
- Can't test Kubernetes-specific features (Services, DNS, StatefulSets)
- Can't test Helm charts
- Can't test pod affinity rules

### Solution: KinD (Kubernetes in Docker) + Fabric8

#### Dependencies

```groovy
// build.gradle
testImplementation 'org.testcontainers:k3s:1.19.3'
testImplementation 'io.fabric8:kubernetes-client:6.9.2'
testImplementation 'io.fabric8:kubernetes-server-mock:6.9.2'
```

#### Example Test

```java
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.testcontainers.containers.K3sContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisK8sIntegrationTest {
    
    @Container
    static K3sContainer k3s = new K3sContainer(
        DockerImageName.parse("rancher/k3s:v1.27.4-k3s1")
    )
    .withStartupTimeout(Duration.ofMinutes(3));
    
    private static KubernetesClient kubeClient;
    
    @BeforeAll
    static void setupK8s() throws Exception {
        kubeClient = new DefaultKubernetesClient(
            Config.fromKubeconfig(k3s.getKubeConfigYaml())
        );
        
        // Deploy Redis Sentinel StatefulSet
        kubeClient.apps().statefulSets()
            .inNamespace("default")
            .load(RedisK8sIntegrationTest.class
                .getResourceAsStream("/k8s/redis-sentinel-statefulset.yaml"))
            .create();
        
        // Wait for pods ready (3 sentinels + 1 master + 2 replicas = 6 pods)
        kubeClient.pods()
            .inNamespace("default")
            .withLabel("app", "redis-sentinel")
            .waitUntilReady(120, TimeUnit.SECONDS);
    }
    
    @Test
    void testSentinelServiceDiscovery() {
        // Get Sentinel service
        Service sentinelSvc = kubeClient.services()
            .inNamespace("default")
            .withName("redis-sentinel")
            .get();
        
        String host = sentinelSvc.getSpec().getClusterIP();
        int port = sentinelSvc.getSpec().getPorts().get(0).getPort();
        
        // Connect via Sentinel
        RedisSentinelConfiguration config = new RedisSentinelConfiguration()
            .master("mymaster")
            .sentinel(host, port);
        
        // Verify connection
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        
        RedisTemplate<String, String> template = new StringRedisTemplate(factory);
        template.opsForValue().set("k8s-test", "works!");
        
        assertThat(template.opsForValue().get("k8s-test")).isEqualTo("works!");
    }
}
```

#### Kubernetes Manifest (StatefulSet)

```yaml
# src/test/resources/k8s/redis-sentinel-statefulset.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: redis-sentinel
spec:
  serviceName: redis-sentinel
  replicas: 3
  selector:
    matchLabels:
      app: redis-sentinel
  template:
    metadata:
      labels:
        app: redis-sentinel
    spec:
      containers:
        - name: redis
          image: redis:7-alpine
          ports:
            - containerPort: 6379
              name: redis
            - containerPort: 26379
              name: sentinel
          volumeMounts:
            - name: data
              mountPath: /data
          resources:
            limits:
              memory: "256Mi"
              cpu: "500m"
            requests:
              memory: "128Mi"
              cpu: "250m"
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 1Gi
```

### Benefits

| Feature | Traditional | Kubernetes | Winner |
|---------|-------------|------------|--------|
| **Service Discovery** | Manual IPs | Kubernetes DNS | K8s |
| **Persistence** | tmpfs/bind mounts | PersistentVolumeClaims | K8s |
| **High Availability** | Manual Sentinel | StatefulSet + headless Service | K8s |
| **Resource Management** | Docker limits | Requests + Limits + QoS | K8s |

---

## 4. Network Simulation with Toxiproxy

### Problem

**Without network simulation:**
- Can't test behavior under latency (slow replicas)
- Can't test timeout handling
- Can't test Sentinel failover timing

### Solution: Toxiproxy (Network Chaos Engineering)

#### Dependencies

```groovy
testImplementation 'org.testcontainers:toxiproxy:1.19.3'
```

#### Example: Latency Testing

```java
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.ToxiproxyContainer.ContainerProxy;
import eu.rekawek.toxiproxy.model.ToxicDirection;

@Testcontainers
class RedisLatencyTest {
    
    @Container
    static Network network = Network.newNetwork();
    
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withNetwork(network)
        .withExposedPorts(6379);
    
    @Container
    static ToxiproxyContainer toxiproxy = new ToxiproxyContainer(
        DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0")
    )
    .withNetwork(network);
    
    @Test
    void testReadTimeoutWithSlowReplica() throws Exception {
        // Create proxy for Redis
        ContainerProxy proxy = toxiproxy.getProxy(redis, 6379);
        
        // Add 500ms latency (downstream = Redis → Client)
        proxy.toxics()
            .latency("high-latency", ToxicDirection.DOWNSTREAM, 500);
        
        // Configure client with 300ms timeout
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(300))
            .build();
        
        // Attempt read → should timeout
        assertThatThrownBy(() -> {
            RedisTemplate<String, String> template = createTemplate(
                proxy.getContainerIpAddress(),
                proxy.getProxyPort(),
                clientConfig
            );
            template.opsForValue().get("key");
        })
        .isInstanceOf(QueryTimeoutException.class);
    }
    
    @Test
    void testReadFromFallbackToMaster() throws Exception {
        // Scenario: Replica slow, master fast
        ContainerProxy replicaProxy = toxiproxy.getProxy(replica, 6379);
        replicaProxy.toxics().latency("slow-replica", ToxicDirection.DOWNSTREAM, 1000);
        
        // REPLICA_PREFERRED should fall back to master
        RedisSentinelConfiguration config = new RedisSentinelConfiguration()
            .master("mymaster")
            .sentinel(sentinel.getHost(), sentinel.getFirstMappedPort());
        
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.REPLICA_PREFERRED)
            .commandTimeout(Duration.ofMillis(500))  // Replica will timeout
            .build();
        
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfig);
        factory.afterPropertiesSet();
        
        RedisTemplate<String, String> template = new StringRedisTemplate(factory);
        
        // Should succeed (falls back to master)
        assertThat(template.opsForValue().get("key")).isNotNull();
    }
}
```

#### Available Toxics

| Toxic | Effect | Use Case |
|-------|--------|----------|
| **latency** | Add delay (ms) | Test slow replicas, timeout handling |
| **bandwidth** | Limit throughput (KB/s) | Test large dataset transfers |
| **slow_close** | Delay connection close | Test connection pool exhaustion |
| **timeout** | Hold data without sending | Test client timeout behavior |
| **slicer** | Split data into small chunks | Test partial read handling |
| **limit_data** | Close after N bytes | Test connection drops mid-transfer |

---

## 5. Redis Sentinel Cluster Builder

### Problem

**Current `RedisTestContainers.createSentinelCluster()`:**
- Fixed topology (1 master + 2 replicas + 3 sentinels)
- No resource limits
- No customization (memory, AOF, etc.)

### Solution: Fluent Builder API

```java
package com.macstab.oss.redis.laned.testutil;

public class RedisSentinelClusterBuilder {
    private int replicas = 2;
    private int sentinels = 3;
    private int quorum = 2;
    private Long memoryLimit = null;  // MB
    private Long cpuShares = null;
    private boolean enableAOF = false;
    private boolean enableRDB = false;
    private Duration startupTimeout = Duration.ofSeconds(30);
    
    public RedisSentinelClusterBuilder withReplicas(int count) {
        this.replicas = count;
        return this;
    }
    
    public RedisSentinelClusterBuilder withSentinels(int count, int quorum) {
        this.sentinels = count;
        this.quorum = quorum;
        return this;
    }
    
    public RedisSentinelClusterBuilder withMemoryLimit(long megabytes) {
        this.memoryLimit = megabytes;
        return this;
    }
    
    public RedisSentinelClusterBuilder withCpuLimit(long shares) {
        this.cpuShares = shares;
        return this;
    }
    
    public RedisSentinelClusterBuilder withPersistence(boolean aof, boolean rdb) {
        this.enableAOF = aof;
        this.enableRDB = rdb;
        return this;
    }
    
    public SentinelCluster build() {
        // Implementation: create containers with specified settings
        Network network = Network.newNetwork();
        
        // Master
        GenericContainer<?> master = createRedisContainer(network, "redis-master")
            .withCommand(buildRedisCommand(true));
        
        if (memoryLimit != null) {
            master.withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                .withMemory(memoryLimit * 1024 * 1024));
        }
        
        master.start();
        
        // Replicas
        List<GenericContainer<?>> replicaList = new ArrayList<>();
        for (int i = 0; i < replicas; i++) {
            GenericContainer<?> replica = createRedisContainer(network, "redis-replica" + (i + 1))
                .withCommand(buildRedisCommand(false));
            replica.start();
            replicaList.add(replica);
        }
        
        // Sentinels
        List<GenericContainer<?>> sentinelList = new ArrayList<>();
        for (int i = 0; i < sentinels; i++) {
            GenericContainer<?> sentinel = createSentinelContainer(network, "sentinel" + (i + 1));
            sentinel.start();
            sentinelList.add(sentinel);
        }
        
        return new SentinelCluster(network, master, replicaList, sentinelList);
    }
    
    private String[] buildRedisCommand(boolean isMaster) {
        List<String> cmd = new ArrayList<>();
        cmd.add("redis-server");
        cmd.add("--protected-mode");
        cmd.add("no");
        
        if (!isMaster) {
            cmd.add("--replicaof");
            cmd.add("redis-master");
            cmd.add("6379");
        }
        
        if (enableAOF) {
            cmd.add("--appendonly");
            cmd.add("yes");
        }
        
        if (enableRDB) {
            cmd.add("--save");
            cmd.add("60");
            cmd.add("1");
        }
        
        return cmd.toArray(new String[0]);
    }
}
```

#### Usage

```java
// Minimal cluster (for fast unit tests)
SentinelCluster minimal = new RedisSentinelClusterBuilder()
    .withReplicas(1)
    .withSentinels(1, 1)
    .withMemoryLimit(128)  // 128 MB per container
    .withCpuLimit(256)      // 25% CPU
    .build();

// Production-like cluster (for integration tests)
SentinelCluster production = new RedisSentinelClusterBuilder()
    .withReplicas(3)
    .withSentinels(3, 2)
    .withPersistence(true, true)  // AOF + RDB
    .withMemoryLimit(512)
    .build();
```

---

## 6. Performance Benchmarking

### Problem

**Need to measure:**
- Throughput (ops/sec)
- Latency (p50, p95, p99)
- Connection pool efficiency

### Solution: Redis Benchmark via Testcontainers

```java
@Test
void benchmarkReadPerformance() throws Exception {
    // Start cluster
    SentinelCluster cluster = RedisTestContainers.createSentinelCluster();
    
    // Run redis-benchmark (inside container)
    Container.ExecResult result = cluster.master().execInContainer(
        "redis-benchmark",
        "-h", "localhost",
        "-p", "6379",
        "-t", "get",         // Test GET commands only
        "-n", "100000",      // 100k requests
        "-c", "50",          // 50 parallel connections
        "-q"                  // Quiet (only summary)
    );
    
    // Parse output
    // Example: "GET: 123456.78 requests per second"
    String output = result.getStdout();
    Pattern pattern = Pattern.compile("GET: ([\\d.]+) requests per second");
    Matcher matcher = pattern.matcher(output);
    
    if (matcher.find()) {
        double rps = Double.parseDouble(matcher.group(1));
        assertThat(rps).isGreaterThan(50_000);  // At least 50k ops/sec
    }
}
```

---

## Summary

| Enhancement | Benefit | Effort | Priority |
|-------------|---------|--------|----------|
| **Resource limits** | Stable CI/CD, cost savings | Low | ⭐⭐⭐ High |
| **Persistent volumes** | Test crash recovery | Low | ⭐⭐ Medium |
| **Kubernetes (Fabric8)** | Test production topology | High | ⭐ Low |
| **Toxiproxy** | Test network failures | Medium | ⭐⭐⭐ High |
| **Cluster builder** | Flexible test setups | Medium | ⭐⭐ Medium |
| **Benchmarking** | Validate performance | Low | ⭐⭐ Medium |

---

**Recommended Next Steps:**

1. ✅ **Start with resource limits** (immediate CI/CD improvement)
2. ✅ **Add Toxiproxy** (critical for testing ReadFrom fallback)
3. ⏸️ **Consider Fabric8** (only if you deploy to Kubernetes)

---

_Last Updated: 2026-03-06 | Author: Christian Schnapka - Macstab GmbH_
