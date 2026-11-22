"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { fetchProducts } from "@/lib/api/inventory";
import { formatUnits } from "@/lib/formatters";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { Freshness, primaryAction, secondaryAction, surface, WorkspaceHeading } from "@/components/Workspace";

const filters = [
  { key: "", label: "All stock" },
  { key: "in_stock", label: "4+ available" },
  { key: "low_stock", label: "Low stock" },
  { key: "out_of_stock", label: "Out of stock" },
];

export function ProductsClient() {
  const params = useSearchParams();
  const search = params.get("q") ?? "";
  const filter = filters.find((entry) => entry.key === params.get("filter"))?.key ?? "";
  const inventory = useWarehouseQuery(fetchProducts);
  const needle = search.trim().toLowerCase();
  const rows = (inventory.data ?? []).filter((item) => {
    if (needle && !`${item.name} ${item.sku}`.toLowerCase().includes(needle)) return false;
    if (filter === "in_stock") return item.available >= 4;
    if (filter === "low_stock") return item.available > 0 && item.available <= 3;
    if (filter === "out_of_stock") return item.available === 0;
    return true;
  });
  const returnTo = `/products${params.size > 0 ? `?${params}` : ""}`;

  return (
    <div className="space-y-6">
      <WorkspaceHeading title="Inventory" description="Find warehouse items and see what is available to reserve."
        action={<Link className={primaryAction} href="/requests/new?returnTo=/products">New request</Link>} />
      <section className={surface} aria-labelledby="catalog-title">
        <header className="space-y-4 border-b border-slate-200 p-4 sm:p-5">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div><h2 id="catalog-title" className="font-semibold">Product catalog</h2><Freshness {...inventory} /></div>
            <button type="button" className={secondaryAction} onClick={() => void inventory.refresh()}>Refresh</button>
          </div>
          <form action="/products" className="flex flex-wrap items-end gap-3">
            <label className="min-w-0 flex-1 text-sm font-semibold">Name or SKU
              <input type="search" name="q" key={search} defaultValue={search} className="mt-1 block min-h-11 w-full min-w-44 rounded-lg border border-slate-300 px-3 text-sm font-normal" />
            </label>
            <label className="text-sm font-semibold">Availability
              <select name="filter" key={filter} defaultValue={filter} className="mt-1 block min-h-11 rounded-lg border border-slate-300 px-3 text-sm font-normal">
                {filters.map((entry) => <option key={entry.key} value={entry.key}>{entry.label}</option>)}
              </select>
            </label>
            <button type="submit" className={secondaryAction}>Apply filters</button>
          </form>
          {inventory.error && <p role="alert" className="text-sm text-rose-900">{inventory.error}</p>}
          {inventory.data && <p className="text-xs text-slate-600">{rows.length} of {inventory.data.length} loaded items</p>}
        </header>
        {rows.length ? (
          <ul className="divide-y divide-slate-100">
            {rows.map((item) => (
              <li key={item.sku} className="grid items-center gap-4 p-4 sm:p-5 md:grid-cols-[minmax(0,1.25fr)_minmax(0,1fr)_auto]">
                <div>
                  <p className="break-words font-semibold text-slate-950">{item.name}</p>
                  <p className="mt-1 break-all font-mono text-xs text-slate-600">{item.sku}</p>
                </div>
                <dl className="grid max-w-xs grid-cols-3 gap-2 text-sm tabular-nums">
                  <div><dt className="text-xs text-slate-600">Available</dt>
                    <dd className={`font-semibold ${item.available === 0 ? "text-rose-900" : item.available <= 3 ? "text-amber-900" : "text-teal-900"}`}>
                      {formatUnits(item.available)}
                    </dd>
                  </div>
                  <div><dt className="text-xs text-slate-600">On hand</dt><dd>{item.onHand}</dd></div>
                  <div><dt className="text-xs text-slate-600">Reserved</dt><dd>{item.reserved}</dd></div>
                </dl>
                {item.available === 0 ? (
                  <Link className={secondaryAction} href={`/suppliers?sku=${encodeURIComponent(item.sku)}&returnTo=${encodeURIComponent(returnTo)}`}>Check supplier</Link>
                ) : (
                  <Link className={secondaryAction} href={`/requests/new?sku=${encodeURIComponent(item.sku)}&returnTo=${encodeURIComponent(returnTo)}`}>Request stock</Link>
                )}
              </li>
            ))}
          </ul>
        ) : inventory.data && <p className="p-5 text-sm text-slate-600">{inventory.data.length ? "No loaded items match these filters." : "The warehouse catalog is empty."}</p>}
        {inventory.data?.length === 500 && <p className="border-t border-slate-200 p-4 text-sm text-slate-600">Filters apply to the 500 items loaded from the warehouse.</p>}
      </section>
    </div>
  );
}
