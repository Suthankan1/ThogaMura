#!/usr/bin/env bash
# ==============================================================================
# ThogaMura Unified Orchestration Script
# Usage:
#   ./start.sh          - Start all components (DB, Server, Simulator, Gateway, UI)
#   ./start.sh status   - Check status and health of all components
#   ./start.sh stop     - Stop all running components cleanly
#   ./start.sh restart  - Restart all components
#   ./start.sh logs [name] - Tail logs (server|gateway|frontend|supplier)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RUN_DIR="$SCRIPT_DIR/.run"
mkdir -p "$RUN_DIR"

# Text styles
BOLD='\033[1m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Component names
PID_SERVER="$RUN_DIR/network-server.pid"
PID_GATEWAY="$RUN_DIR/api-gateway.pid"
PID_SIMULATOR="$RUN_DIR/supplier-simulator.pid"
PID_FRONTEND="$RUN_DIR/frontend.pid"

LOG_SERVER="$RUN_DIR/network-server.log"
LOG_GATEWAY="$RUN_DIR/api-gateway.log"
LOG_SIMULATOR="$RUN_DIR/supplier-simulator.log"
LOG_FRONTEND="$RUN_DIR/frontend.log"

# Default Ports
PORT_PG_DEFAULT=5432
PORT_COMMAND=7443
PORT_NIO=7444
PORT_SIMULATOR=8090
PORT_GATEWAY=8080
PORT_FRONTEND=3000

# ------------------------------------------------------------------------------
# Helper Functions
# ------------------------------------------------------------------------------

is_pid_running() {
    local pid_file="$1"
    if [ -f "$pid_file" ]; then
        local pid
        pid=$(cat "$pid_file" 2>/dev/null || true)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
    fi
    return 1
}

is_port_listening() {
    local host="${1:-127.0.0.1}"
    local port="$2"
    nc -z "$host" "$port" >/dev/null 2>&1
}

wait_for_port() {
    local host="$1"
    local port="$2"
    local timeout="${3:-15}"
    local name="$4"

    local elapsed=0
    while ! nc -z "$host" "$port" >/dev/null 2>&1; do
        sleep 1
        elapsed=$((elapsed + 1))
        if [ "$elapsed" -ge "$timeout" ]; then
            echo -e "${RED}[ERROR] Timed out waiting for $name on $host:$port ($timeout seconds)${NC}"
            return 1
        fi
    done
    return 0
}

wait_for_http() {
    local url="$1"
    local timeout="${2:-20}"
    local name="$3"

    local elapsed=0
    while ! curl -s -f -o /dev/null "$url"; do
        sleep 1
        elapsed=$((elapsed + 1))
        if [ "$elapsed" -ge "$timeout" ]; then
            echo -e "${RED}[ERROR] Timed out waiting for $name at $url ($timeout seconds)${NC}"
            return 1
        fi
    done
    return 0
}

