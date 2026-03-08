#!/bin/bash
# Delete duplicate test utility files after migration to redis-laned-test-utils

set -e

cd "$(dirname "$0")/.."

echo "🧹 Cleaning up duplicate test utility files..."
echo ""

# Files to delete (now in redis-laned-test-utils)
FILES_TO_DELETE=(
  # DisabledOnNonLinuxHost duplicates
  "redis-laned-core/src/test/java/com/macstab/oss/redis/laned/DisabledOnNonLinuxHost.java"
  "redis-laned-metrics/src/test/java/com/macstab/oss/redis/laned/DisabledOnNonLinuxHost.java"
  "redis-laned-spring-boot-3-starter/src/test/java/com/macstab/oss/redis/laned/DisabledOnNonLinuxHost.java"
  "redis-laned-spring-boot-4-starter/src/test/java/com/macstab/oss/redis/laned/DisabledOnNonLinuxHost.java"
  
  # TestcontainersSupport (obsolete - replaced by build.gradle.kts config)
  "redis-laned-core/src/test/java/com/macstab/oss/redis/laned/TestcontainersSupport.java"
  "redis-laned-metrics/src/test/java/com/macstab/oss/redis/laned/TestcontainersSupport.java"
  "redis-laned-spring-boot-3-starter/src/test/java/com/macstab/oss/redis/laned/TestcontainersSupport.java"
  "redis-laned-spring-boot-4-starter/src/test/java/com/macstab/oss/redis/laned/TestcontainersSupport.java"
  
  # TestcontainersDebug (temporary debugging utility)
  "redis-laned-core/src/test/java/com/macstab/oss/redis/laned/TestcontainersDebug.java"
)

DELETED_COUNT=0

for file in "${FILES_TO_DELETE[@]}"; do
  if [ -f "$file" ]; then
    echo "🗑️  Deleting: $file"
    rm "$file"
    ((DELETED_COUNT++))
  else
    echo "⏭️  Skipping: $file (not found)"
  fi
done

echo ""
echo "✅ Cleanup complete! Deleted $DELETED_COUNT files."
echo ""
echo "Next steps:"
echo "  1. Update imports in Sentinel tests:"
echo "     Old: com.macstab.oss.redis.laned.DisabledOnNonLinuxHost"
echo "     New: com.macstab.oss.redis.laned.test.condition.DisabledOnNonLinuxHost"
echo "  2. Verify build: ./gradlew clean build"
echo "  3. Run tests: ./gradlew test"
