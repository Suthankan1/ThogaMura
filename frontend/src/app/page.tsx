"use client";

import { useEffect, useState, useCallback } from "react";
import Link from "next/link";
import { fetchStockRequests, fetchProducts } from "@/lib/api/inventory";
import type { StockRequest, Product } from "@/lib/types/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { formatFreshness, formatUnits } from "@/lib/formatters";

const technicalConcepts = [
  {
    title: "Java TCP Sockets & Framing",
    member: "Member 1 (@sankharavindu)",
    concept: "java.net.Socket, newline framing, frame deadline & safe same-ID retries",
    href: "/requests/new",
    actionLabel: "Test TCP Command Path",
  },
  {
    title: "Java URI / HttpURLConnection",
    member: "Member 2 (@sandunikarunarathna)",
    concept: "Outbound supplier HTTP lookup, timeouts, SSRF allowlisting & advisory states",
    href: "/suppliers",
    actionLabel: "Query Supplier",
  },
  {
    title: "Java NIO Selectors & Channels",
    member: "Member 3 (@Suthankan1)",
    concept: "Version-only invalidation broadcast on private demo LAN & SSE browser bridge",
    href: "/activity",
    actionLabel: "View Live Signals",
  },
  {
    title: "PostgreSQL Transactions & Concurrency",
    member: "Member 4 (@Yasiru-ruwantha)",
    concept: "Atomic stock reservation, durable command receipts & single-movement dispatch",
    href: "/warehouse",
    actionLabel: "Inspect Queue",
  },
  {
    title: "JSSE TLS & Identity Protection",
    member: "Member 5 (@vibhathkalsara99)",
    concept: "Server certificate verification, SAN hostname validation & fail-closed security",
    href: "/activity",
    actionLabel: "Inspect TLS Status",
  },
];

