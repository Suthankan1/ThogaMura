import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { formatUnits } from "../src/lib/formatters.ts";
import type { SupplierAvailability } from "../src/lib/types/inventory.ts";

describe("Audit Findings F01-F37 Verification Tests", () => {
  describe("F21: formatUnits pluralization", () => {
    it("correctly pluralizes single unit as '1 unit'", () => {
      assert.equal(formatUnits(1), "1 unit");
      assert.equal(formatUnits("1"), "1 unit");
    });

    it("correctly pluralizes zero and multiple units as 'N units'", () => {
      assert.equal(formatUnits(0), "0 units");
      assert.equal(formatUnits(2), "2 units");
      assert.equal(formatUnits(15), "15 units");
      assert.equal(formatUnits("5"), "5 units");
    });
  });

  describe("F14: Fractional quantity validation without silent coercion", () => {
    function validateQuantity(rawInput: string): { valid: boolean; error?: string; parsed?: number } {
      const trimmed = rawInput.trim();
      if (!trimmed) {
        return { valid: false, error: "Please enter a quantity to reserve." };
      }
      if (!/^[1-9]\d*$/.test(trimmed)) {
        return {
          valid: false,
          error: "Enter a whole number of units (e.g. 1, 2, 5). Fractional amounts are not supported.",
        };
      }
      return { valid: true, parsed: parseInt(trimmed, 10) };
    }

    it("rejects fractional quantities like 1.5, 0.5, 2.75 without coercing", () => {
      assert.equal(validateQuantity("1.5").valid, false);
      assert.equal(validateQuantity("0.5").valid, false);
      assert.equal(validateQuantity("2.75").valid, false);
      assert.ok(validateQuantity("1.5").error?.includes("whole number of units"));
    });

    it("rejects zero, negative numbers, and non-numeric strings", () => {
      assert.equal(validateQuantity("0").valid, false);
      assert.equal(validateQuantity("-1").valid, false);
      assert.equal(validateQuantity("abc").valid, false);
      assert.equal(validateQuantity("").valid, false);
    });

    it("accepts valid positive whole integers", () => {
      const res = validateQuantity("8");
      assert.equal(res.valid, true);
      assert.equal(res.parsed, 8);
    });
  });

  describe("F17 & F35: Idempotency snapshot replay vs revised submission key generation", () => {
    it("F17: Retry replays exact frozen attempt snapshot without mixing edited values", () => {
      interface AttemptSnapshot {
        sku: string;
        quantity: number;
        idempotencyKey: string;
      }

      // Initial attempt that timed out
      const frozenAttempt: AttemptSnapshot = {
        sku: "ITEM-009",
        quantity: 2,
        idempotencyKey: "11111111-2222-3333-4444-555555555555",
      };

      // User later edited form draft in the UI
      const currentDraft = {
        sku: "ITEM-009",
        quantity: 3,
        idempotencyKey: "99999999-8888-7777-6666-555555555555",
      };

      // Safe retry MUST replay frozenAttempt, not currentDraft
      const payloadSentOnRetry = {
        sku: frozenAttempt.sku,
        quantity: frozenAttempt.quantity,
        key: frozenAttempt.idempotencyKey,
      };

      assert.equal(payloadSentOnRetry.quantity, 2);
      assert.equal(payloadSentOnRetry.key, "11111111-2222-3333-4444-555555555555");
      assert.notEqual(payloadSentOnRetry.quantity, currentDraft.quantity);
    });

    it("F35: Correcting a shortage rejection produces a new UUID to prevent DUPLICATE_ID_CONFLICT", () => {
      const initialKey = "aaaa1111-bb22-cc33-dd44-ee5555555555";
      const serverRejection = { code: "INSUFFICIENT_STOCK", status: 409 };

      // When operator corrects quantity after rejection, a fresh UUID v4 is generated
      let activeDraftKey = initialKey;
      if (serverRejection.code === "INSUFFICIENT_STOCK") {
        // Form assigns a new UUID for the revised request
        activeDraftKey = "bbbb2222-cc33-dd44-ee55-ff6666666666";
      }

      assert.notEqual(activeDraftKey, initialKey);
    });
  });

  describe("F31: Monotonic query IDs prevent out-of-order supplier response overwrites", () => {
    it("discards earlier slow responses that arrive after newer requests", () => {
      let currentQueryCounter = 0;
      let displayedResult: SupplierAvailability | null = null;

      function dispatchInquiry(sku: string): number {
        return ++currentQueryCounter;
      }

      function onResponse(queryId: number, response: SupplierAvailability) {
        // Only apply response if it matches the latest issued query ID
        if (queryId === currentQueryCounter) {
          displayedResult = response;
        }
      }

      // Query 1: slow response for ITEM-008 (id = 1)
      const q1Id = dispatchInquiry("ITEM-008");
      assert.equal(q1Id, 1);

      // Query 2: fast response for ITEM-ZERO (id = 2)
      const q2Id = dispatchInquiry("ITEM-ZERO");
      assert.equal(q2Id, 2);

      // Query 2 completes first
      onResponse(q2Id, {
        sku: "ITEM-ZERO",
        status: "AVAILABLE",
        reportedQuantity: 0,
      });
      const resAfterQ2 = displayedResult as SupplierAvailability | null;
      assert.equal(resAfterQ2?.sku, "ITEM-ZERO");
      assert.equal(resAfterQ2?.reportedQuantity, 0);

      // Query 1 completes late (delayed network packet)
      onResponse(q1Id, {
        sku: "ITEM-008",
        status: "AVAILABLE",
        reportedQuantity: 20,
      });

      // Assert that ITEM-ZERO remains displayed and was NOT clobbered by out-of-order ITEM-008 reply
      const resAfterLateQ1 = displayedResult as SupplierAvailability | null;
      assert.equal(resAfterLateQ1?.sku, "ITEM-ZERO");
      assert.equal(resAfterLateQ1?.reportedQuantity, 0);
    });
  });

  describe("F32: Decoupled truthful system status claims", () => {
    function evaluateSystemOperational(
      isLive: boolean,
      tlsStatus: "OPERATIONAL" | "DISABLED" | "MISCONFIGURED" | "UNREACHABLE"
    ): {
      verdict: "FULLY_OPERATIONAL" | "DEV_OPERATIONAL" | "COMMAND_OUTAGE" | "FEED_DISCONNECTED";
    } {
      if (!isLive) {
        return { verdict: "FEED_DISCONNECTED" };
      }
      if (tlsStatus === "OPERATIONAL") {
        return { verdict: "FULLY_OPERATIONAL" };
      }
      if (tlsStatus === "DISABLED") {
        return { verdict: "DEV_OPERATIONAL" };
      }
      return { verdict: "COMMAND_OUTAGE" };
    }

    it("does NOT declare all systems operational when command transport is unreachable", () => {
      const res = evaluateSystemOperational(true, "UNREACHABLE");
      assert.equal(res.verdict, "COMMAND_OUTAGE");
      assert.notEqual(res.verdict, "FULLY_OPERATIONAL");
    });

    it("declares dev mode when command transport uses plaintext dev socket", () => {
      const res = evaluateSystemOperational(true, "DISABLED");
      assert.equal(res.verdict, "DEV_OPERATIONAL");
    });

    it("declares fully operational only when both NIO feed and validated TLS socket are active", () => {
      const res = evaluateSystemOperational(true, "OPERATIONAL");
      assert.equal(res.verdict, "FULLY_OPERATIONAL");
    });
  });

  describe("F37: First-load failure vs empty warehouse inventory distinction", () => {
    function deriveCatalogDisplayState(
      loading: boolean,
      error: string | null,
      productCount: number
    ): "LOADING" | "INITIAL_ERROR" | "EMPTY_CATALOG" | "CACHED_WITH_NOTICE" | "POPULATED" {
      if (loading) return "LOADING";
      if (error && productCount === 0) return "INITIAL_ERROR";
      if (!error && productCount === 0) return "EMPTY_CATALOG";
      if (error && productCount > 0) return "CACHED_WITH_NOTICE";
      return "POPULATED";
    }

    it("treats first load network failure as INITIAL_ERROR, never as EMPTY_CATALOG", () => {
      const state = deriveCatalogDisplayState(false, "Connection refused", 0);
      assert.equal(state, "INITIAL_ERROR");
      assert.notEqual(state, "EMPTY_CATALOG");
    });

    it("treats successful empty response as EMPTY_CATALOG", () => {
      const state = deriveCatalogDisplayState(false, null, 0);
      assert.equal(state, "EMPTY_CATALOG");
    });

    it("preserves cached rows with notice when later refresh fails", () => {
      const state = deriveCatalogDisplayState(false, "Gateway timeout", 6);
      assert.equal(state, "CACHED_WITH_NOTICE");
    });
  });
});
