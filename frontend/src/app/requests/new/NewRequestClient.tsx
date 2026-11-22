"use client";

import { useState, useEffect, useId, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { createStockRequest, fetchProducts, ApiError } from "@/lib/api/inventory";
import type { StockRequest, Product } from "@/lib/types/inventory";
import { formatOperationalTime } from "@/lib/formatters";

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
  const skuHelpId = useId();
  const skuErrorId = useId();
  const qtyId = useId();
  const qtyHelpId = useId();
  const qtyErrorId = useId();

  const [sku, setSku] = useState(() => searchParams.get("sku") || "");
  const [quantity, setQuantity] = useState<number | "">("");
  const [idempotencyKey, setIdempotencyKey] = useState(() => generateUuidV4());
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<{ code: string; message: string; isRetryable?: boolean } | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ sku?: string; quantity?: string }>({});
  const [successResult, setSuccessResult] = useState<StockRequest | null>(null);

  // Available catalog products for picker guidance (QA-07)
  const [catalog, setCatalog] = useState<Product[]>([]);
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogFetchedAt, setCatalogFetchedAt] = useState<Date | null>(null);

  useEffect(() => {
    let ignore = false;
    async function loadCatalog() {
      try {
        const products = await fetchProducts();
        if (!ignore) {
          setCatalog(products);
          setCatalogFetchedAt(new Date());
        }
      } catch {
        // Fallback silently; user can still type SKU manually
      } finally {
        if (!ignore) setCatalogLoading(false);
      }
    }
    void loadCatalog();
    return () => {
      ignore = true;
    };
  }, []);

  // Matching product details if SKU matches catalog
  const matchingProduct = catalog.find(
    (p) => p.sku.toLowerCase() === sku.trim().toLowerCase()
  );

  async function executeSubmit(targetKey: string) {
    const nextFieldErrors: { sku?: string; quantity?: string } = {};

    if (!sku.trim()) {
      nextFieldErrors.sku = "Please enter or select a valid product SKU.";
    }

    const qty = typeof quantity === "number" ? quantity : parseInt(String(quantity), 10);
    if (isNaN(qty) || qty <= 0) {
      nextFieldErrors.quantity = "Quantity must be a positive integer greater than zero.";
    }

    if (Object.keys(nextFieldErrors).length > 0) {
      setFieldErrors(nextFieldErrors);
      setError({
        code: "VALIDATION_ERROR",
        message: "Please correct the highlighted form errors before submitting.",
        isRetryable: false,
      });
      return;
    }

    setFieldErrors({});
    setSubmitting(true);
    setError(null);
    setSuccessResult(null);

    try {
      const result = await createStockRequest(sku.trim(), qty, targetKey);
      setSuccessResult(result);
    } catch (err) {
      if (err instanceof ApiError) {
        const isConflictOrTimeout =
          err.status === 504 ||
          err.status === 503 ||
          err.code === "GATEWAY_TIMEOUT" ||
          err.code === "WAREHOUSE_TIMEOUT" ||
          err.code === "SERVER_UNAVAILABLE";

        setError({
          code: err.code,
          message: err.message,
          isRetryable: isConflictOrTimeout,
        });
      } else {
        setError({
          code: "REQUEST_FAILED",
          message: "Network failure submitting stock request. Idempotency key is preserved for safe retry.",
          isRetryable: true,
        });
      }
    } finally {
      setSubmitting(false);
    }
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    void executeSubmit(idempotencyKey);
  }

  function handleRetrySameRequest() {
    void executeSubmit(idempotencyKey);
  }

  function handleReset() {
    setSku("");
    setQuantity("");
    setError(null);
    setFieldErrors({});
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
          aria-live="polite"
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
              href="/warehouse"
              className="rounded-lg border border-emerald-300 bg-white px-4 py-2 text-xs font-semibold text-emerald-900 hover:bg-emerald-50"
            >
              Open in Warehouse Queue →
            </Link>
          </div>
        </div>
      )}

      {/* Error Summary & Safe Retry (QA-08) */}
      {error && (
        <div
          role="alert"
          aria-live="assertive"
          className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-rose-900 space-y-3"
        >
          <div className="flex items-center justify-between">
            <h2 className="font-bold flex items-center gap-2">
              <span className="text-rose-600 font-bold" aria-hidden="true">⚠</span>
              Reservation Rejection ({error.code})
            </h2>
            <span className="text-xs font-mono text-rose-700">HTTP Status</span>
          </div>
          <p className="text-sm text-rose-800">{error.message}</p>

          {/* Sourcing advisory link on insufficient stock */}
          {error.code === "INSUFFICIENT_STOCK" && (
            <div className="pt-1">
              <Link
                href={`/suppliers?sku=${encodeURIComponent(sku)}`}
                className="inline-flex items-center gap-1.5 text-xs font-semibold text-teal-800 underline hover:text-teal-950"
              >
                Inquire external supplier availability for {sku} →
              </Link>
            </div>
          )}

          {/* QA-08: Safe retry keeping same key & payload for timeouts / uncertain results */}
          {error.isRetryable && (
            <div className="pt-2 flex items-center gap-3 border-t border-rose-200">
              <button
                type="button"
                onClick={handleRetrySameRequest}
                disabled={submitting}
                className="inline-flex items-center gap-1.5 rounded-lg bg-rose-700 px-3.5 py-1.5 text-xs font-semibold text-white shadow-xs hover:bg-rose-800 focus:outline-hidden focus:ring-2 focus:ring-rose-600 disabled:opacity-50"
              >
                Retry same request (Preserve Idempotency Key)
              </button>
              <span className="text-xs text-rose-700">
                Safe to retry: Replays original canonical key without duplicate stock reservation.
              </span>
            </div>
          )}
        </div>
      )}

      {/* Reservation Form */}
      <form
        onSubmit={handleSubmit}
        className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-6"
        noValidate
      >
        <div className="space-y-5">
          {/* SKU Entry & Searchable Product Guidance (QA-07 & QA-08) */}
          <div>
            <div className="flex items-center justify-between">
              <label htmlFor={skuId} className="block text-sm font-semibold text-slate-900">
                Product SKU <span className="text-rose-600">*</span>
              </label>
              {catalog.length > 0 && (
                <span className="text-xs text-slate-500">
                  {catalog.length} catalog items available
                </span>
              )}
            </div>

            {/* Searchable / quick product selector (QA-07) */}
            {catalog.length > 0 && (
              <div className="mt-1.5 mb-2">
                <label htmlFor="catalog-picker" className="sr-only">
                  Select product from warehouse catalog
                </label>
                <select
                  id="catalog-picker"
                  value={catalog.some((p) => p.sku === sku) ? sku : ""}
                  onChange={(e) => {
                    if (e.target.value) setSku(e.target.value);
                  }}
                  className="block w-full rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-xs text-slate-700 focus:border-teal-600 focus:bg-white focus:outline-hidden focus:ring-1 focus:ring-teal-600"
                >
                  <option value="">-- Choose from warehouse catalog (optional) --</option>
                  {catalog.map((p) => (
                    <option key={p.sku} value={p.sku}>
                      {p.name} ({p.sku}) — Available: {p.available} units
                    </option>
                  ))}
                </select>
              </div>
            )}

            <input
              id={skuId}
              type="text"
              required
              value={sku}
              onChange={(e) => {
                setSku(e.target.value);
                if (fieldErrors.sku) setFieldErrors((prev) => ({ ...prev, sku: undefined }));
              }}
              placeholder="e.g. ITEM-007"
              aria-describedby={`${skuHelpId} ${fieldErrors.sku ? skuErrorId : ""}`}
              aria-invalid={!!fieldErrors.sku}
              className={`mt-1 block w-full rounded-lg border px-3.5 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600 font-mono ${
                fieldErrors.sku ? "border-rose-500 bg-rose-50/30" : "border-slate-300"
              }`}
            />

            {fieldErrors.sku && (
              <p id={skuErrorId} role="alert" className="mt-1 text-xs font-semibold text-rose-600">
                {fieldErrors.sku}
              </p>
            )}

            {/* Matching Product Information Guidance (QA-07) */}
            {matchingProduct && (
              <div className="mt-2 flex items-center justify-between rounded-lg bg-teal-50/70 border border-teal-200 px-3 py-1.5 text-xs text-teal-900">
                <div>
                  <strong className="font-semibold">{matchingProduct.name}</strong>
                  <span className="ml-2 font-mono text-teal-800">
                    · Available: <strong>{matchingProduct.available} units</strong> (On hand: {matchingProduct.onHand}, Reserved: {matchingProduct.reserved})
                  </span>
                </div>
                {catalogFetchedAt && (
                  <span className="text-[11px] text-teal-700">
                    Fetched {formatOperationalTime(catalogFetchedAt)}
                  </span>
                )}
              </div>
            )}

            <div className="mt-2 flex flex-wrap items-center gap-1.5 text-xs text-slate-500">
              <span>Demo Quick-Fill:</span>
              <button
                type="button"
                onClick={() => setSku("ITEM-007")}
                className="rounded bg-slate-100 px-2 py-0.5 font-mono text-teal-800 hover:bg-slate-200"
              >
                ITEM-007 (Carrots - Stocked)
              </button>
              <button
                type="button"
                onClick={() => setSku("ITEM-009")}
                className="rounded bg-slate-100 px-2 py-0.5 font-mono text-amber-800 hover:bg-slate-200"
              >
                ITEM-009 (Potatoes - Low Stock)
              </button>
              <button
                type="button"
                onClick={() => setSku("ITEM-008")}
                className="rounded bg-slate-100 px-2 py-0.5 font-mono text-rose-800 hover:bg-slate-200"
              >
                ITEM-008 (Onions - Out of Stock)
              </button>
            </div>
            <p id={skuHelpId} className="mt-1 text-xs text-slate-500">
              Exact item identifier in warehouse database.
            </p>
          </div>

          {/* Quantity Entry (QA-08: Associated DOM help and errors) */}
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
              onChange={(e) => {
                setQuantity(e.target.value === "" ? "" : parseInt(e.target.value, 10));
                if (fieldErrors.quantity) setFieldErrors((prev) => ({ ...prev, quantity: undefined }));
              }}
              placeholder="e.g. 5"
              aria-describedby={`${qtyHelpId} ${fieldErrors.quantity ? qtyErrorId : ""}`}
              aria-invalid={!!fieldErrors.quantity}
              className={`mt-1.5 block w-full rounded-lg border px-3.5 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600 ${
                fieldErrors.quantity ? "border-rose-500 bg-rose-50/30" : "border-slate-300"
              }`}
            />
            {fieldErrors.quantity && (
              <p id={qtyErrorId} role="alert" className="mt-1 text-xs font-semibold text-rose-600">
                {fieldErrors.quantity}
              </p>
            )}
            <p id={qtyHelpId} className="mt-1 text-xs text-slate-500">
              Positive integer units. Atomically checked against available stock (on_hand - reserved).
            </p>
          </div>

          {/* Expandable RFC 4122 v4 Idempotency Key Details (QA-08) */}
          <details className="rounded-lg bg-slate-50 border border-slate-200 p-3.5 text-xs group">
            <summary className="font-semibold text-slate-700 cursor-pointer select-none hover:text-teal-700 flex items-center justify-between">
              <span>Advanced: Idempotency Key &amp; Replay Safety</span>
              <span className="text-slate-400 group-open:rotate-180 transition-transform">▼</span>
            </summary>
            <div className="mt-3 space-y-2 pt-2 border-t border-slate-200 text-slate-600">
              <div className="flex items-center justify-between">
                <span className="font-medium text-slate-700">Active Request Key:</span>
                <button
                  type="button"
                  onClick={() => setIdempotencyKey(generateUuidV4())}
                  className="text-[11px] font-semibold text-teal-700 hover:text-teal-800 underline"
                >
                  Generate New Key (Intentional New Request)
                </button>
              </div>
              <code className="block font-mono bg-white p-2 rounded border border-slate-200 text-slate-800 break-all select-all">
                {idempotencyKey}
              </code>
              <p className="text-[11px] text-slate-500">
                RFC 4122 v4 key preserved across retries. When submitting after a network timeout or uncertain result, using the same key guarantees PostgreSQL re-returns the recorded decision rather than reserving stock a second time.
              </p>
            </div>
          </details>
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
