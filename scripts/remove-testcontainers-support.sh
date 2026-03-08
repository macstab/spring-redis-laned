#!/bin/bash
# Remove all TestcontainersSupport class references

set -e

cd "$(dirname "$0")/.."

echo "Removing TestcontainersSupport extends..."
find . -name "*IntegrationTest.java" -type f -exec sed -i '' \
  's/ extends com\.macstab\.oss\.redis\.laned\.TestcontainersSupport//' {} \;

find . -name "*IntegrationTest.java" -type f -exec sed -i '' \
  's/ extends TestcontainersSupport//' {} \;

echo "Removing static configure() blocks..."
find . -name "*IntegrationTest.java" -type f -exec perl -i -p0e \
  's/\n\s*\/\/ CRITICAL:.*?\n\s*static \{\n\s*.*?TestcontainersSupport\.configure\(\);\n\s*\}\n//gs' {} \;

echo "Removing TestcontainersSupport.java files..."
find . -name "TestcontainersSupport.java" -type f -delete

echo "Done!"
