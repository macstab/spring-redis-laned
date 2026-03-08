# @LanedRedisConnection - Annotation-Based Configuration

Configuration via annotation that **overrides all YAML/properties settings**.

## Quick Start

```java
import com.macstab.oss.redis.laned.config.LanedRedisConnection;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;

@SpringBootApplication
@LanedRedisConnection(lanes = 8, strategy = LaneSelectionStrategyType.THREAD_AFFINITY)
public class MyApplication {
  public static void main(String[] args) {
    SpringApplication.run(MyApplication.class, args);
  }
}
```

Done! `LanedConnectionManager` is auto-configured with 8 lanes and thread affinity strategy.

**✅ Status: WORKING** — This feature is fully implemented and production-ready (Spring Boot 3.x and 4.x).

---

## Configuration Precedence

**Highest to lowest priority:**

1. ✅ **`@LanedRedisConnection` annotation** (this approach)
2. YAML/properties (`spring.data.redis.connection.*`)
3. Defaults (lanes=4, strategy=ROUND_ROBIN)

### Example: Annotation Overrides YAML

**application.yml:**
```yaml
spring:
  data:
    redis:
      connection:
        strategy: LANED
        lanes: 4  # Ignored when annotation present
```

**Application class:**
```java
@SpringBootApplication
@LanedRedisConnection(
  lanes = 16, 
  strategy = LaneSelectionStrategyType.LEAST_USED
)  // This wins
public class MyApplication {
  // Result: lanes=16, strategy=LEAST_USED (annotation takes precedence)
}
```

---

## Annotation Reference

### Full Signature

```java
import com.macstab.oss.redis.laned.config.LanedRedisConnection;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;

@LanedRedisConnection(
  lanes = 8,
  strategy = LaneSelectionStrategyType.THREAD_AFFINITY,
  metricsEnabled = true
)
```

### Attributes

#### `lanes` (int)
Number of connection lanes (pools).

- **Default:** `4`
- **Range:** `1-64` (enforced by properties validation)
- **Recommendation:**
  - Low concurrency (<10 threads): `2-4` lanes
  - Medium concurrency (10-50 threads): `4-8` lanes  
  - High concurrency (50-200 threads): `8-16` lanes
  - Very high concurrency (>200 threads): `16-32` lanes

**Example:**
```java
@LanedRedisConnection(lanes = 16)  // High-concurrency app
```

#### `strategy` (LaneSelectionStrategyType)
Lane selection strategy.

- **Default:** `LaneSelectionStrategyType.ROUND_ROBIN`
- **Options:**
  - `ROUND_ROBIN` - Cycle through lanes sequentially (best for uniform workloads)
  - `THREAD_AFFINITY` - Sticky lane per thread (best for thread-local caching)
  - `LEAST_USED` - Select lane with lowest active connections (best for mixed workloads)

**Example:**
```java
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;

@LanedRedisConnection(
  lanes = 8,
  strategy = LaneSelectionStrategyType.THREAD_AFFINITY  // Sticky lanes
)
```

#### `metricsEnabled` (boolean)
Enable/disable detailed lane metrics.

- **Default:** `true`
- **Metrics exposed:**
  - `redis.laned.lane.selection` - Lane selection distribution
  - `redis.laned.lane.active_connections` - Active connections per lane
  - `redis.laned.lane.wait_time` - Time waiting for available connection

**When to disable:**
- Ultra-low latency requirements (metrics add ~10-50μs overhead)
- Very high throughput (>100k ops/sec)

**Example:**
```java
@LanedRedisConnection(
  lanes = 32,
  metricsEnabled = false  // Disable for maximum performance
)
```

---

## Usage Patterns

### 1. Main Application Class

Most common - annotate your `@SpringBootApplication`:

```java
import com.macstab.oss.redis.laned.config.LanedRedisConnection;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;

@SpringBootApplication
@LanedRedisConnection(
  lanes = 8, 
  strategy = LaneSelectionStrategyType.THREAD_AFFINITY
)
public class MyApplication {
  public static void main(String[] args) {
    SpringApplication.run(MyApplication.class, args);
  }
}
```

### 2. Configuration Class

Separate configuration class (better for large apps):