load_environment() {
    if [ ! -f .env ]; then
        if [ -f .env.example ]; then
            echo -e "${YELLOW}[INFO] .env not found. Initializing from .env.example...${NC}"
            cp .env.example .env
        fi
    fi

    # Read .env if present using portable parser compatible with macOS bash 3.2
    if [ -f .env ]; then
        local shell_tls_enabled_set="${THOGAMURA_TLS_ENABLED+x}"
        local shell_tls_enabled="${THOGAMURA_TLS_ENABLED:-}"
        local shell_tls_server_set="${THOGAMURA_TLS_SERVER_ENABLED+x}"
        local shell_tls_server="${THOGAMURA_TLS_SERVER_ENABLED:-}"
        local shell_tls_client_set="${THOGAMURA_TLS_CLIENT_ENABLED+x}"
        local shell_tls_client="${THOGAMURA_TLS_CLIENT_ENABLED:-}"
        local shell_hostname_set="${THOGAMURA_TLS_HOSTNAME_VERIFICATION+x}"
        local shell_hostname="${THOGAMURA_TLS_HOSTNAME_VERIFICATION:-}"
        local shell_warehouse_set="${THOGAMURA_WAREHOUSE_HOST+x}"
        local shell_warehouse="${THOGAMURA_WAREHOUSE_HOST:-}"
        local shell_command_host_set="${THOGAMURA_COMMAND_HOST+x}"
        local shell_command_host="${THOGAMURA_COMMAND_HOST:-}"
        local shell_keystore_path_set="${THOGAMURA_TLS_KEYSTORE_PATH+x}"
        local shell_keystore_path="${THOGAMURA_TLS_KEYSTORE_PATH:-}"
        local shell_keystore_password_set="${THOGAMURA_TLS_KEYSTORE_PASSWORD+x}"
        local shell_keystore_password="${THOGAMURA_TLS_KEYSTORE_PASSWORD:-}"
        local shell_truststore_path_set="${THOGAMURA_TLS_TRUSTSTORE_PATH+x}"
        local shell_truststore_path="${THOGAMURA_TLS_TRUSTSTORE_PATH:-}"
        local shell_truststore_password_set="${THOGAMURA_TLS_TRUSTSTORE_PASSWORD+x}"
        local shell_truststore_password="${THOGAMURA_TLS_TRUSTSTORE_PASSWORD:-}"

        while IFS= read -r line || [ -n "$line" ]; do
            local trimmed
            trimmed=$(echo "$line" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
            case "$trimmed" in
                \#*|"") continue ;;
                *=*)
                    local key="${trimmed%%=*}"
                    local val="${trimmed#*=}"
                    val="${val#\"}"
                    val="${val%\"}"
                    val="${val#\'}"
                    val="${val%\'}"
                    export "$key"="$val"
                    ;;
            esac
        done < .env

        [ -n "$shell_tls_enabled_set" ] && export THOGAMURA_TLS_ENABLED="$shell_tls_enabled"
        [ -n "$shell_tls_server_set" ] && export THOGAMURA_TLS_SERVER_ENABLED="$shell_tls_server"
        [ -n "$shell_tls_client_set" ] && export THOGAMURA_TLS_CLIENT_ENABLED="$shell_tls_client"
        [ -n "$shell_hostname_set" ] && export THOGAMURA_TLS_HOSTNAME_VERIFICATION="$shell_hostname"
        [ -n "$shell_warehouse_set" ] && export THOGAMURA_WAREHOUSE_HOST="$shell_warehouse"
        [ -n "$shell_command_host_set" ] && export THOGAMURA_COMMAND_HOST="$shell_command_host"
        [ -n "$shell_keystore_path_set" ] && export THOGAMURA_TLS_KEYSTORE_PATH="$shell_keystore_path"
        [ -n "$shell_keystore_password_set" ] && export THOGAMURA_TLS_KEYSTORE_PASSWORD="$shell_keystore_password"
        [ -n "$shell_truststore_path_set" ] && export THOGAMURA_TLS_TRUSTSTORE_PATH="$shell_truststore_path"
        [ -n "$shell_truststore_password_set" ] && export THOGAMURA_TLS_TRUSTSTORE_PASSWORD="$shell_truststore_password"
    fi

    # Smart PostgreSQL port detection:
    # If 5432 is already bound by an external host process, route Docker to 5433
    local container_id
    container_id=$(docker compose ps -q db 2>/dev/null || true)
    local is_our_container_running=0
    if [ -n "$container_id" ]; then
        local state
        state=$(docker inspect -f '{{.State.Running}}' "$container_id" 2>/dev/null || echo "false")
        if [ "$state" = "true" ]; then
            local mapped_port
            mapped_port=$(docker port "$container_id" 5432/tcp 2>/dev/null | head -n 1 | sed -E 's/.*:([0-9]+)$/\1/' || echo "")
            if [ "$mapped_port" = "${POSTGRES_PORT:-5432}" ]; then
                is_our_container_running=1
            fi
        fi
    fi

    if [ "$is_our_container_running" -eq 0 ] && nc -z 127.0.0.1 5432 >/dev/null 2>&1; then
        echo -e "${YELLOW}[INFO] Host port 5432 is in use by another service. Using port 5433 for Docker PostgreSQL.${NC}"
        export POSTGRES_PORT=5433
        export THOGAMURA_DB_URL="jdbc:postgresql://localhost:5433/thogamura"
    else
        export POSTGRES_PORT="${POSTGRES_PORT:-5432}"
        export THOGAMURA_DB_URL="${THOGAMURA_DB_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT}/thogamura}"
    fi
}

