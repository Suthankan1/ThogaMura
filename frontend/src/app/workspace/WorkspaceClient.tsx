"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { Freshness, RequestBadge, WorkspaceHeading, primaryAction, secondaryAction, surface } from "@/components/Workspace";
import { formatFullDateTime } from "@/lib/formatters";
import type { Product, StockRequestStatus } from "@/lib/types/inventory";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";

type Role = "OPERATOR" | "PICKER" | "SUPERVISOR";
type Session = { name: string; role: Role; branch: string; csrf: string };
type Status = StockRequestStatus | "CANCELLED";
type Request = { requestId: string; sku: string; branchId: string; quantity: number; status: Status; createdAt: string; decisionReason: string | null };
type Movement = { id: string; sku: string; reason: "RECEIVE" | "DISPATCH"; quantityDelta: number; occurredAt: string; branchId: string | null; recordedBy: string | null; note: string | null };
type Page<T> = { items: T[]; nextCursor: string | null };
type Section = "Inventory" | "Requests" | "Receiving" | "Movements" | "Suppliers";
type Command = { path: string; body?: { sku: string; quantity: number; note?: string }; label: string; key: string };

const field = "min-h-11 w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm font-normal text-slate-950";
const panel = `${surface} space-y-4 p-4 sm:p-6`;
const number = new Intl.NumberFormat("en");
const roles: Record<Role, string> = { OPERATOR: "Branch operator", PICKER: "Warehouse picker", SUPERVISOR: "Warehouse supervisor" };
const statuses: Status[] = ["ACCEPTED", "REJECTED", "PICKED", "DISPATCHED", "CANCELLED"];

class WorkspaceError extends Error {
  constructor(message: string, readonly status: number, readonly reference?: string, readonly code?: string) {
    super(message);
  }
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`/api/v2${path}`, { cache: "no-store", ...init });
  if (response.status === 204) return undefined as T;
  const payload = await response.json().catch(() => null);
  if (!response.ok || payload === null) {
    throw new WorkspaceError(payload?.message ?? "The workspace could not confirm the result.", response.status, payload?.requestId, payload?.code);
  }
  return payload as T;
}

function Label({ children, name }: { children: ReactNode; name: string }) {
  return <label className="block space-y-1 text-sm font-semibold text-slate-700"><span>{name}</span>{children}</label>;
}

function Warning({ children }: { children: ReactNode }) {
  return <p role="alert" className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-950">{children}</p>;
}

export function WorkspaceClient() {
  const [session, setSession] = useState<Session | null>();
  const [problem, setProblem] = useState("");
  const [disabled, setDisabled] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    call<Session>("/session", { signal: controller.signal }).then(setSession).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setSession(null);
      if (error instanceof WorkspaceError && error.status === 404) setDisabled(true);
      else if (!(error instanceof WorkspaceError && error.status === 401)) setProblem("Could not connect to the workspace. Reload to check the gateway.");
    });
    return () => controller.abort();
  }, []);

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setLoading(true);
    setProblem("");
    try {
      setSession(await call<Session>("/session", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Workspace-Client": "browser" },
        body: JSON.stringify({ name: data.get("name"), password: data.get("password") }),
      }));
    } catch (error) {
      setProblem(error instanceof Error ? error.message : "Could not sign in.");
    } finally {
      form.reset();
      setLoading(false);
    }
  }

  const expire = useCallback(() => {
    setSession(null);
    setProblem("Your session ended. Sign in again to continue.");
  }, []);

  if (session) return <Desk key={session.name + ":" + session.branch} session={session} onExpire={expire} onSignOut={() => setSession(null)} />;
  return <div className="mx-auto max-w-lg space-y-6">
    <WorkspaceHeading title="Branch workspace" description="Sign in to manage stock requests and warehouse operations for your assigned role." />
    {session === undefined ? <p role="status">Checking your session…</p> : disabled ?
      <div className={panel}><p>The branch workspace is disabled on this gateway.</p><Link className={secondaryAction} href="/products">Open demo inventory</Link></div> :
      <form className={panel} onSubmit={signIn}>
        <Label name="Account"><input className={field} required name="name" maxLength={40} autoComplete="username" /></Label>
        <Label name="Password"><input className={field} required type="password" name="password" maxLength={128} autoComplete="current-password" /></Label>
        {problem && <Warning>{problem}</Warning>}
        <button type="submit" className={primaryAction} disabled={loading}>{loading ? "Signing in…" : "Sign in"}</button>
      </form>}
  </div>;
}

