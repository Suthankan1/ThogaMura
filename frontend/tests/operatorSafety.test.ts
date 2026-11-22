import assert from "node:assert/strict";
import { test } from "node:test";
import { ApiError, fetchProducts, fetchSupplierAvailability } from "../src/lib/api/inventory.ts";
import { filterMovements, movementCsv } from "../src/lib/movementReport.ts";
import { parseQuantity, parseReservationAttempt } from "../src/lib/reservationAttempt.ts";
import { safeReturnTo } from "../src/lib/returnTo.ts";

test("reservation snapshots reject coercion and retain exactly the intended amount", () => {
  for (const value of ["1.5", "1e3", "2147483648", "0"]) assert.equal(parseQuantity(value), null);
  assert.equal(parseQuantity("2147483647"), 2147483647);
  const pending = { sku: "ITEM-007", quantity: 2, idempotencyKey: "5a3e07fd-158b-4d77-9b97-51844197ed9c" };
  assert.deepEqual(parseReservationAttempt(JSON.stringify(pending)), pending);
  assert.equal(parseReservationAttempt(JSON.stringify({ ...pending, quantity: "2" })), null);
});

test("return navigation stays on recognized workspace routes", () => {
  assert.equal(safeReturnTo("/products?q=onions"), "/products?q=onions");
  assert.equal(safeReturnTo("/missing"), "/products");
  assert.equal(safeReturnTo("//unrelated.example/products"), "/products");
});

test("incomplete product records are rejected instead of showing zero stock", async (t) => {
  t.mock.method(globalThis, "fetch", async () => Response.json([{ sku: "ITEM-007", name: "Carrot" }]));
  await assert.rejects(fetchProducts(), (error) => error instanceof ApiError && error.code === "INVALID_RESPONSE");
});

test("supplier and warehouse deadlines map to distinct operator outcomes", async (t) => {
  const mock = t.mock.method(globalThis, "fetch", async () => Response.json({ code: "WAREHOUSE_TIMEOUT" }, { status: 504 }));
  assert.equal((await fetchSupplierAvailability("ITEM-007")).status, "WAREHOUSE_TIMEOUT");
  mock.mock.mockImplementation(async () => Response.json({ code: "SUPPLIER_TIMEOUT" }, { status: 504 }));
  assert.equal((await fetchSupplierAvailability("ITEM-007")).status, "SUPPLIER_TIMEOUT");
});

test("movement export applies date and SKU filters and quotes spreadsheet cells", () => {
  const row = { id: 1, stockRequestId: "r", sku: "ITEM-007", occurredAt: "2026-11-05T10:00:00Z", quantityDelta: -1, reason: "=unsafe-formula" };
  assert.deepEqual(filterMovements([row], "007", "2026-11-01", "2026-11-05"), [row]);
  assert.deepEqual(filterMovements([row], "007", "2026-11-06", ""), []);
  assert.match(movementCsv([row]), /"'-?=unsafe-formula"/);
});
