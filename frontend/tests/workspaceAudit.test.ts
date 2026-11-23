import assert from "node:assert/strict";
import { test } from "node:test";
import { commandResult, definitiveCommandFailure, WorkspaceError, type Command } from "../src/lib/api/workspace.ts";
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
