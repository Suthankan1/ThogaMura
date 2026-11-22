"use client";

import { useState, useEffect, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { fetchSupplierAvailability } from "@/lib/api/inventory";
import type { SupplierAvailability } from "@/lib/types/inventory";
import { formatFullDateTime } from "@/lib/formatters";

export function SuppliersClient() {
  const searchParams = useSearchParams();
  const initialSku = searchParams.get("sku") || "";

  const [sku, setSku] = useState(initialSku);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<SupplierAvailability | null>(null);

  useEffect(() => {
    if (!initialSku) return;
    let ignore = false;
    async function init() {
      setLoading(true);
      setResult(null);
      const res = await fetchSupplierAvailability(initialSku.trim());
      if (!ignore) {
        setResult(res);
        setLoading(false);
      }
    }
    void init();
    return () => {
      ignore = true;
    };
  }, [initialSku]);

  async function executeInquiry(skuToQuery: string) {
    if (!skuToQuery.trim()) return;
    setLoading(true);
    setResult(null);

    const res = await fetchSupplierAvailability(skuToQuery.trim());
    setResult(res);
    setLoading(false);
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    void executeInquiry(sku);
  }

  const isConfirmedZero =
    result &&
    ((result.status === "AVAILABLE" && result.reportedQuantity === 0) ||
      (result.status === "UNAVAILABLE" && result.reportedQuantity === 0) ||
      result.status === "UNAVAILABLE");

  const isConfirmedPositive =
    result && result.status === "AVAILABLE" && (result.reportedQuantity ?? 0) > 0;

  return (
    <div className="mx-auto max-w-2xl space-y-8">
      {/* Page Header */}
      <div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center rounded-full bg-teal-50 px-2.5 py-0.5 text-xs font-semibold text-teal-800 border border-teal-200">
            External Sourcing
          </span>
          <span className="text-xs text-slate-400">·</span>
          <span className="text-xs font-medium text-slate-500">Advisory Stock Lookup</span>
        </div>
        <h1 className="mt-1.5 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
          Check Supplier Stock
        </h1>
        <p className="mt-1.5 text-xs text-slate-600 sm:text-sm">
          Query advisory stock availability from external suppliers when central warehouse inventory is depleted.
        </p>
      </div>

      {/* Primary Inquiry Form */}
      <form
        onSubmit={handleSubmit}
        className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-xs space-y-4"
      >
        <div>
          <label htmlFor="supplier-sku-input" className="block text-sm font-semibold text-slate-900">
            Product SKU to Inquire
          </label>
          <p className="text-xs text-slate-500 mt-0.5">
            Enter any warehouse product SKU to query external supplier stock via HTTP.
          </p>

          <div className="mt-3 flex flex-col sm:flex-row gap-3">
            <input
              id="supplier-sku-input"
              type="text"
              required
              value={sku}
              onChange={(e) => setSku(e.target.value)}
              placeholder="e.g. ITEM-008"
              className="flex-1 rounded-xl border border-slate-300 px-4 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600 font-mono"
            />
            <button
              type="submit"
              disabled={loading || !sku.trim()}
              className="inline-flex min-h-[44px] items-center justify-center gap-2 rounded-xl bg-teal-700 px-6 py-2.5 text-sm font-semibold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50 transition-colors"
            >
              {loading && (
                <svg className="h-4 w-4 animate-spin text-white" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                </svg>
              )}
              {loading ? "Checking..." : "Check Availability"}
            </button>
          </div>
        </div>
      </form>

      {/* Results View */}
      {result && (
        <div className="space-y-4">
          {/* Positive Available Units (QA-01) */}
          {isConfirmedPositive && (
            <div
              role="status"
              aria-live="polite"
              className="rounded-2xl border border-emerald-200 bg-emerald-50/70 p-6 shadow-xs text-emerald-950 space-y-4"
            >
              <div className="flex items-center justify-between">
                <span className="inline-flex items-center rounded-full bg-emerald-100 px-3 py-1 text-xs font-bold text-emerald-800">
                  AVAILABLE AT SUPPLIER
                </span>
                <span className="text-xs font-mono text-emerald-800">
                  Source: {result.source || "MOCK_SUPPLIER"}
                </span>
              </div>

              <div>
                <span className="text-xs font-semibold text-emerald-800 uppercase tracking-wider block">Available Quantity</span>
                <p className="mt-1 font-mono text-3xl font-extrabold text-emerald-900">
                  {result.reportedQuantity} units
                </p>
                <p className="mt-1 text-xs text-emerald-800">
                  Supplier confirms stock is on hand. Note: Supplier data is advisory and does not automatically purchase goods or alter central inventory.
                </p>
              </div>

              <div className="grid grid-cols-2 gap-4 rounded-xl bg-white/80 p-4 text-xs border border-emerald-100">
                <div>
                  <span className="text-slate-500 block">Product SKU:</span>
                  <span className="font-mono font-bold text-sm text-slate-900">{result.sku}</span>
                </div>
                <div>
                  <span className="text-slate-500 block">Supplier:</span>
                  <span className="font-semibold text-slate-800">{result.supplier || "Demo Supplier"}</span>
                </div>
                <div className="col-span-2">
                  <span className="text-slate-500 block">Checked At:</span>
                  <span className="font-mono text-slate-700" title={result.checkedAt || ""}>
                    {formatFullDateTime(result.checkedAt)}
                  </span>
                </div>
              </div>
            </div>
          )}

          {/* Confirmed Zero Stock (QA-01: Out of stock at supplier) */}
          {isConfirmedZero && (
            <div
              role="status"
              aria-live="polite"
              className="rounded-2xl border border-amber-200 bg-amber-50/70 p-6 shadow-xs text-amber-950 space-y-4"
            >
              <div className="flex items-center justify-between">
                <span className="inline-flex items-center rounded-full bg-amber-100 px-3 py-1 text-xs font-bold text-amber-800">
                  OUT OF STOCK AT SUPPLIER
                </span>
                <span className="text-xs font-mono text-amber-800">
                  Source: {result.source || "MOCK_SUPPLIER"}
                </span>
              </div>

              <div>
                <span className="text-xs font-semibold text-amber-800 uppercase tracking-wider block">Available Quantity</span>
                <p className="mt-1 font-mono text-3xl font-extrabold text-amber-900">
                  0 units
                </p>
                <p className="mt-1 text-xs text-amber-800">
                  The supplier confirmed that SKU <code className="font-mono font-semibold">{result.sku}</code> is in catalog, but currently has 0 available units.
                </p>
              </div>

              <div className="grid grid-cols-2 gap-4 rounded-xl bg-white/80 p-4 text-xs border border-amber-100">
                <div>
                  <span className="text-slate-500 block">Product SKU:</span>
                  <span className="font-mono font-bold text-sm text-slate-900">{result.sku}</span>
                </div>
                <div>
                  <span className="text-slate-500 block">Supplier:</span>
                  <span className="font-semibold text-slate-800">{result.supplier || "Demo Supplier"}</span>
                </div>
                <div className="col-span-2">
                  <span className="text-slate-500 block">Checked At:</span>
                  <span className="font-mono text-slate-700" title={result.checkedAt || ""}>
                    {formatFullDateTime(result.checkedAt)}
                  </span>
                </div>
              </div>
            </div>
          )}

          {/* Unknown SKU 404 (QA-02) */}
          {result.status === "UNKNOWN" && (
            <div
              role="status"
              aria-live="polite"
              className="rounded-2xl border border-slate-300 bg-slate-50 p-6 shadow-xs text-slate-900 space-y-3"
            >
              <div className="flex items-center justify-between">
                <span className="inline-flex items-center rounded-full bg-slate-200 px-3 py-1 text-xs font-bold text-slate-800">
                  SKU NOT FOUND
                </span>
                <span className="text-xs font-mono text-slate-500">HTTP 404</span>
              </div>
              <h2 className="text-base font-bold text-slate-900">
                SKU not found in supplier catalog
              </h2>
              <p className="text-xs text-slate-600">
                The supplier returned HTTP 404: SKU <code className="font-mono font-semibold">{result.sku}</code> is not carried in the supplier product catalog. You can edit the SKU above to try another search.
              </p>
            </div>
          )}

          {/* Supplier Outage / Unavailable 503 / Timeout (QA-03) */}
          {result.status === "SUPPLIER_UNAVAILABLE" && (
            <div
              role="alert"
              aria-live="assertive"
              className="rounded-2xl border border-rose-200 bg-rose-50/80 p-6 shadow-xs text-rose-950 space-y-4"
            >
              <div className="flex items-center justify-between">
                <span className="inline-flex items-center rounded-full bg-rose-100 px-3 py-1 text-xs font-bold text-rose-800">
                  SUPPLIER OUTAGE
                </span>
                <span className="text-xs font-mono text-rose-700">HTTP 503 / Timeout</span>
              </div>
              <div>
                <h2 className="text-base font-bold text-rose-900">
                  Supplier service unavailable. Try again.
                </h2>
                <p className="mt-1 text-xs text-rose-800">
                  {result.message || "Supplier HTTP service timed out or returned an error. Outages do not mean stock is zero."}
                </p>
              </div>
              <div>
                <button
                  type="button"
                  onClick={() => void executeInquiry(sku)}
                  disabled={loading}
                  className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl bg-rose-700 px-4 py-2 text-xs font-semibold text-white shadow-xs hover:bg-rose-800 focus:outline-hidden focus:ring-2 focus:ring-rose-600 disabled:opacity-50"
                >
                  Retry inquiry
                </button>
              </div>
            </div>
          )}

          {/* Warehouse Gateway Outage */}
          {result.status === "WAREHOUSE_UNAVAILABLE" && (
            <div
              role="alert"
              aria-live="assertive"
              className="rounded-2xl border border-rose-200 bg-rose-50/80 p-6 shadow-xs text-rose-950 space-y-4"
            >
              <div className="flex items-center justify-between">
                <span className="inline-flex items-center rounded-full bg-rose-100 px-3 py-1 text-xs font-bold text-rose-800">
                  GATEWAY OUTAGE
                </span>
                <span className="text-xs font-mono text-rose-700">TCP Socket Outage</span>
              </div>
              <div>
                <h2 className="text-base font-bold text-rose-900">
                  Warehouse command server unreachable
                </h2>
                <p className="mt-1 text-xs text-rose-800">
                  {result.message || "The central warehouse command server is unreachable over Java TCP."}
                </p>
              </div>
              <div>
                <button
                  type="button"
                  onClick={() => void executeInquiry(sku)}
                  disabled={loading}
                  className="inline-flex min-h-[40px] items-center gap-1.5 rounded-xl bg-rose-700 px-4 py-2 text-xs font-semibold text-white shadow-xs hover:bg-rose-800 focus:outline-hidden focus:ring-2 focus:ring-rose-600 disabled:opacity-50"
                >
                  Retry connection
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Evaluator Demo Scenarios & Member 2 Technical Disclosure (Collapsed) */}
      <details className="group rounded-2xl border border-slate-200/80 bg-white shadow-xs">
        <summary className="flex cursor-pointer items-center justify-between p-5 text-sm font-bold text-slate-900 hover:bg-slate-50 rounded-2xl select-none">
          <div className="flex items-center gap-2">
            <span className="text-xs font-bold text-teal-800 bg-teal-100 rounded-md px-1.5 py-0.5">M2</span>
            <span>Evaluator Demo Scenarios &amp; HTTP Architecture (Member 2)</span>
          </div>
          <span className="text-xs font-medium text-slate-500 group-open:rotate-180 transition-transform">
            ▼
          </span>
        </summary>
        <div className="border-t border-slate-100 p-5 space-y-4 text-xs text-slate-600">
          <p>
            The central warehouse server executes outbound Java <code className="font-mono text-teal-800">java.net.HttpURLConnection</code> requests to the configured mock supplier service. This demonstrates SSRF allowlisting, configurable connect/read timeouts, and error classification.
          </p>

          <div>
            <span className="font-bold text-slate-800 block mb-2">Preset Simulator Test Cases:</span>
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => {
                  setSku("ITEM-008");
                  void executeInquiry("ITEM-008");
                }}
                className="rounded-lg border border-teal-200 bg-teal-50 px-3 py-1.5 font-mono text-teal-800 hover:bg-teal-100"
              >
                ITEM-008 (Available Units)
              </button>
              <button
                type="button"
                onClick={() => {
                  setSku("ITEM-ZERO");
                  void executeInquiry("ITEM-ZERO");
                }}
                className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-1.5 font-mono text-amber-800 hover:bg-amber-100"
              >
                ITEM-ZERO (Confirmed Zero)
              </button>
              <button
                type="button"
                onClick={() => {
                  setSku("ITEM-UNKNOWN");
                  void executeInquiry("ITEM-UNKNOWN");
                }}
                className="rounded-lg border border-slate-300 bg-slate-100 px-3 py-1.5 font-mono text-slate-700 hover:bg-slate-200"
              >
                ITEM-UNKNOWN (404 Catalog)
              </button>
              <button
                type="button"
                onClick={() => {
                  setSku("ITEM-OUTAGE");
                  void executeInquiry("ITEM-OUTAGE");
                }}
                className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-1.5 font-mono text-rose-700 hover:bg-rose-100"
              >
                ITEM-OUTAGE (503 Service Outage)
              </button>
            </div>
          </div>
        </div>
      </details>

      {/* Navigation Footer */}
      <div className="pt-2 flex justify-between text-xs text-slate-500">
        <Link href="/products" className="font-semibold text-teal-700 hover:text-teal-900">
          ← Back to Inventory Catalog
        </Link>
        <Link href="/activity" className="font-semibold text-slate-600 hover:text-slate-900">
          View System Status →
        </Link>
      </div>
    </div>
  );
}
