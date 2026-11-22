import type {
  Product,
  StockRequest,
  StockMovement,
  SupplierAvailability,
  SupplierStatus,
  ApiErrorResponse,
} from "../types/inventory";

const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL ||
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

/**
 * Parses JSON error from gateway response or returns default error object.
 */
async function parseErrorResponse(res: Response, fallbackCode: string): Promise<ApiError> {
  try {
    const errorData = (await res.json()) as ApiErrorResponse;
    return new ApiError(
      res.status,
      errorData.code || fallbackCode,
      errorData.message || `Request failed with status ${res.status}`,
      errorData.requestId
    );
  } catch {
    return new ApiError(res.status, fallbackCode, `HTTP Error ${res.status}: ${res.statusText}`);
  }
}

/**
 * Fetches the authoritative product catalog from GET /api/v1/products.
 */
export async function fetchProducts(): Promise<Product[]> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 5000);

  try {
    const res = await fetch(`${GATEWAY_URL}/api/v1/products`, {
      signal: controller.signal,
      headers: { Accept: "application/json" },
      cache: "no-store",
    });

    if (!res.ok) {
      throw await parseErrorResponse(res, "PRODUCTS_FETCH_FAILED");
    }

    return (await res.json()) as Product[];
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new ApiError(503, "GATEWAY_UNREACHABLE", "Cannot reach API Gateway service");
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Submits a stock reservation request with a durable canonical UUID v4 idempotency key.
 */
export async function createStockRequest(
  sku: string,
  quantity: number,
  idempotencyKey: string
): Promise<StockRequest> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 8000);

  try {
    const res = await fetch(`${GATEWAY_URL}/api/v1/requests`, {
      method: "POST",
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        "Idempotency-Key": idempotencyKey,
      },
      body: JSON.stringify({ sku, quantity }),
    });

    if (!res.ok) {
      throw await parseErrorResponse(res, "REQUEST_SUBMISSION_FAILED");
    }

    return (await res.json()) as StockRequest;
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new ApiError(503, "GATEWAY_UNREACHABLE", "Cannot reach API Gateway service", idempotencyKey);
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Fetches all stock requests with optional status filtering.
 */
export async function fetchStockRequests(statusFilter?: string): Promise<StockRequest[]> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 5000);

  try {
    const url = new URL(`${GATEWAY_URL}/api/v1/requests`);
    if (statusFilter && statusFilter !== "ALL") {
      url.searchParams.set("status", statusFilter);
    }

    const res = await fetch(url.toString(), {
      signal: controller.signal,
      headers: { Accept: "application/json" },
      cache: "no-store",
    });

    if (!res.ok) {
      throw await parseErrorResponse(res, "REQUESTS_FETCH_FAILED");
    }

    return (await res.json()) as StockRequest[];
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new ApiError(503, "GATEWAY_UNREACHABLE", "Cannot reach API Gateway service");
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Marks an ACCEPTED request as PICKED.
 */
export async function pickStockRequest(
  stockRequestId: string
): Promise<{ stockRequestId: string; status: string }> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 6000);

  try {
    const res = await fetch(`${GATEWAY_URL}/api/v1/requests/${stockRequestId}/pick`, {
      method: "POST",
      signal: controller.signal,
      headers: { Accept: "application/json" },
    });

    if (!res.ok) {
      throw await parseErrorResponse(res, "PICK_FAILED");
    }

    return (await res.json()) as { stockRequestId: string; status: string };
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new ApiError(503, "GATEWAY_UNREACHABLE", "Cannot reach API Gateway service");
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Marks a PICKED request as DISPATCHED.
 */
export async function dispatchStockRequest(
  stockRequestId: string
): Promise<{ stockRequestId: string; status: string }> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 6000);

  try {
    const res = await fetch(`${GATEWAY_URL}/api/v1/requests/${stockRequestId}/dispatch`, {
      method: "POST",
      signal: controller.signal,
      headers: { Accept: "application/json" },
    });

    if (!res.ok) {
      throw await parseErrorResponse(res, "DISPATCH_FAILED");
    }

    return (await res.json()) as { stockRequestId: string; status: string };
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new ApiError(503, "GATEWAY_UNREACHABLE", "Cannot reach API Gateway service");
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Fetches historical dispatch movements.
 */
export async function fetchStockMovements(): Promise<StockMovement[]> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 5000);

  try {
    const res = await fetch(`${GATEWAY_URL}/api/v1/movements`, {
      signal: controller.signal,
      headers: { Accept: "application/json" },
      cache: "no-store",
    });

    if (!res.ok) {
      throw await parseErrorResponse(res, "MOVEMENTS_FETCH_FAILED");
    }

    return (await res.json()) as StockMovement[];
  } catch (err) {
    if (err instanceof ApiError) throw err;
    throw new ApiError(503, "GATEWAY_UNREACHABLE", "Cannot reach API Gateway service");
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Advisory supplier stock inquiry via GET /api/v1/suppliers/availability?sku=...
 */
export async function fetchSupplierAvailability(sku: string): Promise<SupplierAvailability> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 8000);

  try {
    const url = new URL(`${GATEWAY_URL}/api/v1/suppliers/availability`);
    url.searchParams.set("sku", sku);

    const res = await fetch(url.toString(), {
      signal: controller.signal,
      headers: { Accept: "application/json" },
      cache: "no-store",
    });

    if (!res.ok) {
      const errorData = (await res.json().catch(() => ({}))) as ApiErrorResponse;
      let status: SupplierStatus = "SUPPLIER_UNAVAILABLE";
      let message = errorData.message;

      if (res.status === 404 || errorData.code === "NOT_FOUND" || errorData.code === "SKU_NOT_FOUND") {
        status = "UNKNOWN";
        message = message || "SKU not found in supplier catalog";
      } else if (errorData.code === "WAREHOUSE_UNAVAILABLE" || (res.status === 503 && message?.toLowerCase().includes("warehouse"))) {
        status = "WAREHOUSE_UNAVAILABLE";
        message = message || "The central warehouse command server is unreachable over Java TCP.";
      } else if (res.status === 504 || errorData.code === "SUPPLIER_TIMEOUT" || errorData.code === "WAREHOUSE_TIMEOUT") {
        status = "SUPPLIER_UNAVAILABLE";
        message = "Supplier inquiry timed out. Try again.";
      } else {
        status = "SUPPLIER_UNAVAILABLE";
        message = message || "Supplier service unavailable. Try again.";
      }

      return {
        sku,
        status,
        message,
      };
    }

    return (await res.json()) as SupplierAvailability;
  } catch {
    return {
      sku,
      status: "SUPPLIER_UNAVAILABLE",
      message: "Supplier service unavailable. Try again.",
    };
  } finally {
    clearTimeout(timeoutId);
  }
}
