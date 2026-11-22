"use client";

import { useEffect, useState, useCallback, useMemo } from "react";
import Link from "next/link";
import { fetchStockRequests, fetchProducts, ApiError } from "@/lib/api/inventory";
import type { StockRequest, StockRequestStatus, Product } from "@/lib/types/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { formatOperationalTime, formatFullDateTime, formatFreshness, formatUnits } from "@/lib/formatters";

const STATUS_FILTERS: Array<{ label: string; value: string }> = [
  { label: "All Requests", value: "ALL" },
  { label: "Accepted", value: "ACCEPTED" },
  { label: "Rejected", value: "REJECTED" },
  { label: "Picked", value: "PICKED" },
  { label: "Dispatched", value: "DISPATCHED" },
];

export function RequestsClient() {
  const [requests, setRequests] = useState<StockRequest[]>([]);
  const [catalog, setCatalog] = useState<Product[]>([]);
  const [activeFilter, setActiveFilter] = useState("ALL");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null);
  const [refreshNotice, setRefreshNotice] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const { subscribeInvalidation, isLive, warningMessage } = useStockEvents();

  // Map of SKU -> Product Name (F21: recognize product name rather than raw SKU alone)
  const productNames = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of catalog) {
      map.set(p.sku.toLowerCase(), p.name);
    }
    return map;
  }, [catalog]);

  const loadRequests = useCallback(async (isManual = false) => {
    if (isManual) {
      setRefreshing(true);
    }
    setError(null);
    setRefreshNotice(null);

    try {
      const [requestsData, productsData] = await Promise.all([
        fetchStockRequests(activeFilter),
        fetchProducts().catch(() => []),
      ]);
      setRequests(requestsData);
      if (productsData.length > 0) {
        setCatalog(productsData);
      }
      setError(null);
      const now = new Date();
      setLastRefreshed(now);
      if (isManual) {
        setRefreshNotice(`Requests updated ${formatFreshness(now)}.`);
      }
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? `${err.code}: ${err.message}`
          : "Failed to fetch stock requests from gateway.";
      setError(msg);
      if (isManual) {
        setRefreshNotice("Refresh failed. Displaying cached requests.");
      }
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [activeFilter]);

  useEffect(() => {
    let ignore = false;
    async function init() {
      try {
        const [requestsData, productsData] = await Promise.all([
          fetchStockRequests(activeFilter),
          fetchProducts().catch(() => []),
        ]);
        if (!ignore) {
          setRequests(requestsData);
          if (productsData.length > 0) {
            setCatalog(productsData);
          }
          setError(null);
          setLastRefreshed(new Date());
        }
      } catch (err) {
        if (!ignore) {
          setError(
            err instanceof ApiError
              ? `${err.code}: ${err.message}`
              : "Failed to fetch stock requests from gateway."
          );
        }
      } finally {
        if (!ignore) {
          setLoading(false);
        }
      }
    }
    void init();
    return () => {
      ignore = true;
    };
  }, [activeFilter]);

  // Real-time invalidation via SSE
  useEffect(() => {
    return subscribeInvalidation(() => {
      void loadRequests(false);
    });
  }, [subscribeInvalidation, loadRequests]);

  const activeFilterLabel =
    STATUS_FILTERS.find((f) => f.value === activeFilter)?.label ?? activeFilter;

  // F23: Await clipboard write before announcing success, handle failure gracefully
  async function copyToClipboard(id: string) {
    if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(id);
        setCopiedId(id);
        setTimeout(() => setCopiedId(null), 2000);
      } catch {
        // Fallback for permission denial
        setCopiedId(null);
      }
    }
  }

  const statusColors: Record<StockRequestStatus, { badge: string; text: string }> = {
    ACCEPTED: { badge: "bg-blue-50 text-blue-800 border-blue-200", text: "Accepted" },
    PICKED: { badge: "bg-amber-50 text-amber-800 border-amber-200", text: "Picked" },
    DISPATCHED: { badge: "bg-emerald-50 text-emerald-800 border-emerald-200", text: "Dispatched" },
    REJECTED: { badge: "bg-rose-50 text-rose-800 border-rose-200", text: "Rejected" },
  };

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <span className="inline-flex items-center rounded-full bg-teal-50 px-2.5 py-0.5 text-xs font-semibold text-teal-800 border border-teal-200">
              Branch Orders
            </span>
            <span className="text-xs text-slate-400">·</span>
            <span className="text-xs font-medium text-slate-500">PostgreSQL Durable Receipts</span>
          </div>
          <h1 className="mt-1.5 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
            Stock Requests History
          </h1>
          <p className="mt-1 text-xs text-slate-600 sm:text-sm">
            Authoritative log of all branch customer reservations, warehouse picks, and dispatches.
          </p>
        </div>
        <div className="flex items-center gap-2.5">
          <button
            type="button"
            onClick={() => void loadRequests(true)}
            disabled={loading || refreshing}
            className="inline-flex min-h-[40px] items-center gap-2 rounded-xl border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 shadow-xs hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50"
          >
            <svg
              className={`h-4 w-4 ${loading || refreshing ? "animate-spin text-teal-600" : ""}`}
              fill="none"
              viewBox="0 0 24 24"
              strokeWidth="2"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99"
              />
            </svg>
            {refreshing ? "Refreshing..." : "Refresh Requests"}
          </button>
          <Link
            href="/requests/new?returnTo=/requests"
            className="inline-flex min-h-[40px] items-center justify-center rounded-xl bg-teal-700 px-4 py-2 text-xs font-bold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 transition-colors"
          >
            + New Request
          </Link>
        </div>
      </div>

      {/* Network Warning Context Banner */}
      {warningMessage && (
        <div
          role="alert"
          className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4 text-amber-900"
        >
          <svg className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 9a1 1 0 100-2 1 1 0 000 2z"
              clipRule="evenodd"
            />
          </svg>
          <div className="text-xs">
            <strong className="font-semibold text-amber-900 block">Live updates disconnected</strong>
            <p className="text-amber-800 mt-0.5">{warningMessage}</p>
          </div>
        </div>
      )}

      {/* Action / Refresh Notice */}
      {refreshNotice && (
        <div
          role="status"
          aria-live="polite"
          className="rounded-xl border border-teal-200 bg-teal-50 p-3.5 text-xs font-semibold text-teal-900"
        >
          ✓ {refreshNotice}
        </div>
      )}

      {error && (
        <div
          role="alert"
          aria-live="assertive"
          className="flex items-start justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-900"
        >
          <div>
            <h2 className="font-semibold text-sm">Failed to Load Requests</h2>
            <p className="mt-1 text-xs text-rose-800">{error}</p>
          </div>
          <button
            type="button"
            onClick={() => void loadRequests(true)}
            className="rounded-lg bg-rose-100 px-3 py-1 text-xs font-semibold text-rose-900 hover:bg-rose-200"
          >
            Retry
          </button>
        </div>
      )}

      {/* Status Filter Tabs & Connection Line */}
      <div className="flex flex-col gap-3 rounded-xl border border-slate-200/80 bg-white p-4 shadow-xs">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <nav className="flex flex-wrap gap-1.5" aria-label="Request Status Filter">
            {STATUS_FILTERS.map((tab) => {
              const isSelected = activeFilter === tab.value;
              return (
                <button
                  key={tab.value}
                  type="button"
                  aria-pressed={isSelected}
                  onClick={() => setActiveFilter(tab.value)}
                  className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors ${
                    isSelected
                      ? "bg-teal-700 text-white shadow-xs"
                      : "bg-slate-100 text-slate-700 hover:bg-slate-200"
                  }`}
                >
                  {tab.label}
                </button>
              );
            })}
          </nav>

          <div className="flex items-center gap-3 text-xs text-slate-500">
            <span>
              Feed:{" "}
              <strong className={isLive ? "text-emerald-700" : "text-amber-700"}>
                {isLive ? "Live NIO/SSE Synced" : "Live updates disconnected"}
              </strong>
            </span>
            {lastRefreshed && (
              <span>
                Fetched:{" "}
                <strong className="font-mono text-slate-700">
                  {formatOperationalTime(lastRefreshed)}
                </strong>
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Requests Content */}
      {loading ? (
        <div className="space-y-3">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="h-20 animate-pulse rounded-xl border border-slate-200 bg-slate-100" />
          ))}
        </div>
      ) : requests.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 p-12 text-center bg-white">
          <h2 className="text-base font-semibold text-slate-900">
            {activeFilter !== "ALL"
              ? `No ${activeFilterLabel.toLowerCase()} requests`
              : "No stock requests found"}
          </h2>
          <p className="mt-1 text-xs text-slate-500">
            {activeFilter !== "ALL"
              ? `No requests currently match status filter '${activeFilterLabel}'.`
              : "No stock requests have been submitted yet."}
          </p>
          <div className="mt-4 flex justify-center gap-3">
            {activeFilter !== "ALL" ? (
              <button
                type="button"
                onClick={() => setActiveFilter("ALL")}
                className="inline-flex min-h-[40px] items-center rounded-xl bg-teal-700 px-4 py-2 text-xs font-semibold text-white hover:bg-teal-800"
              >
                View all requests
              </button>
            ) : (
              <Link
                href="/requests/new?returnTo=/requests"
                className="inline-flex min-h-[40px] items-center rounded-xl bg-teal-700 px-4 py-2 text-xs font-semibold text-white hover:bg-teal-800"
              >
                Create first request
              </Link>
            )}
          </div>
        </div>
      ) : (
        <>
          {/* Mobile Request Cards (< 768px, F21: Product names, F22: consistent status) */}
          <div className="space-y-3 md:hidden">
            {requests.map((r) => {
              const productName = productNames.get(r.sku.toLowerCase()) || r.sku;
              const statusInfo = statusColors[r.status] || {
                badge: "bg-slate-100 text-slate-800 border-slate-200",
                text: r.status,
              };

              return (
                <div
                  key={r.requestId}
                  className="rounded-xl border border-slate-200/90 bg-white p-4 shadow-xs space-y-3"
                >
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <h2 className="font-bold text-base text-slate-900">{productName}</h2>
                      <p className="font-mono text-xs text-slate-500">{r.sku}</p>
                      <div className="flex items-center gap-1.5 mt-1 text-xs text-slate-500">
                        <span className="font-mono">Ref: {r.requestId.slice(0, 8)}...</span>
                        <button
                          type="button"
                          onClick={() => copyToClipboard(r.requestId)}
                          aria-label={`Copy reference ID for ${productName}`}
                          className="text-teal-700 hover:text-teal-900 text-[11px] underline"
                          title="Copy Full UUID"
                        >
                          {copiedId === r.requestId ? "Copied!" : "Copy"}
                        </button>
                      </div>
                    </div>
                    <span
                      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold shrink-0 ${statusInfo.badge}`}
                    >
                      {statusInfo.text}
                    </span>
                  </div>

                  <div className="flex items-baseline justify-between border-t border-slate-100 pt-2.5 text-xs">
                    <div>
                      <span className="text-slate-500">Quantity: </span>
                      <strong className="font-mono text-base font-bold text-slate-900">
                        {formatUnits(r.quantity)}
                      </strong>
                    </div>
                    <span className="text-slate-500 font-mono text-[11px]" title={r.createdAt || ""}>
                      {r.createdAt ? formatFullDateTime(r.createdAt) : "Recent"}
                    </span>
                  </div>

                  {/* F20: Target exact request and stage in warehouse queue */}
                  <div className="pt-1">
                    {r.status === "ACCEPTED" || r.status === "PICKED" ? (
                      <Link
                        href={`/warehouse?requestId=${encodeURIComponent(r.requestId)}&stage=${r.status === "PICKED" ? "DISPATCH" : "PICK"}`}
                        className="flex min-h-[44px] w-full items-center justify-center rounded-xl bg-teal-700 px-4 py-2.5 text-xs font-bold text-white shadow-xs hover:bg-teal-800 transition-colors"
                      >
                        Open in Warehouse Queue →
                      </Link>
                    ) : r.status === "DISPATCHED" ? (
                      <div className="text-center py-2 text-xs font-semibold text-emerald-700 bg-emerald-50 rounded-lg">
                        ✓ Dispatched &amp; Completed
                      </div>
                    ) : (
                      /* F22: Do not invent 'Insufficient Stock' on mobile when unsupplied */
                      <div className="text-center py-2 text-xs font-semibold text-rose-700 bg-rose-50 rounded-lg">
                        ✕ Closed ({r.status})
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          {/* Desktop Requests Table (>= 768px, F21: Product names, F20: deep links) */}
          <div className="hidden md:block overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-left text-sm">
                <thead className="bg-slate-50 text-xs font-semibold uppercase tracking-wider text-slate-600">
                  <tr>
                    <th scope="col" className="px-5 py-3">
                      Product / SKU
                    </th>
                    <th scope="col" className="px-5 py-3 text-right">
                      Units
                    </th>
                    <th scope="col" className="px-5 py-3 text-center">
                      Status
                    </th>
                    <th scope="col" className="px-5 py-3">
                      Submitted
                    </th>
                    <th scope="col" className="px-5 py-3 text-right">
                      Fulfillment
                    </th>
                    <th scope="col" className="px-5 py-3">
                      Reference ID
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {requests.map((r) => {
                    const productName = productNames.get(r.sku.toLowerCase()) || r.sku;
                    const statusInfo = statusColors[r.status] || {
                      badge: "bg-slate-100 text-slate-800 border-slate-200",
                      text: r.status,
                    };

                    return (
                      <tr key={r.requestId} className="hover:bg-slate-50">
                        <td className="px-5 py-4">
                          <div className="font-semibold text-slate-900">{productName}</div>
                          <div className="font-mono text-xs text-slate-500">{r.sku}</div>
                        </td>
                        <td className="px-5 py-4 text-right font-mono font-bold text-slate-900">
                          {formatUnits(r.quantity)}
                        </td>
                        <td className="px-5 py-4 text-center">
                          <span
                            className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold ${statusInfo.badge}`}
                          >
                            {statusInfo.text}
                          </span>
                        </td>
                        <td className="px-5 py-4 text-xs text-slate-500 font-mono" title={r.createdAt || ""}>
                          {r.createdAt ? formatFullDateTime(r.createdAt) : "Recent"}
                        </td>
                        <td className="px-5 py-4 text-right text-xs">
                          {/* F20: Deep link directly to request and stage in warehouse */}
                          {r.status === "ACCEPTED" || r.status === "PICKED" ? (
                            <Link
                              href={`/warehouse?requestId=${encodeURIComponent(r.requestId)}&stage=${r.status === "PICKED" ? "DISPATCH" : "PICK"}`}
                              className="font-bold text-teal-700 hover:text-teal-900"
                            >
                              Open in Queue →
                            </Link>
                          ) : r.status === "DISPATCHED" ? (
                            <span className="text-emerald-700 font-semibold">Completed</span>
                          ) : (
                            <span className="text-rose-600 font-semibold">Closed</span>
                          )}
                        </td>
                        <td className="px-5 py-4 font-mono text-xs text-slate-500">
                          <div className="flex items-center gap-1.5">
                            <span title={r.requestId}>{r.requestId.slice(0, 10)}...</span>
                            <button
                              type="button"
                              onClick={() => copyToClipboard(r.requestId)}
                              aria-label={`Copy reference ID for ${productName}`}
                              className="text-teal-700 hover:text-teal-900 text-[11px] underline"
                              title="Copy Full UUID"
                            >
                              {copiedId === r.requestId ? "Copied" : "Copy"}
                            </button>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
