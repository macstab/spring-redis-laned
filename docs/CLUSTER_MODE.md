# Cluster Mode Support

*By [Christian Schnapka (Per)](https://macstab.com) · Principal+ Embedded Engineer · [Macstab GmbH](https://macstab.com)*

**Status:** Planned for future release

---

## Table of Contents

<!-- TOC -->
* [Cluster Mode Support](#cluster-mode-support)
  * [Table of Contents](#table-of-contents)
  * [Current Limitation](#current-limitation)
  * [Why Cluster Mode Is Different](#why-cluster-mode-is-different)
  * [Planned Solution: Per-Shard Laning](#planned-solution-per-shard-laning)
  * [Implementation Strategy](#implementation-strategy)
  * [When You Need This](#when-you-need-this)
  * [Configuration (Planned)](#configuration-planned)
  * [Timeline](#timeline)
  * [Workaround (Current Release)](#workaround-current-release)
<!-- TOC -->

## Current Limitation

The MVP (v1.0.0) applies laned connections globally — all lanes connect to the same Redis endpoint. This works for:
- Standalone Redis
- Redis Sentinel (failover coordination)
- Redis Enterprise proxy mode (single endpoint)

It does NOT work optimally for:
- Redis OSS Cluster (multi-shard, hash-slot routing)

---

## Why Cluster Mode Is Different

Redis Cluster uses **consistent hashing** to distribute keys across multiple shards (typically 16,384 hash slots). Lettuce's `ClusterConnectionProvider` already maintains **one connection per shard**.

Example 6-shard cluster:

```
Shard 0: slots 0–2730    → connection 0
Shard 1: slots 2731–5460 → connection 1
Shard 2: slots 5461–8190 → connection 2
Shard 3: slots 8191–10920 → connection 3
Shard 4: slots 10921–13650 → connection 4
Shard 5: slots 13651–16383 → connection 5
```

Commands for different shards already use different connections — no cross-shard HOL blocking.

**HOL blocking in cluster mode** is per-shard: if shard 2 has a slow command, only commands routing to shard 2 are affected.

---

## Planned Solution: Per-Shard Laning

Apply laned connections **per shard**, not globally:

```
Shard 0:
  ├── Lane 0 (connection 0)
  ├── Lane 1 (connection 1)
  └── ...
Shard 1:
  ├── Lane 0 (connection 6)
  ├── Lane 1 (connection 7)
  └── ...
```

**Connection count:**
```
N_shards × N_lanes = 6 × 8 = 48 connections per pod
```

Still far lower than a pool (50 × 6 = 300 connections per pod for equivalent isolation).

---

## Implementation Strategy

1. **Wrap `ClusterConnectionProvider`** instead of replacing it
2. **Detect shard assignment** via hash slot calculation
3. **Apply round-robin** within the shard's lane group
4. **Track metrics** per shard (optional)

**Pseudocode:**

```java
public class LanedClusterConnectionProvider extends ClusterConnectionProvider {
    
    private final Map<Integer, LanedLettuceConnectionProvider> perShardProviders;
    
    public <K, V> StatefulRedisConnection<K, V> getConnection(Intent intent) {
        int hashSlot = calculateHashSlot(intent.getKey());
        int shardId = clusterPartitions.getPartitionBySlot(hashSlot).getNodeId();
        
        LanedLettuceConnectionProvider laneProvider = perShardProviders.get(shardId);
        return laneProvider.getConnection(intent);
    }
}
```

---

## When You Need This

**You DON'T need per-shard laning if:**
- Workload is uniformly distributed across shards
- No hot shards (traffic evenly balanced)
- Individual shard commands are consistently fast

**You DO need per-shard laning if:**
- Hot keys concentrate traffic on one shard
- Mixed fast/slow commands route to the same shard
- Per-shard p99 latency shows HOL blocking (sleeping threads, divergent p50/p99)

---

## Configuration (Planned)

```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - localhost:7000
          - localhost:7001
          - localhost:7002
      connection:
        strategy: LANED
        lanes: 8
        cluster-mode: PER_SHARD  # GLOBAL (current) | PER_SHARD (planned)
```

---

## Timeline

**Target release:** v1.2.0 (Q3 2026)

Depends on production validation of standalone/Sentinel laning (v1.0.0) and reactive transaction support (v1.1.0).

---

## Workaround (Current Release)

If you are running Redis Cluster and experiencing per-shard HOL blocking:

1. **Profile first** — confirm the bottleneck is intra-shard HOL, not cross-shard routing
2. **Use separate connection factories per shard** (manual, not recommended)
3. **Wait for v1.2.0** — automatic per-shard laning

---

*Christian Schnapka, Principal+ Engineer · [Macstab GmbH](https://macstab.com)*
