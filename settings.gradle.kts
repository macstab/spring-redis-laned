rootProject.name = "spring-redis-laned"

// Apply Testcontainers DinD configuration BEFORE any projects load
apply(from = "gradle/testcontainers-dind.init.gradle.kts")

include(
    "redis-laned-core",
    "redis-laned-test-utils",
    "redis-laned-spring-boot-3-starter",
    "redis-laned-spring-boot-4-starter",
    "redis-laned-metrics",
    "redis-laned-examples",
    "redis-laned-load-tests",
    "redis-laned-benchmarks"
)
