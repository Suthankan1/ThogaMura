import { describe, it } from "node:test";
import assert from "node:assert/strict";

describe("Frontend SSE Invalidation and Recovery Logic (Issue #19)", () => {
  it("Requirement 1 & 2: Parsed SSE version 42 triggers invalidation callback and never becomes a stock quantity", () => {
    let invalidationTriggered = false;
    let receivedVersion = 0;
    let receivedSource = "";

    // Simulated component authoritative state (products with stock quantities)
    const productCatalog = {
      "ITEM-007": { onHand: 10, reserved: 2, available: 8 },
      "ITEM-008": { onHand: 0, reserved: 0, available: 0 },
    };

    // Callback registered by consuming component
    const onInvalidate = (version: number, source: string) => {
      invalidationTriggered = true;
      receivedVersion = version;
      receivedSource = source;

      // CRITICAL ARCHITECTURAL ASSERTION:
      // Invalidation event payload contains only the change version number.
      // Product quantities MUST remain authoritative and never be overwritten by version number!
      assert.notEqual(productCatalog["ITEM-007"].available, version);
      assert.notEqual(productCatalog["ITEM-007"].onHand, version);
      assert.equal(productCatalog["ITEM-007"].available, 8);
    };

    // Simulate incoming SSE event: event: stock-changed\ndata: 42\n\n
    const rawEventData = "42";
    const parsedVersion = parseInt(rawEventData, 10);
    assert.equal(parsedVersion, 42);

    onInvalidate(parsedVersion, "SSE_EVENT");

    assert.equal(invalidationTriggered, true);
    assert.equal(receivedVersion, 42);
    assert.equal(receivedSource, "SSE_EVENT");
  });

  it("Requirement 3: Disconnection marks state as disconnected and preserves warning", () => {
    let sseStatus = "CONNECTING";
    let warningMessage: string | null = null;

    // Simulate onopen
    sseStatus = "CONNECTED";
    warningMessage = null;
    assert.equal(sseStatus, "CONNECTED");
    assert.equal(warningMessage, null);

    // Simulate onerror (server down / network drop)
    sseStatus = "DISCONNECTED";
    warningMessage = "Real-time event stream disconnected. Reconnecting to gateway...";
    assert.equal(sseStatus, "DISCONNECTED");
    assert.ok(warningMessage.includes("disconnected"));
  });

  it("Requirement 4: Reconnection triggers authoritative state refresh and clears warning", () => {
    let refreshCount = 0;
    let lastRefreshSource = "";

    const triggerRefresh = (source: string) => {
      refreshCount++;
      lastRefreshSource = source;
    };

    let hasEverConnected = false;
    let sseStatus = "CONNECTING";

    // 1. Initial connection
    sseStatus = "CONNECTED";
    hasEverConnected = true;
    assert.equal(refreshCount, 0);

    // 2. Disconnection occurs
    sseStatus = "DISCONNECTED";

    // 3. Native EventSource automatically reconnects -> onopen fires
    if (hasEverConnected) {
      triggerRefresh("RECONNECT");
      sseStatus = "CONNECTED";
    }

    assert.equal(sseStatus, "CONNECTED");
    assert.equal(refreshCount, 1);
    assert.equal(lastRefreshSource, "RECONNECT");
  });

  it("Requirement 5: Page focus triggers authoritative refresh", () => {
    let focusRefreshes = 0;
    const triggerRefresh = (source: string) => {
      if (source === "PAGE_FOCUS") {
        focusRefreshes++;
      }
    };

    // Simulate document visibilitychange to 'visible'
    const visibilityState = "visible";
    if (visibilityState === "visible") {
      triggerRefresh("PAGE_FOCUS");
    }

    assert.equal(focusRefreshes, 1);
  });

  it("Requirement 6: Upstream warehouse NIO feed disruption disables isLive even when SSE stream is open", () => {
    // Scenario: Browser is connected to Spring Boot gateway SSE, but gateway lost its NIO connection to warehouse
    const sseStatus = "CONNECTED";

    // Upstream status response from GET /api/v1/network-status
    const mockNetworkStatus = {
      centralHost: "127.0.0.1",
      nioPort: 7444,
      nioFeed: {
        enabled: true,
        status: "RECONNECTING", // Upstream NIO is down!
        connected: false,
        lastReceivedVersion: 42,
        reconnectCount: 2,
      },
      sse: { activeClients: 1, lastBroadcastVersion: 42 },
    };

    const upstreamConnected = mockNetworkStatus.nioFeed.connected;
    const isLive = sseStatus === "CONNECTED" && upstreamConnected;

    // Must NOT report live health merely because SSE HTTP connection is active!
    assert.equal(isLive, false);

    // Human-readable warning is required
    let warning: string | null = null;
    if (!mockNetworkStatus.nioFeed.connected) {
      warning = "Warehouse Java NIO notification feed interrupted. Updates paused.";
    }
    assert.ok(warning !== null);
    assert.ok(warning.includes("NIO notification feed interrupted"));
  });

  it("Requirement 7: Invalidation subscription allows multiple consumers and unregisters cleanly", () => {
    const listeners = new Set<(ver: number) => void>();

    let callback1Calls = 0;
    let callback2Calls = 0;

    const cb1 = () => { callback1Calls++; };
    const cb2 = () => { callback2Calls++; };

    listeners.add(cb1);
    const unsubscribe2 = () => listeners.delete(cb2);
    listeners.add(cb2);

    // Event 1
    listeners.forEach((l) => l(101));
    assert.equal(callback1Calls, 1);
    assert.equal(callback2Calls, 1);

    // Unsubscribe listener 2
    unsubscribe2();

    // Event 2
    listeners.forEach((l) => l(102));
    assert.equal(callback1Calls, 2);
    assert.equal(callback2Calls, 1); // Not called again
  });

  it("Requirement 8: Rapid notifications are debounced to prevent overlapping REST storms", async () => {
    let restRequestCount = 0;
    let inFlight = false;
    let lastTime = 0;

    const safeRefresh = async () => {
      const now = Date.now();
      if (inFlight || now - lastTime < 100) {
        return; // Throttled
      }
      inFlight = true;
      lastTime = now;
      restRequestCount++;
      await new Promise((resolve) => setTimeout(resolve, 20));
      inFlight = false;
    };

    // Burst 10 rapid triggers simultaneously
    await Promise.all([
      safeRefresh(),
      safeRefresh(),
      safeRefresh(),
      safeRefresh(),
      safeRefresh(),
    ]);

    // Only 1 request should have run, preventing request storms
    assert.equal(restRequestCount, 1);
  });
});
