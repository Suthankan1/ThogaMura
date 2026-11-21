"use client";

import { useState, useEffect, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { fetchSupplierAvailability } from "@/lib/api/inventory";
import type { SupplierAvailability } from "@/lib/types/inventory";

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
      const res = await fetchSupplierAvailability(initialSku.trim());
      if (!ignore) {
        setResult(res);
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

  return (
    <div className="mx-auto max-w-3xl space-y-8">
      {/* Page Header */}
      <div>
        <p className="text-xs font-semibold uppercase tracking-wider text-teal-700">
          Member 2 — URL/HttpURLConnection Integration
        </p>
        <h1 className="mt-1 text-3xl font-bold tracking-tight text-slate-900 sm:text-4xl">
          Advisory Supplier Availability
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          When warehouse stock is depleted, query the independent supplier HTTP simulator. Results are strictly advisory and never fabricate or alter central stock.
        </p>
      </div>

      {/* Architectural Disclaimer */}
      <div className="rounded-xl border border-slate-200 bg-slate-50 p-4 text-xs text-slate-600 space-y-1">
        <strong className="text-slate-900 block font-semibold">
          Architectural Notice (Member 2 Technical Area):
        </strong>
        <p>
          The central warehouse server executes outbound Java <code className="font-mono text-teal-800">java.net.HttpURLConnection</code> requests to the configured mock supplier service. This data is read-only and does not automatically purchase goods or mutate PostgreSQL inventory balances.
        </p>
      </div>

      {/* Inquiry Form */}
      <form
        onSubmit={handleSubmit}
        className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs space-y-4"
      >
        <div>
          <label htmlFor="supplier-sku-input" className="block text-sm font-semibold text-slate-900">
            Product SKU to Inquire
          </label>
          <div className="mt-2 flex gap-3">
            <input
              id="supplier-sku-input"
              type="text"
              required
              value={sku}
              onChange={(e) => setSku(e.target.value)}
              placeholder="e.g. ITEM-008"
              className="flex-1 rounded-lg border border-slate-300 px-3.5 py-2 text-sm text-slate-900 placeholder:text-slate-400 focus:border-teal-600 focus:outline-hidden focus:ring-1 focus:ring-teal-600 font-mono"
            />
            <button
              type="submit"
              disabled={loading || !sku.trim()}
              className="inline-flex items-center gap-2 rounded-lg bg-teal-700 px-5 py-2 text-sm font-semibold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50"
            >
              {loading && (
                <svg className="h-4 w-4 animate-spin" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
                </svg>
              )}
              {loading ? "Querying Supplier..." : "Inquire Availability"}
            </button>
          </div>
          <div className="mt-2.5 flex flex-wrap items-center gap-1.5 text-xs text-slate-500">
            <span>Demo Scenarios:</span>
            <button
              type="button"
              onClick={() => {
                setSku("ITEM-008");
                void executeInquiry("ITEM-008");
              }}
              className="rounded bg-slate-100 px-2 py-0.5 font-mono text-teal-800 hover:bg-slate-200"
            >
              ITEM-008 (Available Units)
            </button>
            <button
              type="button"
              onClick={() => {
                setSku("ITEM-ZERO");
                void executeInquiry("ITEM-ZERO");
              }}
              className="rounded bg-slate-100 px-2 py-0.5 font-mono text-amber-800 hover:bg-slate-200"
            >
              ITEM-ZERO (Confirmed Zero)
            </button>
            <button
              type="button"
              onClick={() => {
                setSku("ITEM-UNKNOWN");
                void executeInquiry("ITEM-UNKNOWN");
              }}
              className="rounded bg-slate-100 px-2 py-0.5 font-mono text-slate-700 hover:bg-slate-200"
            >
              ITEM-UNKNOWN (404 Catalog)
            </button>
            <button
              type="button"
              onClick={() => {
                setSku("ITEM-OUTAGE");
                void executeInquiry("ITEM-OUTAGE");
              }}
              className="rounded bg-slate-100 px-2 py-0.5 font-mono text-rose-700 hover:bg-slate-200"
            >
              ITEM-OUTAGE (503 Service Outage)
            </button>
          </div>
          <p className="mt-2 text-xs text-slate-500">
            Click any scenario button above to test the Java URL HTTP mock connector response mapping.
          </p>
        </div>
      </form>

      {/* Results View */}
      {result && (
        <div className="space-y-4">
          {result.status === "AVAILABLE" && (
            <div className="rounded-xl border border-emerald-200 bg-emerald-50 p-6 shadow-xs text-emerald-950 space-y-4">
              <div className="flex items-center justify-between">
                <span className="inline-flex items-center rounded-full bg-emerald-100 px-3 py-1 text-xs font-bold text-emerald-800">
                  AVAILABLE AT SUPPLIER
                </span>
                <span className="text-xs font-mono text-emerald-800">
                  Source: {result.source || "MOCK_SUPPLIER"}
                </span>
              </div>

              <div className="grid grid-cols-2 gap-4 rounded-lg bg-white/70 p-4 text-xs">
                <div>
                  <span className="text-slate-500 block">Product SKU:</span>
                  <span className="font-mono font-bold text-base text-slate-900">{result.sku}</span>
                </div>
                <div>
                  <span className="text-slate-500 block">Reported Quantity:</span>
                  <span className="font-mono font-bold text-base text-emerald-700">
                    {result.reportedQuantity} units
                  </span>
                </div>
                <div>
                  <span className="text-slate-500 block">Supplier Name:</span>
                  <span className="font-semibold text-slate-800">{result.supplier || "Demo Supplier"}</span>
                </div>
                <div>
                  <span className="text-slate-500 block">Checked At (UTC):</span>
                  <span className="font-mono text-slate-700">
                    {result.checkedAt ? new Date(result.checkedAt).toLocaleString() : "Recent"}
                  </span>
                </div>
              </div>
            </div>
          )}

          {result.status === "UNAVAILABLE" && (
            <div className="rounded-xl border border-amber-200 bg-amber-50 p-6 shadow-xs text-amber-950 space-y-3">
              <span className="inline-flex items-center rounded-full bg-amber-100 px-3 py-1 text-xs font-bold text-amber-800">
                CONFIRMED ZERO UNITS
              </span>
              <p className="text-sm text-amber-900">
                Supplier confirmed this SKU is recognized but currently has 0 available units in stock.
              </p>
              {result.checkedAt && (
                <p className="text-xs text-amber-700">
                  Verified at: {new Date(result.checkedAt).toLocaleString()}
                </p>
              )}
            </div>
          )}

          {result.status === "UNKNOWN" && (
            <div className="rounded-xl border border-slate-300 bg-slate-50 p-6 shadow-xs text-slate-900 space-y-3">
              <span className="inline-flex items-center rounded-full bg-slate-200 px-3 py-1 text-xs font-bold text-slate-800">
                UNKNOWN TO SUPPLIER
              </span>
              <p className="text-sm text-slate-700">
                Supplier returned HTTP 404: SKU <code className="font-mono font-semibold">{result.sku}</code> is not carried in the supplier product catalog.
              </p>
            </div>
          )}

          {result.status === "SUPPLIER_UNAVAILABLE" && (
            <div className="rounded-xl border border-rose-200 bg-rose-50 p-6 shadow-xs text-rose-950 space-y-3">
              <span className="inline-flex items-center rounded-full bg-rose-100 px-3 py-1 text-xs font-bold text-rose-800">
                SUPPLIER SERVICE OUTAGE
              </span>
              <p className="text-sm text-rose-800">
                {result.message || "Supplier HTTP service is offline, timed out, or returned an invalid response. Stock cannot be confirmed."}
              </p>
              <p className="text-xs text-rose-700">
                Note: A supplier timeout is not evidence that stock is zero.
              </p>
            </div>
          )}

          {result.status === "WAREHOUSE_UNAVAILABLE" && (
            <div className="rounded-xl border border-rose-200 bg-rose-50 p-6 shadow-xs text-rose-950 space-y-3">
              <span className="inline-flex items-center rounded-full bg-rose-100 px-3 py-1 text-xs font-bold text-rose-800">
                WAREHOUSE GATEWAY OUTAGE
              </span>
              <p className="text-sm text-rose-800">
                {result.message || "The central warehouse command server is unreachable over Java TCP."}
              </p>
            </div>
          )}
        </div>
      )}

      {/* Navigation */}
      <div className="pt-4 flex justify-between text-sm">
        <Link href="/products" className="font-medium text-teal-700 hover:text-teal-800">
          ← Back to Product Catalog
        </Link>
        <Link href="/activity" className="font-medium text-slate-600 hover:text-slate-900">
          View Network Status →
        </Link>
      </div>
    </div>
  );
}
