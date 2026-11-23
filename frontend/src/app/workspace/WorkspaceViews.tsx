"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import Link from "next/link";
import { ConfirmAction } from "@/components/ConfirmAction";
import { Availability, Field, field, Freshness, RequestBadge, requestStates, secondaryAction, stockFilters, surface, textAction, Warning } from "@/components/Workspace";
import { exactProduct, UUID_V4, workspacePage, workspaceRequest, workspaceSupplier, type Movement, type Read, type Request, type Role, type Status } from "@/lib/api/workspace";
import { formatAge, formatFullDateTime, formatUnits, localDateBounds } from "@/lib/formatters";
import { downloadCsv, movementCsv } from "@/lib/movementReport";
import { readTabValue, useTabValue, writeTabValue } from "@/lib/useTabValue";
import type { Product } from "@/lib/types/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import type { Submit } from "./WorkspaceClient";

export const panel = `${surface} min-w-0 space-y-4 p-4 sm:p-6`;
export type View = {
  params: Pick<URLSearchParams, "get">;
  scope: string; scrollKey: string; read: Read; revision: number; role: Role;
  update: (values: Record<string, string | null>) => void;
  open: (id: string | null) => void;
  requestHref: (id: string | null) => string;
};

export function Inventory({ view, onReserve, supplierHref, onSupplier }: {
  view: View; onReserve?: (sku: string) => void; supplierHref: (sku: string) => string; onSupplier: (sku: string) => void;
}) {
  const { warningMessage } = useStockEvents();
  const q = view.params.get("q") ?? "";
  const filter = view.params.get("filter") ?? "";
  const path = queryPath("/products", { search: q, availability: filter });
  return <div className="min-w-0 space-y-4">
    {warningMessage && <p role="status" className="rounded-lg border border-amber-300 bg-amber-50 p-3 text-sm text-amber-950">{warningMessage}</p>}
    <Filters view={view} searchLabel="Product name or SKU" extra={<Field name="Availability">
      <select name="filter" className={field} defaultValue={filter}>{stockFilters.map(({ key, label }) => <option value={key} key={key}>{label}</option>)}</select>
    </Field>} />
    <Paged<Product> key={path} title="Warehouse inventory" path={path} view={view} filtered={!!(q || filter)}
      empty="The warehouse catalogue is empty.">
      {(items) => <ul className="divide-y divide-slate-200">{items.map((item) => <li key={item.sku} className="grid min-w-0 gap-3 py-4 first:pt-0 sm:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div><p className="break-words font-semibold">{item.name}</p><p className="break-all font-mono text-xs text-slate-600">{item.sku}</p></div>
        <div className="space-y-2 text-sm"><Availability units={item.available} />
          <p className="text-slate-700">{item.onHand} on hand · {item.reserved} reserved</p>
        </div>
        <div className="flex flex-wrap gap-x-4 sm:col-span-2">
          {onReserve && item.available > 0 && <button className={textAction} onClick={() => onReserve(item.sku)}>Reserve <span className="sr-only">{item.sku}</span></button>}
          <Link className={textAction} href={supplierHref(item.sku)} onNavigate={(event) => { event.preventDefault(); onSupplier(item.sku); }}>Check supplier <span className="sr-only">for {item.sku}</span></Link>
        </div>
      </li>)}</ul>}
    </Paged>
  </div>;
}

const statusEntries = Object.entries(requestStates) as [Status, { label: string }][];
const actionDetails = {
  cancel: { label: "Cancel reservation", noun: "Cancellation", confirmation: "Confirm cancel", consequence: "This releases the reserved units for other branches." },
  pick: { label: "Mark picked", noun: "Pick", confirmation: "Confirm pick", consequence: "The request will be ready for dispatch." },
  dispatch: { label: "Dispatch stock", noun: "Dispatch", confirmation: "Confirm dispatch", consequence: "This removes the units from on-hand and reserved stock and records a movement." },
} as const;
type Action = keyof typeof actionDetails;

