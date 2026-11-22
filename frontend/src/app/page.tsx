"use client";

import Link from "next/link";
import { fetchProducts, fetchStockRequests } from "@/lib/api/inventory";
import { formatFullDateTime, formatUnits } from "@/lib/formatters";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { Freshness, primaryAction, RequestBadge, surface, WorkspaceHeading } from "@/components/Workspace";

const readPicks = () => fetchStockRequests("ACCEPTED");
const readDispatches = () => fetchStockRequests("PICKED");

export default function Home() {
  const products = useWarehouseQuery(fetchProducts);
  const picks = useWarehouseQuery(readPicks);
  const dispatches = useWarehouseQuery(readDispatches);
  const { warningMessage } = useStockEvents();
  const shortages = products.data?.filter((item) => item.available === 0);
  const pending = [
    ...picks.data?.slice(0, 3).map((item) => ({ item, stage: "pick" })) ?? [],
    ...dispatches.data?.slice(0, 3).map((item) => ({ item, stage: "dispatch" })) ?? [],
  ];

  return (
    <div className="space-y-7">
      <WorkspaceHeading title="Operations overview" description="See fulfillment work and stock shortages at the central warehouse."
        action={<Link href="/requests/new" className={primaryAction}>New request</Link>} />

      {warningMessage && (
        <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-950">
          {warningMessage} <Link className="font-semibold underline" href="/activity">View diagnostics</Link>
        </p>
      )}

      <div className="grid gap-3 sm:grid-cols-3">
        {[
          { title: "To pick", rows: picks.data, query: picks, href: "/warehouse?stage=pick" },
          { title: "Ready to dispatch", rows: dispatches.data, query: dispatches, href: "/warehouse?stage=dispatch" },
          { title: "Out of stock", rows: shortages, query: products, href: "/products?filter=out_of_stock" },
        ].map(({ title, rows, query, href }) => (
          <Link key={title} href={href} className={`${surface} block p-4 hover:border-teal-500 sm:p-5`}>
            <p className="text-sm font-semibold text-slate-700">{title}</p>
            <p className="my-2 text-3xl font-semibold tabular-nums text-slate-950">{rows?.length ?? "—"}</p>
            <Freshness {...query} />
            <p className="mt-2 text-xs text-slate-600">In loaded records</p>
          </Link>
        ))}
      </div>

      <div className="grid items-start gap-5 lg:grid-cols-[minmax(0,1.5fr)_minmax(0,1fr)]">
        <section className={surface} aria-labelledby="pending-heading">
          <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 p-4 sm:p-5">
            <h2 id="pending-heading" className="text-lg font-semibold">Pending fulfillment</h2>
            <Link href="/warehouse" className="text-sm font-semibold text-teal-800 underline">Open queue</Link>
          </header>
          {pending.length > 0 ? (
            <ul className="divide-y divide-slate-100">
              {pending.map(({ item, stage }) => (
                <li key={item.requestId} className="flex flex-wrap items-center justify-between gap-3 p-4 sm:p-5">
                  <div>
                    <Link className="text-sm font-semibold text-slate-950 hover:underline"
                      href={`/warehouse?stage=${stage}&requestId=${encodeURIComponent(item.requestId)}`}>
                      {products.data?.find((product) => product.sku === item.sku)?.name ?? item.sku}
                    </Link>
                    <p className="mt-1 text-xs text-slate-600">{formatUnits(item.quantity)} · Requested {formatFullDateTime(item.createdAt)}</p>
                  </div>
                  <RequestBadge status={item.status} />
                </li>
              ))}
            </ul>
          ) : (
            <p className="p-5 text-sm text-slate-600">
              {picks.error || dispatches.error ? "Some fulfillment work could not be checked."
                : picks.loading || dispatches.loading ? "Checking warehouse queue…" : "No pending items in the loaded queues."}
            </p>
          )}
        </section>

        <section className={surface} aria-labelledby="shortages-heading">
          <header className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 p-4 sm:p-5">
            <h2 id="shortages-heading" className="text-lg font-semibold">Stock shortages</h2>
            <Link href="/products?filter=out_of_stock" className="text-sm font-semibold text-teal-800 underline">View inventory</Link>
          </header>
          {shortages?.length ? (
            <ul className="divide-y divide-slate-100">
              {shortages.slice(0, 5).map((item) => (
                <li key={item.sku} className="flex flex-wrap items-center justify-between gap-3 p-4 sm:p-5">
                  <div><p className="text-sm font-semibold">{item.name}</p><p className="text-xs text-slate-600">{item.sku} · 0 available</p></div>
                  <Link className="text-sm font-semibold text-teal-800 underline" href={`/suppliers?sku=${encodeURIComponent(item.sku)}`}>Check supplier</Link>
                </li>
              ))}
            </ul>
          ) : (
            <p className="p-5 text-sm text-slate-600">
              {products.error ? "Warehouse inventory could not be checked." : products.loading ? "Checking inventory…" : "No shortages in the loaded inventory."}
            </p>
          )}
        </section>
      </div>
    </div>
  );
}
