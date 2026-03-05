# Redis Laned Test Utilities

Shared test infrastructure for Redis Laned modules.

## Features

### ✅ Implemented (MVP)

- **`@DisabledOnNonLinuxHost`** - JUnit condition for OS/container detection
- **`@RedisStandalone`** - Auto-start standalone Redis container
- **`@RedisSentinel`** - Auto-start full Sentinel cluster

### 🔮 Planned (Future)

- **`@LanedRedisTest`** - Auto-configure `LanedConnectionManager` with Spring Test integration
- Custom AssertJ assertions for Redis operations
- Test data builders
- Performance test utilities

## Usage

### Standalone Redis

```java
@RedisStandalone(version = "7.4", port = 6379)
class MyRedisTest {
  // Redis container auto-started on localhost:6379
}
```

### Sentinel Cluster

```java
@RedisSentinel(
  masterName = "mymaster",
  replicas = 2,
  sentinels = 3
)
class SentinelFailoverTest {
  // Full Sentinel cluster running
  // Note: Linux host or dev container required
}
```

### OS Detection

```java
@DisabledOnNonLinuxHost
class SentinelTest {
  // Skipped on macOS/Windows host
  // Runs on Linux host or in dev containers
}
```

## Dependencies

Add to your module's `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation(project(":redis-laned-test-utils"))
}
```

## Architecture

```
com.macstab.oss.redis.laned.test/
├── condition/               # JUnit execution conditions
│   └── DisabledOnNonLinuxHost
├── annotation/              # Test annotations
│   ├── RedisStandalone
│   ├── RedisSentinel
│   └── LanedRedisTest (planned)
└── extension/               # JUnit 5 extensions
    ├── RedisContainerExtension
    ├── SentinelContainerExtension
    └── LanedRedisExtension (planned)
```

## Platform Requirements

**Standalone Redis:**
- ✅ Any platform (macOS, Windows, Linux, containers)

**Sentinel Cluster:**
- ✅ Linux host
- ✅ Dev containers / CI containers
- ❌ macOS host (Docker Desktop VM limitations)
- ❌ Windows host (WSL2/Hyper-V limitations)

Sentinel tests automatically skip on unsupported platforms via `@DisabledOnNonLinuxHost`.