export default function Home() {
  const [requests, setRequests] = useState<StockRequest[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [requestsError, setRequestsError] = useState(false);
  const [productsError, setProductsError] = useState(false);
  const [lastFetchedAt, setLastFetchedAt] = useState<Date | null>(null);

  const { subscribeInvalidation } = useStockEvents();

  const loadLiveData = useCallback(async () => {
    try {
      const [requestsData, productsData] = await Promise.all([
        fetchStockRequests()
          .then((r) => {
            setRequestsError(false);
            return r;
          })
          .catch(() => {
            setRequestsError(true);
            return [];
          }),
        fetchProducts()
          .then((p) => {
            setProductsError(false);
            return p;
          })
          .catch(() => {
            setProductsError(true);
            return [];
          }),
      ]);

      setRequests(requestsData);
      setProducts(productsData);
      setLastFetchedAt(new Date());
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadLiveData();
  }, [loadLiveData]);

  // Real-time invalidation updates Overview counts
  useEffect(() => {
    return subscribeInvalidation(() => {
      void loadLiveData();
    });
  }, [subscribeInvalidation, loadLiveData]);

  const toPickRequests = requests.filter((r) => r.status === "ACCEPTED");
  const toDispatchRequests = requests.filter((r) => r.status === "PICKED");
  const outOfStockProducts = products.filter((p) => p.available === 0);
  const lowStockProducts = products.filter((p) => p.available >= 1 && p.available <= 3);

  // Attention queue items (F06: live work displacing static descriptions)
  const attentionItems: Array<{
    id: string;
    title: string;
    subtitle: string;
    badge: string;
    badgeColor: string;
    href: string;
    actionText: string;
  }> = [];

  for (const r of toPickRequests.slice(0, 2)) {
    const prod = products.find((p) => p.sku === r.sku);
    attentionItems.push({
      id: `pick-${r.requestId}`,
      title: `${prod?.name || r.sku} (${formatUnits(r.quantity)})`,
      subtitle: `Accepted branch order awaiting pick · Ref: ${r.requestId.slice(0, 8)}...`,
      badge: "To Pick",
      badgeColor: "bg-blue-50 text-blue-800 border-blue-200",
      href: `/warehouse?requestId=${encodeURIComponent(r.requestId)}&stage=PICK`,
      actionText: "Pick item →",
    });
  }

  for (const r of toDispatchRequests.slice(0, 2)) {
    const prod = products.find((p) => p.sku === r.sku);
    attentionItems.push({
      id: `dispatch-${r.requestId}`,
      title: `${prod?.name || r.sku} (${formatUnits(r.quantity)})`,
      subtitle: `Picked and staged in loading bay awaiting courier release`,
      badge: "Ready to Dispatch",
      badgeColor: "bg-amber-50 text-amber-800 border-amber-200",
      href: `/warehouse?requestId=${encodeURIComponent(r.requestId)}&stage=DISPATCH`,
      actionText: "Review dispatch →",
    });
  }

  for (const p of outOfStockProducts.slice(0, 2)) {
    attentionItems.push({
      id: `oos-${p.sku}`,
      title: `${p.name} (${p.sku})`,
      subtitle: `0 available in warehouse · On hand: ${p.onHand}, Reserved: ${p.reserved}`,
      badge: "Out of Stock",
      badgeColor: "bg-rose-50 text-rose-800 border-rose-200",
      href: `/suppliers?sku=${encodeURIComponent(p.sku)}`,
      actionText: "Check supplier →",
    });
  }

  return (
    <div className="space-y-8">
      {/* Page Header (F05: One primary New Request action, clear visual hierarchy F08) */}
      <section className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-xs sm:p-8">
        <div className="flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
          <div className="max-w-2xl">
            <div className="flex items-center gap-2">
              <span className="text-xs font-semibold text-teal-800 bg-teal-50 border border-teal-200 px-2 py-0.5 rounded-full">
                Warehouse Operations
              </span>
              <span className="text-xs text-slate-400">·</span>
              <span className="text-xs font-medium text-slate-500">
                {lastFetchedAt ? `Updated ${formatFreshness(lastFetchedAt)}` : "Live Coordination"}
              </span>
            </div>
            <h1 className="mt-2 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
              Warehouse Overview
            </h1>
            <p className="mt-2 text-sm leading-relaxed text-slate-600">
              Live fulfillment status across central warehouse inventory balances, active branch customer reservations, and external supplier availability.
            </p>
          </div>

          <div className="shrink-0">
            <Link
              href="/requests/new"
              className="inline-flex min-h-[44px] items-center justify-center rounded-xl bg-teal-700 px-6 py-2.5 text-sm font-semibold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 transition-colors"
            >
              + New Request
            </Link>
          </div>
        </div>
      </section>

      {/* Live Workload Metrics Grid (F06: Current work counts linking to filtered queues) */}
      <section aria-labelledby="live-workload-title">
        <div className="mb-3 flex items-center justify-between">
          <h2 id="live-workload-title" className="text-sm font-bold uppercase tracking-wider text-slate-500">
            Current Workloads
          </h2>
          <span className="text-xs text-slate-400">Select any queue to open filtered view</span>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 sm:gap-4">
          {/* To Pick */}
          <Link
            href="/warehouse?stage=PICK"
            className="group rounded-2xl border border-blue-200/80 bg-blue-50/50 p-4 transition-all hover:bg-blue-50 hover:shadow-xs"
          >
            <span className="text-xs font-bold uppercase tracking-wider text-blue-700 block">
              To Pick
            </span>
            <div className="mt-2 flex items-baseline justify-between">
              {loading ? (
                <div className="h-8 w-12 animate-pulse bg-blue-200/70 rounded" />
              ) : requestsError ? (
                <span className="text-xs font-semibold text-slate-500">Unavailable</span>
              ) : (
                <span className="font-mono text-2xl sm:text-3xl font-extrabold text-blue-950">
                  {toPickRequests.length}
                </span>
              )}
              <span className="text-xs font-semibold text-blue-700 group-hover:translate-x-0.5 transition-transform">
                View →
              </span>
            </div>
            <span className="text-[11px] text-blue-700/80 mt-1 block">Accepted branch orders</span>
          </Link>

          {/* To Dispatch */}
          <Link
            href="/warehouse?stage=DISPATCH"
            className="group rounded-2xl border border-amber-200/80 bg-amber-50/50 p-4 transition-all hover:bg-amber-50 hover:shadow-xs"
          >
            <span className="text-xs font-bold uppercase tracking-wider text-amber-800 block">
              To Dispatch
            </span>
            <div className="mt-2 flex items-baseline justify-between">
              {loading ? (
                <div className="h-8 w-12 animate-pulse bg-amber-200/70 rounded" />
              ) : requestsError ? (
                <span className="text-xs font-semibold text-slate-500">Unavailable</span>
              ) : (
                <span className="font-mono text-2xl sm:text-3xl font-extrabold text-amber-950">
                  {toDispatchRequests.length}
                </span>
              )}
              <span className="text-xs font-semibold text-amber-800 group-hover:translate-x-0.5 transition-transform">
                View →
              </span>
            </div>
            <span className="text-[11px] text-amber-800/80 mt-1 block">Picked in loading bay</span>
          </Link>

          {/* Out of Stock */}
          <Link
            href="/products?filter=out_of_stock"
            className="group rounded-2xl border border-rose-200/80 bg-rose-50/50 p-4 transition-all hover:bg-rose-50 hover:shadow-xs"
          >
            <span className="text-xs font-bold uppercase tracking-wider text-rose-800 block">
              Out of Stock
            </span>
            <div className="mt-2 flex items-baseline justify-between">
              {loading ? (
                <div className="h-8 w-12 animate-pulse bg-rose-200/70 rounded" />
              ) : productsError ? (
                <span className="text-xs font-semibold text-slate-500">Unavailable</span>
              ) : (
                <span className="font-mono text-2xl sm:text-3xl font-extrabold text-rose-950">
                  {outOfStockProducts.length}
                </span>
              )}
              <span className="text-xs font-semibold text-rose-800 group-hover:translate-x-0.5 transition-transform">
                View →
              </span>
            </div>
            <span className="text-[11px] text-rose-800/80 mt-1 block">Check external suppliers</span>
          </Link>

          {/* Low Stock */}
          <Link
            href="/products?filter=low_stock"
            className="group rounded-2xl border border-teal-200/80 bg-teal-50/50 p-4 transition-all hover:bg-teal-50 hover:shadow-xs"
          >
            <span className="text-xs font-bold uppercase tracking-wider text-teal-800 block">
              Low Stock
            </span>
            <div className="mt-2 flex items-baseline justify-between">
              {loading ? (
                <div className="h-8 w-12 animate-pulse bg-teal-200/70 rounded" />
              ) : productsError ? (
                <span className="text-xs font-semibold text-slate-500">Unavailable</span>
              ) : (
                <span className="font-mono text-2xl sm:text-3xl font-extrabold text-teal-950">
                  {lowStockProducts.length}
                </span>
              )}
              <span className="text-xs font-semibold text-teal-800 group-hover:translate-x-0.5 transition-transform">
                View →
              </span>
            </div>
            <span className="text-[11px] text-teal-800/80 mt-1 block">1–3 available balance</span>
          </Link>
        </div>
      </section>

      {/* Items Awaiting Attention (F06: live work list) */}
      <section aria-labelledby="attention-title">
        <div className="mb-3 flex items-center justify-between">
          <h2 id="attention-title" className="text-sm font-bold uppercase tracking-wider text-slate-500">
            Work Awaiting Immediate Attention
          </h2>
          <span className="text-xs text-slate-400">Live operational tasks</span>
        </div>

        {loading ? (
          <div className="space-y-3">
            {[1, 2, 3].map((i) => (
              <div key={i} className="h-16 animate-pulse rounded-xl border border-slate-200 bg-slate-100" />
            ))}
          </div>
        ) : attentionItems.length === 0 ? (
          <div className="rounded-2xl border border-dashed border-slate-300 p-8 text-center bg-white">
            <p className="text-sm font-semibold text-slate-900">All fulfillment queues clear</p>
            <p className="mt-1 text-xs text-slate-500">
              No items awaiting picking or dispatch, and warehouse stock levels are healthy.
            </p>
          </div>
        ) : (
          <div className="divide-y divide-slate-100 rounded-2xl border border-slate-200/80 bg-white shadow-xs">
            {attentionItems.map((item) => (
              <div key={item.id} className="flex flex-col sm:flex-row sm:items-center sm:justify-between p-4 gap-3">
                <div className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className={`inline-flex items-center rounded-full border px-2 py-0.5 text-[11px] font-semibold ${item.badgeColor}`}>
                      {item.badge}
                    </span>
                    <h3 className="font-bold text-sm text-slate-900">{item.title}</h3>
                  </div>
                  <p className="text-xs text-slate-500">{item.subtitle}</p>
                </div>
                <Link
                  href={item.href}
                  className="inline-flex items-center text-xs font-bold text-teal-700 hover:text-teal-900 shrink-0"
                >
                  {item.actionText}
                </Link>
              </div>
            ))}
          </div>
        )}
      </section>

      {/* Dedicated Academic Architecture & Verification Reference (F07: lecturer evaluation) */}
      <section>
        <details className="group rounded-2xl border border-slate-200/80 bg-white shadow-xs transition-all">
          <summary className="flex cursor-pointer items-center justify-between p-5 text-sm font-bold text-slate-900 hover:bg-slate-50 rounded-2xl select-none">
            <div className="flex items-center gap-2.5">
              <span className="flex h-6 w-6 items-center justify-center rounded-md bg-teal-100 text-teal-800 text-xs font-bold">
                ⚙
              </span>
              <span>Academic Architecture &amp; Member Evaluation Reference (Assignment 02)</span>
            </div>
            <span className="text-xs font-medium text-slate-500 group-open:rotate-180 transition-transform">
              ▼
            </span>
          </summary>

          <div className="border-t border-slate-100 p-5 space-y-6">
            <p className="text-xs text-slate-600 leading-relaxed">
              ThogaMura demonstrates five distinct Java networking and distributed system concepts owned individually by team members, complying strictly with Coursework Assignment 02:
            </p>

            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {technicalConcepts.map((item) => (
                <div
                  key={item.title}
                  className="rounded-xl border border-slate-200/60 bg-slate-50/70 p-4 text-xs space-y-2"
                >
                  <div className="font-bold text-slate-900">{item.title}</div>
                  <div className="font-semibold text-teal-800">{item.member}</div>
                  <p className="text-slate-600 leading-normal">{item.concept}</p>
                  <div className="pt-1">
                    <Link
                      href={item.href}
                      className="inline-flex items-center font-bold text-teal-700 hover:text-teal-900"
                    >
                      {item.actionLabel} →
                    </Link>
                  </div>
                </div>
              ))}
            </div>

            {/* Non-Negotiable Architecture Invariants */}
            <div className="rounded-xl border border-teal-200 bg-teal-50/70 p-5 text-slate-800">
              <h3 className="text-sm font-bold text-teal-950">Architectural Invariants</h3>
              <ul className="mt-2.5 grid gap-2 text-xs text-slate-700 sm:grid-cols-2">
                <li className="flex items-start gap-2">
                  <span className="font-bold text-teal-800">1.</span>
                  <span>PostgreSQL 17 is the sole database authority; no direct browser or gateway DB access.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="font-bold text-teal-800">2.</span>
                  <span>Stock commands travel across Java TCP/TLS sockets in a separate JVM process.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="font-bold text-teal-800">3.</span>
                  <span>Java NIO on port 7444 delivers private-LAN version-only invalidation signals.</span>
                </li>
                <li className="flex items-start gap-2">
                  <span className="font-bold text-teal-800">4.</span>
                  <span>Supplier inquiries use outbound Java HTTP networking to an independent mock service.</span>
                </li>
              </ul>
            </div>
          </div>
        </details>
      </section>
    </div>
  );
}
