#!/usr/bin/env bash
# ==============================================================================
# ThogaMura Demo Data Seeder (QA-06)
# Idempotently seeds stocked, low-stock, and zero-stock SKUs along with
# representative requests across all lifecycle states (ACCEPTED, PICKED, DISPATCHED, REJECTED).
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

DB_USER="${POSTGRES_USER:-thogamura}"
DB_NAME="${POSTGRES_DB:-thogamura}"
SQL_FILE="$ROOT_DIR/backend/network-server/src/test/resources/fixtures/dev-seed-fixtures.sql"

echo "=================================================================="
echo "  Seeding ThogaMura Demo Inventory & Request Lifecycle State     "
echo "=================================================================="

# Check if Docker compose DB is running
if docker compose ps -q db 2>/dev/null | grep -q .; then
    echo "Connecting to PostgreSQL container via Docker Compose..."
    docker compose exec -T db psql -U "$DB_USER" -d "$DB_NAME" < "$SQL_FILE"
elif command -v psql >/dev/null 2>&1 && [ -n "${THOGAMURA_DB_URL:-}" ]; then
    echo "Connecting to PostgreSQL via local psql client..."
    psql "${THOGAMURA_DB_URL}" < "$SQL_FILE"
else
    echo "[ERROR] Cannot find running PostgreSQL database container or psql client."
    echo "Run './start.sh' first to initialize the database."
    exit 1
fi

echo ""
echo "✓ Successfully seeded:"
echo "  • Stocked SKUs:         ITEM-007 (Organic Carrots - In Stock 4+)"
echo "  • Zero-Stock SKUs:      ITEM-008 (Red Onions - Out of Stock 0)"
echo "  • Low-Stock SKUs:       ITEM-009 (Potatoes - Low Stock 1-3)"
echo "  • Boundary SKUs:        ITEM-010 (4 available), ITEM-011 (1 available), ITEM-012 (3 available)"
echo "  • Request Lifecycle:    ACCEPTED, PICKED, DISPATCHED (with audit record), and REJECTED"
echo "=================================================================="
