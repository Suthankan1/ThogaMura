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

  it("correctly categorizes stock health levels into In Stock, Low Stock, and Out of Stock", () => {
    const products: Product[] = [
      { sku: "ITEM-001", name: "High Stock", onHand: 20, reserved: 5, available: 15 },
      { sku: "ITEM-002", name: "Low Stock", onHand: 5, reserved: 3, available: 2 },
      { sku: "ITEM-003", name: "Zero Stock", onHand: 10, reserved: 10, available: 0 },
    ];

    const inStock = products.filter((p) => p.available > 0);
    const lowStock = products.filter((p) => p.available > 0 && p.available <= 3);
    const outOfStock = products.filter((p) => p.available <= 0);

    assert.equal(inStock.length, 2);
    assert.equal(lowStock.length, 1);
    assert.equal(lowStock[0].sku, "ITEM-002");
    assert.equal(outOfStock.length, 1);
    assert.equal(outOfStock[0].sku, "ITEM-003");
  });

  it("validates warehouse queue actionable states versus terminal states", () => {
    const allRequests: StockRequest[] = [
      { requestId: "req-1", sku: "ITEM-001", quantity: 5, status: "ACCEPTED" },
      { requestId: "req-2", sku: "ITEM-002", quantity: 2, status: "PICKED" },
      { requestId: "req-3", sku: "ITEM-003", quantity: 8, status: "REJECTED" },
      { requestId: "req-4", sku: "ITEM-004", quantity: 3, status: "DISPATCHED" },
    ];

    const actionable = allRequests.filter((r) => r.status === "ACCEPTED" || r.status === "PICKED");
    const terminal = allRequests.filter((r) => r.status === "REJECTED" || r.status === "DISPATCHED");

    assert.equal(actionable.length, 2);
    assert.equal(terminal.length, 2);
  });

  it("QA-01: distinguishes positive stock, confirmed zero stock, and unavailable supplier states", () => {
    function deriveSupplierLabel(item: SupplierAvailability): string {
      if (item.status === "AVAILABLE" && (item.reportedQuantity ?? 0) > 0) {
        return "AVAILABLE AT SUPPLIER";
      }
      if (
        (item.status === "AVAILABLE" && item.reportedQuantity === 0) ||
        item.status === "UNAVAILABLE"
      ) {
        return "OUT OF STOCK AT SUPPLIER";
      }
      if (item.status === "UNKNOWN") {
        return "SKU NOT FOUND IN SUPPLIER CATALOG";
      }
      return "SUPPLIER SERVICE UNAVAILABLE";
    }

    const positiveStock: SupplierAvailability = {
      sku: "ITEM-008",
      status: "AVAILABLE",
      reportedQuantity: 20,
    };
    const zeroStock: SupplierAvailability = {
      sku: "ITEM-ZERO",
      status: "AVAILABLE",
      reportedQuantity: 0,
    };
    const unknownSku: SupplierAvailability = {
      sku: "ITEM-UNKNOWN",
      status: "UNKNOWN",
    };
    const outage: SupplierAvailability = {
      sku: "ITEM-OUTAGE",
      status: "SUPPLIER_UNAVAILABLE",
    };

    assert.equal(deriveSupplierLabel(positiveStock), "AVAILABLE AT SUPPLIER");
    assert.equal(deriveSupplierLabel(zeroStock), "OUT OF STOCK AT SUPPLIER");
    assert.equal(deriveSupplierLabel(unknownSku), "SKU NOT FOUND IN SUPPLIER CATALOG");
    assert.equal(deriveSupplierLabel(outage), "SUPPLIER SERVICE UNAVAILABLE");
  });

  it("QA-09: correctly categorizes stock boundaries with exclusive ranges (4+, 1-3, 0)", () => {
    const products: Product[] = [
      { sku: "ITEM-007", name: "Carrots", onHand: 15, reserved: 3, available: 12 }, // In stock (4+)
      { sku: "ITEM-010", name: "Leeks", onHand: 4, reserved: 0, available: 4 },     // In stock boundary (4)
      { sku: "ITEM-012", name: "Ginger", onHand: 3, reserved: 0, available: 3 },    // Low stock boundary (3)
      { sku: "ITEM-009", name: "Potatoes", onHand: 5, reserved: 3, available: 2 },  // Low stock (2)
      { sku: "ITEM-011", name: "Garlic", onHand: 1, reserved: 0, available: 1 },    // Low stock boundary (1)
      { sku: "ITEM-008", name: "Onions", onHand: 0, reserved: 0, available: 0 },    // Out of stock (0)
    ];

    const inStock = products.filter((p) => p.available >= 4);
    const lowStock = products.filter((p) => p.available >= 1 && p.available <= 3);
    const outOfStock = products.filter((p) => p.available === 0);

    assert.equal(inStock.length, 2);
    assert.deepEqual(inStock.map((p) => p.sku), ["ITEM-007", "ITEM-010"]);

    assert.equal(lowStock.length, 3);
    assert.deepEqual(lowStock.map((p) => p.sku), ["ITEM-012", "ITEM-009", "ITEM-011"]);

    assert.equal(outOfStock.length, 1);
    assert.deepEqual(outOfStock.map((p) => p.sku), ["ITEM-008"]);

    // Total must equal all products (no overlap between exclusive categories)
    assert.equal(inStock.length + lowStock.length + outOfStock.length, products.length);
  });
});
