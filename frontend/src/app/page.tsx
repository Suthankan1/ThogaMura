"use client";

import { ArrowUpRight } from "lucide-react";
import Link from "next/link";
import { fetchProducts, fetchStockRequests } from "@/lib/api/inventory";
import { formatAge, formatFullDateTime, formatUnits } from "@/lib/formatters";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { Freshness, primaryAction, secondaryAction, RequestBadge, SkeletonRows, StockAllocationBar, surface, textAction, WorkspaceHeading } from "@/components/Workspace";

const readPicks = () => fetchStockRequests("ACCEPTED");
const readDispatches = () => fetchStockRequests("PICKED");

export default function Home() {
  const products = useWarehouseQuery(fetchProducts, true, true, "demo:products");
  const picks = useWarehouseQuery(readPicks, true, true, "demo:picks");
  const dispatches = useWarehouseQuery(readDispatches, true, true, "demo:dispatches");
  const { warningMessage } = useStockEvents();
  const shortages = products.data?.filter((item) => item.available === 0);
  const lowStock = products.data?.filter((item) => item.available > 0 && item.available <= 3);
  const allPending = [
    ...picks.data?.map((item) => ({ item, stage: "pick" })) ?? [],
    ...dispatches.data?.map((item) => ({ item, stage: "dispatch" })) ?? [],
  ].sort((a, b) => (a.item.createdAt ?? "").localeCompare(b.item.createdAt ?? ""));
  const pending = allPending.slice(0, 6);

  return (
    <div className="space-y-7">
      <div className="overview-heading"><WorkspaceHeading title="Warehouse overview" description={["Central warehouse", picks.data && `${picks.data.length} request${picks.data.length === 1 ? "" : "s"} to pick`, shortages && `${shortages.length} item${shortages.length === 1 ? "" : "s"} out of stock`].filter(Boolean).join(" · ")}
        action={<Link href="/requests/new?returnTo=/" className={primaryAction}>New request</Link>} /></div>

      {warningMessage && (
        <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-950">
          {warningMessage} <Link className="font-semibold underline" href="/activity">View diagnostics</Link>
        </p>
      )}

      <nav aria-label="Warehouse totals" className="overview-totals">
        {[
          { title: "To pick", rows: picks.data, href: "/warehouse?stage=pick" },
          { title: "Ready to dispatch", rows: dispatches.data, href: "/warehouse?stage=dispatch" },
          { title: "Out of stock", rows: shortages, href: "/products?filter=out_of_stock" },
          { title: "Low stock", rows: lowStock, href: "/products?filter=low_stock" },
        ].map(({ title, rows, href }) => (
          <Link key={title} href={href} className="metric-card work-surface summary-tile">
            <span className="flex items-start justify-between gap-2 text-sm font-semibold text-slate-700">{title}
              <ArrowUpRight size={16} aria-hidden="true" className="mt-0.5 shrink-0 text-teal-800" />
            </span>
            <strong className="text-3xl leading-none font-semibold tabular-nums">{rows?.length ?? "—"}</strong>
          </Link>
        ))}
      </nav>

      <div className="space-y-2 min-[58rem]:hidden">
        <p className={surface + " p-3"}>{products.error ? "Inventory unavailable" : shortages?.[0] ?
          <Link className={textAction} href={`/suppliers?returnTo=/&sku=${encodeURIComponent(shortages[0].sku)}`}>{shortages[0].name} · Out of stock · Check supplier</Link> :
          products.data ? "No stockouts in loaded inventory" : "Checking inventory…"}</p>
        <p className={surface + " p-3"}>{picks.error || dispatches.error ? "Queue unavailable" : pending[0] ?
          <Link className={textAction} href={`/warehouse?stage=${pending[0].stage}&requestId=${pending[0].item.requestId}`}>{products.data?.find(p => p.sku === pending[0].item.sku)?.name ?? pending[0].item.sku} · Requested {formatAge(pending[0].item.createdAt)} ago</Link> :
          picks.data && dispatches.data ? "No waiting requests in loaded queues" : "Checking queue…"}</p>
      </div>
      <div className="grid items-start gap-5 min-[58rem]:grid-cols-2 xl:grid-cols-[5fr_7fr]">
        <section className={surface} aria-labelledby="pending-heading">
          <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 px-4 py-3">
            <div>
              <h2 id="pending-heading" className="text-lg font-semibold">Fulfillment queue</h2>
              {allPending.length > 6 && (
                <p className="text-xs text-slate-600">Showing oldest 6 of {allPending.length}</p>
              )}
            </div>
            <Link href="/warehouse" className={textAction}>View queue</Link>
          </header>
          {pending.length > 0 ? (
            <ul className="divide-y divide-slate-100">
              {pending.map(({ item, stage }) => (
                <li key={item.requestId} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                  <div>
                    <Link className={textAction} aria-label={`Open ${stage === "pick" ? "picking" : "dispatch"} request for ${products.data?.find((product) => product.sku === item.sku)?.name ?? item.sku}`}
                      href={`/warehouse?stage=${stage}&requestId=${encodeURIComponent(item.requestId)}`}>
                      {products.data?.find((product) => product.sku === item.sku)?.name ?? item.sku}
                    </Link>
                    <p className="mt-1 text-xs text-slate-600">{formatUnits(item.quantity)} </p>
                  </div>
                  <div className="space-y-2"><RequestBadge status={item.status} /><p title={formatFullDateTime(item.createdAt)} className="text-sm text-slate-700">Requested {formatAge(item.createdAt)} ago</p></div>
                </li>
              ))}
            </ul>
          ) : (picks.loading || dispatches.loading) && !picks.error && !dispatches.error ? (
            <SkeletonRows rows={3} />
          ) : (
            <p className="p-5 text-sm text-slate-600">
              {picks.error || dispatches.error ? "Some fulfillment work could not be checked."
                : "No pending items in the loaded queues."}
            </p>
          )}
        </section>

        <section className={surface + " stock-exceptions"} aria-labelledby="shortages-heading">
          <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 px-4 py-3">
            <h2 id="shortages-heading" className="text-lg font-semibold">Stock exceptions</h2>
            <Link href="/products" className={textAction}>View inventory</Link>
          </header>
          {(shortages?.length || lowStock?.length) ? (
            <ul className="divide-y divide-slate-100">
              {[...(shortages ?? []).slice(0, 3), ...[...(lowStock ?? [])].sort((a, b) => a.available - b.available).slice(0, 3)].map((item) => (
                <li key={item.sku} className="exception-row">
                  <div className="exception-product space-y-1.5">
                    <p className="text-sm font-semibold">{item.name}</p>
                    <p className="font-mono text-xs text-slate-600">{item.sku}</p>
                    <StockAllocationBar onHand={item.onHand} reserved={item.reserved} available={item.available} />
                  </div>
                  <span className={`stock-level rounded-full px-2.5 py-1 text-xs font-semibold tabular-nums ${item.available === 0 ? "bg-rose-50 text-rose-900" : "bg-amber-50 text-amber-900"}`}>
                    {item.available === 0 ? <><span className="sr-only">0 available · </span>Out of stock</> : `${item.available} available`}
                  </span>
                  <Link className={secondaryAction} aria-label={`Check supplier for ${item.name}`} href={`/suppliers?returnTo=/&sku=${encodeURIComponent(item.sku)}`}>Check supplier</Link>
                </li>
              ))}
            </ul>
          ) : products.loading && !products.error ? (
            <SkeletonRows rows={3} />
          ) : (
            <p className="p-5 text-sm text-slate-600">
              {products.error ? "Warehouse inventory could not be checked." : "No shortages in the loaded inventory."}
            </p>
          )}
        </section>
      </div>
      <div className="flex flex-wrap gap-x-5 gap-y-2 text-xs text-slate-600">
        <div>Loaded picking requests<Freshness {...picks} /></div><div>Loaded dispatch requests<Freshness {...dispatches} /></div><div>Inventory{products.data && ` · ${products.data.length} loaded items`}<Freshness {...products} /></div>
      </div>
    </div>
  );
}