is_loopback_host() {
    local host="${1:-}"
    case "$host" in
        localhost|127.*|::1|0:0:0:0:0:0:0:1) return 0 ;;
    esac
    return 1
}

is_true_value() {
    case "${1:-false}" in
        1|true|TRUE|yes|YES|on|ON) return 0 ;;
        *) return 1 ;;
    esac
}

validate_tls_runtime() {
    local tls_enabled="${THOGAMURA_TLS_ENABLED:-false}"
    local tls_server_enabled="${THOGAMURA_TLS_SERVER_ENABLED:-$tls_enabled}"
    local tls_client_enabled="${THOGAMURA_TLS_CLIENT_ENABLED:-$tls_enabled}"
    local warehouse_host="${THOGAMURA_WAREHOUSE_HOST:-${THOGAMURA_COMMAND_HOST:-127.0.0.1}}"
    local hostname_verification="${THOGAMURA_TLS_HOSTNAME_VERIFICATION:-true}"

    export RUNTIME_TLS_SERVER_ENABLED="$tls_server_enabled"
    export RUNTIME_TLS_CLIENT_ENABLED="$tls_client_enabled"
    export RUNTIME_WAREHOUSE_HOST="$warehouse_host"

    if ! is_true_value "$hostname_verification"; then
        echo -e "${RED}[ERROR] THOGAMURA_TLS_HOSTNAME_VERIFICATION must remain true.${NC}"
        return 1
    fi

    if is_true_value "$tls_server_enabled"; then
        local keystore_path="${THOGAMURA_TLS_KEYSTORE_PATH:-.certs/server-keystore.p12}"
        local keystore_password="${THOGAMURA_TLS_KEYSTORE_PASSWORD:-}"
        if [ ! -f "$keystore_path" ]; then
            echo -e "${RED}[ERROR] TLS is enabled but the warehouse keystore is missing: $keystore_path${NC}"
            return 1
        fi
        if [ -z "$keystore_password" ] || [ "$keystore_password" = "change-this-keystore-password" ]; then
            echo -e "${RED}[ERROR] TLS is enabled but THOGAMURA_TLS_KEYSTORE_PASSWORD is missing.${NC}"
            return 1
        fi
        export RUNTIME_TLS_SERVER_ENABLED="true"
    else
        export RUNTIME_TLS_SERVER_ENABLED="false"
    fi

    if is_true_value "$tls_client_enabled"; then
        local truststore_path="${THOGAMURA_TLS_TRUSTSTORE_PATH:-.certs/gateway-truststore.p12}"
        local truststore_password="${THOGAMURA_TLS_TRUSTSTORE_PASSWORD:-}"
        if [ ! -f "$truststore_path" ]; then
            echo -e "${RED}[ERROR] TLS is enabled but the gateway truststore is missing: $truststore_path${NC}"
            return 1
        fi
        if [ -z "$truststore_password" ] || [ "$truststore_password" = "change-this-truststore-password" ]; then
            echo -e "${RED}[ERROR] TLS is enabled but THOGAMURA_TLS_TRUSTSTORE_PASSWORD is missing.${NC}"
            return 1
        fi
        export RUNTIME_TLS_CLIENT_ENABLED="true"
    else
        export RUNTIME_TLS_CLIENT_ENABLED="false"
    fi

    if ! is_loopback_host "$warehouse_host" && ! is_true_value "$RUNTIME_TLS_CLIENT_ENABLED"; then
        echo -e "${RED}[ERROR] Remote warehouse host '$warehouse_host' requires TLS client mode.${NC}"
        echo -e "${RED}[ERROR] Set THOGAMURA_TLS_CLIENT_ENABLED=true or THOGAMURA_TLS_ENABLED=true.${NC}"
        return 1
    fi

    return 0
}

# ------------------------------------------------------------------------------
# Action: Start
# ------------------------------------------------------------------------------

