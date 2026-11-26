import assert from "node:assert/strict";
import { test } from "node:test";
import { classifyWorkspaceSupplierError, commandResult, definitiveCommandFailure, WorkspaceError, type Command } from "../src/lib/api/workspace.ts";
import { parseQuantity } from "../src/lib/reservationAttempt.ts";
import { filterMovements, movementCsv } from "../src/lib/movementReport.ts";

const key = "10000000-0000-4000-8000-000000000001";
const command: Command = { key, path: "/receiving", label: "Receiving", body: { sku: "ITEM-010", quantity: 3, note: "Delivery" } };
const receipt = { status: "RECEIVED", sku: "ITEM-010", quantity: 3, onHand: 10, reserved: 2, available: 8 };

test("receiving accepts a matching receipt only after checking the reported stock balance", () => {
  assert.deepEqual(commandResult(command, receipt), {});
  for (const malformed of [{}, { ...receipt, sku: "ITEM-011" }, { ...receipt, available: 9 }, { ...receipt, quantity: 4 }]) {
    assert.throws(() => commandResult(command, malformed), (error) =>
      error instanceof WorkspaceError && !definitiveCommandFailure(error));
  }
});

test("reservation and transition confirmation correlate to the stored command and request", () => {
  const reserve = { ...command, path: "/requests" };
  const accepted = { status: "ACCEPTED", sku: "ITEM-010", quantity: 3, requestId: key };
  assert.deepEqual(commandResult(reserve, accepted), { reference: key });
  assert.throws(() => commandResult(reserve, { ...accepted, requestId: "other" }));
  const dispatch = { ...command, path: `/requests/${key}/dispatch` };
  assert.deepEqual(commandResult(dispatch, { status: "DISPATCHED", stockRequestId: key }), { reference: key });
  assert.throws(() => commandResult(dispatch, { status: "PICKED", stockRequestId: key }));
});

test("quantity validation rejects scientific notation and values outside the integer range", () => {
  assert.equal(parseQuantity("2147483647"), 2147483647);
  for (const invalid of ["0", "1e3", "1.5", "2147483648", "-2"]) assert.equal(parseQuantity(invalid), null);
});

test("movement filtering uses calendar dates in the same zone as the displayed time", () => {
  const timestamp = new Date(2026, 10, 23, 0, 0).toISOString();
  const row = { id: 1, stockRequestId: key, sku: "ITEM-010", quantityDelta: -3, reason: "DISPATCH", occurredAt: timestamp };
  assert.deepEqual(filterMovements([row], "", "2026-11-23", "2026-11-23"), [row]);
  assert.deepEqual(filterMovements([row], "", "", "2026-11-22"), []);
  const receive = { ...row, id: "2", stockRequestId: null, receivingCommandId: key, branchId: null, recordedBy: "supervisor",
    reason: "RECEIVE" as const, note: "=external()", quantityDelta: 3 };
  assert.match(movementCsv([receive]), /"Receiving command"/);
  assert.match(movementCsv([receive]), /"'=external\(\)"/);
});

test("follow-up confirmations match the target, assignment and status", () => {
  const assignment = { owner: "picker", sku: "ITEM-008", dueDate: "2026-12-01" };
  const create = { path: "/supplier-follow-ups", assignment, key, label: "Supplier follow-up" };
  const result = { ...assignment, id: key, status: "OPEN" };
  assert.deepEqual(commandResult(create, result), {});
  assert.throws(() => commandResult(create, { ...result, dueDate: "2026-12-02" }));
  assert.throws(() => commandResult(create, { ...result, id: "other" }));
  assert.ok(definitiveCommandFailure(new WorkspaceError("Already open", 409, key, "FOLLOWUP_OPEN")));
});

test("workspace supplier errors classify 404, 504, and 503 into distinct actionable states", () => {
  const unknown = classifyWorkspaceSupplierError(new WorkspaceError("Not found", 404));
  assert.equal(unknown.status, 404);
  assert.equal(unknown.code, "SUPPLIER_UNKNOWN");

  const timeout = classifyWorkspaceSupplierError(new WorkspaceError("Timed out", 504));
  assert.equal(timeout.status, 504);
  assert.equal(timeout.code, "SUPPLIER_TIMEOUT");

  const unavailable = classifyWorkspaceSupplierError(new WorkspaceError("Down", 503));
  assert.equal(unavailable.status, 503);
  assert.equal(unavailable.code, "SUPPLIER_UNAVAILABLE");

  const fallback = classifyWorkspaceSupplierError(new Error("Unexpected payload"));
  assert.equal(fallback.status, 502);
});

test("workspace movement CSV export includes Product column when extended movement fields exist", () => {
  const row = {
    id: "12",
    stockRequestId: null,
    receivingCommandId: key,
    branchId: null,
    recordedBy: "supervisor",
    sku: "ITEM-010",
    productName: "Organic Carrots",
    reason: "RECEIVE" as const,
    note: "Morning receipt",
    quantityDelta: 5,
    occurredAt: "2026-11-26T00:00:00Z",
  };
  const csv = movementCsv([row]);
  assert.match(csv, /"Product"/);
  assert.match(csv, /"Organic Carrots"/);
});
