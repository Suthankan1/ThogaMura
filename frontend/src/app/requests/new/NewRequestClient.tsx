"use client";

import { useState, useId, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { createStockRequest, ApiError } from "@/lib/api/inventory";
import type { StockRequest } from "@/lib/types/inventory";

function generateUuidV4(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (c) =>
    (
      +c ^
      (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (+c / 4)))
    ).toString(16)
  );
}

export function NewRequestClient() {
  const searchParams = useSearchParams();

  const skuId = useId();
  const qtyId = useId();

  const [sku, setSku] = useState(() => searchParams.get("sku") || "");
  const [quantity, setQuantity] = useState<number | "">("");
  const [idempotencyKey, setIdempotencyKey] = useState(() => generateUuidV4());
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const [successResult, setSuccessResult] = useState<StockRequest | null>(null);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!sku.trim()) {
      setError({ code: "VALIDATION_ERROR", message: "Please enter a valid product SKU." });
      return;
    }

    const qty = typeof quantity === "number" ? quantity : parseInt(String(quantity), 10);
    if (isNaN(qty) || qty <= 0) {
      setError({ code: "VALIDATION_ERROR", message: "Quantity must be a positive integer greater than zero." });
      return;
    }

    setSubmitting(true);
    setError(null);
    setSuccessResult(null);

    try {
      const result = await createStockRequest(sku.trim(), qty, idempotencyKey);
      setSuccessResult(result);
    } catch (err) {
      if (err instanceof ApiError) {
        setError({ code: err.code, message: err.message });
      } else {
        setError({
          code: "REQUEST_FAILED",
          message: "Network failure submitting stock request. Idempotency key is preserved for safe retry.",
        });
      }
    } finally {
      setSubmitting(false);
    }
  }

  function handleReset() {
    setSku("");
    setQuantity("");
    setError(null);
    setSuccessResult(null);
    setIdempotencyKey(generateUuidV4());
  }

  return (
    <div className="mx-auto max-w-2xl space-y-8">
      <div>
        <p className="text-xs font-semibold uppercase tracking-wider text-teal-700">
          Transactional Stock Reservation
        </p>
        <h1 className="mt-1 text-3xl font-bold tracking-tight text-slate-900 sm:text-4xl">
          New Stock Request
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          Submit an atomic reservation request through the Spring Boot Gateway over Java TCP/TLS to PostgreSQL.
        </p>
      </div>

      {/* Success Notification */}
      {successResult && (
        <div
          role="status"
          className="rounded-xl border border-emerald-200 bg-emerald-50 p-6 shadow-xs text-emerald-900 space-y-4"
        >
          <div className="flex items-center gap-3">
            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-emerald-600 text-white font-bold text-sm">
              ✓
            </span>
            <div>
              <h2 className="text-lg font-bold">Stock Reservation Accepted</h2>
              <p className="text-xs text-emerald-700">Authoritative balance confirmed in PostgreSQL.</p>
            </div>
          </div>

          <dl className="grid grid-cols-2 gap-4 rounded-lg bg-white/70 p-4 text-xs">
            <div>
              <dt className="text-slate-500">Request ID (Canonical UUID v4):</dt>
              <dd className="font-mono font-semibold text-slate-900 break-all">
                {successResult.requestId}
              </dd>
            </div>
            <div>
              <dt className="text-slate-500">Confirmed Status:</dt>
              <dd className="font-semibold text-emerald-700">{successResult.status}</dd>
            </div>
            <div>
              <dt className="text-slate-500">SKU:</dt>
              <dd className="font-mono font-semibold text-slate-900">{successResult.sku}</dd>
            </div>
            <div>
              <dt className="text-slate-500">Reserved Units:</dt>
              <dd className="font-mono font-semibold text-slate-900">{successResult.quantity}</dd>
            </div>
          </dl>

          <div className="flex items-center gap-3 pt-2">
            <button
              type="button"
              onClick={handleReset}
              className="rounded-lg bg-emerald-700 px-4 py-2 text-xs font-semibold text-white hover:bg-emerald-800"
            >
              Submit Another Request
            </button>
            <Link
              href="/requests"
              className="rounded-lg border border-emerald-300 bg-white px-4 py-2 text-xs font-semibold text-emerald-900 hover:bg-emerald-50"
            >
              View Requests Queue
            </Link>
          </div>
        </div>
      )}

      {/* Error Summary */}
      {error && (
        <div
          role="alert"
          aria-live="assertive"
          className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-rose-900 space-y-2"
        >
          <div className="flex items-center justify-between">
            <h2 className="font-bold flex items-center gap-2">
              <span className="text-rose-600 font-bold">⚠</span>
              Reservation Rejection ({error.code})
            </h2>
            <span className="text-xs font-mono text-rose-700">HTTP Error</span>
          </div>
          <p className="text-sm text-rose-800">{error.message}</p>
          {error.code === "INSUFFICIENT_STOCK" && (
            <div className="pt-2">
              <Link
                href={`/suppliers?sku=${encodeURIComponent(sku)}`}
                className="inline-flex items-center gap-1.5 text-xs font-semibold text-rose-900 underline hover:text-rose-950"
              >
                Inquire external supplier availability for {sku} →
              </Link>
            </div>
          )}
        </div>
      )}

      {/* Reservation Form */}
      <form
        onSubmit={handleSubmit}
        className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-6"
      >
        <div className="space-y-4">
          <div>
            <label htmlFor={skuId} className="block text-sm font-semibold text-slate-900">
              Product SKU <span className="text-rose-600">*</span>
            </label>
            <input
              id={skuId}
              type="text"
              required
              value={sku}
              onChange={(e) => setSku(e.target.value)}
              placeholder="e.g. ITEM-007"
              className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3.5 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600 font-mono"
            />
            <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs text-slate-500">
              <span>Quick Fill:</span>
              <button
                type="button"
                onClick={() => setSku("ITEM-007")}
                className="rounded bg-slate-100 px-2 py-0.5 font-mono text-teal-800 hover:bg-slate-200"
              >
                ITEM-007 (Carrots)
              </button>
              <button
                type="button"
                onClick={() => setSku("ITEM-008")}
                className="rounded bg-slate-100 px-2 py-0.5 font-mono text-teal-800 hover:bg-slate-200"
              >
                ITEM-008 (Onions)
              </button>
              <button
                type="button"
                onClick={() => setSku("ITEM-009")}
                className="rounded bg-slate-100 px-2 py-0.5 font-mono text-teal-800 hover:bg-slate-200"
              >
                ITEM-009 (Potatoes)
              </button>
            </div>
            <p className="mt-1 text-xs text-slate-500">
              Exact item identifier in warehouse database.
            </p>
          </div>

          <div>
            <label htmlFor={qtyId} className="block text-sm font-semibold text-slate-900">
              Quantity to Reserve <span className="text-rose-600">*</span>
            </label>
            <input
              id={qtyId}
              type="number"
              min={1}
              required
              value={quantity}
              onChange={(e) =>
                setQuantity(e.target.value === "" ? "" : parseInt(e.target.value, 10))
              }
              placeholder="e.g. 5"
              className="mt-1.5 block w-full rounded-lg border border-slate-300 px-3.5 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600"
            />
            <p className="mt-1 text-xs text-slate-500">
              Positive integer units. Atomically checked against available stock (on_hand - reserved).
            </p>
          </div>

          <div className="rounded-lg bg-slate-50 p-3.5 border border-slate-100 text-xs space-y-1">
            <div className="flex items-center justify-between">
              <span className="font-semibold text-slate-700">
                Durable Idempotency Key (RFC 4122 v4):
              </span>
              <button
                type="button"
                onClick={() => setIdempotencyKey(generateUuidV4())}
                className="text-[11px] font-semibold text-teal-700 hover:text-teal-800 underline"
              >
                Generate New Key
              </button>
            </div>
            <code className="block font-mono text-slate-600 break-all select-all">
              {idempotencyKey}
            </code>
            <p className="text-slate-500 text-[11px] pt-1">
              Preserved across retries so duplicate submissions cannot reserve stock twice. Re-submitting with the same key safely replays the cached reservation decision.
            </p>
          </div>
        </div>

        <div className="flex items-center justify-between border-t border-slate-100 pt-5">
          <Link
            href="/products"
            className="text-sm font-medium text-slate-600 hover:text-slate-900"
          >
            ← Back to Products
          </Link>
          <button
            type="submit"
            disabled={submitting}
            className="inline-flex items-center gap-2 rounded-lg bg-teal-700 px-5 py-2.5 text-sm font-semibold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 focus:ring-offset-2 disabled:opacity-50"
          >
            {submitting && (
              <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                <circle
                  className="opacity-25"
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="currentColor"
                  strokeWidth="4"
                />
                <path
                  className="opacity-75"
                  fill="currentColor"
                  d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                />
              </svg>
            )}
            {submitting ? "Reserving Stock..." : "Submit Reservation"}
          </button>
        </div>
      </form>
    </div>
  );
}