function Desk({ session, onSignOut, onExpire }: { session: Session; onSignOut: () => void; onExpire: () => void }) {
  const router = useRouter();
  const params = useSearchParams();
  const target = params.get("request") ?? "";
  const [section, setSection] = useState<Section>(target ? "Requests" : "Inventory");
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState<{ text: string; error: boolean; reference?: string }>();
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const storageKey = `thogamura.workspace.pending:${session.name}:${session.branch}`;
  const [pending, setPending] = useState<Command | null>(() => {
    try { return JSON.parse(sessionStorage.getItem(storageKey) ?? "null") as Command | null; }
    catch { return null; }
  });

  const read = useCallback(async <T,>(path: string) => {
    try { return await call<T>(path); }
    catch (error) {
      if (error instanceof WorkspaceError && error.status === 401) onExpire();
      throw error;
    }
  }, [onExpire]);

  function save(command: Command | null) {
    setPending(command);
    try {
      if (command) sessionStorage.setItem(storageKey, JSON.stringify(command));
      else sessionStorage.removeItem(storageKey);
    } catch { /* The active tab still retains the command when storage is unavailable. */ }
  }

  async function submit(action: Omit<Command, "key"> | Command) {
    if (inFlight.current || (pending && (!("key" in action) || action.key !== pending.key))) return;
    inFlight.current = true;
    setBusy(true);
    setNotice(undefined);
    const command = "key" in action ? action : { ...action, key: crypto.randomUUID() };
    save(command);
    try {
      const result = await call<{ requestId?: string; status: string }>(command.path, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Workspace-CSRF": session.csrf, "Idempotency-Key": command.key },
        body: command.body ? JSON.stringify(command.body) : undefined,
      });
      setNotice({ text: command.label + " confirmed.", error: false, reference: result.requestId });
      save(null);
      setRevision((value) => value + 1);
    } catch (error) {
      const details = error instanceof WorkspaceError ? error : null;
      if (details?.status === 401) onExpire();
      else if (details && [400, 403, 404, 409].includes(details.status) && details.code !== "INVALID_CSRF") {
        save(null);
        setRevision((value) => value + 1);
      }
      setNotice({ text: error instanceof Error ? error.message : "The result is uncertain. Retry the saved action.", error: true,
        reference: command.path === "/requests" ? details?.reference : undefined });
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  async function signOut() {
    try {
      await call("/session", { method: "DELETE", headers: { "X-Workspace-CSRF": session.csrf } });
      onSignOut();
    } catch (error) {
      setNotice({ error: true, text: error instanceof Error ? error.message : "Sign-out failed." });
    }
  }

  const sections: Section[] = session.role === "SUPERVISOR"
    ? ["Inventory", "Requests", "Receiving", "Movements", "Suppliers"] : ["Inventory", "Requests", "Suppliers"];
  const selected = target ? "Requests" : section;
  function navigate(next: Section, request = "") {
    setSection(next);
    if (request) router.replace(`/workspace?request=${encodeURIComponent(request)}`);
    else if (target) router.replace("/workspace");
  }

  return <div className="space-y-6">
    <WorkspaceHeading title="Branch workspace" description={`${session.name} · ${roles[session.role]} · ${session.branch}`}
      action={<button className={secondaryAction} disabled={busy} onClick={signOut}>Sign out</button>} />
    <nav aria-label="Workspace sections" className="flex flex-wrap gap-2 border-b border-slate-200 pb-3">
      {sections.map((item) => <button key={item} type="button" aria-current={item === selected ? "page" : undefined}
        className={item === selected ? primaryAction : secondaryAction} onClick={() => navigate(item)}>{item}</button>)}
    </nav>
    {notice && <div role={notice.error ? "alert" : "status"} className={`rounded-lg border p-3 text-sm ${notice.error ? "border-amber-200 bg-amber-50 text-amber-950" : "border-teal-200 bg-teal-50 text-teal-950"}`}>
      {notice.text}{notice.reference && <> <button className="font-semibold underline" onClick={() => navigate("Requests", notice.reference)}>Open request</button></>}
    </div>}
    {pending && !busy && <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-950">
      <p>Awaiting confirmation for {pending.label.toLowerCase()}. Retry with the saved command ID.</p>
      <button className={secondaryAction} onClick={() => submit(pending)}>Retry saved action</button>
    </div>}
    {selected === "Inventory" && <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_20rem]">
      <Inventory read={read} revision={revision} />
      {session.role === "OPERATOR" && <StockForm key={revision} onSubmit={submit} disabled={busy || !!pending} />}
    </div>}
    {selected === "Requests" && <Requests key={target} target={target} open={(id) => navigate("Requests", id)} role={session.role}
      read={read} revision={revision} onSubmit={submit} disabled={busy || !!pending} />}
    {selected === "Receiving" && <StockForm key={revision} receive onSubmit={submit} disabled={busy || !!pending} />}
    {selected === "Movements" && <Paged<Movement> title="Movements ledger" path="/movements" read={read} revision={revision}>
      {(items) => <Table headings={["SKU", "Movement", "Units", "Recorded", "Reference"]} rows={items.map((row) => ({
        key: row.id, cells: [row.sku, row.reason === "RECEIVE" ? "Received" : "Dispatched", row.quantityDelta > 0 ? "+" + row.quantityDelta : row.quantityDelta,
          formatFullDateTime(row.occurredAt), row.note ? `${row.recordedBy} · ${row.note}` : row.branchId],
      }))} />}
    </Paged>}
    {selected === "Suppliers" && <Supplier read={read} />}
  </div>;
}

