"use client";

import { useEffect, useState, useCallback, useRef, useMemo } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import {
  fetchStockRequests,
  pickStockRequest,
  dispatchStockRequest,
  fetchStockMovements,
  fetchProducts,
  ApiError,
} from "@/lib/api/inventory";
import type { StockRequest, StockMovement, Product } from "@/lib/types/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";
import {
  formatOperationalTime,
  formatFullDateTime,
  formatFreshness,
  formatUnits,
} from "@/lib/formatters";

export function WarehouseQueueClient() {
  const searchParams = useSearchParams();
  const targetRequestId = searchParams.get("requestId");
  const targetStage = searchParams.get("stage");

  const [queue, setQueue] = useState<StockRequest[]>([]);
  const [movements, setMovements] = useState<StockMovement[]>([]);
  const [catalog, setCatalog] = useState<Product[]>([]);
  const [activeTab, setActiveTab] = useState<"QUEUE" | "MOVEMENTS">("QUEUE");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false);
  const [lastFetchedAt, setLastFetchedAt] = useState<Date | null>(null);
  const [actionLoading, setActionLoading] = useState<Record<string, boolean>>({});
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [refreshNotice, setRefreshNotice] = useState<string | null>(null);

  // F24: Item-specific verification modal for dispatch
  const [dispatchReviewItem, setDispatchReviewItem] = useState<StockRequest | null>(null);
  const dispatchModalRef = useRef<HTMLDivElement | null>(null);
  const dispatchCancelBtnRef = useRef<HTMLButtonElement | null>(null);

  // Tab button refs for WAI-ARIA arrow navigation (F27)
  const queueTabRef = useRef<HTMLButtonElement | null>(null);
  const movementsTabRef = useRef<HTMLButtonElement | null>(null);

  // Targeted card highlight ref (F20)
  const highlightedCardRef = useRef<HTMLDivElement | null>(null);

  const { subscribeInvalidation, isLive, warningMessage } = useStockEvents();

  // Map of SKU -> Product Name (F21)
  const productNames = useMemo(() => {
    const map = new Map<string, string>();
    for (const p of catalog) {
      map.set(p.sku.toLowerCase(), p.name);
    }
    return map;
  }, [catalog]);

  const loadWarehouseData = useCallback(async (isManualRefresh = false) => {
    if (isManualRefresh) {
      setRefreshing(true);
    }
    setError(null);
    setRefreshNotice(null);

    try {
      const [allRequests, movementRecords, productsData] = await Promise.all([
        fetchStockRequests(),
        fetchStockMovements(),
        fetchProducts().catch(() => []),
      ]);

      const actionable = allRequests.filter(
        (r) => r.status === "ACCEPTED" || r.status === "PICKED"
      );
      setQueue(actionable);
      setMovements(movementRecords);
      if (productsData.length > 0) {
        setCatalog(productsData);
      }
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
        const [allRequests, movementRecords, productsData] = await Promise.all([
          fetchStockRequests(),
          fetchStockMovements(),
          fetchProducts().catch(() => []),
        ]);
        if (!ignore) {
          const actionable = allRequests.filter(
            (r) => r.status === "ACCEPTED" || r.status === "PICKED"
          );
          setQueue(actionable);
          setMovements(movementRecords);
          if (productsData.length > 0) {
            setCatalog(productsData);
          }
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

  // F20: Handle incoming deep link with requestId and stage
  useEffect(() => {
    if (!targetRequestId || loading) return;

    // Switch to queue tab
    setActiveTab("QUEUE");

    // Scroll matching card into view and focus
    const timer = setTimeout(() => {
      const cardEl = document.getElementById(`request-card-${targetRequestId}`);
      if (cardEl) {
        cardEl.scrollIntoView({ behavior: "smooth", block: "center" });
        cardEl.focus();
      }
    }, 150);

    return () => clearTimeout(timer);
  }, [targetRequestId, loading]);

  // Real-time invalidation
  useEffect(() => {
    return subscribeInvalidation(() => {
      void loadWarehouseData(false);
    });
  }, [subscribeInvalidation, loadWarehouseData]);

  // Handle Routine Pick (F26: dark amber/teal button with high contrast)
  async function handlePick(requestId: string) {
    setActionLoading((prev) => ({ ...prev, [requestId]: true }));
    setError(null);
    setSuccessMessage(null);

    try {
      await pickStockRequest(requestId);
      setSuccessMessage(`Request ${requestId.slice(0, 8)}... marked as PICKED and staged in loading bay.`);
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

  // F24 & F36: Execute Dispatch after item-specific verification, with lost-reply recovery
  async function executeDispatch(item: StockRequest) {
    const requestId = item.requestId;
    setActionLoading((prev) => ({ ...prev, [requestId]: true }));
    setError(null);
    setSuccessMessage(null);
    setDispatchReviewItem(null); // Close modal

    try {
      await dispatchStockRequest(requestId);
      setSuccessMessage(
        `Dispatched ${formatUnits(item.quantity)} of ${productNames.get(item.sku.toLowerCase()) || item.sku} (Ref: ${requestId.slice(0, 8)}...). Stock balances permanently decremented in PostgreSQL.`
      );
      await loadWarehouseData(false);
    } catch (err) {
      // F36: Lost dispatch reply recovery - verify actual server state before leaving actionable!
      setError("Verifying dispatch outcome with central warehouse...");
      try {
        const freshRequests = await fetchStockRequests();
        const updated = freshRequests.find((r) => r.requestId === requestId);
        if (updated && updated.status === "DISPATCHED") {
          // It was committed! Recover gracefully
          setError(null);
          setSuccessMessage(
            `Confirmed: Request ${requestId.slice(0, 8)}... was already DISPATCHED and completed.`
          );
          await loadWarehouseData(false);
          return;
        }
      } catch {
        // Fall through to general error reporting
      }

      if (err instanceof ApiError) {
        setError(`Failed to dispatch request: ${err.message}`);
      } else {
        setError("Network failure marking request as dispatched.");
      }
    } finally {
      setActionLoading((prev) => ({ ...prev, [requestId]: false }));
    }
  }

  // Manage dispatch review modal focus trap and escape key (F24)
  useEffect(() => {
    if (!dispatchReviewItem) return;

    const timer = setTimeout(() => {
      dispatchCancelBtnRef.current?.focus();
    }, 50);

    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        setDispatchReviewItem(null);
      }
    }

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      clearTimeout(timer);
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [dispatchReviewItem]);

  // Tab keyboard arrow navigation (F27)
  const handleTabKeyDown = (e: React.KeyboardEvent, currentTab: "QUEUE" | "MOVEMENTS") => {
    if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
      e.preventDefault();
      if (currentTab === "QUEUE") {
        setActiveTab("MOVEMENTS");
        movementsTabRef.current?.focus();
      } else {
        setActiveTab("QUEUE");
        queueTabRef.current?.focus();
      }
    }
  };

  const pendingPicks = queue.filter((r) => r.status === "ACCEPTED");
  const pendingDispatches = queue.filter((r) => r.status === "PICKED");
  const totalMovements = movements.length;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <span className="inline-flex items-center rounded-full bg-teal-50 px-2.5 py-0.5 text-xs font-semibold text-teal-800 border border-teal-200">
              Warehouse Floor
            </span>
            <span className="text-xs text-slate-400">·</span>
            <span className="text-xs font-medium text-slate-500">Pick &amp; Dispatch Fulfillment</span>
          </div>
          <h1 className="mt-1.5 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
            Warehouse Fulfillment Queue
          </h1>
          <p className="mt-1 text-xs text-slate-600 sm:text-sm">
            Review accepted branch reservations and execute Pick and Dispatch workflow transitions.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => void loadWarehouseData(true)}
            disabled={loading || refreshing}
            className="inline-flex min-h-[40px] items-center gap-2 rounded-xl border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 shadow-xs hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50"
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

      {/* Navigation Tabs with WAI-ARIA and Integrated Counts (F25, F27) */}
      <div
        role="tablist"
        aria-label="Warehouse views"
        className="flex border-b border-slate-200 gap-2"
      >
        <button
          ref={queueTabRef}
          role="tab"
          id="tab-queue"
          aria-selected={activeTab === "QUEUE"}
          aria-controls="tabpanel-queue"
          tabIndex={activeTab === "QUEUE" ? 0 : -1}
          onKeyDown={(e) => handleTabKeyDown(e, "QUEUE")}
          onClick={() => setActiveTab("QUEUE")}
          className={`flex items-center gap-2 border-b-2 py-3 px-4 text-xs sm:text-sm font-bold transition-colors ${
            activeTab === "QUEUE"
              ? "border-teal-700 text-teal-800"
              : "border-transparent text-slate-500 hover:text-slate-800"
          }`}
        >
          <span>Active Queue</span>
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700 font-mono">
            {queue.length}
          </span>
        </button>
        <button
          ref={movementsTabRef}
          role="tab"
          id="tab-movements"
          aria-selected={activeTab === "MOVEMENTS"}
          aria-controls="tabpanel-movements"
          tabIndex={activeTab === "MOVEMENTS" ? 0 : -1}
          onKeyDown={(e) => handleTabKeyDown(e, "MOVEMENTS")}
          onClick={() => setActiveTab("MOVEMENTS")}
          className={`flex items-center gap-2 border-b-2 py-3 px-4 text-xs sm:text-sm font-bold transition-colors ${
            activeTab === "MOVEMENTS"
              ? "border-teal-700 text-teal-800"
              : "border-transparent text-slate-500 hover:text-slate-800"
          }`}
        >
          <span>Stock Movement Audit</span>
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700 font-mono">
            {movements.length}
          </span>
        </button>
      </div>

      {/* Network / Success / Error Notifications */}
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

      {refreshNotice && (
        <div
          role="status"
          aria-live="polite"
          className="rounded-xl border border-teal-200 bg-teal-50 p-3 text-xs font-semibold text-teal-900"
        >
          ✓ {refreshNotice}
        </div>
      )}

      {successMessage && (
        <div
          role="status"
          aria-live="polite"
          className="rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-xs font-semibold text-emerald-900"
        >
          ✓ {successMessage}
        </div>
      )}

      {error && (
        <div
          role="alert"
          aria-live="assertive"
          className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-xs font-semibold text-rose-900"
        >
          ⚠ {error}
        </div>
      )}

      {/* Tab 1: Active Queue */}
      <div
        role="tabpanel"
        id="tabpanel-queue"
        aria-labelledby="tab-queue"
        hidden={activeTab !== "QUEUE"}
        className="space-y-6"
      >
        {loading ? (
          <div className="space-y-3">
            {[1, 2, 3].map((i) => (
              <div key={i} className="h-24 animate-pulse rounded-xl border border-slate-200 bg-slate-100" />
            ))}
          </div>
        ) : !hasLoadedOnce && error ? (
          <div className="rounded-2xl border border-dashed border-rose-300 bg-rose-50/50 p-10 text-center">
            <h3 className="font-bold text-rose-900">Queue unavailable</h3>
            <p className="mt-1 text-xs text-rose-700">
              Could not retrieve fulfillment items from the warehouse command server.
            </p>
            <button
              type="button"
              onClick={() => void loadWarehouseData(true)}
              className="mt-4 inline-flex min-h-[40px] items-center rounded-xl bg-rose-700 px-4 py-2 text-xs font-semibold text-white hover:bg-rose-800"
            >
              Retry Connection
            </button>
          </div>
        ) : queue.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 p-12 text-center bg-white">
            <h3 className="font-bold text-slate-900 text-base">
              Fulfillment queue is clear {formatFreshness(lastFetchedAt)}
            </h3>
            <p className="mt-1 text-xs text-slate-500">
              All accepted stock reservations have been picked and dispatched. No pending work in queue.
            </p>
            <div className="mt-4">
              <Link
                href="/requests/new?returnTo=/warehouse"
                className="inline-flex min-h-[40px] items-center rounded-xl bg-teal-700 px-4 py-2 text-xs font-bold text-white hover:bg-teal-800"
              >
                Create Stock Request
              </Link>
            </div>
          </div>
        ) : (
          <div className="space-y-6">
            {/* Stage 1: Items to Pick */}
            {pendingPicks.length > 0 && (
              <div className="space-y-3">
                <div className="flex items-center justify-between">
                  <h2 className="text-base font-bold text-slate-900 flex items-center gap-2">
                    <span className="flex h-6 w-6 items-center justify-center rounded-full bg-blue-100 text-blue-800 text-xs font-bold">
                      1
                    </span>
                    <span>To Pick from Shelves ({pendingPicks.length})</span>
                  </h2>
                  <span className="text-xs text-slate-500">Accepted customer reservations</span>
                </div>

                <div className="grid gap-3 sm:grid-cols-2">
                  {pendingPicks.map((r) => {
                    const isProcessing = !!actionLoading[r.requestId];
                    const isTarget = targetRequestId === r.requestId;
                    const productName = productNames.get(r.sku.toLowerCase()) || r.sku;

                    return (
                      <div
                        key={r.requestId}
                        id={`request-card-${r.requestId}`}
                        tabIndex={-1}
                        className={`rounded-xl border p-4 shadow-xs space-y-3 flex flex-col justify-between transition-all ${
                          isTarget
                            ? "border-teal-500 ring-2 ring-teal-600 bg-teal-50/30"
                            : "border-blue-200/90 bg-white"
                        }`}
                      >
                        <div>
                          <div className="flex items-start justify-between gap-2">
                            <div>
                              <h3 className="font-bold text-base text-slate-900">{productName}</h3>
                              <p className="font-mono text-xs text-slate-500">{r.sku}</p>
                              <span className="font-mono text-[11px] text-slate-400 block mt-0.5">
                                Ref: {r.requestId.slice(0, 10)}...
                              </span>
                            </div>
                            <span className="inline-flex items-center rounded-full bg-blue-50 border border-blue-200 px-2.5 py-0.5 text-xs font-semibold text-blue-800 shrink-0">
                              Accepted
                            </span>
                          </div>

                          <div className="mt-3 border-t border-slate-100 pt-2.5 flex items-baseline justify-between text-xs">
                            <span className="text-slate-500">Quantity to Pick:</span>
                            <strong className="font-mono text-xl font-bold text-slate-900">
                              {formatUnits(r.quantity)}
                            </strong>
                          </div>
                        </div>

                        {/* F26: Accessible high contrast button */}
                        <button
                          type="button"
                          onClick={() => void handlePick(r.requestId)}
                          disabled={isProcessing}
                          className="flex min-h-[44px] w-full items-center justify-center rounded-xl bg-amber-800 px-4 py-2.5 text-xs font-bold text-white shadow-xs hover:bg-amber-900 focus:outline-hidden focus:ring-2 focus:ring-amber-700 disabled:opacity-50 transition-colors"
                        >
                          {isProcessing ? "Confirming Pick..." : "Confirm Pick"}
                        </button>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}

            {/* Stage 2: Items to Dispatch */}
            {pendingDispatches.length > 0 && (
              <div className="space-y-3 pt-4 border-t border-slate-100">
                <div className="flex items-center justify-between">
                  <h2 className="text-base font-bold text-slate-900 flex items-center gap-2">
                    <span className="flex h-6 w-6 items-center justify-center rounded-full bg-amber-100 text-amber-800 text-xs font-bold">
                      2
                    </span>
                    <span>To Dispatch for Courier Release ({pendingDispatches.length})</span>
                  </h2>
                  <span className="text-xs text-slate-500">Picked items staged in loading bay</span>
                </div>

                <div className="grid gap-3 sm:grid-cols-2">
                  {pendingDispatches.map((r) => {
                    const isProcessing = !!actionLoading[r.requestId];
                    const isTarget = targetRequestId === r.requestId;
                    const productName = productNames.get(r.sku.toLowerCase()) || r.sku;

                    return (
                      <div
                        key={r.requestId}
                        id={`request-card-${r.requestId}`}
                        tabIndex={-1}
                        className={`rounded-xl border p-4 shadow-xs space-y-3 flex flex-col justify-between transition-all ${
                          isTarget
                            ? "border-teal-500 ring-2 ring-teal-600 bg-teal-50/30"
                            : "border-amber-200/90 bg-white"
                        }`}
                      >
                        <div>
                          <div className="flex items-start justify-between gap-2">
                            <div>
                              <h3 className="font-bold text-base text-slate-900">{productName}</h3>
                              <p className="font-mono text-xs text-slate-500">{r.sku}</p>
                              <span className="font-mono text-[11px] text-slate-400 block mt-0.5">
                                Ref: {r.requestId.slice(0, 10)}...
                              </span>
                            </div>
                            <span className="inline-flex items-center rounded-full bg-amber-50 border border-amber-200 px-2.5 py-0.5 text-xs font-semibold text-amber-800 shrink-0">
                              Picked &amp; Staged
                            </span>
                          </div>

                          <div className="mt-3 border-t border-slate-100 pt-2.5 flex items-baseline justify-between text-xs">
                            <span className="text-slate-500">Quantity to Dispatch:</span>
                            <strong className="font-mono text-xl font-bold text-slate-900">
                              {formatUnits(r.quantity)}
                            </strong>
                          </div>
                        </div>

                        {/* F24: Review Dispatch opens confirmation modal */}
                        <button
                          type="button"
                          onClick={() => setDispatchReviewItem(r)}
                          disabled={isProcessing}
                          className="flex min-h-[44px] w-full items-center justify-center rounded-xl bg-teal-700 px-4 py-2.5 text-xs font-bold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50 transition-colors"
                        >
                          {isProcessing ? "Dispatching..." : "Review & Dispatch"}
                        </button>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Tab 2: Stock Movements Audit Records (F28: Responsive mobile cards) */}
      <div
        role="tabpanel"
        id="tabpanel-movements"
        aria-labelledby="tab-movements"
        hidden={activeTab !== "MOVEMENTS"}
        className="space-y-4"
      >
        <div>
          <h2 className="text-base font-bold text-slate-900">Stock Movements History</h2>
          <p className="text-xs text-slate-500 mt-0.5">
            Immutable PostgreSQL audit records of completed stock dispatches.
          </p>
        </div>

        {movements.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 p-12 text-center bg-white text-xs text-slate-500">
            No stock movements recorded yet. Completed dispatches will record durable audit rows here.
          </div>
        ) : (
          <>
            {/* Mobile Movement Cards (< 768px, F28: zero horizontal clipping) */}
            <div className="space-y-3 md:hidden">
              {movements.map((m) => {
                const productName = productNames.get(m.sku.toLowerCase()) || m.sku;
                return (
                  <div
                    key={m.id}
                    className="rounded-xl border border-slate-200/90 bg-white p-4 shadow-xs space-y-2 text-xs"
                  >
                    <div className="flex items-start justify-between">
                      <div>
                        <span className="font-mono font-bold text-slate-900">#{m.id}</span>
                        <h3 className="font-bold text-slate-900 text-sm mt-0.5">{productName}</h3>
                        <p className="font-mono text-slate-500 text-[11px]">{m.sku}</p>
                      </div>
                      <span className="font-mono text-base font-bold text-rose-700 bg-rose-50 border border-rose-200 px-2.5 py-0.5 rounded-full">
                        {m.quantityDelta} units
                      </span>
                    </div>

                    <div className="border-t border-slate-100 pt-2 flex flex-col gap-1 text-[11px] text-slate-600">
                      <div>
                        <span className="text-slate-400">Reason: </span>
                        <strong className="font-semibold text-slate-700">{m.reason}</strong>
                      </div>
                      <div>
                        <span className="text-slate-400">Request: </span>
                        <code className="font-mono select-all text-slate-700">{m.stockRequestId.slice(0, 16)}...</code>
                      </div>
                      <div>
                        <span className="text-slate-400">Timestamp: </span>
                        <span className="font-mono text-slate-700">{formatFullDateTime(m.occurredAt)}</span>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Desktop Movements Table (>= 768px) */}
            <div className="hidden md:block overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
              <div className="overflow-x-auto">
                <table className="min-w-full divide-y divide-slate-200 text-left text-xs">
                  <thead className="bg-slate-50 font-semibold uppercase tracking-wider text-slate-600">
                    <tr>
                      <th scope="col" className="px-4 py-3">
                        Movement ID
                      </th>
                      <th scope="col" className="px-4 py-3">
                        Product / SKU
                      </th>
                      <th scope="col" className="px-4 py-3 text-right">
                        Quantity Delta
                      </th>
                      <th scope="col" className="px-4 py-3">
                        Reason
                      </th>
                      <th scope="col" className="px-4 py-3">
                        Timestamp
                      </th>
                      <th scope="col" className="px-4 py-3">
                        Request ID
                      </th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100 text-slate-700">
                    {movements.map((m) => {
                      const productName = productNames.get(m.sku.toLowerCase()) || m.sku;
                      return (
                        <tr key={m.id} className="hover:bg-slate-50">
                          <td className="px-4 py-3 font-mono font-bold text-slate-900">#{m.id}</td>
                          <td className="px-4 py-3">
                            <div className="font-semibold text-slate-900">{productName}</div>
                            <div className="font-mono text-[11px] text-slate-500">{m.sku}</div>
                          </td>
                          <td className="px-4 py-3 text-right font-mono font-bold text-rose-700">
                            {m.quantityDelta}
                          </td>
                          <td className="px-4 py-3 font-semibold text-slate-600">
                            {m.reason}
                          </td>
                          <td className="px-4 py-3 font-mono text-slate-500" title={m.occurredAt}>
                            {formatFullDateTime(m.occurredAt)}
                          </td>
                          <td className="px-4 py-3 font-mono text-[11px] text-slate-600 select-all" title={m.stockRequestId}>
                            {m.stockRequestId.slice(0, 16)}...
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

      {/* F24: Item-Specific Dispatch Verification Modal */}
      {dispatchReviewItem && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="dispatch-dialog-title"
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-xs"
        >
          <div
            ref={dispatchModalRef}
            className="w-full max-w-md rounded-2xl bg-white p-6 shadow-2xl border border-slate-200 space-y-4 animate-in fade-in zoom-in-95 duration-150"
          >
            <div>
              <span className="text-[11px] font-bold uppercase tracking-wider text-teal-800 bg-teal-50 border border-teal-200 px-2 py-0.5 rounded-md">
                Fulfillment Verification
              </span>
              <h2 id="dispatch-dialog-title" className="mt-2 text-lg font-bold text-slate-900">
                Confirm Physical Stock Dispatch
              </h2>
              <p className="mt-1 text-xs text-slate-600">
                Verify the picked item before releasing to courier transport:
              </p>
            </div>

            <div className="rounded-xl bg-slate-50 p-4 border border-slate-200 space-y-2 text-xs">
              <div className="flex justify-between">
                <span className="text-slate-500">Product:</span>
                <span className="font-bold text-slate-900">
                  {productNames.get(dispatchReviewItem.sku.toLowerCase()) || dispatchReviewItem.sku}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">SKU:</span>
                <span className="font-mono font-semibold text-slate-800">{dispatchReviewItem.sku}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-500">Dispatch Quantity:</span>
                <span className="font-mono font-bold text-sm text-teal-800">
                  {formatUnits(dispatchReviewItem.quantity)}
                </span>
              </div>
              <div className="flex justify-between border-t border-slate-200 pt-2">
                <span className="text-slate-500">Reference:</span>
                <span className="font-mono text-[11px] text-slate-700 truncate max-w-[200px]" title={dispatchReviewItem.requestId}>
                  {dispatchReviewItem.requestId}
                </span>
              </div>
            </div>

            <p className="text-xs text-slate-500 leading-normal">
              Confirming dispatch permanently decrements on-hand inventory in PostgreSQL and writes an immutable audit record.
            </p>

            <div className="flex items-center justify-end gap-3 pt-2 border-t border-slate-100">
              <button
                ref={dispatchCancelBtnRef}
                type="button"
                onClick={() => setDispatchReviewItem(null)}
                className="inline-flex min-h-[40px] items-center rounded-xl border border-slate-300 bg-white px-4 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void executeDispatch(dispatchReviewItem)}
                className="inline-flex min-h-[40px] items-center rounded-xl bg-teal-700 px-5 py-2 text-xs font-bold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 transition-colors"
              >
                Dispatch {formatUnits(dispatchReviewItem.quantity)}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
