"use client";

import { useEffect, useState, useCallback } from "react";
import Link from "next/link";
import {
  fetchStockRequests,
  pickStockRequest,
  dispatchStockRequest,
  fetchStockMovements,
  ApiError,
} from "@/lib/api/inventory";
import type { StockRequest, StockMovement } from "@/lib/types/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";
import {
  formatOperationalTime,
  formatFullDateTime,
  formatFreshness,
} from "@/lib/formatters";

export function WarehouseQueueClient() {
  const [queue, setQueue] = useState<StockRequest[]>([]);
  const [movements, setMovements] = useState<StockMovement[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false);
  const [lastFetchedAt, setLastFetchedAt] = useState<Date | null>(null);
  const [actionLoading, setActionLoading] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [refreshNotice, setRefreshNotice] = useState<string | null>(null);

  const { subscribeInvalidation, isLive, warningMessage } = useStockEvents();

  const loadWarehouseData = useCallback(async (isManualRefresh = false) => {
    if (isManualRefresh) {
      setRefreshing(true);
    }
    setError(null);
    setRefreshNotice(null);

    try {
      const [allRequests, movementRecords] = await Promise.all([
        fetchStockRequests(),
        fetchStockMovements(),
      ]);

      const actionable = allRequests.filter(
        (r) => r.status === "ACCEPTED" || r.status === "PICKED"
      );
      setQueue(actionable);
      setMovements(movementRecords);
      const now = new Date();
      setLastFetchedAt(now);
      setHasLoadedOnce(true);
      setError(null);

      if (isManualRefresh) {
        setRefreshNotice(`Queue updated ${formatFreshness(now)}.`);
      }
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? `${err.code}: ${err.message}`
          : "Failed to load warehouse fulfillment queue.";
      setError(msg);

      if (isManualRefresh) {
        setRefreshNotice("Refresh failed. Retaining last known queue state.");
      }
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    let ignore = false;
    async function init() {
      try {
        const [allRequests, movementRecords] = await Promise.all([
          fetchStockRequests(),
          fetchStockMovements(),
        ]);
        if (!ignore) {
          const actionable = allRequests.filter(
            (r) => r.status === "ACCEPTED" || r.status === "PICKED"
          );
          setQueue(actionable);
          setMovements(movementRecords);
          setLastFetchedAt(new Date());
          setHasLoadedOnce(true);
          setError(null);
        }
      } catch (err) {
        if (!ignore) {
          setError(
            err instanceof ApiError
              ? `${err.code}: ${err.message}`
              : "Failed to load warehouse fulfillment queue."
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
  }, []);

  // Real-time invalidation
  useEffect(() => {
    return subscribeInvalidation(() => {
      void loadWarehouseData(false);
    });
  }, [subscribeInvalidation, loadWarehouseData]);

  async function handlePick(requestId: string) {
    setActionLoading((prev) => ({ ...prev, [requestId]: true }));
    setError(null);
    setSuccessMessage(null);

    try {
      await pickStockRequest(requestId);
      setSuccessMessage(`Request ${requestId.slice(0, 8)}... successfully marked as PICKED.`);
      await loadWarehouseData(false);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(`Failed to pick request: ${err.message}`);
      } else {
        setError("Network failure marking request as picked.");
      }
    } finally {
      setActionLoading((prev) => ({ ...prev, [requestId]: false }));
    }
  }

  async function handleDispatch(requestId: string) {
    setActionLoading((prev) => ({ ...prev, [requestId]: true }));
    setError(null);
    setSuccessMessage(null);

    try {
      await dispatchStockRequest(requestId);
      setSuccessMessage(
        `Request ${requestId.slice(0, 8)}... successfully marked as DISPATCHED. PostgreSQL balances decremented.`
      );
      await loadWarehouseData(false);
    } catch (err) {
      if (err instanceof ApiError) {
        setError(`Failed to dispatch request: ${err.message}`);
      } else {
        setError("Network failure marking request as dispatched.");
      }
    } finally {
      setActionLoading((prev) => ({ ...prev, [requestId]: false }));
    }
  }

  const pendingPicks = queue.filter((r) => r.status === "ACCEPTED").length;
  const pendingDispatches = queue.filter((r) => r.status === "PICKED").length;
  const totalMovements = movements.length;

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wider text-teal-700">
            Operations &amp; Fulfillment
          </p>
          <h1 className="mt-1 text-3xl font-bold tracking-tight text-slate-900 sm:text-4xl">
            Warehouse Queue
          </h1>
          <p className="mt-2 text-sm text-slate-600">
            Pickers review accepted reservations and execute strict ACCEPTED → PICKED → DISPATCHED transitions.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => void loadWarehouseData(true)}
            disabled={loading || refreshing}
            className="inline-flex items-center gap-2 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-sm font-semibold text-slate-700 shadow-xs hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50"
          >
            <svg
              className={`h-4 w-4 ${refreshing || loading ? "animate-spin text-teal-600" : ""}`}
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
            {refreshing ? "Refreshing..." : "Refresh Queue"}
          </button>
        </div>
      </div>

      {/* Metrics Summary (QA-04: Show Queue unavailable if no successful fetch exists) */}
      <div className="grid grid-cols-3 gap-4">
        <div className="rounded-xl border border-blue-200 bg-blue-50/60 p-4">
          <span className="text-xs font-semibold uppercase tracking-wider text-blue-700">Pending Pick</span>
          {hasLoadedOnce ? (
            <p className="mt-1 font-mono text-2xl font-bold text-blue-900">{pendingPicks}</p>
          ) : (
            <p className="mt-1 text-sm font-semibold text-slate-500">Queue unavailable</p>
          )}
        </div>
        <div className="rounded-xl border border-amber-200 bg-amber-50/60 p-4">
          <span className="text-xs font-semibold uppercase tracking-wider text-amber-700">Pending Dispatch</span>
          {hasLoadedOnce ? (
            <p className="mt-1 font-mono text-2xl font-bold text-amber-900">{pendingDispatches}</p>
          ) : (
            <p className="mt-1 text-sm font-semibold text-slate-500">Queue unavailable</p>
          )}
        </div>
        <div className="rounded-xl border border-emerald-200 bg-emerald-50/60 p-4">
          <span className="text-xs font-semibold uppercase tracking-wider text-emerald-700">Audit Movements</span>
          {hasLoadedOnce ? (
            <p className="mt-1 font-mono text-2xl font-bold text-emerald-900">{totalMovements}</p>
          ) : (
            <p className="mt-1 text-sm font-semibold text-slate-500">Queue unavailable</p>
          )}
        </div>
      </div>

      {/* Real-time Invalidation Disruption Warning (QA-05) */}
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
            <h2 className="font-semibold text-amber-900">Live updates disconnected</h2>
            <p className="mt-1 text-sm text-amber-800">{warningMessage}</p>
          </div>
        </div>
      )}

      {/* Action / Refresh Notice */}
      {refreshNotice && (
        <div
          role="status"
          aria-live="polite"
          className="rounded-xl border border-teal-200 bg-teal-50 p-4 text-xs font-semibold text-teal-900"
        >
          ✓ {refreshNotice}
        </div>
      )}

      {successMessage && (
        <div
          role="status"
          aria-live="polite"
          className="rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm font-semibold text-emerald-900"
        >
          ✓ {successMessage}
        </div>
      )}

      {error && (
        <div
          role="alert"
          aria-live="assertive"
          className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm font-semibold text-rose-900"
        >
          ⚠ {error}
        </div>
      )}

      {/* Actionable Queue */}
      <div className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-xl font-bold text-slate-900">
            Pending Fulfillment ({hasLoadedOnce ? queue.length : "—"})
          </h2>
          <div className="flex items-center gap-3 text-xs text-slate-500">
            <span>
              Feed status:{" "}
              <strong className={isLive ? "text-emerald-700" : "text-amber-700"}>
                {isLive ? "Live NIO/SSE Synced" : "Live updates disconnected"}
              </strong>
            </span>
            {lastFetchedAt && (
              <span>
                Last successful fetch:{" "}
                <strong className="font-mono text-slate-700">
                  {formatOperationalTime(lastFetchedAt)}
                </strong>
              </span>
            )}
          </div>
        </div>

        {loading ? (
          <div className="space-y-3">
            {[1, 2, 3].map((i) => (
              <div
                key={i}
                className="h-20 animate-pulse rounded-xl border border-slate-200 bg-slate-100"
              />
            ))}
          </div>
        ) : !hasLoadedOnce && error ? (
          <div className="rounded-xl border border-dashed border-rose-300 bg-rose-50/50 p-10 text-center">
            <h3 className="font-semibold text-rose-900">Queue unavailable</h3>
            <p className="mt-1 text-xs text-rose-700">
              Could not retrieve the fulfillment queue from the warehouse server.
            </p>
            <button
              type="button"
              onClick={() => void loadWarehouseData(true)}
              className="mt-4 inline-flex items-center rounded-lg bg-rose-700 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-rose-800"
            >
              Retry Connection
            </button>
          </div>
        ) : queue.length === 0 ? (
          <div className="rounded-xl border border-dashed border-slate-300 p-10 text-center">
            <h3 className="font-semibold text-slate-900">
              No pending work {formatFreshness(lastFetchedAt)}
            </h3>
            <p className="mt-1 text-xs text-slate-500">
              All accepted stock reservations have been picked and dispatched. No pending work in queue.
            </p>
            <Link
              href="/requests/new"
              className="mt-4 inline-flex items-center rounded-lg bg-teal-700 px-3.5 py-1.5 text-xs font-semibold text-white hover:bg-teal-800"
            >
              Create Stock Request
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
                      Item
                    </th>
                    <th scope="col" className="px-6 py-3 text-right">
                      Quantity
                    </th>
                    <th scope="col" className="px-6 py-3 text-center">
                      Current Stage
                    </th>
                    <th scope="col" className="px-6 py-3 text-right">
                      Picker Action
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {queue.map((r) => {
                    const isProcessing = !!actionLoading[r.requestId];
                    return (
                      <tr key={r.requestId} className="hover:bg-slate-50">
                        <td className="px-6 py-4 font-mono text-xs text-slate-900 select-all">
                          {r.requestId}
                        </td>
                        <td className="px-6 py-4 font-mono font-semibold text-slate-900">
                          {r.sku}
                        </td>
                        <td className="px-6 py-4 text-right font-mono font-bold text-slate-800">
                          {r.quantity}
                        </td>
                        <td className="px-6 py-4 text-center">
                          <span
                            className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                              r.status === "ACCEPTED"
                                ? "bg-blue-100 text-blue-800"
                                : "bg-amber-100 text-amber-800"
                            }`}
                          >
                            {r.status}
                          </span>
                        </td>
                        <td className="px-6 py-4 text-right">
                          {r.status === "ACCEPTED" && (
                            <button
                              type="button"
                              onClick={() => void handlePick(r.requestId)}
                              disabled={isProcessing}
                              className="inline-flex items-center rounded-lg bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white shadow-xs hover:bg-amber-700 disabled:opacity-50"
                            >
                              {isProcessing ? "Processing..." : "Mark Picked"}
                            </button>
                          )}
                          {r.status === "PICKED" && (
                            <button
                              type="button"
                              onClick={() => void handleDispatch(r.requestId)}
                              disabled={isProcessing}
                              className="inline-flex items-center rounded-lg bg-emerald-700 px-3 py-1.5 text-xs font-semibold text-white shadow-xs hover:bg-emerald-800 disabled:opacity-50"
                            >
                              {isProcessing ? "Processing..." : "Mark Dispatched"}
                            </button>
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

      {/* Dispatch Movement History */}
      <div className="space-y-4 pt-6 border-t border-slate-200">
        <div>
          <h2 className="text-xl font-bold text-slate-900">Stock Movements History</h2>
          <p className="mt-1 text-xs text-slate-500">
            Immutable PostgreSQL audit records of completed stock dispatches.
          </p>
        </div>

        {movements.length === 0 ? (
          <p className="text-xs text-slate-500 rounded-lg border border-dashed border-slate-200 p-6 text-center">
            No stock movements recorded yet. Completed dispatches will appear here.
          </p>
        ) : (
          <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-left text-xs">
                <thead className="bg-slate-50 font-semibold uppercase tracking-wider text-slate-600">
                  <tr>
                    <th scope="col" className="px-4 py-2.5">
                      Movement ID
                    </th>
                    <th scope="col" className="px-4 py-2.5">
                      Request ID
                    </th>
                    <th scope="col" className="px-4 py-2.5">
                      SKU
                    </th>
                    <th scope="col" className="px-4 py-2.5 text-right">
                      Quantity Delta
                    </th>
                    <th scope="col" className="px-4 py-2.5">
                      Reason
                    </th>
                    <th scope="col" className="px-4 py-2.5">
                      Timestamp
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 font-mono text-slate-700">
                  {movements.map((m) => (
                    <tr key={m.id} className="hover:bg-slate-50">
                      <td className="px-4 py-2.5 font-bold text-slate-900">#{m.id}</td>
                      <td className="px-4 py-2.5 text-slate-600 select-all" title={m.stockRequestId}>
                        {m.stockRequestId.slice(0, 18)}...
                      </td>
                      <td className="px-4 py-2.5 font-semibold text-slate-900">{m.sku}</td>
                      <td className="px-4 py-2.5 text-right font-bold text-rose-700">
                        {m.quantityDelta}
                      </td>
                      <td className="px-4 py-2.5 font-sans font-semibold text-slate-600">
                        {m.reason}
                      </td>
                      <td
                        className="px-4 py-2.5 font-sans text-slate-500"
                        title={m.occurredAt}
                      >
                        {formatFullDateTime(m.occurredAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