do_start() {
    echo -e "${BOLD}${BLUE}==================================================================${NC}"
    echo -e "${BOLD}${BLUE}  Starting ThogaMura Systems (Branch-to-Warehouse Stock Flow)      ${NC}"
    echo -e "${BOLD}${BLUE}==================================================================${NC}"

    load_environment
    if ! validate_tls_runtime; then
        exit 1
    fi

    if [ "$RUNTIME_TLS_SERVER_ENABLED" = "true" ] && [ "$RUNTIME_TLS_CLIENT_ENABLED" = "true" ]; then
        echo -e "${GREEN}[TLS] Validated TLS command transport enabled for both warehouse server and gateway.${NC}"
    elif [ "$RUNTIME_TLS_SERVER_ENABLED" = "true" ] || [ "$RUNTIME_TLS_CLIENT_ENABLED" = "true" ]; then
        echo -e "${YELLOW}[TLS] TLS is enabled only on one process; verify warehouse and gateway modes intentionally.${NC}"
    elif is_loopback_host "$RUNTIME_WAREHOUSE_HOST"; then
        echo -e "${YELLOW}[TLS] Loopback plaintext development mode explicitly enabled; not for remote LAN use.${NC}"
    fi

    # 1. PostgreSQL Database via Docker Compose
    echo -e "\n${CYAN}[1/5] Checking PostgreSQL Database...${NC}"
    if ! command -v docker >/dev/null 2>&1; then
        echo -e "${RED}[ERROR] Docker is not installed or not in PATH.${NC}"
        exit 1
    fi

    if ! docker info >/dev/null 2>&1; then
        echo -e "${RED}[ERROR] Docker daemon is not running. Please start Docker Desktop/daemon.${NC}"
        exit 1
    fi

    echo -e "${GREEN}Starting database on port ${POSTGRES_PORT}...${NC}"
    docker compose up -d db

    # Wait for PostgreSQL to be ready
    echo -n "Waiting for PostgreSQL to accept connections..."
    local db_ready=0
    for _ in {1..20}; do
        if docker compose exec -T db pg_isready -U "${POSTGRES_USER:-thogamura}" -d "${POSTGRES_DB:-thogamura}" >/dev/null 2>&1; then
            db_ready=1
            break
        fi
        sleep 1
        echo -n "."
    done
    echo ""

    if [ "$db_ready" -ne 1 ]; then
        echo -e "${RED}[ERROR] PostgreSQL database failed to become ready in time.${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ PostgreSQL is ready on 127.0.0.1:${POSTGRES_PORT}${NC}"

    # 2. Check / Build Backend Jars
    local server_jar="backend/network-server/target/network-server-0.1.0-SNAPSHOT.jar"
    local gateway_jar="backend/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar"

    local needs_build=0
    if [ ! -f "$server_jar" ] || [ ! -f "$gateway_jar" ]; then
        needs_build=1
    elif [ -n "$(find backend -name '*.java' -newer "$server_jar" 2>/dev/null | head -n 1)" ]; then
        needs_build=1
    elif [ -n "$(find backend -name 'pom.xml' -newer "$server_jar" 2>/dev/null | head -n 1)" ]; then
        needs_build=1
    fi

    if [ "$needs_build" -eq 1 ]; then
        echo -e "\n${YELLOW}[BUILD] Backend jars are missing or outdated. Packaging Maven reactor...${NC}"
        mvn -f backend/pom.xml package -DskipTests
    fi

    # 3. Start Central Java Network Server (Warehouse)
    echo -e "\n${CYAN}[2/5] Starting Central Java Network Server...${NC}"
    if is_pid_running "$PID_SERVER"; then
        echo -e "${YELLOW}Network Server is already running (PID: $(cat "$PID_SERVER"))${NC}"
    else
        THOGAMURA_COMMAND_SERVER_ENABLED=true \
        THOGAMURA_NOTIFICATIONS_ENABLED=true \
        THOGAMURA_COMMAND_SERVER_HOST="${THOGAMURA_COMMAND_SERVER_HOST:-127.0.0.1}" \
        THOGAMURA_TLS_SERVER_ENABLED="$RUNTIME_TLS_SERVER_ENABLED" \
        THOGAMURA_TLS_KEYSTORE_PATH="${THOGAMURA_TLS_KEYSTORE_PATH:-.certs/server-keystore.p12}" \
        THOGAMURA_TLS_KEYSTORE_PASSWORD="${THOGAMURA_TLS_KEYSTORE_PASSWORD:-}" \
        THOGAMURA_TLS_KEYSTORE_TYPE="${THOGAMURA_TLS_KEYSTORE_TYPE:-PKCS12}" \
        THOGAMURA_DB_URL="${THOGAMURA_DB_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT}/thogamura}" \
        THOGAMURA_DB_USER="${THOGAMURA_DB_USER:-thogamura}" \
        THOGAMURA_DB_PASSWORD="${THOGAMURA_DB_PASSWORD:-${POSTGRES_PASSWORD:-change-this-local-password}}" \
        nohup java -jar "$server_jar" > "$LOG_SERVER" 2>&1 &
        echo $! > "$PID_SERVER"

        echo -n "Waiting for TCP Command Server (port ${PORT_COMMAND}) and NIO Feed (port ${PORT_NIO})..."
        if ! wait_for_port "127.0.0.1" "$PORT_COMMAND" 15 "TCP Command Server"; then
            echo -e "\n${RED}[ERROR] Central Network Server failed to open TCP port ${PORT_COMMAND}.${NC}"
            echo -e "${YELLOW}--- Last 25 lines of $LOG_SERVER ---${NC}"
            tail -n 25 "$LOG_SERVER" 2>/dev/null || true
            echo -e "${YELLOW}-------------------------------------${NC}"
            exit 1
        fi
        if ! wait_for_port "127.0.0.1" "$PORT_NIO" 15 "NIO Notification Server"; then
            echo -e "\n${RED}[ERROR] Central Network Server failed to open NIO port ${PORT_NIO}.${NC}"
            echo -e "${YELLOW}--- Last 25 lines of $LOG_SERVER ---${NC}"
            tail -n 25 "$LOG_SERVER" 2>/dev/null || true
            echo -e "${YELLOW}-------------------------------------${NC}"
            exit 1
        fi
        echo -e "\n${GREEN}✓ Central Network Server started (PID: $(cat "$PID_SERVER"))${NC}"
    fi

    # 4. Start Supplier HTTP Simulator
    echo -e "\n${CYAN}[3/5] Starting Independent Supplier HTTP Simulator...${NC}"
    if is_pid_running "$PID_SIMULATOR"; then
        echo -e "${YELLOW}Supplier Simulator is already running (PID: $(cat "$PID_SIMULATOR"))${NC}"
    else
        nohup java -cp "$server_jar" \
            -Dloader.main=lk.thogamura.networkserver.integration.supplier.SupplierHttpSimulator \
            org.springframework.boot.loader.launch.PropertiesLauncher "$PORT_SIMULATOR" </dev/null > "$LOG_SIMULATOR" 2>&1 &
        echo $! > "$PID_SIMULATOR"

        wait_for_port "127.0.0.1" "$PORT_SIMULATOR" 10 "Supplier Simulator"
        echo -e "${GREEN}✓ Supplier HTTP Simulator started on http://127.0.0.1:${PORT_SIMULATOR} (PID: $(cat "$PID_SIMULATOR"))${NC}"
    fi

    # 5. Start Spring Boot API Gateway (Branch)
    echo -e "\n${CYAN}[4/5] Starting Branch Spring Boot API Gateway...${NC}"
    if is_pid_running "$PID_GATEWAY"; then
        echo -e "${YELLOW}API Gateway is already running (PID: $(cat "$PID_GATEWAY"))${NC}"
    else
        THOGAMURA_NOTIFICATIONS_SUBSCRIBER_ENABLED=true \
        THOGAMURA_WAREHOUSE_HOST="$RUNTIME_WAREHOUSE_HOST" \
        THOGAMURA_COMMAND_HOST="$RUNTIME_WAREHOUSE_HOST" \
        THOGAMURA_COMMAND_PORT="${PORT_COMMAND}" \
        THOGAMURA_TLS_CLIENT_ENABLED="$RUNTIME_TLS_CLIENT_ENABLED" \
        THOGAMURA_TLS_TRUSTSTORE_PATH="${THOGAMURA_TLS_TRUSTSTORE_PATH:-.certs/gateway-truststore.p12}" \
        THOGAMURA_TLS_TRUSTSTORE_PASSWORD="${THOGAMURA_TLS_TRUSTSTORE_PASSWORD:-}" \
        THOGAMURA_TLS_TRUSTSTORE_TYPE="${THOGAMURA_TLS_TRUSTSTORE_TYPE:-PKCS12}" \
        THOGAMURA_TLS_HOSTNAME_VERIFICATION=true \
        THOGAMURA_NOTIFICATIONS_HOST="${THOGAMURA_NOTIFICATIONS_HOST:-127.0.0.1}" \
        THOGAMURA_NOTIFICATIONS_PORT="${PORT_NIO}" \
        nohup java -jar "$gateway_jar" > "$LOG_GATEWAY" 2>&1 &
        echo $! > "$PID_GATEWAY"

        echo -n "Waiting for Gateway REST/SSE endpoints (http://127.0.0.1:${PORT_GATEWAY})..."
        if ! wait_for_http "http://127.0.0.1:${PORT_GATEWAY}/actuator/health" 20 "API Gateway"; then
            echo -e "\n${RED}[ERROR] API Gateway failed to become healthy.${NC}"
            echo -e "${YELLOW}--- Last 25 lines of $LOG_GATEWAY ---${NC}"
            tail -n 25 "$LOG_GATEWAY" 2>/dev/null || true
            echo -e "${YELLOW}-------------------------------------${NC}"
            exit 1
        fi
        echo -e "\n${GREEN}✓ API Gateway started on http://127.0.0.1:${PORT_GATEWAY} (PID: $(cat "$PID_GATEWAY"))${NC}"
    fi

    # 6. Start Next.js Frontend
    echo -e "\n${CYAN}[5/5] Starting Next.js Web Frontend...${NC}"
    if is_port_listening "127.0.0.1" "$PORT_FRONTEND"; then
        echo -e "${YELLOW}Frontend is already running on port ${PORT_FRONTEND}${NC}"
    else
        if [ ! -d "frontend/node_modules" ]; then
            echo -e "${YELLOW}[NPM] Installing frontend dependencies...${NC}"
            npm --prefix frontend install
        fi

        python3 -c "import subprocess; p = subprocess.Popen(['npx', 'next', 'dev'], cwd='frontend', stdin=subprocess.DEVNULL, stdout=open('$LOG_FRONTEND', 'a'), stderr=subprocess.STDOUT, start_new_session=True); open('$PID_FRONTEND', 'w').write(str(p.pid))"

        echo -n "Waiting for Next.js to start on http://localhost:${PORT_FRONTEND}..."
        wait_for_http "http://localhost:${PORT_FRONTEND}" 25 "Next.js Frontend"
        echo -e "\n${GREEN}✓ Next.js Frontend started on http://localhost:${PORT_FRONTEND}${NC}"
    fi

    echo -e "\n${BOLD}${GREEN}==================================================================${NC}"
    echo -e "${BOLD}${GREEN}  All ThogaMura Components Running Successfully!                  ${NC}"
    echo -e "${BOLD}${GREEN}==================================================================${NC}"
    echo -e "  • ${BOLD}Web Application:${NC}      http://localhost:${PORT_FRONTEND}"
    echo -e "  • ${BOLD}API Gateway:${NC}          http://localhost:${PORT_GATEWAY} (Actuator / Health)"
    echo -e "  • ${BOLD}Warehouse Command:${NC}    127.0.0.1:${PORT_COMMAND} (Java TCP Socket)"
    echo -e "  • ${BOLD}NIO Notification:${NC}     127.0.0.1:${PORT_NIO} (Java NIO Selector Feed)"
    echo -e "  • ${BOLD}Supplier Simulator:${NC}   http://127.0.0.1:${PORT_SIMULATOR}"
    echo -e "  • ${BOLD}PostgreSQL DB:${NC}        127.0.0.1:${POSTGRES_PORT}"
    echo -e "------------------------------------------------------------------"
    echo -e "  Check status anytime:   ${CYAN}./start.sh status${NC}"
    echo -e "  Stop all services:      ${CYAN}./start.sh stop${NC}"
    echo -e "  View logs:              ${CYAN}./start.sh logs [server|gateway|frontend|supplier]${NC}"
    echo -e "==================================================================\n"
}

