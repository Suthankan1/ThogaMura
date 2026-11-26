import type { Product, StockRequestStatus, SupplierAvailability } from "../types/inventory";

export type Status = StockRequestStatus | "CANCELLED";
export type Role = "OPERATOR" | "PICKER" | "SUPERVISOR";
export type Session = { name: string; role: Role; branch: string; csrf: string };
export type Request = { requestId: string; sku: string; productName: string; branchId: string; quantity: number; status: Status; createdAt: string; updatedAt: string; events?: { stage: Status | "REQUESTED"; at: string }[]; currentStageSince?: string; decisionReason: string | null };
export type Movement = { id: string; sku: string; productName?: string | null; reason: "RECEIVE" | "DISPATCH"; quantityDelta: number; occurredAt: string; branchId: string | null; recordedBy: string | null; note: string | null; stockRequestId: string | null; receivingCommandId: string | null };
export type Page<T> = { items: T[]; nextCursor: string | null };
export type Command = {
  path: string;
  body?: { sku: string; quantity: number; note?: string };
  assignment?: { sku: string; owner: string; dueDate: string };
  item?: { sku: string; quantity: number };
  label: string;
  key: string;
};
export type Read = <T>(path: string) => Promise<T>;

export const WORKSPACE_ENABLED = process.env.NEXT_PUBLIC_WORKSPACE_ENABLED === "true";
export const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export const workspaceDeadline = 15_000;

export class WorkspaceError extends Error {
  readonly status: number;
  readonly reference?: string;
  readonly code?: string;

  constructor(message: string, status: number, reference?: string, code?: string) {
    super(message);
    this.status = status;
    this.reference = reference;
    this.code = code;
  }
}

export function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export async function workspaceCall<T>(path: string, init: RequestInit = {}): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; abort(); }, workspaceDeadline);
  if (init.signal?.aborted) abort();
  else init.signal?.addEventListener("abort", abort, { once: true });
  try {
    const response = await fetch(`/api/v2${path}`, { ...init, signal: controller.signal, cache: "no-store" });
    if (response.status === 204) return undefined as T;
    const payload: unknown = await response.json();
    if (!record(payload) || !response.ok) {
      const details = record(payload) ? payload : {};
      throw new WorkspaceError(
        typeof details.message === "string" ? details.message : "The workspace could not confirm the result.",
        response.status,
        typeof details.followUpId === "string" ? details.followUpId : typeof details.requestId === "string" ? details.requestId : undefined,
        typeof details.code === "string" ? details.code : undefined,
      );
    }
    return payload as T;
  } catch (error) {
    if (error instanceof WorkspaceError || init.signal?.aborted) throw error;
    throw new WorkspaceError(timedOut ? "The gateway did not respond before the deadline." : "The browser could not confirm the gateway response.", timedOut ? 504 : 502);
  } finally {
    clearTimeout(timer);
    init.signal?.removeEventListener("abort", abort);
  }
}

export function validUnits(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 && value <= 2147483647;
}

export function validBalance(row: Record<string, unknown>) {
  return validUnits(row.onHand) && validUnits(row.reserved) && validUnits(row.available) &&
    row.onHand - row.reserved === row.available;
}

const transitions = { cancel: "CANCELLED", pick: "PICKED", dispatch: "DISPATCHED" } as const;

export function commandResult(command: Command, value: unknown): { reference?: string } {
  if (record(value)) {
    if (command.path.startsWith("/supplier-follow-ups") && value.id === (command.path.split("/")[2] ?? command.key) &&
      value.status === (command.path.endsWith("/close") ? "CLOSED" : "OPEN") &&
      (!command.assignment || (value.owner === command.assignment.owner && value.sku === command.assignment.sku && value.dueDate === command.assignment.dueDate))) return {};
    if (command.path === "/receiving" && command.body && value.status === "RECEIVED" &&
      value.sku === command.body.sku && value.quantity === command.body.quantity && validBalance(value) &&
      (value.onHand as number) >= command.body.quantity) return {};
    if (command.path === "/requests" && command.body && value.status === "ACCEPTED" &&
      value.requestId === command.key && value.sku === command.body.sku && value.quantity === command.body.quantity) {
      return { reference: command.key };
    }
    const match = /^\/requests\/([^/]+)\/(cancel|pick|dispatch)$/.exec(command.path);
    if (match && value.stockRequestId === match[1] && value.status === transitions[match[2] as keyof typeof transitions]) {
      return { reference: match[1] };
    }
  }
  throw new WorkspaceError("The gateway returned an incomplete or mismatched confirmation.", 502);
}