export function Requests({ view, target, queue, disabled, onSubmit }: {
  view: View; target: string; queue: boolean; disabled: boolean; onSubmit: Submit;
}) {
  const [review, setReview] = useState<{ row: Request; action: Action }>();
  const status = view.params.get("status") ?? (queue ? "ACCEPTED" : "");
  const { q, from, through } = listFilters(view);
  const path = queryPath("/requests", { status, search: q, ...localDateBounds(from, through) });
  const reviewAction = review && actionDetails[review.action];
  const exact = q.trim().toLowerCase();
  function apply(values: Record<string, string | null>) {
    const id = values.q?.toLowerCase();
    if (id && UUID_V4.test(id)) view.open(id);
    else view.update(values);
  }

  return <div className="space-y-4">
    {target ? <>
      <Link className={secondaryAction} href={view.requestHref(null)} onNavigate={(event) => { event.preventDefault(); view.open(null); }}>
        {queue ? "Back to fulfillment queue" : "Back to requests"}
      </Link>
      <RequestDetail key={target} {...{ view, target, disabled }} review={(row, action) => setReview({ row, action })} />
    </> : <>
      <Filters view={view} searchLabel="Product, request reference, or branch" dates onApply={apply}
        extra={<Field name="Status"><select name="status" className={field} defaultValue={status}>
          {!queue && <option value="">All statuses</option>}
          {statusEntries.filter(([value]) => !queue || value === "ACCEPTED" || value === "PICKED")
            .map(([value, { label }]) => <option key={value} value={value}>{label}</option>)}
        </select></Field>} />
      <Paged<Request> key={path} path={path} view={view} title={queue ? "Fulfillment queue" : "Stock requests"}
        filtered={!!(q || from || through || (!queue && status))}
        empty={queue ? status === "PICKED" ? "No requests are ready to dispatch." : "No requests are waiting to be picked." : "No stock requests have been recorded."}>
        {(items, stale) => <ul className="divide-y divide-slate-200">{items.map((row) => {
          const action = row.status === "ACCEPTED" ? "pick" : row.status === "PICKED" ? "dispatch" : null;
          return <li key={row.requestId} className="grid gap-3 py-4 first:pt-0 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_auto]">
            <div className="min-w-0">
              <Link className={textAction} href={view.requestHref(row.requestId)} aria-label={`Open request ${row.requestId}`}
                onNavigate={(event) => { event.preventDefault(); view.open(row.requestId); }}>{row.productName ?? row.sku}</Link>
              <p className="break-all font-mono text-xs text-slate-600">{row.sku} · {row.requestId.slice(0, 8)}</p>
              <p className="mt-1 break-words text-sm text-slate-700">{row.branchId} · {formatUnits(row.quantity)}</p>
            </div>
            <div className="space-y-2 text-sm"><RequestBadge status={row.status} />
              <p className="text-slate-700">Created {formatFullDateTime(row.createdAt)}</p>
              {queue && <p className="font-semibold text-amber-900">Waiting {formatAge(status === "PICKED" ? row.updatedAt : row.createdAt)}</p>}
            </div>
            {queue && action && <button className={`${secondaryAction} self-start`} disabled={disabled || stale}
              onClick={() => setReview({ row, action })}>{actionDetails[action].label}</button>}
          </li>;
        })}</ul>}
      </Paged>
      {UUID_V4.test(exact) && <Link className={textAction} href={view.requestHref(exact)}
        onNavigate={(event) => { event.preventDefault(); view.open(exact); }}>Open exact request</Link>}
    </>}
    {review && reviewAction && !disabled && <ConfirmAction title={reviewAction.label} action={reviewAction.confirmation} destructive={review.action === "cancel"}
      description={`${formatUnits(review.row.quantity)} of ${review.row.productName ?? review.row.sku} for ${review.row.branchId}. ${reviewAction.consequence}`}
      onClose={() => setReview(undefined)} onConfirm={() => {
        const { row, action } = review;
        setReview(undefined);
        void onSubmit({ path: `/requests/${row.requestId}/${action}`, label: reviewAction.noun, item: { sku: row.sku, quantity: row.quantity } });
      }} />}
  </div>;
}

