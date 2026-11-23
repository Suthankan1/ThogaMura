"use client";

import { listPage, ListPages, updateListParams } from "@/components/ListPages";
import { useCallback, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { fetchProducts, fetchStockRequests } from "@/lib/api/inventory";
import { formatFullDateTime, formatUnits } from "@/lib/formatters";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { Freshness, primaryAction, RequestBadge, secondaryAction, surface, textAction, WorkspaceHeading } from "@/components/Workspace";

const statuses = [
  { key: "ALL", label: "All requests" }, { key: "ACCEPTED", label: "To pick" },
  { key: "PICKED", label: "Ready to dispatch" }, { key: "DISPATCHED", label: "Dispatched" },
  { key: "REJECTED", label: "Declined" },
];

export function RequestsClient() {
  const [copyNotice, setCopyNotice] = useState("");
  const params = useSearchParams();
  const filter = statuses.find((option) => option.key === params.get("status"))?.key ?? "ALL";
  const search = params.get("q") ?? "";
  const reference = params.get("ref");
  const read = useCallback(() => fetchStockRequests(filter), [filter]);
  const requests = useWarehouseQuery(read);
  const products = useWarehouseQuery(fetchProducts);
  const needle = search.trim().toLowerCase();
  const loaded = (requests.data ?? []).filter((request) => filter === "ALL" || request.status === filter);
  const exactRead = useCallback(() => fetchStockRequests(), []);
  const fullHistory = useWarehouseQuery(exactRead, !!reference && filter !== "ALL");
  const selected = reference && (loaded.find((request) => request.requestId === reference) ??
    fullHistory.data?.find((request) => request.requestId === reference));
  const rows = loaded.filter((request) =>
    !needle || request.requestId.includes(needle) || request.sku.toLowerCase().includes(needle) || nameFor(request.sku).toLowerCase().includes(needle)
  ).sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));

  const page = listPage(rows, params.get("page"));

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
              <button type="button" className={textAction} onClick={async () => {
                try {
                  await navigator.clipboard.writeText(selected.requestId);
                  setCopyNotice("Reference copied.");
                } catch {
                  setCopyNotice("Select the full reference above to copy it.");
                }
              }}>Copy full reference</button>
              <span role="status" className="ml-2 text-xs">{copyNotice}</span>
            </div>
            <RequestBadge status={selected.status} />
          </div>
          <dl className="mt-4 flex flex-wrap gap-x-8 gap-y-3 text-sm">
            <div><dt className="text-slate-600">SKU</dt><dd className="font-semibold">{selected.sku}</dd></div>
            <div><dt className="text-slate-600">Requested</dt><dd className="font-semibold tabular-nums">{formatUnits(selected.quantity)}</dd></div>
            <div><dt className="text-slate-600">Created</dt><dd>{formatFullDateTime(selected.createdAt)}</dd></div>
          </dl>
          {["ACCEPTED", "PICKED", "DISPATCHED"].includes(selected.status) && (
            <Link className={textAction}
              href={`/warehouse?stage=${selected.status === "PICKED" ? "dispatch" : selected.status === "DISPATCHED" ? "movements" : "pick"}&requestId=${encodeURIComponent(selected.requestId)}`}>
              {selected.status === "DISPATCHED" ? "View stock movements" : "Open warehouse work"}
            </Link>
          )}
        </section>
      )}

      {reference && !selected && requests.data && !requests.loading && (!fullHistory.loading || fullHistory.error) && (
        <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">The demo gateway could not confirm this reference in its bounded history. Try searching by SKU or opening the relevant warehouse stage.</p>
      )}

      <section className={surface} aria-labelledby="request-list-heading">
        <header className="space-y-4 border-b border-slate-200 p-4 sm:p-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div><h2 id="request-list-heading" className="font-semibold">Request history</h2><Freshness {...requests} /></div>
            <button type="button" className={secondaryAction} onClick={() => void requests.refresh()}>Refresh</button>
          </div>
          <form className="grid items-end gap-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,auto)_auto]" onSubmit={(event) => {
            event.preventDefault();
            const data = new FormData(event.currentTarget);
            updateListParams({ q: String(data.get("q")).trim(), status: String(data.get("status")), page: null, ref: null });
          }}>
            <label className="min-w-0 flex-1 text-sm font-semibold">Product, SKU, or reference
              <input type="search" name="q" key={search} defaultValue={search} className="mt-1 block min-h-11 w-full min-w-0 rounded-lg border border-slate-300 bg-white px-3 text-sm font-normal" />
            </label>
            <label className="text-sm font-semibold">Status
              <select name="status" key={filter} defaultValue={filter} className="mt-1 block min-h-11 w-full min-w-0 rounded-lg border border-slate-300 bg-white px-3 text-sm font-normal">
                {statuses.map((option) => <option key={option.key} value={option.key}>{option.label}</option>)}
              </select>
            </label>
            <button className={`${secondaryAction} justify-self-start`} type="submit">Apply filters</button>
          </form>
          {requests.error && <p role="alert" className="text-sm text-rose-900">{requests.error}</p>}
        </header>
        {rows.length ? (
          <ul className="divide-y divide-slate-100">
            {page.items.map((request) => {
              const detail = new URLSearchParams({ ref: request.requestId });
              if (filter !== "ALL") detail.set("status", filter);
              if (search) detail.set("q", search);
              if (page.number > 1) detail.set("page", String(page.number));
              return (
                <li key={request.requestId} className="grid items-center gap-3 p-4 sm:grid-cols-[minmax(0,1fr)_auto_auto] sm:p-5">
                  <div className="min-w-0 flex-1">
                    <Link className={textAction} href={`/requests?${detail}`}>{nameFor(request.sku)}</Link>
                    <p className="break-all font-mono text-xs text-slate-600">{request.sku} · {loaded.some((row) => row.requestId !== request.requestId && row.requestId.endsWith(request.requestId.slice(-8))) ? request.requestId : request.requestId.slice(-8)}</p>
                    <p className="mt-1 text-xs text-slate-600">{formatFullDateTime(request.createdAt)}</p>
                  </div>
                  <p className="text-sm font-semibold tabular-nums">{formatUnits(request.quantity)}</p>
                  <RequestBadge status={request.status} />
                </li>
              );
            })}
          </ul>
        ) : requests.data && <p className="p-5 text-sm text-slate-600">No matching requests in the loaded records.</p>}
        <ListPages {...page} onChange={(next) => updateListParams({ page: String(next) })} />
        {requests.data?.length === 500 && <p className="border-t border-slate-200 p-4 text-sm text-slate-600">Showing a bounded history. Use a status filter to narrow the warehouse query.</p>}
      </section>
    </div>
  );
}
