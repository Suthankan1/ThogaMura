#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$PROJECT_ROOT"
source "$PROJECT_ROOT/start.sh"

PASS_COUNT=0

expect_success() {
    local name="$1"
    shift
    if "$@"; then
        echo "PASS: $name"
        PASS_COUNT=$((PASS_COUNT + 1))
    else
        echo "FAIL: $name" >&2
        exit 1
    fi
}

expect_failure() {
    local name="$1"
    shift
    if "$@" >/dev/null 2>&1; then
        echo "FAIL: $name" >&2
        exit 1
    else
        echo "PASS: $name"
        PASS_COUNT=$((PASS_COUNT + 1))
    fi
}

reset_tls_environment() {
    unset THOGAMURA_TLS_ENABLED
    unset THOGAMURA_TLS_SERVER_ENABLED
    unset THOGAMURA_TLS_CLIENT_ENABLED
    unset THOGAMURA_TLS_HOSTNAME_VERIFICATION
    unset THOGAMURA_WAREHOUSE_HOST
    unset THOGAMURA_COMMAND_HOST
    unset THOGAMURA_TLS_KEYSTORE_PATH
    unset THOGAMURA_TLS_KEYSTORE_PASSWORD
    unset THOGAMURA_TLS_TRUSTSTORE_PATH
    unset THOGAMURA_TLS_TRUSTSTORE_PASSWORD
}

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

touch "$TEMP_DIR/server-keystore.p12"
touch "$TEMP_DIR/gateway-truststore.p12"

reset_tls_environment
export THOGAMURA_TLS_SERVER_ENABLED=false
export THOGAMURA_TLS_CLIENT_ENABLED=false
export THOGAMURA_WAREHOUSE_HOST=127.0.0.1
expect_success "loopback plaintext development mode" validate_tls_runtime

reset_tls_environment
export THOGAMURA_TLS_HOSTNAME_VERIFICATION=false
expect_failure "hostname verification cannot be disabled" validate_tls_runtime

reset_tls_environment
export THOGAMURA_TLS_CLIENT_ENABLED=false
export THOGAMURA_WAREHOUSE_HOST=192.168.8.100
expect_failure "remote warehouse requires TLS" validate_tls_runtime

reset_tls_environment
export THOGAMURA_TLS_SERVER_ENABLED=true
export THOGAMURA_TLS_CLIENT_ENABLED=true
export THOGAMURA_TLS_HOSTNAME_VERIFICATION=true
export THOGAMURA_WAREHOUSE_HOST=192.168.8.100
export THOGAMURA_TLS_KEYSTORE_PATH="$TEMP_DIR/server-keystore.p12"
export THOGAMURA_TLS_KEYSTORE_PASSWORD=test-keystore-password
export THOGAMURA_TLS_TRUSTSTORE_PATH="$TEMP_DIR/gateway-truststore.p12"
export THOGAMURA_TLS_TRUSTSTORE_PASSWORD=test-truststore-password
expect_success "validated TLS runtime configuration" validate_tls_runtime

reset_tls_environment
export THOGAMURA_TLS_SERVER_ENABLED=true
export THOGAMURA_TLS_KEYSTORE_PATH="$TEMP_DIR/missing-server-keystore.p12"
export THOGAMURA_TLS_KEYSTORE_PASSWORD=test-keystore-password
expect_failure "missing server keystore fails closed" validate_tls_runtime

reset_tls_environment
export THOGAMURA_TLS_CLIENT_ENABLED=true
export THOGAMURA_TLS_TRUSTSTORE_PATH="$TEMP_DIR/missing-gateway-truststore.p12"
export THOGAMURA_TLS_TRUSTSTORE_PASSWORD=test-truststore-password
expect_failure "missing gateway truststore fails closed" validate_tls_runtime

echo "TLS runtime verification passed: $PASS_COUNT checks"