function RequestDetail({ target, view, disabled, review }: {
  target: string; view: View; disabled: boolean; review: (row: Request, action: Action) => void;
}) {
  const { read, revision, role } = view;
  const valid = UUID_V4.test(target);
  const load = useCallback(() => workspaceRequest(read, target), [read, target]);
  const query = useWarehouseQuery(load, valid);
  const refresh = query.refresh;
  useEffect(() => { if (revision) void refresh(); }, [revision, refresh]);
  const [copy, setCopy] = useState("");
  const row = query.data;
  const actions: Action[] = row?.status === "ACCEPTED" ? (role === "OPERATOR" ? ["cancel"] : ["pick", "cancel"]) :
    row?.status === "PICKED" && role !== "OPERATOR" ? ["dispatch"] : [];
  const stages: Status[] = row?.status === "REJECTED" ? ["REJECTED"] : row?.status === "CANCELLED" ?
    ["ACCEPTED", "CANCELLED"] : ["ACCEPTED", "PICKED", "DISPATCHED"];

  return <section className={`${panel} border-teal-200`}>
    <div className="flex flex-wrap items-start justify-between gap-3"><h2 className="text-xl font-semibold">Request details</h2>
      {valid && <button className={secondaryAction} onClick={refresh} disabled={query.loading}>Refresh request</button>}</div>
    {!valid ? <Warning>Enter a complete request reference to open the record.</Warning> : <Freshness {...query} />}
    {query.error && <Warning>{query.error}</Warning>}
    {row && <>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div><h3 className="break-words text-2xl font-semibold">{row.productName ?? row.sku}</h3><p className="break-all font-mono text-sm text-slate-600">{row.sku}</p></div>
        <RequestBadge status={row.status} />
      </div>
      <div className="flex flex-wrap items-center gap-x-4">
        <p className="select-all break-all font-mono text-sm">{row.requestId}</p>
        <button className={textAction} onClick={async () => {
          try { await navigator.clipboard.writeText(row.requestId); setCopy("Request reference copied."); }
          catch { setCopy("Select the request reference to copy it manually."); }
        }}>Copy reference</button>
        <span role="status" className="text-sm text-slate-700">{copy}</span>
      </div>
      <dl className="grid gap-3 rounded-lg bg-slate-50 p-4 text-sm sm:grid-cols-2">
        <div><dt className="text-slate-600">Requested</dt><dd className="font-semibold">{formatUnits(row.quantity)}</dd></div>
        <div><dt className="text-slate-600">Branch</dt><dd className="break-words font-semibold">{row.branchId}</dd></div>
        <div><dt className="text-slate-600">Created</dt><dd>{formatFullDateTime(row.createdAt)}</dd></div>
        <div><dt className="text-slate-600">Last transition</dt><dd>{formatFullDateTime(row.updatedAt)}</dd></div>
      </dl>
      {row.decisionReason && <p className="break-words text-sm">{row.decisionReason}</p>}
      <ol aria-label="Request lifecycle" className="flex flex-wrap gap-2">
        {stages.map((stage, index) => <li key={stage} aria-current={stage === row.status ? "step" : undefined}
          className={`rounded-lg border px-3 py-2 text-sm ${stage === row.status ? "border-teal-600 bg-teal-50 font-semibold" : "border-slate-200 text-slate-700"}`}>
          {index < stages.indexOf(row.status) && <span aria-label="Complete">✓ </span>}{requestStates[stage].label}
        </li>)}
      </ol>
      <div className="flex flex-wrap gap-2">
        {actions.map((action) => <button key={action} className={`${secondaryAction} ${action === "cancel" ? "border-rose-300 text-rose-900" : ""}`}
          disabled={disabled || query.loading || !!query.error} onClick={() => review(row, action)}>{actionDetails[action].label}</button>)}
      </div>
    </>}
  </section>;
}

export function Ledger({ view }: { view: View }) {
  const { q, from, through } = listFilters(view);
  const reason = view.params.get("filter") ?? "";
  const path = queryPath("/movements", { search: q, reason, ...localDateBounds(from, through) });
  return <div className="space-y-4">
    <Filters view={view} searchLabel="SKU, reference, branch, or note" dates extra={<Field name="Movement type">
      <select name="filter" className={field} defaultValue={reason}>
        <option value="">All movements</option><option value="RECEIVE">Received</option><option value="DISPATCH">Dispatched</option>
      </select>
    </Field>} />
    <Paged<Movement> key={path} title="Movements ledger" path={path} view={view} filtered={!!(q || from || through || reason)} empty="No stock movements have been recorded."
      action={(items) => items.length ? <button className={secondaryAction} onClick={() => downloadCsv("warehouse-movements.csv", movementCsv(items))}>Export this page</button> : null}>
      {(items) => <ul className="divide-y divide-slate-200">{items.map((row) => <li key={row.id} className="grid min-w-0 gap-3 py-4 first:pt-0 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
        <div className="space-y-2"><p className="break-all font-semibold">{row.sku}</p>
          <span className={`inline-flex rounded-lg px-3 py-1 font-semibold tabular-nums ${row.reason === "RECEIVE" ? "bg-teal-50 text-teal-950" : "bg-amber-50 text-amber-950"}`}>
            {row.reason === "RECEIVE" ? "Received +" : "Dispatched "}{row.quantityDelta}
          </span>
          <p className="text-sm text-slate-600">{formatFullDateTime(row.occurredAt)} · Movement {row.id}</p>
        </div>
        <div className="min-w-0 text-sm">
          {row.stockRequestId && <Link className={textAction} href={view.requestHref(row.stockRequestId)}
            onNavigate={(event) => { event.preventDefault(); view.open(row.stockRequestId!); }}>Open dispatch request</Link>}
          {row.branchId && <p className="break-words">{row.branchId}</p>}
          {row.note && <p className="break-words">{row.recordedBy} · {row.note}</p>}
          {row.receivingCommandId && <p className="mt-2 break-all font-mono text-xs text-slate-600">Command {row.receivingCommandId}</p>}
        </div>
      </li>)}</ul>}
    </Paged>
  </div>;
}

