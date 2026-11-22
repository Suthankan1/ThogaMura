import type { Product, StockRequest, StockMovement, SupplierAvailability, SupplierStatus } from "../types/inventory";

const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL ||
  process.env.NEXT_PUBLIC_API_BASE_URL ||
  (typeof window !== "undefined" ? window.location.origin : "http://localhost:8080");

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;

  constructor(status: number, code: string, message: string, requestId?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.requestId = requestId;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isText(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function isNonnegativeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0;
}

function badResponse() {
  return new ApiError(502, "INVALID_RESPONSE", "Gateway returned an incomplete warehouse response.");
}

async function warehouseJson(path: string, init?: RequestInit): Promise<unknown> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(`${GATEWAY_URL}/api/v1${path}`, {
      ...init,
      headers: { Accept: "application/json", ...init?.headers },
      signal: controller.signal,
      cache: "no-store",
    });
    const payload: unknown = await response.json().catch(() => null);
    if (!response.ok) {
      const details = isRecord(payload) ? payload : {};
      throw new ApiError(
        response.status,
        isText(details.code) ? details.code : "GATEWAY_ERROR",
        isText(details.message) ? details.message : `Gateway request failed (HTTP ${response.status}).`,
        isText(details.requestId) ? details.requestId : undefined,
      );
    }
    if (payload === null) throw badResponse();
    return payload;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (controller.signal.aborted) {
      throw new ApiError(504, "GATEWAY_TIMEOUT", "Gateway did not confirm the outcome before the deadline.");
    }
    throw new ApiError(503, "GATEWAY_UNREACHABLE", "The browser could not reach the gateway.");
  } finally {
    clearTimeout(timeout);
  }
}

function rows<T>(value: unknown, isRow: (item: unknown) => item is T): T[] {
  if (!Array.isArray(value) || !value.every(isRow)) throw badResponse();
  return value;
}

function isProduct(row: unknown): row is Product {
  return isRecord(row) && isText(row.sku) && isText(row.name)
    && isNonnegativeInteger(row.onHand) && isNonnegativeInteger(row.reserved)
    && isNonnegativeInteger(row.available) && row.onHand - row.reserved === row.available;
}

function isStockRequest(row: unknown): row is StockRequest {
  return isRecord(row) && isText(row.requestId) && isText(row.sku)
    && isNonnegativeInteger(row.quantity) && row.quantity > 0
    && (row.status === "ACCEPTED" || row.status === "REJECTED" || row.status === "PICKED" || row.status === "DISPATCHED")
    && (row.createdAt === undefined || isText(row.createdAt));
}

function isMovement(row: unknown): row is StockMovement {
  return isRecord(row) && isNonnegativeInteger(row.id) && isText(row.stockRequestId)
    && isText(row.sku) && isText(row.reason) && isText(row.occurredAt)
    && typeof row.quantityDelta === "number" && Number.isSafeInteger(row.quantityDelta);
}

export async function fetchProducts(): Promise<Product[]> {
  return rows(await warehouseJson("/products"), isProduct);
}

export async function createStockRequest(sku: string, quantity: number, idempotencyKey: string): Promise<StockRequest> {
  const value = await warehouseJson("/requests", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": idempotencyKey },
    body: JSON.stringify({ sku, quantity }),
  });
  if (!isStockRequest(value) || value.status !== "ACCEPTED" ||
      value.requestId !== idempotencyKey || value.sku !== sku || value.quantity !== quantity) {
    throw badResponse();
  }
  return value;
}

export async function fetchStockRequests(statusFilter?: string): Promise<StockRequest[]> {
  const query = statusFilter && statusFilter !== "ALL" ? `?status=${encodeURIComponent(statusFilter)}` : "";
  return rows(await warehouseJson(`/requests${query}`), isStockRequest);
}

async function transition(stockRequestId: string, action: "pick" | "dispatch", expected: "PICKED" | "DISPATCHED") {
  const value = await warehouseJson(`/requests/${encodeURIComponent(stockRequestId)}/${action}`, { method: "POST" });
  if (!isRecord(value) || value.stockRequestId !== stockRequestId || value.status !== expected) throw badResponse();
  return { stockRequestId, status: expected };
}

export function pickStockRequest(stockRequestId: string) {
  return transition(stockRequestId, "pick", "PICKED");
}

export function dispatchStockRequest(stockRequestId: string) {
  return transition(stockRequestId, "dispatch", "DISPATCHED");
}

export async function fetchStockMovements(): Promise<StockMovement[]> {
  return rows(await warehouseJson("/movements"), isMovement);
}

const supplierErrorStatuses: Record<string, SupplierStatus> = {
  NOT_FOUND: "UNKNOWN",
  SKU_NOT_FOUND: "UNKNOWN",
  SUPPLIER_TIMEOUT: "SUPPLIER_TIMEOUT",
  SUPPLIER_UNAVAILABLE: "SUPPLIER_UNAVAILABLE",
  SUPPLIER_UNKNOWN: "INVALID_SUPPLIER_RESPONSE",
  WAREHOUSE_TIMEOUT: "WAREHOUSE_TIMEOUT",
  WAREHOUSE_UNAVAILABLE: "WAREHOUSE_UNAVAILABLE",
  VALIDATION_ERROR: "INVALID_REQUEST",
  GATEWAY_UNREACHABLE: "GATEWAY_UNAVAILABLE",
  GATEWAY_TIMEOUT: "GATEWAY_TIMEOUT",
  INVALID_RESPONSE: "INVALID_SUPPLIER_RESPONSE",
};

export async function fetchSupplierAvailability(sku: string): Promise<SupplierAvailability> {
  try {
    const value = await warehouseJson(`/suppliers/availability?sku=${encodeURIComponent(sku)}`);
    if (!isRecord(value) || value.status !== "AVAILABLE" || value.sku !== sku ||
        !isNonnegativeInteger(value.reportedQuantity) || !isText(value.supplier) ||
        !isText(value.checkedAt) || !Number.isFinite(Date.parse(value.checkedAt)) || !isText(value.source)) {
      throw badResponse();
    }
    return {
      sku,
      status: "AVAILABLE",
      supplier: value.supplier,
      source: value.source,
      checkedAt: value.checkedAt,
      reportedQuantity: value.reportedQuantity,
      estimatedRestockDays: isNonnegativeInteger(value.estimatedRestockDays) ? value.estimatedRestockDays : undefined,
    };
  } catch (error) {
    if (error instanceof ApiError) {
      return {
        sku,
        status: supplierErrorStatuses[error.code] ?? "INQUIRY_FAILED",
        message: error.message,
      };
    }
    throw error;
  }
}
