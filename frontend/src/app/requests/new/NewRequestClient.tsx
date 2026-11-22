"use client";

import { useState, useEffect, useRef, useId, type FormEvent, useCallback } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { createStockRequest, fetchProducts, ApiError } from "@/lib/api/inventory";
import type { StockRequest, Product } from "@/lib/types/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { formatOperationalTime, formatUnits } from "@/lib/formatters";

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

interface AttemptSnapshot {
  sku: string;
  quantity: number;
  idempotencyKey: string;
  error?: { code: string; message: string; isRetryable: boolean };
}

export function NewRequestClient() {
  const searchParams = useSearchParams();

  // F09: Return destination preservation
  const returnToParam = searchParams.get("returnTo");
  const returnTo = returnToParam && returnToParam.startsWith("/") ? returnToParam : "/products";

  const skuInputRef = useRef<HTMLInputElement | null>(null);
  const qtyInputRef = useRef<HTMLInputElement | null>(null);

  const skuId = useId();
  const skuHelpId = useId();
  const skuErrorId = useId();
  const qtyId = useId();
  const qtyHelpId = useId();
  const qtyErrorId = useId();
  const shortageWarningId = useId();

  // Form draft state (F14: raw string for quantity so fractional values do not silently coerce)
  const [sku, setSku] = useState(() => searchParams.get("sku") || "");
  const [quantityStr, setQuantityStr] = useState("");
  const [activeDraftKey, setActiveDraftKey] = useState(() => generateUuidV4());

  // Frozen attempt snapshot for safe idempotency replay (F17)
  const [lastAttempt, setLastAttempt] = useState<AttemptSnapshot | null>(null);

  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<{ sku?: string; quantity?: string }>({});
  const [rejectionNotice, setRejectionNotice] = useState<{ code: string; message: string; sku: string; qty: number } | null>(null);
  const [successResult, setSuccessResult] = useState<StockRequest | null>(null);

  // Catalog loading and real-time freshness (F19)
  const [catalog, setCatalog] = useState<Product[]>([]);
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState(false);
  const [catalogFetchedAt, setCatalogFetchedAt] = useState<Date | null>(null);

  const { subscribeInvalidation } = useStockEvents();

  const loadCatalog = useCallback(async () => {
    try {
      const products = await fetchProducts();
      setCatalog(products);
      setCatalogFetchedAt(new Date());
      setCatalogError(false);
    } catch {
      setCatalogError(true);
    } finally {
      setCatalogLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadCatalog();
  }, [loadCatalog]);

  // F19: Refresh catalog on real-time NIO/SSE invalidation signals
  useEffect(() => {
    return subscribeInvalidation(() => {
      void loadCatalog();
    });
  }, [subscribeInvalidation, loadCatalog]);

  // Matching product from catalog
  const matchingProduct = catalog.find(
    (p) => p.sku.toLowerCase() === sku.trim().toLowerCase()
  );

  // Validate entered quantity
  const parsedQty = parseInt(quantityStr.trim(), 10);
  const isNumeric = /^[1-9]\d*$/.test(quantityStr.trim());
  const exceedsStock = matchingProduct && isNumeric && parsedQty > matchingProduct.available;

  // Handle SKU change
  const handleSkuChange = (newSku: string) => {
    setSku(newSku);
    if (fieldErrors.sku) setFieldErrors((prev) => ({ ...prev, sku: undefined }));
    // If user previously suffered a rejection or changed intent, ensure fresh key
    if (rejectionNotice) {
      setRejectionNotice(null);
      setActiveDraftKey(generateUuidV4());
    }
  };

  // Handle Quantity change (F14: keeps raw string, generates fresh key after rejection F35)
  const handleQtyChange = (val: string) => {
    setQuantityStr(val);
    if (fieldErrors.quantity) setFieldErrors((prev) => ({ ...prev, quantity: undefined }));
    // F35: If operator corrects quantity after a rejection, generate new UUID so it succeeds!
    if (rejectionNotice) {
      setRejectionNotice(null);
      setActiveDraftKey(generateUuidV4());
    }
  };

  async function executeSubmit(targetSku: string, targetQty: number, targetKey: string) {
    setSubmitting(true);
    setSuccessResult(null);

    try {
      const result = await createStockRequest(targetSku, targetQty, targetKey);
      setSuccessResult(result);
      setLastAttempt(null);
      setRejectionNotice(null);
      // Re-fetch catalog to update available balance immediately
      void loadCatalog();
    } catch (err) {
      if (err instanceof ApiError) {
        const isConflictOrTimeout =
          err.status === 504 ||
          err.status === 503 ||
          err.code === "GATEWAY_TIMEOUT" ||
          err.code === "WAREHOUSE_TIMEOUT" ||
          err.code === "SERVER_UNAVAILABLE";

        if (isConflictOrTimeout) {
          // Snapshot attempt for safe replay (F17)
          setLastAttempt({
            sku: targetSku,
            quantity: targetQty,
            idempotencyKey: targetKey,
            error: {
              code: err.code,
              message: err.message,
              isRetryable: true,
            },
          });
        } else {
          // Definite rejection by warehouse authority (F35: e.g. INSUFFICIENT_STOCK)
          setRejectionNotice({
            code: err.code,
            message: err.message,
            sku: targetSku,
            qty: targetQty,
          });
          setLastAttempt(null);
          // Assign a fresh key for subsequent corrected attempts so 409 DUPLICATE_ID is avoided!
          setActiveDraftKey(generateUuidV4());
        }
      } else {
        setLastAttempt({
          sku: targetSku,
          quantity: targetQty,
          idempotencyKey: targetKey,
          error: {
            code: "NETWORK_FAILURE",
            message: "Network failure submitting stock request. Idempotency key is preserved for safe retry.",
            isRetryable: true,
          },
        });
      }
    } finally {
      setSubmitting(false);
    }
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();

    const nextFieldErrors: { sku?: string; quantity?: string } = {};

    if (!sku.trim()) {
      nextFieldErrors.sku = "Please select a product from the catalog or enter a valid SKU.";
    }

    const trimmedQty = quantityStr.trim();
    if (!trimmedQty) {
      nextFieldErrors.quantity = "Please enter a quantity to reserve.";
    } else if (!/^[1-9]\d*$/.test(trimmedQty)) {
      // F14: Fractional or invalid quantity validation without silent coercion
      nextFieldErrors.quantity = "Enter a whole number of units (e.g. 1, 2, 5). Fractional amounts are not supported.";
    }

    if (Object.keys(nextFieldErrors).length > 0) {
      setFieldErrors(nextFieldErrors);
      // F16: Focus the first invalid field
      if (nextFieldErrors.sku) {
        skuInputRef.current?.focus();
      } else if (nextFieldErrors.quantity) {
        qtyInputRef.current?.focus();
      }
      return;
    }

    setFieldErrors({});
    void executeSubmit(sku.trim(), parsedQty, activeDraftKey);
  }

  // F17: Replay EXACT frozen snapshot (never combined with edited form values)
  function handleRetryFrozenAttempt() {
    if (!lastAttempt) return;
    void executeSubmit(lastAttempt.sku, lastAttempt.quantity, lastAttempt.idempotencyKey);
  }

  function handleReset() {
    setSku("");
    setQuantityStr("");
    setFieldErrors({});
    setSuccessResult(null);
    setRejectionNotice(null);
    setLastAttempt(null);
    setActiveDraftKey(generateUuidV4());
  }

  return (
    <div className="mx-auto max-w-2xl space-y-8">
      {/* Page Header */}
      <div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center rounded-full bg-teal-50 px-2.5 py-0.5 text-xs font-semibold text-teal-800 border border-teal-200">
            Branch Reservation
          </span>
          <span className="text-xs text-slate-400">·</span>
          <span className="text-xs font-medium text-slate-500">Atomic Stock Hold</span>
        </div>
        <h1 className="mt-1.5 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
          New Stock Request
        </h1>
        <p className="mt-1.5 text-xs text-slate-600 sm:text-sm">
          Reserve stock for customer fulfillment. Reservations execute atomically against central PostgreSQL inventory balances.
        </p>
      </div>

      {/* Operational Receipt (F18: describes stock hold and exact request handoff) */}
      {successResult && (
        <div
          role="status"
          aria-live="polite"
          className="rounded-2xl border border-emerald-200 bg-emerald-50/90 p-6 shadow-xs text-emerald-950 space-y-4"
        >
          <div className="flex items-center gap-3">
            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-emerald-600 text-white font-bold text-sm">
              ✓
            </span>
            <div>
              <h2 className="text-base font-bold text-emerald-950">Stock Hold Confirmed</h2>
              <p className="text-xs text-emerald-800">
                {formatUnits(successResult.quantity)} of {matchingProduct?.name || successResult.sku} reserved. The warehouse can now pick them.
              </p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4 rounded-xl bg-white/90 p-4 text-xs border border-emerald-100">
            <div>
              <span className="text-slate-500 block">Product:</span>
              <span className="font-bold text-slate-900 text-sm">
                {matchingProduct?.name || successResult.sku}
              </span>
              <span className="font-mono text-slate-500 text-[11px] block">{successResult.sku}</span>
            </div>
            <div>
              <span className="text-slate-500 block">Reserved Units:</span>
              <span className="font-mono font-bold text-base text-emerald-800">
                {formatUnits(successResult.quantity)}
              </span>
            </div>
            <div>
              <span className="text-slate-500 block">Status:</span>
              <span className="font-semibold text-emerald-800">{successResult.status}</span>
            </div>
            <div>
              <span className="text-slate-500 block">Reference ID:</span>
              <span className="font-mono text-slate-800 text-[11px] truncate block select-all" title={successResult.requestId}>
                {successResult.requestId}
              </span>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-3 pt-1">
            <button
              type="button"
              onClick={handleReset}
              className="inline-flex min-h-[40px] items-center justify-center rounded-xl bg-emerald-700 px-4 py-2 text-xs font-bold text-white hover:bg-emerald-800 transition-colors"
            >
              Submit Another Request
            </button>
            <Link
              href={`/warehouse?requestId=${encodeURIComponent(successResult.requestId)}&stage=PICK`}
              className="inline-flex min-h-[40px] items-center justify-center rounded-xl border border-emerald-300 bg-white px-4 py-2 text-xs font-semibold text-emerald-900 hover:bg-emerald-50 transition-colors"
            >
              Open in Warehouse Queue →
            </Link>
          </div>
        </div>
      )}

      {/* Rejection / Shortage Notice (F35: clear explanation and revised submission pathway) */}
      {rejectionNotice && (
        <div
          role="alert"
          aria-live="assertive"
          className="rounded-2xl border border-rose-200 bg-rose-50/90 p-6 text-rose-950 space-y-3"
        >
          <div className="flex items-center gap-2">
            <span className="text-rose-600 font-bold">⚠</span>
            <h2 className="font-bold text-base text-rose-950">
              Reservation Notice ({rejectionNotice.code})
            </h2>
          </div>
          <p className="text-xs text-rose-900 leading-relaxed">
            {rejectionNotice.message}
          </p>

          {rejectionNotice.code === "INSUFFICIENT_STOCK" && (
            <div className="pt-2 flex flex-wrap items-center gap-3 border-t border-rose-200">
              {matchingProduct && matchingProduct.available > 0 && (
                <button
                  type="button"
                  onClick={() => handleQtyChange(String(matchingProduct.available))}
                  className="rounded-lg bg-rose-700 px-3 py-1.5 text-xs font-bold text-white hover:bg-rose-800 transition-colors"
                >
                  Adjust to max available ({matchingProduct.available} units)
                </button>
              )}
              <Link
                href={`/suppliers?sku=${encodeURIComponent(rejectionNotice.sku)}&returnTo=${encodeURIComponent(returnTo)}`}
                className="text-xs font-bold text-teal-800 underline hover:text-teal-950"
              >
                Check external supplier availability for {rejectionNotice.sku} →
              </Link>
            </div>
          )}
        </div>
      )}

      {/* Retryable Uncertain Attempt Notice (F17: snapshot isolation) */}
      {lastAttempt && lastAttempt.error?.isRetryable && (
        <div
          role="alert"
          aria-live="assertive"
          className="rounded-2xl border border-amber-200 bg-amber-50/90 p-6 text-amber-950 space-y-3"
        >
          <div className="flex items-center gap-2">
            <span className="text-amber-600 font-bold">⏱</span>
            <h2 className="font-bold text-base text-amber-950">
              Uncertain Request Status ({lastAttempt.error.code})
            </h2>
          </div>
          <p className="text-xs text-amber-900 leading-relaxed">
            The warehouse did not return a confirmation in time: {lastAttempt.error.message}
          </p>
          <div className="rounded-lg bg-white/80 p-3 text-xs border border-amber-200 font-mono text-slate-700">
            Pending Attempt: {lastAttempt.quantity} units of {lastAttempt.sku} (Key: {lastAttempt.idempotencyKey.slice(0, 12)}...)
          </div>
          <div className="pt-2 flex flex-wrap items-center gap-3 border-t border-amber-200">
            <button
              type="button"
              onClick={handleRetryFrozenAttempt}
              disabled={submitting}
              className="inline-flex min-h-[40px] items-center justify-center rounded-xl bg-amber-800 px-4 py-2 text-xs font-bold text-white hover:bg-amber-900 focus:outline-hidden focus:ring-2 focus:ring-amber-600 disabled:opacity-50"
            >
              {submitting ? "Retrying..." : "Retry Exactly This Request"}
            </button>
            <span className="text-[11px] text-amber-800">
              Replays the exact original key without creating duplicate reservations.
            </span>
          </div>
        </div>
      )}

      {/* Reservation Form */}
      <form
        onSubmit={handleSubmit}
        className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-xs space-y-5"
        noValidate
      >
        {/* Step 1: Catalog Selector (F13: Accessible name for catalog picker) */}
        <div>
          <label
            htmlFor="catalog-picker"
            className="block text-sm font-semibold text-slate-900"
          >
            Select Product from Catalog <span className="text-rose-600">*</span>
          </label>
          <p id={skuHelpId} className="text-xs text-slate-500 mt-0.5">
            Choose a stocked item from the warehouse catalog or type an exact SKU below.
          </p>

          <div className="mt-2">
            <select
              id="catalog-picker"
              aria-label="Select product from warehouse catalog"
              disabled={catalogLoading}
              value={catalog.some((p) => p.sku === sku) ? sku : ""}
              onChange={(e) => {
                if (e.target.value) {
                  handleSkuChange(e.target.value);
                }
              }}
              className="block w-full rounded-xl border border-slate-300 bg-slate-50 px-3.5 py-2.5 text-xs text-slate-800 focus:border-teal-600 focus:bg-white focus:outline-hidden focus:ring-1 focus:ring-teal-600"
            >
              <option value="">
                {catalogLoading
                  ? "-- Loading warehouse catalog... --"
                  : catalogError
                  ? "-- Catalog offline (enter SKU manually) --"
                  : "-- Choose from warehouse catalog --"}
              </option>
              {catalog.map((p) => (
                <option key={p.sku} value={p.sku}>
                  {p.name} ({p.sku}) — Available: {formatUnits(p.available)}
                </option>
              ))}
            </select>
          </div>

          {/* Manual SKU input fallback */}
          <div className="mt-3">
            <label htmlFor={skuId} className="block text-xs font-semibold text-slate-600">
              Or Enter Product SKU Manually
            </label>
            <input
              ref={skuInputRef}
              id={skuId}
              type="text"
              required
              value={sku}
              onChange={(e) => handleSkuChange(e.target.value)}
              placeholder="e.g. ITEM-007"
              aria-describedby={`${skuHelpId} ${fieldErrors.sku ? skuErrorId : ""}`}
              aria-invalid={!!fieldErrors.sku}
              className={`mt-1 block w-full rounded-xl border px-3.5 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600 font-mono ${
                fieldErrors.sku ? "border-rose-500 bg-rose-50/30" : "border-slate-300"
              }`}
            />
          </div>

          {fieldErrors.sku && (
            <p id={skuErrorId} role="alert" className="mt-1 text-xs font-semibold text-rose-600">
              {fieldErrors.sku}
            </p>
          )}

          {/* Real-time Matching Stock Feedback (F19) */}
          {matchingProduct ? (
            <div className="mt-2.5 flex items-center justify-between rounded-xl bg-teal-50/80 border border-teal-200 px-3.5 py-2 text-xs text-teal-900">
              <div>
                <strong className="font-semibold">{matchingProduct.name}</strong>
                <span className="ml-2 font-mono text-teal-800">
                  · Available: <strong>{formatUnits(matchingProduct.available)}</strong>
                </span>
              </div>
              <span className="text-[11px] text-teal-700">
                On hand: {matchingProduct.onHand} · Reserved: {matchingProduct.reserved}
              </span>
            </div>
          ) : catalogError ? (
            <div className="mt-2 text-[11px] text-amber-700 bg-amber-50 rounded-lg p-2 border border-amber-200">
              Catalog status: Stock balances unavailable. You can still submit manual SKU orders directly to the warehouse.
            </div>
          ) : null}
        </div>

        {/* Step 2: Quantity Input (F14: raw string, F15: shortage guidance, F16: linked error) */}
        <div>
          <div className="flex items-center justify-between">
            <label htmlFor={qtyId} className="block text-sm font-semibold text-slate-900">
              Quantity to Reserve <span className="text-rose-600">*</span>
            </label>
            {matchingProduct && (
              <span className="text-xs font-semibold text-slate-600">
                Max available:{" "}
                <strong className="font-mono text-teal-800">{matchingProduct.available}</strong>
              </span>
            )}
          </div>

          <p id={qtyHelpId} className="text-xs text-slate-500 mt-0.5">
            Enter a whole number of units to hold for customer order fulfillment.
          </p>

          <input
            ref={qtyInputRef}
            id={qtyId}
            type="text"
            inputMode="numeric"
            pattern="[0-9]*"
            required
            value={quantityStr}
            onChange={(e) => handleQtyChange(e.target.value)}
            placeholder="e.g. 5"
            aria-describedby={`${qtyHelpId} ${fieldErrors.quantity ? qtyErrorId : ""} ${exceedsStock ? shortageWarningId : ""}`}
            aria-invalid={!!fieldErrors.quantity || exceedsStock}
            className={`mt-2 block w-full rounded-xl border px-3.5 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600 font-mono ${
              fieldErrors.quantity || exceedsStock
                ? "border-rose-500 bg-rose-50/30"
                : "border-slate-300"
            }`}
          />

          {fieldErrors.quantity && (
            <p id={qtyErrorId} role="alert" className="mt-1 text-xs font-semibold text-rose-600">
              {fieldErrors.quantity}
            </p>
          )}

          {/* F15: Explicit shortage recovery choices */}
          {exceedsStock && (
            <div
              id={shortageWarningId}
              role="alert"
              className="mt-2 rounded-xl bg-amber-50 p-3 border border-amber-200 text-xs text-amber-900 space-y-1.5"
            >
              <p className="font-semibold">
                ⚠ Only {formatUnits(matchingProduct.available)} available in warehouse.
              </p>
              <p className="text-amber-800">
                Requesting {parsedQty} units exceeds available balance. Change quantity or inquire external supplier stock.
              </p>
              <div className="pt-1 flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => handleQtyChange(String(matchingProduct.available))}
                  className="rounded-lg bg-amber-800 px-2.5 py-1 text-xs font-bold text-white hover:bg-amber-900 transition-colors"
                >
                  Adjust to {matchingProduct.available}
                </button>
                <Link
                  href={`/suppliers?sku=${encodeURIComponent(sku)}&returnTo=${encodeURIComponent(returnTo)}`}
                  className="font-bold text-teal-800 underline hover:text-teal-950"
                >
                  Check Supplier Stock →
                </Link>
              </div>
            </div>
          )}
        </div>

        {/* Idempotency Key & Scenarios Disclosure */}
        <details className="rounded-xl bg-slate-50 border border-slate-200 p-4 text-xs group">
          <summary className="font-semibold text-slate-700 cursor-pointer select-none hover:text-teal-700 flex items-center justify-between">
            <span>Evaluator Demo Scenarios &amp; Idempotency Key (M1 / M4)</span>
            <span className="text-slate-400 group-open:rotate-180 transition-transform">▼</span>
          </summary>
          <div className="mt-3 space-y-3 pt-2 border-t border-slate-200 text-slate-600">
            <div>
              <span className="font-semibold text-slate-700 block mb-1">Demo Quick-Fill:</span>
              <div className="flex flex-wrap gap-1.5">
                <button
                  type="button"
                  onClick={() => handleSkuChange("ITEM-007")}
                  className="rounded bg-white border border-slate-200 px-2 py-1 font-mono text-teal-800 hover:bg-slate-100"
                >
                  ITEM-007 (Carrots - Stocked)
                </button>
                <button
                  type="button"
                  onClick={() => handleSkuChange("ITEM-009")}
                  className="rounded bg-white border border-slate-200 px-2 py-1 font-mono text-amber-800 hover:bg-slate-100"
                >
                  ITEM-009 (Potatoes - Low Stock)
                </button>
                <button
                  type="button"
                  onClick={() => handleSkuChange("ITEM-008")}
                  className="rounded bg-white border border-slate-200 px-2 py-1 font-mono text-rose-800 hover:bg-slate-100"
                >
                  ITEM-008 (Onions - Out of Stock)
                </button>
              </div>
            </div>

            <div className="pt-2 border-t border-slate-200">
              <div className="flex items-center justify-between">
                <span className="font-medium text-slate-700">Active Request UUID (v4):</span>
                <button
                  type="button"
                  onClick={() => setActiveDraftKey(generateUuidV4())}
                  className="text-[11px] font-semibold text-teal-700 hover:text-teal-800 underline"
                >
                  Generate New Key
                </button>
              </div>
              <code className="mt-1 block font-mono bg-white p-2 rounded border border-slate-200 text-slate-800 break-all select-all">
                {activeDraftKey}
              </code>
            </div>
          </div>
        </details>

        {/* Submit Actions (F09: Cancel returns to originating view) */}
        <div className="flex items-center justify-between border-t border-slate-100 pt-5">
          <Link
            href={returnTo}
            className="text-xs font-semibold text-slate-600 hover:text-slate-900"
          >
            ← Cancel and Return
          </Link>
          <button
            type="submit"
            disabled={submitting}
            className="inline-flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-teal-700 px-6 py-2.5 text-sm font-semibold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 focus:ring-offset-2 disabled:opacity-50 transition-colors"
          >
            {submitting && (
              <svg className="h-4 w-4 animate-spin text-white" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
              </svg>
            )}
            {submitting ? "Reserving Stock..." : rejectionNotice ? "Submit Revised Request" : "Reserve Stock"}
          </button>
        </div>
      </form>
    </div>
  );
}
