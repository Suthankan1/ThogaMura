"use client";

import { useEffect, useState, useMemo, useCallback } from "react";
import Link from "next/link";
import { fetchProducts, ApiError } from "@/lib/api/inventory";
import type { Product } from "@/lib/types/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { formatOperationalTime, formatFreshness } from "@/lib/formatters";

export function ProductsClient() {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [search, setSearch] = useState("");
  const [stockFilter, setStockFilter] = useState<"ALL" | "IN_STOCK" | "LOW_STOCK" | "OUT_OF_STOCK">("ALL");
  const [error, setError] = useState<string | null>(null);
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null);
  const [refreshNotice, setRefreshNotice] = useState<string | null>(null);

  const { subscribeInvalidation, isLive, warningMessage } = useStockEvents();

  const loadAuthoritativeProducts = useCallback(async (isManual = false) => {
    if (isManual) {
      setRefreshing(true);
    }
    setError(null);
    setRefreshNotice(null);

    try {
      const data = await fetchProducts();
      setProducts(data);
      setError(null);
      const now = new Date();
      setLastRefreshed(now);
      if (isManual) {
        setRefreshNotice(`Inventory updated ${formatFreshness(now)}.`);
      }
    } catch (err) {
      const msg =
        err instanceof ApiError
          ? `${err.code}: ${err.message}`
          : "Failed to load products from warehouse.";
      setError(msg);
      if (isManual) {
        setRefreshNotice("Refresh failed. Displaying cached inventory.");
      }
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  // Initial load
  useEffect(() => {
    let ignore = false;
    async function init() {
      try {
        const data = await fetchProducts();
        if (!ignore) {
          setProducts(data);
          setError(null);
          setLastRefreshed(new Date());
        }
      } catch (err) {
        if (!ignore) {
          setError(
            err instanceof ApiError
              ? `${err.code}: ${err.message}`
              : "Failed to load products from warehouse."
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

  // Register real-time cache invalidation listener (SSE invalidation -> REST refetch)
  useEffect(() => {
    return subscribeInvalidation(() => {
      void loadAuthoritativeProducts(false);
    });
  }, [subscribeInvalidation, loadAuthoritativeProducts]);

  // Exclusive stock filtering (QA-09)
  const filteredProducts = useMemo(() => {
    let result = products;
    if (stockFilter === "IN_STOCK") {
      result = result.filter((p) => p.available >= 4);
    } else if (stockFilter === "LOW_STOCK") {
      result = result.filter((p) => p.available >= 1 && p.available <= 3);
    } else if (stockFilter === "OUT_OF_STOCK") {
      result = result.filter((p) => p.available === 0);
    }

    if (!search.trim()) return result;
    const q = search.toLowerCase().trim();
    return result.filter(
      (p) => p.sku.toLowerCase().includes(q) || p.name.toLowerCase().includes(q)
    );
  }, [products, search, stockFilter]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <span className="inline-flex items-center rounded-full bg-teal-50 px-2.5 py-0.5 text-xs font-semibold text-teal-800 border border-teal-200">
              PostgreSQL Authority
            </span>
            <span className="text-xs text-slate-400">·</span>
            <span className="text-xs font-medium text-slate-500">Real-time NIO Synchronization</span>
          </div>
          <h1 className="mt-1.5 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
            Inventory &amp; Stock Levels
          </h1>
          <p className="mt-1 text-xs text-slate-600 sm:text-sm">
            Authoritative on-hand, reserved, and available stock levels stored in PostgreSQL.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => void loadAuthoritativeProducts(true)}
            disabled={loading || refreshing}
            className="inline-flex min-h-[40px] items-center gap-2 rounded-xl border border-slate-300 bg-white px-4 py-2 text-xs font-semibold text-slate-700 shadow-xs hover:bg-slate-50 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50"
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
            {refreshing ? "Refreshing..." : "Refresh Inventory"}
          </button>
          <Link
            href="/requests/new"
            className="inline-flex min-h-[40px] items-center justify-center rounded-xl bg-teal-700 px-4 py-2 text-xs font-bold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 transition-colors"
          >
            + New Request
          </Link>
        </div>
      </div>

      {/* Network Warning Banner (QA-05: Live updates disconnected) */}
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

      {/* Refresh announcement */}
      {refreshNotice && (
        <div
          role="status"
          aria-live="polite"
          className="rounded-xl border border-teal-200 bg-teal-50 p-3.5 text-xs font-semibold text-teal-900"
        >
          ✓ {refreshNotice}
        </div>
      )}

      {/* Error Banner */}
      {error && (
        <div
          role="alert"
          aria-live="assertive"
          className="flex items-start justify-between gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-900"
        >
          <div>
            <h2 className="font-semibold">Failed to Load Products</h2>
            <p className="mt-1 text-sm text-rose-800">{error}</p>
          </div>
          <button
            type="button"
            onClick={() => void loadAuthoritativeProducts(true)}
            className="rounded-lg bg-rose-100 px-3 py-1 text-xs font-semibold text-rose-900 hover:bg-rose-200"
          >
            Retry
          </button>
        </div>
      )}

      {/* Search and Filters */}
      <div className="flex flex-col gap-4 rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="relative flex-1">
            <label htmlFor="search-input" className="sr-only">
              Search products
            </label>
            <input
              id="search-input"
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search by SKU (e.g. ITEM-007) or name..."
              className="w-full rounded-lg border border-slate-300 bg-slate-50 px-3 py-2 text-sm text-slate-900 placeholder:text-slate-500 focus:border-teal-600 focus:bg-white focus:outline-hidden focus:ring-1 focus:ring-teal-600"
            />
          </div>
          {/* QA-05: Accurate path-specific connection status */}
          <div className="flex items-center gap-4 text-xs text-slate-500">
            <span>
              Status:{" "}
              <strong className={isLive ? "text-emerald-700" : "text-amber-700"}>
                {isLive ? "Live NIO/SSE Synced" : "Live updates disconnected"}
              </strong>
            </span>
            {lastRefreshed && (
              <span>
                Last successful fetch:{" "}
                <strong className="font-mono text-slate-700">
                  {formatOperationalTime(lastRefreshed)}
                </strong>
              </span>
            )}
          </div>
        </div>

        {/* Stock Health Filter Pills with aria-pressed (QA-09) */}
        <div className="flex flex-wrap items-center gap-2 border-t border-slate-100 pt-3 text-xs">
          <span className="font-semibold text-slate-500">Filter Stock:</span>
          {[
            { label: "All items", value: "ALL" as const },
            { label: "In stock (4+)", value: "IN_STOCK" as const },
            { label: "Low stock (1–3)", value: "LOW_STOCK" as const },
            { label: "Out of stock (0)", value: "OUT_OF_STOCK" as const },
          ].map((tab) => {
            const isSelected = stockFilter === tab.value;
            return (
              <button
                key={tab.value}
                type="button"
                aria-pressed={isSelected}
                onClick={() => setStockFilter(tab.value)}
                className={`rounded-full px-3 py-1 font-medium transition-colors ${
                  isSelected
                    ? "bg-teal-700 text-white shadow-xs"
                    : "bg-slate-100 text-slate-700 hover:bg-slate-200"
                }`}
              >
                {tab.label}
              </button>
            );
          })}
        </div>
      </div>

      {/* Product Table */}
      {loading ? (
        <div className="space-y-3">
          {[1, 2, 3, 4, 5].map((i) => (
            <div
              key={i}
              className="h-16 animate-pulse rounded-xl border border-slate-200 bg-slate-100"
            />
          ))}
        </div>
      ) : filteredProducts.length === 0 ? (
        <div className="rounded-xl border border-dashed border-slate-300 p-12 text-center">
          <p className="text-base font-medium text-slate-900">No products found</p>
          <p className="mt-1 text-sm text-slate-500">
            {products.length === 0
              ? "Warehouse inventory is empty or not yet seeded."
              : search && stockFilter !== "ALL"
              ? "No products match both your search query and selected stock filter."
              : search
              ? "No product matches your search term."
              : "No products match the selected stock filter."}
          </p>

          {/* QA-10: Context-appropriate filter recovery actions */}
          <div className="mt-4 flex flex-wrap justify-center gap-2">
            {search && stockFilter !== "ALL" && (
              <button
                type="button"
                onClick={() => {
                  setSearch("");
                  setStockFilter("ALL");
                }}
                className="rounded-lg bg-teal-700 px-3 py-1.5 text-xs font-semibold text-white hover:bg-teal-800"
              >
                Clear all filters
              </button>
            )}
            {search && (
              <button
                type="button"
                onClick={() => setSearch("")}
                className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
              >
                Clear search query
              </button>
            )}
            {stockFilter !== "ALL" && (
              <button
                type="button"
                onClick={() => setStockFilter("ALL")}
                className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 hover:bg-slate-50"
              >
                Show all stock levels
              </button>
            )}
          </div>
        </div>
      ) : (
        <>
          {/* Mobile Product Card Stack (< 768px) - Zero Horizontal Scrolling */}
          <div className="space-y-3 md:hidden">
            {filteredProducts.map((p) => {
              const isOutOfStock = p.available === 0;
              const isLowStock = p.available >= 1 && p.available <= 3;

              return (
                <div
                  key={p.sku}
                  className="rounded-xl border border-slate-200/90 bg-white p-4 shadow-xs space-y-3"
                >
                  <div className="flex items-start justify-between gap-2">
                    <div>
                      <h2 className="font-bold text-slate-900 text-base">{p.name}</h2>
                      <p className="font-mono text-xs text-slate-500 mt-0.5">{p.sku}</p>
                    </div>
                    <span
                      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold shrink-0 ${
                        isOutOfStock
                          ? "bg-rose-100 text-rose-800"
                          : isLowStock
                          ? "bg-amber-100 text-amber-800"
                          : "bg-emerald-100 text-emerald-800"
                      }`}
                    >
                      {isOutOfStock
                        ? "Out of Stock"
                        : isLowStock
                        ? "Low Stock (1–3)"
                        : "In Stock (4+)"}
                    </span>
                  </div>

                  <div className="flex items-baseline justify-between border-t border-slate-100 pt-2.5">
                    <div>
                      <span className="text-xs text-slate-500">Available: </span>
                      <strong className="font-mono text-lg font-bold text-slate-900">
                        {p.available} units
                      </strong>
                    </div>
                    <div className="text-xs text-slate-500 font-mono">
                      On Hand: {p.onHand} · Res: {p.reserved}
                    </div>
                  </div>

                  <div className="pt-1">
                    {isOutOfStock ? (
                      <Link
                        href={`/suppliers?sku=${encodeURIComponent(p.sku)}`}
                        className="flex min-h-[44px] w-full items-center justify-center rounded-xl bg-amber-600 px-4 py-2.5 text-xs font-bold text-white shadow-xs hover:bg-amber-700 transition-colors"
                      >
                        Check Supplier Stock
                      </Link>
                    ) : (
                      <Link
                        href={`/requests/new?sku=${encodeURIComponent(p.sku)}`}
                        className="flex min-h-[44px] w-full items-center justify-center rounded-xl bg-teal-700 px-4 py-2.5 text-xs font-bold text-white shadow-xs hover:bg-teal-800 transition-colors"
                      >
                        Request Stock
                      </Link>
                    )}
                  </div>
                </div>
              );
            })}
          </div>

          {/* Desktop Table (>= 768px) */}
          <div className="hidden md:block overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-left text-sm">
                <thead className="bg-slate-50 text-xs font-semibold uppercase tracking-wider text-slate-600">
                  <tr>
                    <th scope="col" className="px-6 py-3">
                      Product / SKU
                    </th>
                    <th scope="col" className="px-6 py-3 text-right">
                      On Hand
                    </th>
                    <th scope="col" className="px-6 py-3 text-right">
                      Reserved
                    </th>
                    <th scope="col" className="px-6 py-3 text-right">
                      Available
                    </th>
                    <th scope="col" className="px-6 py-3 text-center">
                      Stock Health
                    </th>
                    <th scope="col" className="px-6 py-3 text-right">
                      Actions
                    </th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {filteredProducts.map((p) => {
                    const isOutOfStock = p.available === 0;
                    const isLowStock = p.available >= 1 && p.available <= 3;

                    return (
                      <tr key={p.sku} className="hover:bg-slate-50">
                        <td className="px-6 py-4">
                          <div className="font-semibold text-slate-900">{p.name}</div>
                          <div className="font-mono text-xs text-slate-500">{p.sku}</div>
                        </td>
                        <td className="px-6 py-4 text-right font-mono text-slate-700">
                          {p.onHand}
                        </td>
                        <td className="px-6 py-4 text-right font-mono text-slate-500">
                          {p.reserved}
                        </td>
                        <td className="px-6 py-4 text-right font-mono text-base font-bold text-slate-900">
                          {p.available}
                        </td>
                        <td className="px-6 py-4 text-center">
                          <span
                            className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                              isOutOfStock
                                ? "bg-rose-100 text-rose-800"
                                : isLowStock
                                ? "bg-amber-100 text-amber-800"
                                : "bg-emerald-100 text-emerald-800"
                            }`}
                          >
                            {isOutOfStock
                              ? "Out of Stock (0)"
                              : isLowStock
                              ? "Low Stock (1–3)"
                              : "In Stock (4+)"}
                          </span>
                        </td>
                        <td className="px-6 py-4 text-right">
                          <div className="flex items-center justify-end gap-2">
                            {isOutOfStock ? (
                              <Link
                                href={`/suppliers?sku=${encodeURIComponent(p.sku)}`}
                                className="inline-flex items-center rounded-md bg-amber-50 px-2.5 py-1 text-xs font-semibold text-amber-800 hover:bg-amber-100"
                              >
                                Check Supplier
                              </Link>
                            ) : (
                              <Link
                                href={`/requests/new?sku=${encodeURIComponent(p.sku)}`}
                                className="inline-flex items-center rounded-md bg-teal-50 px-2.5 py-1 text-xs font-semibold text-teal-700 hover:bg-teal-100"
                              >
                                Request stock
                              </Link>
                            )}
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