type Read = <T>(path: string) => Promise<T>;
type Submit = (action: Omit<Command, "key">) => Promise<void>;

function StockForm({ receive = false, onSubmit, disabled }: { receive?: boolean; onSubmit: Submit; disabled: boolean }) {
  const title = receive ? "Receive stock" : "Reserve stock";
  function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const fields = new FormData(event.currentTarget);
    const body: Command["body"] = { sku: String(fields.get("sku")).trim(), quantity: Number(fields.get("quantity")) };
    if (receive) body.note = String(fields.get("note")).trim();
    void onSubmit({ path: receive ? "/receiving" : "/requests", body, label: receive ? "Receiving" : "Reservation" });
  }
  return <form className={`${panel} max-w-xl self-start`} onSubmit={send}>
    <h2 className="text-lg font-semibold text-slate-950">{title}</h2>
    <Label name="SKU"><input name="sku" className={field} pattern={String.raw`[A-Za-z0-9_\-]{1,64}`} maxLength={64} required /></Label>
    <Label name="Quantity"><input name="quantity" className={field} type="number" min="1" max="2147483647" step="1" required /></Label>
    {receive && <Label name="Receiving reference"><input name="note" className={field} maxLength={160} required /></Label>}
    <button className={primaryAction} type="submit" disabled={disabled}>{title}</button>
  </form>;
}

function Inventory({ read, revision }: { read: Read; revision: number }) {
  const [prefix, setPrefix] = useState("");
  return <div className="min-w-0 space-y-3">
    <form className="flex items-end gap-2" onSubmit={(event) => {
      event.preventDefault(); setPrefix(String(new FormData(event.currentTarget).get("search")).trim());
    }}>
      <Label name="SKU prefix"><input className={field} name="search" type="search" maxLength={64} /></Label>
      <button className={secondaryAction}>Search inventory</button>
    </form>
    <Paged<Product> key={prefix} title="Warehouse inventory" path={`/products?search=${encodeURIComponent(prefix)}`} read={read} revision={revision}>
      {(items) => <Table headings={["Product", "On hand", "Reserved", "Available"]} rows={items.map((row) => ({
        key: row.sku, cells: [<div key={row.sku}><strong>{row.name}</strong><div className="font-mono text-xs text-slate-600">{row.sku}</div></div>,
          number.format(row.onHand), number.format(row.reserved), number.format(row.available)],
      }))} />}
    </Paged>
  </div>;
}

