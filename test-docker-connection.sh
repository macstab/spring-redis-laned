#!/bin/bash
# Comprehensive Docker connectivity test for Testcontainers debugging

echo "=== Docker Connectivity Test ==="
echo ""

echo "1. Environment:"
echo "   USER: $(whoami)"
echo "   GROUPS: $(groups)"
echo "   DOCKER_HOST: ${DOCKER_HOST:-<not set>}"
echo ""

echo "2. Socket status:"
ls -lah /var/run/docker.sock
echo ""

echo "3. Socket accessibility:"
test -r /var/run/docker.sock && echo "   ✓ Readable" || echo "   ✗ NOT readable"
test -w /var/run/docker.sock && echo "   ✓ Writable" || echo "   ✗ NOT writable"
echo ""

echo "4. Docker CLI test:"
docker version 2>&1 | head -10
echo ""

echo "5. Docker info:"
docker info 2>&1 | grep -E "Server Version|Operating System|OSType|Architecture" 
echo ""

echo "6. Can pull images?"
docker pull hello-world:latest 2>&1 | tail -5
echo ""

echo "7. Can run containers?"
docker run --rm hello-world 2>&1 | tail -5
echo ""

echo "8. Testcontainers config files:"
echo "   ~/.testcontainers.properties:"
cat ~/.testcontainers.properties 2>/dev/null || echo "   (not found)"
echo ""
echo "   Project testcontainers.properties:"
find . -name "testcontainers.properties" -type f -exec echo "   {}" \; -exec head -5 {} \; 2>/dev/null | head -20
echo ""

echo "=== Test complete ==="