# ------------------------------------------------------------------------------
# Action: Status
# ------------------------------------------------------------------------------

do_status() {
    load_environment
    if ! validate_tls_runtime; then
        echo -e "${RED}[TLS] Runtime TLS configuration is invalid.${NC}"
        return 1
    fi

    echo -e "${BOLD}${BLUE}==================================================================${NC}"
    echo -e "${BOLD}${BLUE}  ThogaMura Component Status                                      ${NC}"
    echo -e "${BOLD}${BLUE}==================================================================${NC}"

    printf "%-26s %-10s %-16s %-12s\n" "COMPONENT" "PID" "PORT / TARGET" "STATUS"
    echo "------------------------------------------------------------------"

    # 1. PostgreSQL DB
    local db_status="${RED}STOPPED${NC}"
    local db_pid="docker"
    local db_port="${POSTGRES_PORT:-5432}"
    if docker compose ps -q db 2>/dev/null | grep -q . && is_port_listening "127.0.0.1" "$db_port"; then
        db_status="${GREEN}RUNNING (UP)${NC}"
    fi
    printf "%-26s %-10s %-16s %-20b\n" "PostgreSQL Database" "$db_pid" "127.0.0.1:$db_port" "$db_status"

    # 2. Central Network Server
    local srv_status="${RED}STOPPED${NC}"
    local srv_pid="-"
    if is_pid_running "$PID_SERVER"; then
        srv_pid=$(cat "$PID_SERVER")
        if is_port_listening "127.0.0.1" "$PORT_COMMAND"; then
            srv_status="${GREEN}RUNNING (OK)${NC}"
        else
            srv_status="${YELLOW}STARTING${NC}"
        fi
    fi
    printf "%-26s %-10s %-16s %-20b\n" "Central Network Server" "$srv_pid" "TCP:$PORT_COMMAND NIO:$PORT_NIO" "$srv_status"

    # 3. Supplier Simulator
    local sim_status="${RED}STOPPED${NC}"
    local sim_pid="-"
    if is_pid_running "$PID_SIMULATOR"; then
        sim_pid=$(cat "$PID_SIMULATOR")
        if is_port_listening "127.0.0.1" "$PORT_SIMULATOR"; then
            sim_status="${GREEN}RUNNING (OK)${NC}"
        else
            sim_status="${YELLOW}STARTING${NC}"
        fi
    fi
    printf "%-26s %-10s %-16s %-20b\n" "Supplier HTTP Simulator" "$sim_pid" "HTTP:$PORT_SIMULATOR" "$sim_status"

    # 4. API Gateway
    local gw_status="${RED}STOPPED${NC}"
    local gw_pid="-"
    if is_pid_running "$PID_GATEWAY"; then
        gw_pid=$(cat "$PID_GATEWAY")
        if curl -s -f -o /dev/null "http://127.0.0.1:${PORT_GATEWAY}/actuator/health"; then
            gw_status="${GREEN}RUNNING (HEALTHY)${NC}"
        else
            gw_status="${YELLOW}UNHEALTHY${NC}"
        fi
    fi
    printf "%-26s %-10s %-16s %-20b\n" "Spring API Gateway" "$gw_pid" "HTTP:$PORT_GATEWAY" "$gw_status"

    local transport_mode="${YELLOW}PLAINTEXT LOOPBACK${NC}"
    if [ "$RUNTIME_TLS_SERVER_ENABLED" = "true" ] && [ "$RUNTIME_TLS_CLIENT_ENABLED" = "true" ]; then
        transport_mode="${GREEN}VALIDATED TLS${NC}"
    elif [ "$RUNTIME_TLS_SERVER_ENABLED" = "true" ] || [ "$RUNTIME_TLS_CLIENT_ENABLED" = "true" ]; then
        transport_mode="${YELLOW}PARTIAL TLS CONFIGURATION${NC}"
    fi
    printf "%-26s %-10s %-16s %-20b\n" "TLS command transport" "-" "$RUNTIME_WAREHOUSE_HOST:$PORT_COMMAND" "$transport_mode"

    # 5. Next.js Frontend
    local ui_status="${RED}STOPPED${NC}"
    local ui_pid="-"
    local listening_pid
    listening_pid=$(lsof -ti :$PORT_FRONTEND 2>/dev/null | head -n 1 || echo "")
    if [ -n "$listening_pid" ]; then
        ui_pid="$listening_pid"
        ui_status="${GREEN}RUNNING (OK)${NC}"
    elif is_pid_running "$PID_FRONTEND"; then
        ui_pid=$(cat "$PID_FRONTEND")
        ui_status="${YELLOW}STARTING${NC}"
    fi
    printf "%-26s %-10s %-16s %-20b\n" "Next.js Web UI" "$ui_pid" "HTTP:$PORT_FRONTEND" "$ui_status"

    echo "=================================================================="
    echo -e "Logs directory: ${CYAN}$RUN_DIR${NC}"
    echo ""
}

