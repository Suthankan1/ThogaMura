#!/usr/bin/env bash
# Install the synthetic demo scenario into an empty local Compose database.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
SQL_FILE="$ROOT_DIR/backend/network-server/src/test/resources/fixtures/dev-seed-fixtures.sql"

if [ -z "$(docker compose ps --status running -q db)" ]; then
    echo "Start the local Compose database and run the warehouse migrations before seeding." >&2
    exit 1
fi

docker compose exec -T db sh -c 'exec psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$SQL_FILE"
