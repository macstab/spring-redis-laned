#!/bin/bash
# Migrate modules to use redis-laned-test-utils

set -e

cd "$(dirname "$0")/.."

echo "🔧 Migrating modules to redis-laned-test-utils..."
echo ""

# Modules that need the test-utils dependency
MODULES=(
  "redis-laned-core"
  "redis-laned-metrics"
  "redis-laned-spring-boot-3-starter"
  "redis-laned-spring-boot-4-starter"
)

for module in "${MODULES[@]}"; do
  BUILD_FILE="$module/build.gradle.kts"
  
  if [ ! -f "$BUILD_FILE" ]; then
    echo "⚠️  Skipping $module (build.gradle.kts not found)"
    continue
  fi
  
  # Check if dependency already exists
  if grep -q 'testImplementation(project(":redis-laned-test-utils"))' "$BUILD_FILE"; then
    echo "✓ $module already has test-utils dependency"
  else
    echo "📝 Adding test-utils dependency to $module..."
    
    # Add dependency after first testImplementation line
    if [[ "$OSTYPE" == "darwin"* ]]; then
      # macOS sed
      sed -i '' '/testImplementation/a\
    testImplementation(project(":redis-laned-test-utils"))
' "$BUILD_FILE"
    else
      # Linux sed
      sed -i '/testImplementation/a\    testImplementation(project(":redis-laned-test-utils"))' "$BUILD_FILE"
    fi
    
    echo "✓ Added test-utils dependency to $module"
  fi
  
  echo ""
done

echo "✅ Migration complete!"
echo ""
echo "Next steps:"
echo "  1. Run: ./scripts/cleanup-test-duplicates.sh"
echo "  2. Update imports in Sentinel tests"
echo "  3. Verify: ./gradlew test"
