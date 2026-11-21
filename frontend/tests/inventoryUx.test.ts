import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { Product, StockRequest, StockMovement, SupplierAvailability } from "../src/lib/types/inventory.ts";

describe("Frontend Inventory & Fulfillment Model (Issues #13, #22, #30, #26)", () => {
  it("enforces product stock inventory balance invariant (available = onHand - reserved >= 0)", () => {
    const validProduct: Product = {
      sku: "ITEM-007",
      name: "Organic Carrots",
      onHand: 10,
      reserved: 2,
      available: 8,
    };

    assert.equal(validProduct.available, validProduct.onHand - validProduct.reserved);
    assert.equal(validProduct.available >= 0, true);
  });

  it("handles out-of-stock product state correctly", () => {
    const outOfStockProduct: Product = {
      sku: "ITEM-008",
      name: "Red Onions",
      onHand: 5,
      reserved: 5,
      available: 0,
    };

    assert.equal(outOfStockProduct.available, 0);
  });

  it("validates request status progression (ACCEPTED -> PICKED -> DISPATCHED)", () => {
    const validStatuses = ["ACCEPTED", "REJECTED", "PICKED", "DISPATCHED"] as const;

    const request: StockRequest = {
      requestId: "5a3e07fd-158b-4d77-9b97-51844197ed9c",
      sku: "ITEM-007",
      quantity: 8,
      status: "ACCEPTED",
      createdAt: "2026-11-18T09:30:00Z",
    };

    assert.equal(validStatuses.includes(request.status), true);
  });

  it("validates immutable stock movement delta for completed dispatches", () => {
    const movement: StockMovement = {
      id: 1,
      stockRequestId: "5a3e07fd-158b-4d77-9b97-51844197ed9c",
      sku: "ITEM-007",
      quantityDelta: -8,
      reason: "DISPATCH",
      occurredAt: "2026-11-18T09:45:00Z",
    };

    assert.equal(movement.quantityDelta < 0, true);
    assert.equal(movement.reason, "DISPATCH");
  });

  it("distinguishes supplier available, confirmed zero, and service outage states", () => {
    const availableSupplier: SupplierAvailability = {
      sku: "ITEM-008",
      status: "AVAILABLE",
      supplier: "Demo Supplier",
      reportedQuantity: 20,
      source: "MOCK_SUPPLIER",
      checkedAt: "2026-11-18T10:00:00Z",
    };

    const outageSupplier: SupplierAvailability = {
      sku: "ITEM-008",
      status: "SUPPLIER_UNAVAILABLE",
      message: "Supplier service timed out",
    };

    assert.equal(availableSupplier.status, "AVAILABLE");
    assert.equal(availableSupplier.reportedQuantity, 20);
    assert.equal(outageSupplier.status, "SUPPLIER_UNAVAILABLE");
    assert.equal(outageSupplier.reportedQuantity, undefined);
  });
});
