export interface ReservationAttempt {
  sku: string;
  quantity: number;
  idempotencyKey: string;
}

const STORAGE_KEY = "thogamura.pendingReservation";
const CHANGE_EVENT = "thogamura:pending-reservation";
let fallback: string | null | undefined;

export function isSku(value: string) {
  return /^[A-Za-z0-9_-]{1,64}$/.test(value);
}

export function parseQuantity(value: string) {
  if (!/^[1-9]\d*$/.test(value)) return null;
  const quantity = Number(value);
  return quantity <= 2147483647 ? quantity : null;
}

export function parseReservationAttempt(value: string | null): ReservationAttempt | null {
  if (!value) return null;
  try {
    const attempt = JSON.parse(value) as Partial<ReservationAttempt> | null;
    if (attempt && typeof attempt.sku === "string" && isSku(attempt.sku) &&
        typeof attempt.quantity === "number" && parseQuantity(String(attempt.quantity)) !== null &&
        typeof attempt.idempotencyKey === "string" &&
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(attempt.idempotencyKey)) {
      return { sku: attempt.sku, quantity: attempt.quantity, idempotencyKey: attempt.idempotencyKey };
    }
  } catch {
    // Ignore invalid browser storage values.
  }
  return null;
}

export function getStoredAttempt() {
  if (fallback !== undefined) return fallback;
  try {
    return window.sessionStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

export function subscribeToStoredAttempt(listener: () => void) {
  window.addEventListener(CHANGE_EVENT, listener);
  return () => window.removeEventListener(CHANGE_EVENT, listener);
}

export function storeAttempt(attempt: ReservationAttempt | null) {
  const value = attempt ? JSON.stringify(attempt) : null;
  try {
    if (value === null) window.sessionStorage.removeItem(STORAGE_KEY);
    else window.sessionStorage.setItem(STORAGE_KEY, value);
    fallback = undefined;
  } catch {
    fallback = value;
  }
  window.dispatchEvent(new Event(CHANGE_EVENT));
}

export function clearAttempt(idempotencyKey: string) {
  if (parseReservationAttempt(getStoredAttempt())?.idempotencyKey === idempotencyKey) {
    storeAttempt(null);
  }
}

export function newRequestId() {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}
