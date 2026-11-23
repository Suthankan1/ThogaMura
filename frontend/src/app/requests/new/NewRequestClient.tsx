"use client";

import { useState, useEffect, useRef, useId, type FormEvent, useCallback, useSyncExternalStore } from "react";
import { useSearchParams } from "next/navigation";
import { LoaderCircle, PackageCheck } from "lucide-react";
import Link from "next/link";
import { createStockRequest, fetchProducts, ApiError } from "@/lib/api/inventory";
import type { StockRequest, Product } from "@/lib/types/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { formatOperationalTime, formatUnits } from "@/lib/formatters";
import { clearAttempt, getStoredAttempt, isSku, newRequestId, parseQuantity, parseReservationAttempt,
  storeAttempt, subscribeToStoredAttempt, type ReservationAttempt } from "@/lib/reservationAttempt";
import { primaryAction } from "@/components/Workspace";
import { safeReturnTo } from "@/lib/returnTo";

const getServerAttempt = () => null;
const confirmedFailures = new Set(["INSUFFICIENT_STOCK", "DUPLICATE_ID_CONFLICT", "VALIDATION_ERROR", "NOT_FOUND"]);

export function NewRequestClient() {
  const searchParams = useSearchParams();

  const returnTo = safeReturnTo(searchParams.get("returnTo"));

  const [manual, setManual] = useState(Boolean(searchParams.get("sku")));
  const pickerRef = useRef<HTMLSelectElement>(null);
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
  const rawAttempt = useSyncExternalStore(subscribeToStoredAttempt, getStoredAttempt, getServerAttempt);
  const lastAttempt = parseReservationAttempt(rawAttempt);
  const [attemptError, setAttemptError] = useState<string | null>(null);
  const submittingRef = useRef(false);
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

  const catalogRequest = useRef(0);
  const loadCatalog = useCallback(async () => {
    const call = ++catalogRequest.current;
    try {
      const products = await fetchProducts();
      if (call !== catalogRequest.current) return;
      setCatalog(products);
      setCatalogFetchedAt(new Date());
      setCatalogError(false);
    } catch {
      if (call === catalogRequest.current) setCatalogError(true);
    } finally {
      if (call === catalogRequest.current) setCatalogLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => void loadCatalog(), 0);
    return () => clearTimeout(timer);
  }, [loadCatalog]);

  // F19: Refresh catalog on real-time NIO/SSE invalidation signals
  useEffect(() => {
    return subscribeInvalidation(() => loadCatalog());
  }, [subscribeInvalidation, loadCatalog]);

  const matchingProduct = catalog.find((product) => product.sku === sku.trim());
  const confirmedProduct = successResult && catalog.find((product) => product.sku === successResult.sku);
  const parsedQty = parseQuantity(quantityStr.trim());
  const exceedsStock = matchingProduct !== undefined && parsedQty !== null && parsedQty > matchingProduct.available;

  function handleSkuChange(value: string) {
    setSku(value);
    setFieldErrors({});
    setRejectionNotice(null);
  }

  function handleQtyChange(value: string) {
    setQuantityStr(value);
    setFieldErrors((previous) => ({ ...previous, quantity: undefined }));
    setRejectionNotice(null);
  }

  async function executeSubmit(attempt: ReservationAttempt) {
    if (submittingRef.current) return;
    submittingRef.current = true;
    storeAttempt(attempt);
    setSubmitting(true);
    setAttemptError(null);
    setSuccessResult(null);
    setRejectionNotice(null);
    try {
      const result = await createStockRequest(attempt.sku, attempt.quantity, attempt.idempotencyKey);
      clearAttempt(attempt.idempotencyKey);
      setSuccessResult(result);
      void loadCatalog();
    } catch (error) {
      if (error instanceof ApiError && confirmedFailures.has(error.code)) {
        setRejectionNotice({ code: error.code, message: error.message, sku: attempt.sku, qty: attempt.quantity });
        clearAttempt(attempt.idempotencyKey);
      } else {
        setAttemptError(error instanceof ApiError ? error.message : "Unable to confirm the request.");
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (submittingRef.current || parseReservationAttempt(getStoredAttempt()) || successResult) return;

    const trimmedSku = sku.trim();
    const errors: typeof fieldErrors = {};
    if (!trimmedSku) errors.sku = "Choose a product or enter its SKU.";
    else if (!isSku(trimmedSku)) errors.sku = "Enter a SKU with 1–64 letters, digits, hyphens or underscores.";
    if (parsedQty === null || exceedsStock) errors.quantity = matchingProduct
      ? matchingProduct.available === 0 ? "This product is out of stock." : `Enter a quantity from 1 to ${matchingProduct.available}.`
      : "Enter a positive whole number within the supported range (1–2,147,483,647).";
    setFieldErrors(errors);
    if (errors.sku) (manual ? skuInputRef : pickerRef).current?.focus();
    else if (errors.quantity) qtyInputRef.current?.focus();
    else if (parsedQty !== null) {
      void executeSubmit({ sku: trimmedSku, quantity: parsedQty, idempotencyKey: newRequestId() });
    }
  }

  function handleRetryFrozenAttempt() {
    if (lastAttempt) void executeSubmit(lastAttempt);
  }

  function handleReset() {
    setSku("");
    setQuantityStr("");
    setFieldErrors({});
    setSuccessResult(null);
    setRejectionNotice(null);
    (manual ? skuInputRef : pickerRef).current?.focus();
  }

  return (
    <div className="mx-auto max-w-2xl space-y-8">
      {/* Page Header */}
      <div>
        <h1 className="mt-1.5 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
          New stock request
        </h1>
        <p className="mt-1.5 text-xs text-slate-600 sm:text-sm">
          Choose an item and quantity to request from the warehouse.
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
                {formatUnits(successResult.quantity)} of {confirmedProduct?.name || successResult.sku} reserved. Request history shows the current fulfillment status.
              </p>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4 rounded-xl bg-white/90 p-4 text-xs border border-emerald-100">
            <div>
              <span className="text-slate-500 block">Product:</span>
              <span className="font-bold text-slate-900 text-sm">
                {confirmedProduct?.name || successResult.sku}
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
              className="inline-flex min-h-11 items-center justify-center rounded-xl bg-emerald-700 px-4 py-2 text-xs font-bold text-white hover:bg-emerald-800 transition-colors"
            >
              Submit Another Request
            </button>
            <Link
              href={`/requests?ref=${encodeURIComponent(successResult.requestId)}`}
              className="inline-flex min-h-11 items-center justify-center rounded-xl border border-emerald-300 bg-white px-4 py-2 text-xs font-semibold text-emerald-900 hover:bg-emerald-50 transition-colors"
            >
              View request →
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
              Reservation declined
            </h2>
          </div>
          <p className="text-xs text-rose-900 leading-relaxed">
            {rejectionNotice.message}
          </p>

          {rejectionNotice.code === "INSUFFICIENT_STOCK" && (
            <div className="pt-2 flex flex-wrap items-center gap-3 border-t border-rose-200">
              {matchingProduct && matchingProduct.available > 0 && (
                <button
                  disabled={matchingProduct.available === 0}
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
      {lastAttempt && !submitting && (
        <div
          role="alert"
          aria-live="assertive"
          className="rounded-2xl border border-amber-200 bg-amber-50/90 p-6 text-amber-950 space-y-3"
        >
          <div className="flex items-center gap-2">
            <span className="text-amber-600 font-bold">⏱</span>
            <h2 className="font-bold text-base text-amber-950">
              Request outcome unconfirmed
            </h2>
          </div>
          <p className="text-xs text-amber-900 leading-relaxed">
            {attemptError ?? "This request was submitted before the page reloaded. Retry with the original reference to recover its outcome."}
          </p>
          <div className="rounded-lg bg-white/80 p-3 text-xs border border-amber-200 font-mono text-slate-700">
            {formatUnits(lastAttempt.quantity)} of {lastAttempt.sku} · {lastAttempt.idempotencyKey}
          </div>
          <div className="pt-2 flex flex-wrap items-center gap-3 border-t border-amber-200">
            <button
              type="button"
              onClick={handleRetryFrozenAttempt}
              disabled={submitting}
              className="inline-flex min-h-11 items-center justify-center rounded-xl bg-amber-800 px-4 py-2 text-xs font-bold text-white hover:bg-amber-900 focus:outline-hidden focus:ring-2 focus:ring-amber-600 disabled:opacity-50"
            >
              {submitting ? "Retrying..." : "Retry same request"}
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
          {!manual && <>
          <label
            htmlFor="catalog-picker"
            className="block text-sm font-semibold text-slate-900"
          >
            Product <span className="text-rose-600">*</span>
          </label>
          {catalogFetchedAt && <p className="text-xs text-slate-600">Stock checked {formatOperationalTime(catalogFetchedAt)}{catalogError ? " · refresh failed" : ""}</p>}
          <p id={skuHelpId} className="text-xs text-slate-500 mt-0.5">
            Choose an item from the warehouse catalog.
          </p>

          <div className="mt-2">
            <select
              ref={pickerRef}
              aria-invalid={!!fieldErrors.sku}
              aria-describedby={fieldErrors.sku ? skuErrorId : skuHelpId}
              id="catalog-picker"
              aria-label="Select product from warehouse catalog"
              aria-busy={catalogLoading}
              value={catalog.some((p) => p.sku === sku) ? sku : ""}
              onChange={(e) => {
                handleSkuChange(e.target.value);
              }}
              className="block min-h-11 w-full rounded-xl border border-slate-300 bg-slate-50 px-3.5 py-2.5 text-xs text-slate-800 focus:border-teal-600 focus:bg-white focus:outline-hidden focus:ring-1 focus:ring-teal-600"
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

          </>}
          <button type="button" className="min-h-11 text-sm text-teal-800 underline" onClick={() => { setManual(!manual); handleSkuChange(""); }}>{manual ? "Choose from catalog" : "Enter SKU instead"}</button>
          {manual && (
          <div className="mt-3">
            <label htmlFor={skuId} className="block text-xs font-semibold text-slate-600">
              Product SKU
            </label>
            <input
              ref={skuInputRef}
              id={skuId}
              type="text"
              required
              value={sku}
              onChange={(e) => handleSkuChange(e.target.value)}
              placeholder="e.g. ITEM-007"
              aria-describedby={fieldErrors.sku ? skuErrorId : undefined}
              aria-invalid={!!fieldErrors.sku}
              className={`mt-1 block min-h-11 w-full rounded-xl border px-3.5 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600 font-mono ${
                fieldErrors.sku ? "border-rose-500 bg-rose-50/30" : "border-slate-300"
              }`}
            />
          </div>

          )}
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
              Quantity <span className="text-rose-600">*</span>
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
            aria-invalid={!!fieldErrors.quantity}
            className={`mt-2 block w-32 min-h-11 rounded-xl border px-3.5 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600 font-mono ${
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
                  disabled={matchingProduct.available === 0}
                  type="button"
                  onClick={() => handleQtyChange(String(matchingProduct.available))}
                  className="min-h-11 rounded-xl bg-amber-800 px-2.5 py-1 text-xs font-bold text-white hover:bg-amber-900 transition-colors"
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

        {/* Submit Actions (F09: Cancel returns to originating view) */}
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-5">
          <Link
            href={returnTo}
            className="inline-flex min-h-11 items-center text-sm font-semibold text-slate-600 hover:text-slate-900"
          >
            ← Cancel
          </Link>
          <button
            type="submit"
            disabled={submitting || !!lastAttempt || !!successResult}
            className={primaryAction + " min-w-52"}
          >
            {submitting ? <LoaderCircle size={20} className="motion-safe:animate-spin" aria-hidden="true" /> :
              <PackageCheck size={20} aria-hidden="true" />}
            {submitting ? "Reserving stock…" : rejectionNotice ? "Submit revised request" : "Reserve stock"}
          </button>
        </div>
      </form>
    </div>
  );
}
