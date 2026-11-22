/**
 * Inventory, request, fulfillment, and supplier contract definitions.
 * Conforms to docs/contracts/HTTP_API_V1.md.
 */

export interface Product {
  sku: string;
  name: string;
  onHand: number;
  reserved: number;
  available: number;
}

export type StockRequestStatus = "ACCEPTED" | "REJECTED" | "PICKED" | "DISPATCHED";

export interface StockRequest {
  requestId: string;
  sku: string;
  quantity: number;
  status: StockRequestStatus;
  createdAt?: string;
}

export interface StockMovement {
  id: number;
  stockRequestId: string;
  sku: string;
  quantityDelta: number;
  reason: string;
  occurredAt: string;
}

export type SupplierStatus =
  | "AVAILABLE"
  | "UNAVAILABLE"
  | "UNKNOWN"
  | "SUPPLIER_UNAVAILABLE"
  | "WAREHOUSE_UNAVAILABLE"
  | "WAREHOUSE_TIMEOUT"
  | "SUPPLIER_TIMEOUT"
  | "INVALID_SUPPLIER_RESPONSE"
  | "INVALID_REQUEST"
  | "GATEWAY_UNAVAILABLE"
  | "GATEWAY_TIMEOUT"
  | "INQUIRY_FAILED";

export interface SupplierAvailability {
  sku: string;
  status: SupplierStatus;
  supplier?: string;
  reportedQuantity?: number;
  estimatedRestockDays?: number;
  source?: string;
  checkedAt?: string;
  message?: string;
}

export interface ApiErrorResponse {
  code: string;
  message: string;
  requestId?: string;
}
