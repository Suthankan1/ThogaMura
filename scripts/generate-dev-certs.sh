#!/usr/bin/env bash
# ThogaMura — Member 5: Local Development TLS Certificate and Truststore Generator
# Generates synthetic demonstration certificates using standard Java keytool.
# WARNING: For local development and demonstration only. Never use in production.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

OUT_DIR="${PROJECT_ROOT}/.certs"
CA_DAYS=365
SERVER_DAYS=90
KEYSTORE_PASS="${THOGAMURA_TLS_KEYSTORE_PASSWORD:-}"
TRUSTSTORE_PASS="${THOGAMURA_TLS_TRUSTSTORE_PASSWORD:-}"
EXTRA_SAN=""
LAN_IP=""
DO_CLEAN=false

print_usage() {
  cat <<EOF
ThogaMura Development TLS Certificate Generator (Member 5)

Usage:
  $(basename "$0") [options]

Options:
  --dir <path>       Target directory for generated certificates (default: .certs)
  --lan-ip <ip>      LAN IP address to include in Subject Alternative Names (SAN)
  --san <entries>    Additional SAN entries (e.g. "dns:warehouse.local,ip:192.168.1.50")
  --clean            Remove existing generated certificates and exit
  -h, --help         Show this help message

Examples:
  # Standard localhost development setup:
  $(basename "$0")

  # Two-laptop demonstration with warehouse on 192.168.8.100:
  $(basename "$0") --lan-ip 192.168.8.100

  # Custom SAN with local domain:
  $(basename "$0") --san "dns:warehouse.thogamura.internal,ip:10.0.0.5"

  # Clean generated materials:
  $(basename "$0") --clean
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir)
      OUT_DIR="$2"
      shift 2
      ;;
    --lan-ip)
      LAN_IP="$2"
      shift 2
      ;;
    --san)
      EXTRA_SAN="$2"
      shift 2
      ;;
    --clean)
      DO_CLEAN=true
      shift
      ;;
    -h|--help)
      print_usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      print_usage >&2
      exit 1
      ;;
  esac
done

if [[ "$DO_CLEAN" == "true" ]]; then
  if [[ -d "$OUT_DIR" ]]; then
    echo "Cleaning generated certificates in: $OUT_DIR"
    rm -rf "$OUT_DIR"
    echo "Clean complete."
  else
    echo "Directory does not exist: $OUT_DIR (nothing to clean)"
  fi
  exit 0
fi

# Locate keytool
KEYTOOL_BIN=""
if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/keytool" ]]; then
  KEYTOOL_BIN="${JAVA_HOME}/bin/keytool"
elif command -v keytool >/dev/null 2>&1; then
  KEYTOOL_BIN="$(command -v keytool)"
else
  echo "ERROR: Java 'keytool' not found. Please install JDK 21+ and set JAVA_HOME or add to PATH." >&2
  exit 1
fi

# Generate dynamic passwords if not provided via environment
if [[ -z "$KEYSTORE_PASS" ]]; then
  KEYSTORE_PASS="$(head -c 16 /dev/urandom | xxd -p 2>/dev/null || LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom 2>/dev/null | head -c 24 || date +%s%N)"
fi
if [[ -z "$TRUSTSTORE_PASS" ]]; then
  TRUSTSTORE_PASS="$(head -c 16 /dev/urandom | xxd -p 2>/dev/null || LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom 2>/dev/null | head -c 24 || date +%s%N)"
fi

echo "================================================================="
echo "ThogaMura Local TLS Certificate Setup (Member 5 — Issue #5)"
echo "Tool: $KEYTOOL_BIN"
echo "Target directory: $OUT_DIR"
echo "================================================================="

mkdir -p "$OUT_DIR"

# Assemble Subject Alternative Names (SAN)
SAN="dns:localhost,ip:127.0.0.1"
if [[ -n "$LAN_IP" ]]; then
  SAN="${SAN},ip:${LAN_IP}"
fi
if [[ -n "$EXTRA_SAN" ]]; then
  SAN="${SAN},${EXTRA_SAN}"
fi
echo "Subject Alternative Names (SAN): $SAN"

CA_KEYSTORE="${OUT_DIR}/ca-keystore.p12"
CA_CERT="${OUT_DIR}/ca.crt"
SERVER_KEYSTORE="${OUT_DIR}/server-keystore.p12"
GATEWAY_TRUSTSTORE="${OUT_DIR}/gateway-truststore.p12"
SERVER_CSR="${OUT_DIR}/server.csr"
SERVER_CERT="${OUT_DIR}/server.crt"

# Clean any existing files in output dir
rm -f "$CA_KEYSTORE" "$CA_CERT" "$SERVER_KEYSTORE" "$GATEWAY_TRUSTSTORE" "$SERVER_CSR" "$SERVER_CERT"

