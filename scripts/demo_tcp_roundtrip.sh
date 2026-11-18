#!/bin/bash
set -euo pipefail

echo "=================================================================="
echo "  ThogaMura Issue #9 — Real Gateway-to-Warehouse TCP Round Trip   "
echo "  Member 1: Sankha Ravindu (@sankharavindu)                       "
echo "=================================================================="

WAREHOUSE_TCP_PORT=7445
GATEWAY_HTTP_PORT=8085
HOST="127.0.0.1"

# Build classpaths
SERVER_CP="backend/network-server/target/classes:backend/protocol/target/classes:$(mvn -f backend/network-server/pom.xml dependency:build-classpath | grep -v '\[' | tr '\n' ':')"
GATEWAY_CP="backend/api-gateway/target/classes:backend/protocol/target/classes:$(mvn -f backend/api-gateway/pom.xml dependency:build-classpath | grep -v '\[' | tr '\n' ':')"

echo "[1] Starting Warehouse Command Server (Process A) on ${HOST}:${WAREHOUSE_TCP_PORT}..."
java -cp "${SERVER_CP}" lk.thogamura.networkserver.transport.tcp.TcpCommandServer "${WAREHOUSE_TCP_PORT}" "${HOST}" > server.log 2>&1 &
PID_SERVER=$!
echo "--> Warehouse Command Server started with PID: ${PID_SERVER}"

# Wait for server ready
for i in {1..20}; do
  if grep -q "SERVER_READY:${WAREHOUSE_TCP_PORT}" server.log 2>/dev/null; then
    echo "--> Server is listening on ${HOST}:${WAREHOUSE_TCP_PORT}"
    break
  fi
  sleep 0.2
done

echo ""
echo "[2] Starting Spring Boot Gateway (Process B) on port ${GATEWAY_HTTP_PORT} targeting warehouse ${HOST}:${WAREHOUSE_TCP_PORT}..."
SERVER_PORT=${GATEWAY_HTTP_PORT} THOGAMURA_COMMAND_PORT=${WAREHOUSE_TCP_PORT} THOGAMURA_NOTIFICATIONS_SUBSCRIBER_ENABLED=false \
java -cp "${GATEWAY_CP}" lk.thogamura.gateway.GatewayApplication > gateway.log 2>&1 &
PID_GATEWAY=$!
echo "--> Spring Boot Gateway started with PID: ${PID_GATEWAY}"

# Wait for gateway ready
for i in {1..30}; do
  if grep -q "Started GatewayApplication" gateway.log 2>/dev/null; then
    echo "--> Gateway application is ready on port ${GATEWAY_HTTP_PORT}"
    break
  fi
  sleep 0.3
done

echo ""
echo "=================================================================="
echo "  STEP 1: Real TCP Socket PING/PONG Round-Trip                    "
echo "=================================================================="
echo "Client JVM PID: ${PID_GATEWAY} (Spring Boot Gateway on :${GATEWAY_HTTP_PORT})"
echo "Server JVM PID: ${PID_SERVER} (Warehouse Command Server on :${WAREHOUSE_TCP_PORT})"
echo ""
echo "Request: HTTP GET http://${HOST}:${GATEWAY_HTTP_PORT}/api/v1/ping"
echo "--- Gateway HTTP Response ---"
curl -s -i "http://${HOST}:${GATEWAY_HTTP_PORT}/api/v1/ping"
echo ""

echo "--- Server Logs (Connection & Command Dispatch) ---"
grep "Dispatching command 'PING'" server.log || true

echo ""
echo "=================================================================="
echo "  STEP 2: Negative Test — Stop Warehouse Server (Kill Process A)  "
echo "=================================================================="
echo "Killing Warehouse Server PID: ${PID_SERVER}..."
kill -9 "${PID_SERVER}" 2>/dev/null || true
sleep 0.5
echo "--> Warehouse server terminated."

echo ""
echo "Request: HTTP GET http://${HOST}:${GATEWAY_HTTP_PORT}/api/v1/ping"
echo "--- Gateway HTTP Response (Failure Handled) ---"
curl -s -i "http://${HOST}:${GATEWAY_HTTP_PORT}/api/v1/ping"
echo ""

echo ""
echo "=================================================================="
echo "  STEP 3: Recovery Test — Restart Warehouse Server                "
echo "=================================================================="
echo "Restarting Warehouse Command Server on ${HOST}:${WAREHOUSE_TCP_PORT}..."
java -cp "${SERVER_CP}" lk.thogamura.networkserver.transport.tcp.TcpCommandServer "${WAREHOUSE_TCP_PORT}" "${HOST}" >> server.log 2>&1 &
PID_SERVER_NEW=$!
echo "--> Warehouse Server restarted with new PID: ${PID_SERVER_NEW}"

sleep 0.8

echo ""
echo "Request: HTTP GET http://${HOST}:${GATEWAY_HTTP_PORT}/api/v1/ping"
echo "--- Gateway HTTP Response (Recovered) ---"
curl -s -i "http://${HOST}:${GATEWAY_HTTP_PORT}/api/v1/ping"
echo ""

echo "=================================================================="
echo "  CLEANUP                                                         "
echo "=================================================================="
kill -9 "${PID_SERVER_NEW}" "${PID_GATEWAY}" 2>/dev/null || true
echo "All processes stopped cleanly."