```java
import com.macstab.oss.redis.laned.config.LanedRedisConnection;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;

@Configuration
@LanedRedisConnection(
  lanes = 16, 
  strategy = LaneSelectionStrategyType.LEAST_USED
)
public class RedisConfig {
  // LanedConnectionManager auto-configured
}
```

### 3. Per-Environment Configuration

Different configs for dev/staging/prod:

```java
import com.macstab.oss.redis.laned.config.LanedRedisConnection;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;

@Profile("prod")
@Configuration
@LanedRedisConnection(
  lanes = 32, 
  strategy = LaneSelectionStrategyType.THREAD_AFFINITY
)
public class ProdRedisConfig {
  // High concurrency for production
}

@Profile("dev")
@Configuration
@LanedRedisConnection(
  lanes = 2, 
  strategy = LaneSelectionStrategyType.ROUND_ROBIN
)
public class DevRedisConfig {
  // Low resources for local development
}
```

---

## When to Use Annotation vs YAML

### Use Annotation When:

✅ **Explicit configuration in code** (self-documenting)  
✅ **Per-environment configuration classes** (dev/staging/prod)  
✅ **Override defaults without touching YAML**  
✅ **Type-safe configuration** (compile-time validation)  
✅ **Configuration is stable** (rarely changes)

### Use YAML When:

✅ **Configuration changes without recompilation**  
✅ **Externalized config** (Docker, Kubernetes ConfigMaps)  
✅ **Multiple deployment targets with same JAR**  
✅ **Non-technical users manage config**

---

## Complete Example

```java
package com.example.myapp;

import com.macstab.oss.redis.laned.config.LanedRedisConnection;
import com.macstab.oss.redis.laned.strategy.LaneSelectionStrategyType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * High-concurrency microservice with Redis-backed caching.
 * 
 * Configuration:
 * - 16 lanes (supports 50-200 concurrent threads)
 * - Thread affinity (thread-local caching)
 * - Metrics enabled (observability)
 */
@SpringBootApplication
@LanedRedisConnection(
  lanes = 16,
  strategy = LaneSelectionStrategyType.THREAD_AFFINITY,
  metricsEnabled = true
)
public class MyApplication {
  
  public static void main(String[] args) {
    SpringApplication.run(MyApplication.class, args);
  }
}
```

**application.yml** (minimal - annotation handles laned config):
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      connection:
        strategy: LANED  # Required to enable laned strategy
```

---

## Validation & Error Handling

### Annotation Present
```
INFO: Using @LanedRedisConnection annotation: lanes=16, strategy=THREAD_AFFINITY, metrics=true
```

### Annotation Missing (Falls Back to YAML)
```
DEBUG: Using YAML/properties configuration: lanes=8
```

### Multiple Annotations Found
```
WARN: @LanedRedisConnection found on 2 classes - using first match. 
      Annotation should only appear once.
```

---

## Migration Guide

### From YAML-only to Annotation

**Before:**
```yaml
spring:
  data:
    redis:
      connection:
        strategy: LANED
        lanes: 8
```

**After:**
```java
@SpringBootApplication
@LanedRedisConnection(lanes = 8)
public class MyApplication {
  // ...
}
```

**application.yml (simplified):**
```yaml
spring:
  data:
    redis:
      connection:
        strategy: LANED  # Still required to enable laned strategy
```

---

## FAQ

### Q: Can I use both annotation and YAML?
**A:** Yes, but annotation **always wins**. YAML is ignored when annotation is present.

### Q: What if I don't specify `strategy`?
**A:** Defaults to `ROUND_ROBIN` (good general-purpose choice).

### Q: Can I put the annotation on multiple classes?
**A:** No - only one `@LanedRedisConnection` should exist. If multiple found, first match is used (order undefined).

### Q: Does this work with Redis Sentinel/Cluster?
**A:** Yes - annotation only configures lanes/strategy. Topology (standalone/sentinel/cluster) is still configured via YAML.

### Q: Can I access the annotation at runtime?
**A:** Yes - via `LanedRedisConfigurationSource.getSourceType()` which returns `ANNOTATION` when annotation is used.

---

## See Also

- [Strategy Guide](STRATEGY-GUIDE.md) - Choosing the right selection strategy
- [Metrics Guide](METRICS-GUIDE.md) - Understanding lane metrics
- [Tuning Guide](TUNING-GUIDE.md) - Performance optimization
