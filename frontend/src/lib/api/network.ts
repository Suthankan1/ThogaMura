import type { NetworkStatusResponse } from "../types/network";

const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL ||
  (typeof window !== "undefined" ? window.location.origin : "http://localhost:8080");

/**
 * Fetches the authoritative gateway network status from GET /api/v1/network-status.
 * Returns null if the gateway is unreachable.
 */
export async function fetchNetworkStatus(): Promise<NetworkStatusResponse | null> {
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 4000);

    const res = await fetch(`${GATEWAY_URL}/api/v1/network-status`, {
      signal: controller.signal,
      headers: {
        Accept: "application/json",
      },
      cache: "no-store",
    });

    clearTimeout(timeoutId);

    if (!res.ok) {
      return null;
    }

    const data = (await res.json()) as NetworkStatusResponse;
    return data;
  } catch {
    return null;
  }
}