function Requests({ read, revision, target, role, disabled, open, onSubmit }: {
  read: Read; revision: number; target: string; role: Role; disabled: boolean; open: (id: string) => void; onSubmit: Submit;
}) {
  const [status, setStatus] = useState("");
  return <div className="space-y-4">
    <div className="flex flex-wrap items-end gap-3">
      <form className="flex flex-wrap items-end gap-2" onSubmit={(event) => {
        event.preventDefault(); open(String(new FormData(event.currentTarget).get("request")).trim());
      }}>
        <Label name="Request ID"><input className={field} name="request" defaultValue={target} minLength={36} maxLength={36} required /></Label>
        <button className={secondaryAction}>Find request</button>
      </form>
      {target ? <button className={secondaryAction} onClick={() => open("")}>Back to all requests</button> :
        <Label name="Status"><select className={field} value={status} onChange={(event) => setStatus(event.target.value)}>
          <option value="">All statuses</option>{statuses.map((item) => <option key={item}>{item}</option>)}
        </select></Label>}
    </div>
    {target ? <RequestDetail key={target} {...{ target, read, revision, role, disabled, onSubmit }} /> :
      <Paged<Request> key={status} title="Stock requests" path={`/requests?status=${status}`} read={read} revision={revision}>
        {(items) => <Table headings={["Request", "Branch", "Quantity", "Status", "Created"]} rows={items.map((row) => ({
          key: row.requestId, cells: [<button key={row.requestId} className="text-left font-semibold text-teal-800 underline"
            aria-label={`Open request ${row.requestId}`} onClick={() => open(row.requestId)}>{row.sku} · {row.requestId.slice(0, 8)}</button>,
          row.branchId, row.quantity, <StatusBadge key={row.requestId} status={row.status} />, formatFullDateTime(row.createdAt)],
        }))} />}
      </Paged>}
  </div>;
}

function StatusBadge({ status }: { status: Status }) {
  return status === "CANCELLED" ? <span className="rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-700">Cancelled</span> : <RequestBadge status={status} />;
}

function RequestDetail({ target, read, revision, role, disabled, onSubmit }: {
  target: string; read: Read; revision: number; role: Role; disabled: boolean; onSubmit: Submit;
}) {
  const load = useCallback(() => read<Request>(`/requests/${encodeURIComponent(target)}`), [read, target]);
  const query = useWarehouseQuery(load);
  const refresh = query.refresh;
  useEffect(() => { if (revision) void refresh(); }, [revision, refresh]);
  const [confirm, setConfirm] = useState<string>();
  const row = query.data;
  const actions = row?.status === "ACCEPTED" ? (role === "OPERATOR" ? ["cancel"] : ["pick", "cancel"]) :
    row?.status === "PICKED" && role !== "OPERATOR" ? ["dispatch"] : [];
  return <section className={panel}>
    <div className="flex flex-wrap items-start justify-between gap-3"><h2 className="text-lg font-semibold">Request details</h2>
      <button className={secondaryAction} onClick={refresh} disabled={query.loading}>Refresh</button></div>
    <Freshness {...query} />
    {query.error && <Warning>{query.error}</Warning>}
    {row && <div className="space-y-3">
      <p className="break-all font-mono text-sm">{row.requestId}</p>
      <p><strong>{row.sku}</strong> · {row.quantity} units · {row.branchId} <StatusBadge status={row.status} /></p>
      <p className="text-sm text-slate-600">{formatFullDateTime(row.createdAt)}</p>
      {row.decisionReason && <p className="text-sm">{row.decisionReason}</p>}
      <div className="flex flex-wrap gap-2">
        {actions.map((action) => <button key={action} className={secondaryAction} disabled={disabled || query.loading || !!query.error}
          onClick={() => setConfirm(action)}>{action === "cancel" ? "Cancel reservation" : action === "pick" ? "Mark picked" : "Dispatch stock"}</button>)}
      </div>
      {confirm && actions.includes(confirm) && <div className="flex flex-wrap items-center gap-3 rounded-lg bg-slate-50 p-3">
        <span className="text-sm">Confirm {confirm} for {row.quantity} units of {row.sku}?</span>
        <button className={primaryAction} disabled={disabled || query.loading || !!query.error} onClick={() => {
          const action = confirm; setConfirm(undefined);
          void onSubmit({ path: `/requests/${row.requestId}/${action}`, label: { cancel: "Cancellation", pick: "Pick", dispatch: "Dispatch" }[action] ?? action });
        }}>Confirm {confirm}</button>
        <button className={secondaryAction} onClick={() => setConfirm(undefined)}>Back</button>
      </div>}
    </div>}
  </section>;
}