export function Supplier({ view, returnToDraft }: { view: View; returnToDraft?: () => void }) {
  const sku = view.params.get("sku") ?? "";
  const [repeat, setRepeat] = useState(0);
  return <div className="space-y-4">
    <form className={`${panel} max-w-2xl`} onSubmit={(event) => {
      event.preventDefault();
      const next = String(new FormData(event.currentTarget).get("sku")).trim();
      if (next === sku) setRepeat((value) => value + 1);
      else view.update({ sku: next });
    }}>
      <div><h2 className="text-xl font-semibold">Supplier availability</h2><p className="mt-1 text-sm text-slate-600">Check availability reported by the configured supplier.</p></div>
      <Field name="SKU"><input className={field} key={sku} name="sku" defaultValue={sku} maxLength={64} required pattern={String.raw`[A-Za-z0-9_\-]{1,64}`} /></Field>
      <div className="flex flex-wrap gap-x-4 gap-y-2"><button className={secondaryAction}>Check supplier</button>
        {returnToDraft && <button type="button" className={textAction} onClick={returnToDraft}>Return to reservation draft</button>}</div>
    </form>
    {sku && <SupplierResult key={sku} sku={sku} read={view.read} repeat={repeat} />}
  </div>;
}

function SupplierResult({ sku, read, repeat }: { sku: string; read: Read; repeat: number }) {
  const load = useCallback(() => workspaceSupplier(read, sku), [read, sku]);
  const supplier = useWarehouseQuery(load, true, false);
  const loadStock = useCallback(() => exactProduct(read, sku), [read, sku]);
  const stock = useWarehouseQuery(loadStock);
  const refresh = supplier.refresh;
  const previous = useRef(repeat);
  useEffect(() => { if (repeat !== previous.current) { previous.current = repeat; void refresh(); } }, [repeat, refresh]);
  const item = supplier.data;
  return <section aria-label="Supplier inquiry result" className={`${panel} max-w-2xl border-teal-200`} aria-busy={supplier.loading}>
    <h3 className="text-lg font-semibold">{sku}</h3>
    {supplier.loading ? <p role="status">Checking supplier…</p> : supplier.error ? <Warning>{supplier.error} Check the SKU or run the inquiry again.</Warning> :
      item && <div className="space-y-4" role="status">
        <dl className="grid gap-3 sm:grid-cols-2">
          <div className="rounded-lg bg-teal-50 p-4 text-teal-950"><dt className="text-sm">{item.supplier}</dt><dd className="text-xl font-semibold">{formatUnits(item.reportedQuantity)} reported by supplier</dd></div>
          <div className="rounded-lg bg-slate-50 p-4"><dt className="text-sm">Warehouse</dt><dd className="text-xl font-semibold">{stock.data && !stock.error ? `${formatUnits(stock.data.available)} available` : stock.loading ? "Checking balance…" : "Balance unavailable"}</dd></div>
          <div><dt className="text-sm text-slate-600">Source</dt><dd>{item.source === "MOCK_SUPPLIER" ? "Supplier simulator" : item.source}</dd></div>
          <div><dt className="text-sm text-slate-600">Supplier checked</dt><dd>{formatFullDateTime(item.checkedAt)}</dd></div>
          {item.estimatedRestockDays !== undefined && <div><dt className="text-sm text-slate-600">Estimated restock</dt><dd>{item.estimatedRestockDays} days</dd></div>}
        </dl>
      </div>}
  </section>;
}

function listFilters(view: View) {
  return { q: view.params.get("q") ?? "", from: view.params.get("from") ?? "", through: view.params.get("through") ?? "" };
}

function queryPath(path: string, values: Record<string, string>) {
  return `${path}?${new URLSearchParams(values)}`;
}

