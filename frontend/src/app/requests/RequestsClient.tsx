"use client";

import { useCallback } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { fetchProducts, fetchStockRequests } from "@/lib/api/inventory";
import { formatFullDateTime, formatUnits } from "@/lib/formatters";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { Freshness, primaryAction, RequestBadge, secondaryAction, surface, WorkspaceHeading } from "@/components/Workspace";

const statuses = [
  { key: "ALL", label: "All requests" }, { key: "ACCEPTED", label: "To pick" },
  { key: "PICKED", label: "Ready to dispatch" }, { key: "DISPATCHED", label: "Dispatched" },
  { key: "REJECTED", label: "Declined" },
];

export function RequestsClient() {
  const params = useSearchParams();
  const filter = statuses.find((option) => option.key === params.get("status"))?.key ?? "ALL";
  const search = params.get("q") ?? "";
  const reference = params.get("ref");
  const read = useCallback(() => fetchStockRequests(filter), [filter]);
  const requests = useWarehouseQuery(read);
  const products = useWarehouseQuery(fetchProducts);
  const needle = search.trim().toLowerCase();
  const loaded = (requests.data ?? []).filter((request) => filter === "ALL" || request.status === filter);
  const selected = reference && loaded.find((request) => request.requestId === reference);
  const rows = loaded.filter((request) =>
    !needle || request.requestId.startsWith(needle) || request.sku.toLowerCase().includes(needle)
  ).sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));

  function nameFor(sku: string) {
    return products.data?.find((product) => product.sku === sku)?.name ?? sku;
  }

  return (
    <div className="space-y-6">
      <WorkspaceHeading title="Stock requests" description="Find a request by its reference or see which requests are ready for fulfillment."
        action={<Link href="/requests/new?returnTo=/requests" className={primaryAction}>New request</Link>} />

      {selected && (
        <section className={`${surface} border-teal-400 p-5`} aria-labelledby="request-detail-heading">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h2 id="request-detail-heading" className="text-lg font-semibold">{nameFor(selected.sku)}</h2>
              <p className="mt-1 break-all font-mono text-xs text-slate-600">{selected.requestId}</p>
            </div>
            <RequestBadge status={selected.status} />
          </div>
          <dl className="mt-4 flex flex-wrap gap-x-8 gap-y-3 text-sm">
            <div><dt className="text-slate-600">SKU</dt><dd className="font-semibold">{selected.sku}</dd></div>
            <div><dt className="text-slate-600">Requested</dt><dd className="font-semibold tabular-nums">{formatUnits(selected.quantity)}</dd></div>
            <div><dt className="text-slate-600">Created</dt><dd>{formatFullDateTime(selected.createdAt)}</dd></div>
          </dl>
          {["ACCEPTED", "PICKED", "DISPATCHED"].includes(selected.status) && (
            <Link className="mt-4 inline-block font-semibold text-teal-800 underline"
              href={`/warehouse?stage=${selected.status === "PICKED" ? "dispatch" : selected.status === "DISPATCHED" ? "movements" : "pick"}&requestId=${encodeURIComponent(selected.requestId)}`}>
              {selected.status === "DISPATCHED" ? "View stock movements" : "Open warehouse work"}
            </Link>
          )}
        </section>
      )}

      {reference && !selected && requests.data && !requests.loading && (
        <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">This reference was not found in the loaded request history. Try the status filter or check again after refresh.</p>
      )}

      <section className={surface} aria-labelledby="request-list-heading">
        <header className="space-y-4 border-b border-slate-200 p-4 sm:p-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div><h2 id="request-list-heading" className="font-semibold">Request history</h2><Freshness {...requests} /></div>
            <button type="button" className={secondaryAction} onClick={() => void requests.refresh()}>Refresh</button>
          </div>
          <form action="/requests" className="flex flex-wrap items-end gap-3">
            <label className="min-w-0 flex-1 text-sm font-semibold">Reference or SKU
              <input type="search" name="q" key={search} defaultValue={search} className="mt-1 block min-h-11 w-full min-w-44 rounded-lg border border-slate-300 bg-white px-3 text-sm font-normal" />
            </label>
            <label className="text-sm font-semibold">Status
              <select name="status" key={filter} defaultValue={filter} className="mt-1 block min-h-11 rounded-lg border border-slate-300 bg-white px-3 text-sm font-normal">
                {statuses.map((option) => <option key={option.key} value={option.key}>{option.label}</option>)}
              </select>
            </label>
            <button className={secondaryAction} type="submit">Apply filters</button>
          </form>
          {requests.error && <p role="alert" className="text-sm text-rose-900">{requests.error}</p>}
        </header>
        {rows.length ? (
          <ul className="divide-y divide-slate-100">
            {rows.map((request) => {
              const detail = new URLSearchParams({ ref: request.requestId });
              if (filter !== "ALL") detail.set("status", filter);
              if (search) detail.set("q", search);
              return (
                <li key={request.requestId} className="flex flex-wrap items-center justify-between gap-3 p-4 sm:p-5">
                  <div className="min-w-0 flex-1">
                    <Link className="text-sm font-semibold text-teal-900 hover:underline" href={`/requests?${detail}`}>{nameFor(request.sku)}</Link>
                    <p className="mt-1 truncate text-xs text-slate-600">{request.sku} · {request.requestId.slice(0, 8)} · {formatFullDateTime(request.createdAt)}</p>
                  </div>
                  <p className="text-sm font-semibold tabular-nums">{formatUnits(request.quantity)}</p>
                  <RequestBadge status={request.status} />
                </li>
              );
            })}
          </ul>
        ) : requests.data && <p className="p-5 text-sm text-slate-600">No matching requests in the loaded records.</p>}
        {requests.data?.length === 500 && <p className="border-t border-slate-200 p-4 text-sm text-slate-600">Showing a bounded history. Use a status filter to narrow the warehouse query.</p>}
      </section>
    </div>
  );
}
