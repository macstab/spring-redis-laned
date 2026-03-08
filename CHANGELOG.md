# [1.1.0](https://github.com/macstab/spring-redis-laned/compare/1.0.0...1.1.0) (2026-03-08)


### Features

* Add framework-agnostic metrics interface and lane selection strategies plus introduced dev containers. ([#6](https://github.com/macstab/spring-redis-laned/issues/6)) ([3d94136](https://github.com/macstab/spring-redis-laned/commit/3d94136d7051e0b77e48d2f72abfef1480225f77)), closes [#5](https://github.com/macstab/spring-redis-laned/issues/5)


### BREAKING CHANGES

* RedisStandalone and RedisSentinel now use INSTANCE.get()
instead of parameter injection. SentinelCluster removed @Getter in favor of
explicit typed methods.

- Add RedisManager<T> generic manager for standalone and Sentinel
- Add id attribute to @RedisStandalone and @RedisSentinel (default="default")
- Add INSTANCE field to both annotations for static access
- Update Extensions with ThreadLocal + static getters
- Implement defensive cleanup: both CloseableResource (automatic) + afterAll (backup)
- Remove @Getter from SentinelCluster, add explicit typed methods
- Add getMaster()/getReplicas()/getSentinels() → RedisConnectionInfo (common case)
- Add getMasterContainer()/getReplicaContainers()/getSentinelContainers() → GenericContainer<?> (monitoring)
- Migrate all 5 integration tests in redis-laned-core to use new pattern
- Update Spring Boot tests to use getMasterContainer() for monitoring
- All 532 tests passing

Co-authored-by: Christian Schnapka <per@macstab.com>

* fest: enable key affinity strategy, remove outdated configuration and testing guides, cleanup unused documentation.

- Deleted `ANNOTATION-CONFIGURATION.md`, `TESTCONTAINERS_ENHANCEMENTS.md`, and `TESTING_SENTINEL_READFROM.md` due to outdated content.
- These files are now superseded by centralized documentation in `docs/TESTING.md` and `docs/TECHNICAL_VERIFICATION.md`.
- Streamlines documentation and eliminates duplication.

# 1.0.0 (2026-02-27)


### Features

* Add framework-agnostic metrics interface and lane selection strategies ([#1](https://github.com/macstab/spring-redis-laned/issues/1)) ([a2becca](https://github.com/macstab/spring-redis-laned/commit/a2beccae99be6de0ffc02d2cfa4de4454a2278fb))
