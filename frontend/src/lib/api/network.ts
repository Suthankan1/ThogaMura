import type { NetworkStatusResponse } from "../types/network";

const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL ||
  process.env.NEXT_PUBLIC_API_BASE_URL ||
  (typeof window !== "undefined" ? window.location.origin : "http://localhost:8080");

/**
 * Fetches the authoritative gateway network status from GET /api/v1/network-status.
 * Returns null if the gateway is unreachable.
 */
export async function fetchNetworkStatus(): Promise<NetworkStatusResponse | null> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 7000);
  try {
    const res = await fetch(`${GATEWAY_URL}/api/v1/network-status`, {
      signal: controller.signal,
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    if (!res.ok) return null;
    return await res.json() as NetworkStatusResponse;
  } catch {
    return null;
  } finally {
    clearTimeout(timeoutId);
  }
}

export interface PingResult {
  status: string;
  requestId?: string;
  serverTime?: string;
  latencyMs: number;
}

export async function pingGateway(): Promise<PingResult> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 7000);
  const started = performance.now();
  try {
    const res = await fetch(`${GATEWAY_URL}/api/v1/ping`, {
      signal: controller.signal,
      headers: { Accept: "application/json" },
      cache: "no-store",
    });
    const latencyMs = Math.round(performance.now() - started);
    if (!res.ok) {
      throw new Error(`Warehouse PING failed (${res.status})`);
    }
    const body = (await res.json()) as { status?: string; requestId?: string; serverTime?: string };
    return {
      status: body.status ?? "PONG",
      requestId: body.requestId,
      serverTime: body.serverTime,
      latencyMs,
    };
  } finally {
    clearTimeout(timeoutId);
  }
}
