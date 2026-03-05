# How to run tests with existing Redis (bypass Testcontainers)

Testcontainers is broken in this DinD setup. Use the existing Redis instead.

## Option 1: Comment out @Testcontainers

Edit each test file and:
1. Comment out `@Testcontainers`
2. Comment out `@Container` annotations
3. Hardcode Redis connection to `localhost:6379`

## Option 2: Revert all DinD changes

All the Testcontainers fixes we tried don't work. Revert and run tests on the host instead.

## Files to revert:
- build.gradle.kts (remove DinD detection)
- settings.gradle.kts (remove init script)
- gradle/testcontainers-dind.init.gradle.kts (delete)
- scripts/setup-testcontainers-dind.sh (delete)
- All testcontainers.properties files
- All TestcontainersSupport.java files
- All DisabledOnNonLinuxHost.java files
- All test files (remove static configure() blocks)