function Paged<T>({ title, path, read, revision, children }: {
  title: string; path: string; read: Read; revision: number; children: (items: T[]) => ReactNode;
}) {
  const [history, setHistory] = useState<string[]>([""]);
  const cursor = history.at(-1) ?? "";
  const load = useCallback(() => {
    const separator = path.includes("?") ? "&" : "?";
    return read<Page<T>>(`${path}${separator}cursor=${encodeURIComponent(cursor)}`).then((page) => ({ cursor, page }));
  }, [read, path, cursor]);
  const query = useWarehouseQuery(load);
  const refresh = query.refresh;
  useEffect(() => { if (revision) void refresh(); }, [revision, refresh]);
  const page = query.data?.cursor === cursor ? query.data.page : undefined;
  return <section className={panel}>
    <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="text-lg font-semibold">{title}</h2>
      <button className={secondaryAction} disabled={query.loading} onClick={refresh}>Refresh</button></div>
    <Freshness {...query} />
    {query.error && <Warning>{query.error}</Warning>}
    {page && (page.items.length ? children(page.items) : <p className="text-sm text-slate-600">No matching records.</p>)}
    <div className="flex flex-wrap items-center gap-3">
      <button className={secondaryAction} disabled={history.length === 1 || query.loading} onClick={() => setHistory(history.slice(0, -1))}>Previous</button>
      <span className="text-sm text-slate-600">Page {history.length}</span>
      <button className={secondaryAction} disabled={!page?.nextCursor || query.loading || !!query.error}
        onClick={() => { if (page?.nextCursor) setHistory([...history, page.nextCursor]); }}>Next</button>
    </div>
  </section>;
}

function Table({ headings, rows }: { headings: string[]; rows: { key: string; cells: ReactNode[] }[] }) {
  return <div className="overflow-x-auto"><table className="w-full border-collapse text-left text-sm">
    <thead className="bg-slate-50 text-slate-600"><tr>{headings.map((heading) => <th key={heading} scope="col" className="px-3 py-2 font-semibold">{heading}</th>)}</tr></thead>
    <tbody>{rows.map((row) => <tr key={row.key} className="border-b border-slate-100">{row.cells.map((cell, i) => <td key={headings[i]} className="px-3 py-3">{cell}</td>)}</tr>)}</tbody>
  </table></div>;
}

function Supplier({ read }: { read: Read }) {
  const [sku, setSku] = useState("");
  return <div className="space-y-4">
    <form className={`${panel} max-w-xl`} onSubmit={(event) => {
      event.preventDefault(); setSku(String(new FormData(event.currentTarget).get("sku")).trim());
    }}>
      <h2 className="text-lg font-semibold">Supplier availability</h2>
      <Label name="SKU"><input className={field} name="sku" maxLength={64} required /></Label>
      <button className={secondaryAction}>Check supplier</button>
    </form>
    {sku && <SupplierResult key={sku} sku={sku} read={read} />}
  </div>;
}

function SupplierResult({ sku, read }: { sku: string; read: Read }) {
  const load = useCallback(() => read<{ sku: string; reportedQuantity: number; estimatedRestockDays?: number }>(`/suppliers/availability?sku=${encodeURIComponent(sku)}`), [read, sku]);
  const { data, error, loading, refresh } = useWarehouseQuery(load);
  return <div className={panel}>
    <button className={secondaryAction} disabled={loading} onClick={refresh}>Refresh supplier</button>
    {loading ? <p role="status">Checking supplier…</p> : error ? <Warning>{error}</Warning> :
      data && <p><strong>{data.sku}</strong> · {data.reportedQuantity} units reported by supplier{data.estimatedRestockDays !== undefined && ` · ETA ${data.estimatedRestockDays} days`}</p>}
  </div>;
}
