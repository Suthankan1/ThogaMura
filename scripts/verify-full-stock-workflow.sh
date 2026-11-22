#!/usr/bin/env bash
# Exercise mutable workflows against a fresh, explicitly disposable demo database.
set -euo pipefail

if [ "${THOGAMURA_VERIFY_DISPOSABLE:-}" != "1" ]; then
    echo "Set THOGAMURA_VERIFY_DISPOSABLE=1 after selecting a disposable synthetic demo stack." >&2
    exit 2
fi

python3 - "${THOGAMURA_GATEWAY_URL:-http://127.0.0.1:8080}" <<'PYTHON'
import json
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

base = sys.argv[1].rstrip("/")
sku = "ITEM-007"


def call(path, status, body=None, key=None):
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if key:
        headers["Idempotency-Key"] = key
    request = Request(
        base + "/api/v1" + path,
        json.dumps(body).encode() if body is not None else None,
        headers,
        method="POST" if body is not None or path.endswith(("/pick", "/dispatch")) else "GET",
    )
    try:
        with urlopen(request, timeout=20) as response:
            actual, data = response.status, json.load(response)
    except HTTPError as response:
        with response:
            actual, data = response.code, json.load(response)
    if actual != status:
        raise RuntimeError(f"{path}: expected HTTP {status}, observed {actual} with {data.get('code') if isinstance(data, dict) else 'list'}")
    return data


def stock():
    return next(row for row in call("/products", 200) if row["sku"] == sku)


if call("/ping", 200).get("status") != "PONG":
    raise RuntimeError("Warehouse command probe did not return PONG")

before = stock()
if before["available"] < 1:
    raise RuntimeError("Demo SKU requires at least one available unit")

invalid = call("/requests", 400, {"sku": sku, "quantity": 1.5}, str(uuid4()))
missing = call("/requests", 404, {"sku": "ITEM-SMOKE-NOT-FOUND", "quantity": 1}, str(uuid4()))
assert invalid["code"] == "VALIDATION_ERROR"
assert missing["code"] == "NOT_FOUND"
assert stock() == before

key = str(uuid4())
payload = {"sku": sku, "quantity": 1}
receipt = call("/requests", 201, payload, key)
assert receipt["requestId"] == key and receipt["status"] == "ACCEPTED"
assert call("/requests", 201, payload, key) == receipt
held = stock()
assert held["reserved"] == before["reserved"] + 1 and held["onHand"] == before["onHand"]

assert call(f"/requests/{key}/dispatch", 409)["code"] == "INVALID_TRANSITION"
picked = call(f"/requests/{key}/pick", 200)
assert picked["status"] == "PICKED"
assert call(f"/requests/{key}/pick", 200) == picked
assert stock() == held
dispatched = call(f"/requests/{key}/dispatch", 200)
assert dispatched["status"] == "DISPATCHED"
assert call(f"/requests/{key}/dispatch", 200) == dispatched

after = stock()
assert after["onHand"] == before["onHand"] - 1 and after["reserved"] == before["reserved"]
ledger = [row for row in call("/movements", 200) if row["stockRequestId"] == key]
assert len(ledger) == 1 and ledger[0]["quantityDelta"] == -1 and ledger[0]["reason"] == "DISPATCH"
print(f"Verified request {key}: strict validation, same-result replays, one dispatch movement and consistent stock.")
PYTHON
