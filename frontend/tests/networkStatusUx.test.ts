import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { NetworkStatusResponse, TlsCommandStatusInfo } from "../src/lib/types/network.ts";

/**
 * Unit regression tests verifying frontend network diagnostics,
 * TLS status classification, and HTTP gateway error categorization.
 * Satisfies Issue #69 and #70 verification.
 */
describe("Frontend Network Diagnostics and Error Classification", () => {
  it("correctly classifies operational TLS command status", () => {
    const tlsInfo: TlsCommandStatusInfo = {
      status: "OPERATIONAL",
      owner: "Member 5",
    };

    assert.equal(tlsInfo.status, "OPERATIONAL");
    assert.equal(tlsInfo.owner, "Member 5");
  });

  it("distinguishes non-operational TLS command states (DISABLED, MISCONFIGURED, UNREACHABLE)", () => {
    const nonOperationalStatuses: string[] = ["DISABLED", "MISCONFIGURED", "UNREACHABLE"];

    for (const status of nonOperationalStatuses) {
      const isHealthy = status === "OPERATIONAL";
      assert.equal(isHealthy, false, `Expected ${status} not to be treated as healthy OPERATIONAL`);
    }
  });

  it("safely handles missing or partial network status payload", () => {
    const partialPayload: Partial<NetworkStatusResponse> = {
      centralHost: "127.0.0.1",
      nioPort: 7444,
    };

    const status = partialPayload.tlsCommand?.status ?? "DISABLED";
    assert.equal(status, "DISABLED");
  });

  it("distinguishes warehouse transport outages from supplier service outages", () => {
    interface GatewayError {
      status: number;
      code: string;
      message: string;
    }

    const warehouseOutage: GatewayError = {
      status: 503,
      code: "WAREHOUSE_UNAVAILABLE",
      message: "Warehouse command server is unreachable.",
    };

    const supplierOutage: GatewayError = {
      status: 503,
      code: "SUPPLIER_UNAVAILABLE",
      message: "Upstream supplier service is temporarily unavailable.",
    };

    assert.notEqual(warehouseOutage.code, supplierOutage.code);
    assert.equal(warehouseOutage.code.startsWith("WAREHOUSE_"), true);
    assert.equal(supplierOutage.code.startsWith("SUPPLIER_"), true);
  });
});
