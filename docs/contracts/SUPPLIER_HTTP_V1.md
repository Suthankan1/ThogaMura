# Supplier availability HTTP contract v1 — independent demo service

**Purpose:** Member 2 proves Java URL/HTTP networking to a **separate process**, not Spring-to-Java method calls. This is a planned adapter contract, not a real supplier API.

## Transport and endpoint

- Base URL from central-server environment `THOGAMURA_SUPPLIER_BASE_URL`; dev/mock example `http://127.0.0.1:8090`.
- Request: `GET /v1/stock?sku=ITEM-008`.
- Resolve hostname using normal DNS/host resolution in Java; build URL from configured base using `URI` and correctly URL-encode SKU. Never accept host/port/scheme from a browser parameter.
- Client uses Java `URL` + `HttpURLConnection` (`HttpsURLConnection` for HTTPS). Configure finite connect and read timeouts; limit body bytes before JSON decoding; permit no redirect to untrusted hosts.
- Demo supplier mock runs independently of branch gateway and central network server; it should record incoming requests to prove real network I/O.

## Supplier mock responses (proposed)

### Known SKU (HTTP 200)

```json
{
  "sku": "ITEM-008",
  "supplier": "Demo Supplier",
  "availableUnits": 20,
  "estimatedRestockDays": 3,
  "checkedAt": "2026-11-17T08:00:00Z"
}
```

`availableUnits=0` is a **known confirmed zero** and must not be confused with missing data. Values are **advisory** and do not represent warehouse stock.

### Unknown SKU (HTTP 404)

```json
{"code":"SKU_NOT_FOUND"}
```

Map to command status `UNKNOWN` with **no** `reportedQuantity`.

### Supplier unavailable (HTTP 503 or network connect/read timeout)

Map to command status `UNAVAILABLE` with a safe error code, **no** `reportedQuantity`. Distinguish `SUPPLIER_TIMEOUT` from `SUPPLIER_UNAVAILABLE` if useful.

### Malformed/oversized/contradictory response (HTTP 200)

Map to `UNKNOWN` and log only safe diagnostic metadata. Do not display partial unvalidated quantity.

## Gateway and browser mapping

- Branch browser calls gateway `GET /api/v1/suppliers/availability?sku=...`.
- Gateway sends central TLS/TCP `GET_SUPPLIER_STOCK` using M1 framing; central Java HTTP client calls the mock supplier.
- Branch UI displays `AVAILABLE` (quantity may be zero), `UNKNOWN` (no verified data) or `UNAVAILABLE` (supplier inaccessible). The UI always labels `MOCK_SUPPLIER` data as synthetic.
- No purchase order, payment, automatic reorder, inventory mutation, user-controlled URL or supplier credentials in repository.
- Negative tests: timeouts, 404/503, invalid JSON, response size, query encoding, redirects to non-allowlisted URLs and independent network execution.

If a real supplier API is later chosen, write a separate adapter with its documented API contract and auth; do not claim mock data is live real-world availability.