function Filters({ view, searchLabel, extra, dates, onApply }: {
  view: View; searchLabel: string; extra: ReactNode; dates?: boolean; onApply?: (values: Record<string, string | null>) => void;
}) {
  const { q, from, through } = listFilters(view);
  const [error, setError] = useState("");
  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const filters = Object.fromEntries(Array.from(new FormData(event.currentTarget)).map(([key, value]) => [key, String(value).trim()]));
    if (filters.from && filters.through && filters.from > filters.through) {
      setError("Choose a through date on or after the from date.");
      return;
    }
    setError("");
    (onApply ?? view.update)(filters);
  }
  function clear(form: HTMLFormElement | null) {
    form?.reset();
    setError("");
    view.update({ q: null, status: null, filter: null, from: null, through: null });
  }
  return <form className={`${panel} !space-y-0 grid items-end gap-3 md:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_auto]`} onSubmit={apply}>
    <Field name={searchLabel}><input key={q} name="q" type="search" className={field} defaultValue={q} maxLength={100} /></Field>
    <div key={`${view.params.get("status") ?? ""}:${view.params.get("filter") ?? ""}`} className="min-w-0">{extra}</div>
    {dates && <div className="grid min-w-0 gap-2 sm:grid-cols-2 md:col-span-2 md:col-start-1 md:row-start-2">
      <Field name="From date"><input type="date" name="from" key={from} className={field} defaultValue={from} /></Field>
      <Field name="Through date"><input type="date" name="through" key={through} className={field} defaultValue={through} /></Field>
    </div>}
    <div className="flex flex-wrap gap-2 md:col-start-3 md:row-start-1">
      <button className={secondaryAction}>Apply filters</button>
      <button type="button" className={textAction} onClick={(event) => clear(event.currentTarget.form)}>Clear filters</button>
    </div>
    {error && <div className="md:col-span-3"><Warning>{error}</Warning></div>}
  </form>;
}

function Paged<T>({ title, path, view, filtered, empty, action, children }: {
  title: string; path: string; view: View; filtered: boolean; empty: string;
  action?: (items: T[]) => ReactNode; children: (items: T[], stale: boolean) => ReactNode;
}) {
  const { scrollKey, scope, read, revision } = view;
  const key = `${scope}:pages:${path}`;
  const [history, setHistory] = useTabValue<string[]>(key, [""]);
  const cursor = history.at(-1) ?? "";
  const load = useCallback(() => workspacePage<T>(read, `${path}&cursor=${encodeURIComponent(cursor)}`)
    .then((page) => ({ cursor, page })), [read, path, cursor]);
  const query = useWarehouseQuery(load);
  const refresh = query.refresh;
  useEffect(() => { if (revision) void refresh(); }, [revision, refresh]);
  const page = query.data?.cursor === cursor ? query.data.page : undefined;
  const ready = !!page;
  const restored = useRef(false);
  useEffect(() => {
    if (!ready || restored.current) return;
    restored.current = true;
    const y = readTabValue(scrollKey, 0);
    if (y) window.scrollTo({ top: y, behavior: "instant" });
  }, [ready, scrollKey]);
  useEffect(() => {
    const location = window.location.href;
    const saveScroll = () => {
      if (restored.current && window.location.href === location) writeTabValue(scrollKey, window.scrollY);
    };
    window.addEventListener("scroll", saveScroll, { passive: true });
    window.addEventListener("pagehide", saveScroll);
    return () => {
      window.removeEventListener("scroll", saveScroll);
      window.removeEventListener("pagehide", saveScroll);
    };
  }, [scrollKey]);

  return <section className={panel} aria-busy={query.loading}>
    <header className="flex flex-wrap items-start justify-between gap-3"><div><h2 className="text-xl font-semibold">{title}</h2><Freshness {...query} /></div>
      <div className="flex flex-wrap gap-2">{page && !query.error && action?.(page.items)}
        <button className={secondaryAction} disabled={query.loading} onClick={refresh}>Refresh</button></div>
    </header>
    {query.error && <Warning><p>{query.error}</p><p>Refresh to try this page again{page ? ". The records shown may be out of date." : "."}</p></Warning>}
    {page && (page.items.length ? children(page.items, !!query.error || query.loading) :
      <p className="rounded-lg bg-slate-50 p-4 text-sm text-slate-700">{filtered ? "No records match these filters. Clear or change the filters to broaden the search." : empty}</p>)}
    <div className="flex flex-wrap items-center gap-3">
      <button className={secondaryAction} disabled={history.length <= 1 || query.loading} onClick={() => setHistory(history.slice(0, -1))}>Previous</button>
      <span className="text-sm text-slate-600">Page {history.length}</span>
      <button className={secondaryAction} disabled={!page?.nextCursor || query.loading || !!query.error}
        onClick={() => { if (page?.nextCursor) setHistory([...history, page.nextCursor]); }}>Next</button>
      {history.length > 1 && <button className={textAction} onClick={() => setHistory([""])}>First page</button>}
    </div>
  </section>;
}