export function definitiveCommandFailure(error: WorkspaceError) {
  return (error.status === 400 && error.code === "VALIDATION_ERROR") ||
    (error.status === 403 && error.code === "FORBIDDEN") ||
    (error.status === 404 && error.code === "NOT_FOUND") ||
    (error.status === 409 && ["FOLLOWUP_OPEN", "INSUFFICIENT_STOCK", "INVALID_TRANSITION", "DUPLICATE_ID_CONFLICT"].includes(error.code ?? ""));
}

export async function workspacePage<T>(read: Read, path: string): Promise<Page<T>> {
  const page = await read<Page<T>>(path);
  if (!record(page) || !Array.isArray(page.items) || !page.items.every(record) ||
    (page.nextCursor !== null && (typeof page.nextCursor !== "string" || !page.nextCursor))) {
    throw new WorkspaceError("The warehouse page could not be verified.", 502);
  }
  return page;
}

export async function workspaceRequest(read: Read, id: string): Promise<Request> {
  const row = await read<Request>(`/requests/${encodeURIComponent(id)}`);
  if (!record(row) || row.requestId !== id || !validUnits(row.quantity) || !row.quantity ||
    typeof row.sku !== "string" || typeof row.branchId !== "string" ||
    !["ACCEPTED", "PICKED", "DISPATCHED", "REJECTED", "CANCELLED"].includes(row.status)) {
    throw new WorkspaceError("The warehouse request could not be verified.", 502);
  }
  return row;
}

export async function exactProduct(read: Read, sku: string): Promise<Product | null> {
  if (!sku) return null;
  const data = await workspacePage<Product>(read, `/products?limit=1&sku=${encodeURIComponent(sku)}`);
  const item = data.items.find((row) => row.sku === sku);
  if (item && (!record(item) || !validBalance(item) || typeof item.name !== "string")) {
    throw new WorkspaceError("The product balance could not be verified.", 502);
  }
  return item ?? null;
}

export function classifyWorkspaceSupplierError(error: unknown): WorkspaceError {
  if (error instanceof WorkspaceError) {
    if (error.status === 404) {
      return new WorkspaceError("Supplier has no catalog listing for this SKU.", 404, error.reference, "SUPPLIER_UNKNOWN");
    }
    if (error.status === 504) {
      return new WorkspaceError("Supplier inquiry timed out before confirming stock.", 504, error.reference, "SUPPLIER_TIMEOUT");
    }
    if (error.status === 503) {
      return new WorkspaceError("Supplier service is temporarily unavailable.", 503, error.reference, "SUPPLIER_UNAVAILABLE");
    }
    return error;
  }
  return new WorkspaceError(error instanceof Error ? error.message : "The supplier response could not be verified.", 502);
}

export async function workspaceSupplier(read: Read, sku: string) {
  let result: unknown;
  try {
    result = await read<SupplierAvailability>(`/suppliers/availability?sku=${encodeURIComponent(sku)}`);
  } catch (error) {
    throw classifyWorkspaceSupplierError(error);
  }
  if (!record(result) || result.sku !== sku || result.status !== "AVAILABLE" || !validUnits(result.reportedQuantity) ||
    typeof result.supplier !== "string" || !result.supplier || typeof result.source !== "string" || !result.source ||
    typeof result.checkedAt !== "string" || !Number.isFinite(Date.parse(result.checkedAt))) {
    throw new WorkspaceError("The supplier response could not be verified.", 502);
  }
  return {
    sku, supplier: result.supplier, source: result.source, checkedAt: result.checkedAt,
    reportedQuantity: result.reportedQuantity,
    estimatedRestockDays: validUnits(result.estimatedRestockDays) ? result.estimatedRestockDays : undefined,
  };
}