# ------------------------------------------------------------------------------
# Action: Stop
# ------------------------------------------------------------------------------

stop_process() {
    local pid_file="$1"
    local name="$2"

    if [ -f "$pid_file" ]; then
        local pid
        pid=$(cat "$pid_file" 2>/dev/null || true)
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            echo -n "Stopping $name (PID: $pid)... "
            kill "$pid" 2>/dev/null || true
            for _ in {1..10}; do
                if ! kill -0 "$pid" 2>/dev/null; then
                    break
                fi
                sleep 0.5
            done
            if kill -0 "$pid" 2>/dev/null; then
                kill -9 "$pid" 2>/dev/null || true
            fi
            echo -e "${GREEN}done${NC}"
        fi
        rm -f "$pid_file"
    fi
}

do_stop() {
    echo -e "${BOLD}${YELLOW}==================================================================${NC}"
    echo -e "${BOLD}${YELLOW}  Stopping ThogaMura Services                                     ${NC}"
    echo -e "${BOLD}${YELLOW}==================================================================${NC}"

    # Stop processes in reverse dependency order
    stop_process "$PID_FRONTEND" "Next.js Web UI"
    local frontend_pids
    frontend_pids=$(lsof -ti :$PORT_FRONTEND 2>/dev/null || true)
    if [ -n "$frontend_pids" ]; then
        echo "$frontend_pids" | xargs kill 2>/dev/null || true
    fi
    stop_process "$PID_GATEWAY" "Spring API Gateway"
    stop_process "$PID_SIMULATOR" "Supplier HTTP Simulator"
    stop_process "$PID_SERVER" "Central Java Network Server"

    # Stop Docker DB container cleanly
    echo -n "Stopping PostgreSQL container... "
    docker compose stop db >/dev/null 2>&1 || true
    echo -e "${GREEN}done${NC}"

    echo -e "${GREEN}All services stopped cleanly.${NC}\n"
}

