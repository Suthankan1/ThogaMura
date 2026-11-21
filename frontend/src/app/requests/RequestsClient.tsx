"use client";

import { useEffect, useState, useCallback } from "react";
import Link from "next/link";
import { fetchStockRequests, ApiError } from "@/lib/api/inventory";
import type { StockRequest, StockRequestStatus } from "@/lib/types/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";

const STATUS_FILTERS: Array<{ label: string; value: string }> = [
  { label: "All Requests", value: "ALL" },
  { label: "Accepted", value: "ACCEPTED" },
  { label: "Rejected", value: "REJECTED" },
  { label: "Picked", value: "PICKED" },
  { label: "Dispatched", value: "DISPATCHED" },
];

export function RequestsClient() {
  const [requests, setRequests] = useState<StockRequest[]>([]);
  const [activeFilter, setActiveFilter] = useState("ALL");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null);

  const { subscribeInvalidation, isLive, warningMessage } = useStockEvents();

  const loadRequests = useCallback(async () => {
    try {
      const data = await fetchStockRequests(activeFilter);
      setRequests(data);
      setError(null);
      setLastRefreshed(new Date());
    } catch (err) {
      if (err instanceof ApiError) {
        setError(`${err.code}: ${err.message}`);
      } else {
        setError("Failed to fetch stock requests from gateway.");
      }
    } finally {
      setLoading(false);
    }
  }, [activeFilter]);

  useEffect(() => {
    let ignore = false;
    async function init() {
      try {
        const data = await fetchStockRequests(activeFilter);
        if (!ignore) {
          setRequests(data);
          setError(null);
          setLastRefreshed(new Date());
        }
      } catch (err) {
        if (!ignore) {
          if (err instanceof ApiError) {
            setError(`${err.code}: ${err.message}`);
          } else {
            setError("Failed to fetch stock requests from gateway.");
          }
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
      void loadRequests();
    });
  }, [subscribeInvalidation, loadRequests]);

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wider text-teal-700">
            Fulfillment Tracking
          </p>
          <h1 className="mt-1 text-3xl font-bold tracking-tight text-slate-900 sm:text-4xl">
            Stock Requests
          </h1>
          <p className="mt-2 text-sm text-slate-600">
            Authoritative history of all customer reservations, rejections, and picker state transitions.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => void loadRequests()}
            disabled={loading}
            className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-semibold text-slate-700 shadow-xs hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50"
          >
            <svg
              className={`h-4 w-4 ${loading ? "animate-spin" : ""}`}
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
            Refresh
          </button>
          <Link
            href="/requests/new"
            className="inline-flex items-center rounded-lg bg-teal-700 px-4 py-2 text-sm font-semibold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600"
          >
            + New Request
          </Link>
        </div>
      </div>

      {/* Network Warning */}
      {warningMessage && (
        <div
          role="alert"
          className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4 text-amber-900"
        >
          <svg className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 9a1 1 0 100-2 1 1 0 000 2z"
              clipRule="evenodd"
            />
          </svg>
          <div>
            <h2 className="font-semibold text-amber-900">Real-Time Invalidation Interrupted</h2>
            <p className="mt-1 text-sm text-amber-800">{warningMessage}</p>
          </div>
        </div>
      )}

      {/* Error Banner */}
      {error && (
        <div
          role="alert"
          className="flex items-start justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-900"
        >
          <div>
            <h2 className="font-semibold">Failed to Load Requests</h2>
            <p className="mt-1 text-sm text-rose-800">{error}</p>
          </div>
          <button
            type="button"
            onClick={() => void loadRequests()}
            className="rounded-lg bg-rose-100 px-3 py-1 text-xs font-semibold text-rose-900 hover:bg-rose-200"
          >
            Retry
          </button>
        </div>
      )}

      {/* Status Filter Tabs */}
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-3">
        <nav className="flex gap-2" aria-label="Request Status Filter">
          {STATUS_FILTERS.map((tab) => (
            <button
              key={tab.value}
              type="button"
              onClick={() => setActiveFilter(tab.value)}
              className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-colors ${
                activeFilter === tab.value
                  ? "bg-teal-700 text-white shadow-xs"
                  : "bg-white text-slate-600 hover:bg-slate-100 border border-slate-200"
              }`}
            >
              {tab.label}
            </button>
          ))}
        </nav>
        <div className="text-xs text-slate-500">
          Feed: <strong className={isLive ? "text-emerald-700" : "text-amber-700"}>{isLive ? "Live" : "Paused"}</strong>
          {lastRefreshed && <span className="ml-3">Synced: {lastRefreshed.toLocaleTimeString()}</span>}
        </div>
      </div>

      {/* Requests Table */}
      {loading ? (
        <div className="space-y-3">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="h-16 animate-pulse rounded-xl border border-slate-200 bg-slate-100" />
          ))}
        </div>
      ) : requests.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 p-12 text-center">
          <p className="text-base font-medium text-slate-900">No stock requests found</p>
          <p className="mt-1 text-sm text-slate-500">
            {activeFilter !== "ALL"
              ? `No requests match status filter '${activeFilter}'.`
              : "No stock requests have been submitted yet."}
          </p>
          <Link
            href="/requests/new"
            className="mt-4 inline-flex items-center rounded-lg bg-teal-700 px-4 py-2 text-sm font-semibold text-white hover:bg-teal-800"
          >
            Create First Request
          </Link>
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-slate-200 text-left text-sm">
              <thead className="bg-slate-50 text-xs font-semibold uppercase tracking-wider text-slate-600">
                <tr>
                  <th scope="col" className="px-6 py-3">
                    Request ID
                  </th>
                  <th scope="col" className="px-6 py-3">
                    SKU
                  </th>
                  <th scope="col" className="px-6 py-3 text-right">
                    Quantity
                  </th>
                  <th scope="col" className="px-6 py-3 text-center">
                    Status
                  </th>
                  <th scope="col" className="px-6 py-3">
                    Timestamp
                  </th>
                  <th scope="col" className="px-6 py-3 text-right">
                    Fulfillment
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {requests.map((r) => {
                  const statusColors: Record<StockRequestStatus, string> = {
                    ACCEPTED: "bg-blue-100 text-blue-800",
                    REJECTED: "bg-rose-100 text-rose-800",
                    PICKED: "bg-amber-100 text-amber-800",
                    DISPATCHED: "bg-emerald-100 text-emerald-800",
                  };

                  return (
                    <tr key={r.requestId} className="hover:bg-slate-50">
                      <td className="px-6 py-4 font-mono text-xs text-slate-900 break-all select-all">
                        {r.requestId}
                      </td>
                      <td className="px-6 py-4 font-mono font-semibold text-slate-900">
                        {r.sku}
                      </td>
                      <td className="px-6 py-4 text-right font-mono font-semibold text-slate-800">
                        {r.quantity}
                      </td>
                      <td className="px-6 py-4 text-center">
                        <span
                          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                            statusColors[r.status] || "bg-slate-100 text-slate-700"
                          }`}
                        >
                          {r.status}
                        </span>
                      </td>
                      <td className="px-6 py-4 text-xs text-slate-500 whitespace-nowrap">
                        {r.createdAt ? new Date(r.createdAt).toLocaleString() : "—"}
                      </td>
                      <td className="px-6 py-4 text-right">
                        {(r.status === "ACCEPTED" || r.status === "PICKED") && (
                          <Link
                            href="/warehouse"
                            className="inline-flex items-center rounded-md bg-teal-50 px-2.5 py-1 text-xs font-semibold text-teal-700 hover:bg-teal-100"
                          >
                            Fulfill in Warehouse →
                          </Link>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