echo
echo "1. Generating Development Root CA..."
"$KEYTOOL_BIN" -genkeypair \
  -alias thogamura-ca \
  -keyalg RSA \
  -keysize 2048 \
  -validity "$CA_DAYS" \
  -keystore "$CA_KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASS" \
  -dname "CN=ThogaMura Dev Root CA, OU=Security, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK" \
  -ext BasicConstraints=ca:true,pathlen:0 \
  -ext KeyUsage=keyCertSign,cRLSign

echo "2. Exporting Root CA Certificate..."
"$KEYTOOL_BIN" -exportcert \
  -alias thogamura-ca \
  -keystore "$CA_KEYSTORE" \
  -storepass "$KEYSTORE_PASS" \
  -rfc \
  -file "$CA_CERT"

echo "3. Generating Warehouse Server Keypair..."
"$KEYTOOL_BIN" -genkeypair \
  -alias thogamura-warehouse \
  -keyalg RSA \
  -keysize 2048 \
  -validity "$SERVER_DAYS" \
  -keystore "$SERVER_KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASS" \
  -dname "CN=localhost, OU=Warehouse, O=ThogaMura Coursework, L=Colombo, ST=Western, C=LK"

echo "4. Generating Certificate Signing Request (CSR) for Warehouse Server..."
"$KEYTOOL_BIN" -certreq \
  -alias thogamura-warehouse \
  -keystore "$SERVER_KEYSTORE" \
  -storepass "$KEYSTORE_PASS" \
  -file "$SERVER_CSR"

echo "5. Signing Warehouse Server Certificate with Dev CA..."
"$KEYTOOL_BIN" -gencert \
  -alias thogamura-ca \
  -keystore "$CA_KEYSTORE" \
  -storepass "$KEYSTORE_PASS" \
  -infile "$SERVER_CSR" \
  -outfile "$SERVER_CERT" \
  -rfc \
  -validity "$SERVER_DAYS" \
  -ext BasicConstraints=ca:false \
  -ext KeyUsage=digitalSignature,keyEncipherment \
  -ext ExtendedKeyUsage=serverAuth \
  -ext "SAN=${SAN}"

echo "6. Importing CA Certificate into Server Keystore (establishing chain)..."
"$KEYTOOL_BIN" -importcert \
  -alias thogamura-ca \
  -keystore "$SERVER_KEYSTORE" \
  -storepass "$KEYSTORE_PASS" \
  -noprompt \
  -file "$CA_CERT"

echo "7. Installing Signed Certificate into Server Keystore..."
"$KEYTOOL_BIN" -importcert \
  -alias thogamura-warehouse \
  -keystore "$SERVER_KEYSTORE" \
  -storepass "$KEYSTORE_PASS" \
  -file "$SERVER_CERT"

echo "8. Creating Gateway Truststore (Contains CA cert ONLY, NO private keys)..."
"$KEYTOOL_BIN" -importcert \
  -alias thogamura-ca \
  -keystore "$GATEWAY_TRUSTSTORE" \
  -storetype PKCS12 \
  -storepass "$TRUSTSTORE_PASS" \
  -noprompt \
  -file "$CA_CERT"

# Restrict permissions on generated credential files
chmod 600 "$SERVER_KEYSTORE" "$GATEWAY_TRUSTSTORE" "$CA_KEYSTORE" 2>/dev/null || true
chmod 644 "$CA_CERT" "$SERVER_CERT" 2>/dev/null || true

# Clean temporary CSR
rm -f "$SERVER_CSR"

echo
echo "================================================================="
echo "TLS Certificate Generation Successful!"
echo "================================================================="
echo "Files created:"
echo "  - Warehouse Server Keystore : $SERVER_KEYSTORE"
echo "  - Branch Gateway Truststore : $GATEWAY_TRUSTSTORE"
echo "  - Root CA Public Certificate: $CA_CERT"
echo "  - Server Public Certificate : $SERVER_CERT"
echo
echo "Fingerprints (SHA-256):"
printf "  Root CA : "
"$KEYTOOL_BIN" -printcert -file "$CA_CERT" | grep "SHA256:" | awk '{print $2}' || true
printf "  Server  : "
"$KEYTOOL_BIN" -printcert -file "$SERVER_CERT" | grep "SHA256:" | awk '{print $2}' || true
echo
echo "Add the following variables to your local .env file (DO NOT COMMIT .env):"
echo "-----------------------------------------------------------------"
echo "THOGAMURA_TLS_KEYSTORE_PATH=${OUT_DIR}/server-keystore.p12"
echo "THOGAMURA_TLS_KEYSTORE_PASSWORD=${KEYSTORE_PASS}"
echo "THOGAMURA_TLS_KEYSTORE_TYPE=PKCS12"
echo
echo "THOGAMURA_TLS_TRUSTSTORE_PATH=${OUT_DIR}/gateway-truststore.p12"
echo "THOGAMURA_TLS_TRUSTSTORE_PASSWORD=${TRUSTSTORE_PASS}"
echo "THOGAMURA_TLS_TRUSTSTORE_TYPE=PKCS12"
echo "THOGAMURA_TLS_HOSTNAME_VERIFICATION=true"
echo "-----------------------------------------------------------------"