# ------------------------------------------------------------------------------
# Action: Logs
# ------------------------------------------------------------------------------

do_logs() {
    local target="${1:-}"
    case "$target" in
        server|network-server)
            tail -f "$LOG_SERVER"
            ;;
        gateway|api-gateway)
            tail -f "$LOG_GATEWAY"
            ;;
        frontend|ui|web)
            tail -f "$LOG_FRONTEND"
            ;;
        supplier|simulator)
            tail -f "$LOG_SIMULATOR"
            ;;
        *)
            echo "Usage: ./start.sh logs [server|gateway|frontend|supplier]"
            echo "Available log files:"
            ls -lh "$RUN_DIR"/*.log 2>/dev/null || echo "No log files found yet in $RUN_DIR"
            ;;
    esac
}

# ------------------------------------------------------------------------------
# Entrypoint Dispatcher
# ------------------------------------------------------------------------------

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    ACTION="${1:-start}"

    case "$ACTION" in
        start)
            do_start
            ;;
        status)
            do_status
            ;;
        stop)
            do_stop
            ;;
        restart)
            do_stop
            sleep 1
            do_start
            ;;
        logs)
            shift
            do_logs "$@"
            ;;
        help|--help|-h)
            echo "ThogaMura Unified Orchestrator"
            echo ""
            echo "Usage:"
            echo "  ./start.sh          Start all services (DB, server, simulator, gateway, UI)"
            echo "  ./start.sh status   Show live health and process status"
            echo "  ./start.sh stop     Stop all services cleanly"
            echo "  ./start.sh restart  Restart all services"
            echo "  ./start.sh logs     Tail logs (server|gateway|frontend|supplier)"
            ;;
        *)
            echo -e "${RED}[ERROR] Unknown command: '$ACTION'${NC}"
            echo "Run './start.sh help' for usage instructions."
            exit 1
            ;;
    esac
fi
