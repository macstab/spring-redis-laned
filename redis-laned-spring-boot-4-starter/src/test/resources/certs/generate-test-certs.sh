#!/usr/bin/env bash
# Generate long-lived test certificates for SSL/TLS testing
# Validity: 10 years (3650 days)
# Usage: ./generate-test-certs.sh
#
# Generated files:
#   ca.key         - CA private key (keep secret in real scenarios)
#   ca.crt         - CA certificate (trust store)
#   server.key     - Redis server private key
#   server.crt     - Redis server certificate (signed by CA)
#   client.key     - Client private key
#   client.crt     - Client certificate (signed by CA)
#
# (C)2026 Macstab GmbH

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Certificate validity (10 years)
DAYS=3650

# CA configuration
CA_SUBJECT="/C=DE/ST=Schleswig-Holstein/L=Wentorf/O=Macstab GmbH/OU=Testing/CN=Macstab Test CA"

# Server configuration
SERVER_SUBJECT="/C=DE/ST=Schleswig-Holstein/L=Wentorf/O=Macstab GmbH/OU=Testing/CN=localhost"

# Client configuration
CLIENT_SUBJECT="/C=DE/ST=Schleswig-Holstein/L=Wentorf/O=Macstab GmbH/OU=Testing/CN=test-client"

echo "==> Generating CA private key and certificate..."
openssl genrsa -out ca.key 4096
openssl req -new -x509 -days $DAYS -key ca.key -out ca.crt -subj "$CA_SUBJECT"

echo "==> Generating server private key and certificate..."
openssl genrsa -out server.key 4096
openssl req -new -key server.key -out server.csr -subj "$SERVER_SUBJECT"

# Server certificate with SAN (Subject Alternative Name) for localhost
cat > server-ext.cnf <<EOF
basicConstraints = CA:FALSE
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = @alt_names

[alt_names]
DNS.1 = localhost
DNS.2 = redis
DNS.3 = redis.local
DNS.4 = redis.macstab.local
IP.1 = 127.0.0.1
IP.2 = ::1
EOF

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out server.crt -days $DAYS -extfile server-ext.cnf

echo "==> Generating client private key and certificate..."
openssl genrsa -out client.key 4096
openssl req -new -key client.key -out client.csr -subj "$CLIENT_SUBJECT"

# Client certificate for mutual TLS
cat > client-ext.cnf <<EOF
basicConstraints = CA:FALSE
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
EOF

openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -out client.crt -days $DAYS -extfile client-ext.cnf

# Cleanup temporary files
rm -f server.csr client.csr server-ext.cnf client-ext.cnf ca.srl

echo "==> Verifying certificates..."
openssl verify -CAfile ca.crt server.crt
openssl verify -CAfile ca.crt client.crt

echo "==> Certificate details:"
echo "CA:"
openssl x509 -in ca.crt -noout -subject -dates
echo "Server:"
openssl x509 -in server.crt -noout -subject -dates -ext subjectAltName
echo "Client:"
openssl x509 -in client.crt -noout -subject -dates

echo "==> Done! Certificates valid for 10 years."
echo "    ca.crt, server.crt, server.key, client.crt, client.key"
