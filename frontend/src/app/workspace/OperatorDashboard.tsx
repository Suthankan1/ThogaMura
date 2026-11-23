"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { Field, field, Freshness, secondaryAction, surface, textAction, Warning } from "@/components/Workspace";
import { formatAge, formatUnits } from "@/lib/formatters";
import type { Request } from "@/lib/api/workspace";
import type { Product } from "@/lib/types/inventory";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import type { View } from "./WorkspaceViews";
import type { Submit } from "./WorkspaceClient";

export function StageAge({ since }: { since: string }) {
  const [, tick] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => tick(n => n + 1), 30000);
    return () => clearInterval(timer);
  }, []);
  return <>{formatAge(since)}</>;
}

export function BalanceChange({ available }: { available: number }) {
  const [previous, setPrevious] = useState(available);
  const [change, setChange] = useState("");
  if (previous !== available) {
    setPrevious(available);
    setChange(previous + " → " + available + " available");
  }
  useEffect(() => {
    if (!change) return;
    const timer = setTimeout(() => setChange(""), 4500);
    return () => clearTimeout(timer);
  }, [change]);
  return <span role="status" className="ml-2 text-sm font-semibold text-teal-800">{change}</span>;
}

export const followHref = (id: string) => "/workspace?section=suppliers&followUp=" + encodeURIComponent(id);

export function Overview({ view }: { view: View }) {
  const { read } = view;
  const load = useCallback(() => read<{ stockout: Product | null; waiting: Request | null; counts: { toPick: number; toDispatch: number; lowStock: number; outOfStock: number } }>("/overview"), [read]);
  const query = useWarehouseQuery(load);
  const { stockout, waiting } = query.data ?? {};
  const pending = query.error ?? (query.data ? "" : "Checking warehouse…");
  return <div className="grid gap-3 sm:grid-cols-2">
    <section className={surface + " space-y-2 p-4"}><h2 className="font-semibold">First stockout</h2>
      {pending || (stockout === undefined ? "Warehouse inventory could not be checked." : stockout ? <><p>{stockout.name} · 0 available</p>
        <Link className={textAction} href={"/workspace?section=suppliers&sku=" + encodeURIComponent(stockout.sku)}>Check supplier</Link>
        {stockout.followUpId && <p><Link className={textAction} href={followHref(stockout.followUpId)}>Follow-up open</Link></p>}</> : "No items out of stock.")}
    </section>
    <section className={surface + " space-y-2 p-4"}><h2 className="font-semibold">Longest waiting request</h2>
      {pending || (waiting === undefined ? "The request queue could not be checked." : waiting ?
        <Link className="block space-y-2" data-request-link={waiting.requestId} href={view.requestHref(waiting.requestId)}
          aria-label="View request" aria-describedby="overview-waiting-product"
          onNavigate={event => { event.preventDefault(); view.open(waiting.requestId); }}>
          <p id="overview-waiting-product">{waiting.productName} · {formatUnits(waiting.quantity)}</p>
          <p>Awaiting {waiting.status === "PICKED" ? "dispatch" : "pick"} · <StageAge since={waiting.updatedAt} /></p>
          <span className={textAction}>View request</span>
        </Link> : "No requests waiting.")}
    </section>
    {query.data?.counts && <dl className="grid grid-cols-2 gap-3 text-sm sm:col-span-2 sm:grid-cols-4">
      {([["To pick", "toPick"], ["Ready to dispatch", "toDispatch"], ["Out of stock", "outOfStock"], ["Low stock", "lowStock"]] as const).map(([label, key]) =>
        <div key={key} className={surface + " p-3"}><dt>{label}</dt><dd className="text-xl font-semibold">{query.data!.counts[key]}</dd></div>)}
    </dl>}
    <Freshness {...query} />
    {query.error && <button className={secondaryAction} onClick={query.refresh}>Retry overview</button>}
  </div>;
}

type Task = { id: string; sku: string; owner: string; dueDate: string; status: "OPEN" | "CLOSED" };
export function FollowUp({ id, sku, view, submit, disabled }: {
  id?: string; sku?: string; view: View; submit: Submit; disabled: boolean;
}) {
  const { read, role, revision } = view;
  const staff = role !== "OPERATOR";
  const load = useCallback(() => read<Task>("/supplier-follow-ups/" + encodeURIComponent(id!)), [read, id]);
  const task = useWarehouseQuery(load, !!id);
  const roster = useCallback(() => read<{ items: string[] }>("/supplier-follow-ups/owners"), [read]);
  const owners = useWarehouseQuery(roster, staff, false);
  const [editing, edit] = useState(false);
  const refresh = task.refresh;
  useEffect(() => { if (revision && id) void refresh(); }, [revision, id, refresh]);
  const row = task.data;
  if (!staff && !id) return null;
  return <section className={surface + " max-w-2xl space-y-3 p-4"}>
    <h3 className="font-semibold">Supplier follow-up{row && " · " + row.sku}</h3>
    {id && (task.error ? <Warning>{task.error}</Warning> : row ?
      <p>{row.status === "OPEN" ? "Open" : "Closed"} · Assigned to {row.owner} · Due {row.dueDate}</p> : <p>Loading follow-up…</p>)}
    {row && <Link className={textAction} href={"/workspace?section=inventory&q=" + encodeURIComponent(row.sku)}>View inventory row</Link>}
    {staff && !task.error && (!id || row?.status === "OPEN") && <>
      {!editing && <button className={secondaryAction} disabled={disabled} onClick={() => edit(true)}>{id ? "Edit follow-up" : "Create follow-up"}</button>}
      {editing && <form className="space-y-3" onSubmit={(e) => {
        e.preventDefault();
        const values = new FormData(e.currentTarget);
        void submit({ path: id ? "/supplier-follow-ups/" + id + "/update" : "/supplier-follow-ups", label: "Supplier follow-up",
          assignment: { sku: row?.sku ?? sku!, owner: String(values.get("owner")), dueDate: String(values.get("dueDate")) } });
      }}>
        {owners.error && <Warning>{owners.error}</Warning>}
        <Field name="Owner"><select className={field} name="owner" required defaultValue={row?.owner ?? ""}>
          <option value="">Select owner</option>{owners.data?.items.map(name => <option key={name}>{name}</option>)}</select></Field>
        <Field name="Due date"><input className={field} name="dueDate" required type="date" defaultValue={row?.dueDate} /></Field>
        <button className={secondaryAction} disabled={disabled || !owners.data?.items.length}>Save follow-up</button>
      </form>}
      {id && <button className={secondaryAction} disabled={disabled} onClick={() => void submit({ path: "/supplier-follow-ups/" + id + "/close", label: "Close follow-up" })}>Close follow-up</button>}
    </>}
  </section>;
}
