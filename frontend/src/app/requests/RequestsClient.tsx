"use client";

import { listPage, ListPages, updateListParams } from "@/components/ListPages";
import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { fetchProducts, fetchStockRequests } from "@/lib/api/inventory";
import { formatFullDateTime, formatUnits } from "@/lib/formatters";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { RequestBadge, textAction } from "@/components/Workspace";

import { RecordList } from "@/components/RecordList";

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

  const dialog = useRef<HTMLDialogElement>(null);
  const opener = useRef<HTMLAnchorElement | null>(null);
  useEffect(() => {
    const element = dialog.current;
    if (!reference || !element) return;
    element.showModal();
    return () => {
      element.close();
      const target = opener.current?.isConnected ? opener.current : document.getElementById("records-title");
      target?.focus({ preventScroll: true });
    };
  }, [reference]);

  const listParams = new URLSearchParams(params);
  listParams.delete("ref");
  const returnTo = "/requests" + (listParams.size ? "?" + listParams : "");
  const lookup = filter === "ALL" ? requests : fullHistory;
  function closeDetail() {
    if (window.history.state?.fromRequestList) window.history.back();
    else updateListParams({ ref: null }, true);
  }
  return <>
    <RecordList title="Stock requests" noun="request" count={rows.length} loaded={requests.data?.length}
      returnTo={returnTo} search={search} prompt="Search product, SKU or reference" label="Status"
      filter={filter} filterKey="status" options={statuses} query={requests}
      banner={requests.data?.length === 500 ? (
        <p className="records-message border-t border-amber-200 bg-amber-50/80 text-amber-950">
          Showing the newest 500 requests in demo mode. Filter by status or search by SKU to narrow results, or open a reference directly via URL.
        </p>
      ) : undefined}>
      {!!rows.length && <table className="records-table" aria-label="Stock requests">
        <thead><tr><th>Reference</th><th>Product</th><th className="number">Quantity</th><th>Status</th><th>Created <small>{Intl.DateTimeFormat().resolvedOptions().timeZone}</small></th></tr></thead>
        <tbody>{page.items.map(request => {
          const detail = new URLSearchParams(listParams);
          detail.set("ref", request.requestId);
          const suffix = request.requestId.slice(-8);
          const collision = loaded.some(row => row.requestId !== request.requestId && row.requestId.endsWith(suffix));
          const created = new Date(request.createdAt ?? "");
          return <tr key={request.requestId}>
            <td><a className={textAction + " font-mono"} href={"/requests?" + detail} onClick={event => {
              if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
              event.preventDefault();
              opener.current = event.currentTarget;
              setCopyNotice("");
              window.history.pushState({fromRequestList:true}, "", event.currentTarget.href);
            }}>{collision ? request.requestId : suffix}</a></td>
            <td className="request-product"><strong>{nameFor(request.sku)}</strong><small>{request.sku}</small></td>
            <td className="number">{request.quantity}<span className="phone-label"> {request.quantity === 1 ? "unit" : "units"}</span></td>
            <td className="request-status"><RequestBadge status={request.status}/></td>
            <td className="desktop-cell">{Number.isFinite(created.getTime()) ? created.toLocaleString("en-GB", {
              day:"numeric", month:"short", year:created.getFullYear() === new Date().getFullYear() ? undefined : "numeric", hour:"2-digit", minute:"2-digit"
            }) : "—"}</td>
          </tr>;
        })}</tbody>
      </table>}
      <ListPages {...page} onChange={next => updateListParams({page:String(next)})}/>
    </RecordList>
    {reference && <dialog ref={dialog} className="record-drawer" aria-labelledby="detail-title" onCancel={event => { event.preventDefault(); closeDetail(); }}>
      <div className="mb-5 flex items-center justify-between border-b border-slate-200 pb-3">
        <h2 id="detail-title" className="text-xl font-bold">Request details</h2>
        <button className={textAction} onClick={closeDetail}>Close</button>
      </div>
      {selected ? <>          <div className="flex flex-wrap items-start justify-between gap-3">
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
</> : lookup.error ? <p role="alert">{lookup.error} <button className={textAction} onClick={() => void lookup.refresh()}>Retry lookup</button></p>
        : lookup.loading || !lookup.data ? <p role="status">Loading request…</p> : <p role="status">Reference unavailable in the loaded request history.</p>}
    </dialog>}
  </>;
}
