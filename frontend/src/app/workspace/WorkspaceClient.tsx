"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { Field, field, Warning, WorkspaceHeading, primaryAction, secondaryAction, textAction } from "@/components/Workspace";
import {
  commandResult, definitiveCommandFailure, workspaceCall, WorkspaceError,
  type Command, type Role, type Session,
} from "@/lib/api/workspace";
import { newRequestId } from "@/lib/reservationAttempt";
import { readTabValue, useTabValue, writeTabValue } from "@/lib/useTabValue";
import { Inventory, Ledger, Requests, Supplier, type View } from "./WorkspaceViews";
import { StockForm, emptyDraft, type Draft } from "./StockForm";

import { FollowUp, Overview, followHref } from "./OperatorDashboard";

const roles: Record<Role, string> = { OPERATOR: "Branch operator", PICKER: "Warehouse picker", SUPERVISOR: "Warehouse supervisor" };
const sections = [
  ["overview", "Overview"], ["queue", "Fulfillment queue"], ["inventory", "Inventory"], ["requests", "Requests"],
  ["receiving", "Receiving"], ["movements", "Movements"], ["suppliers", "Suppliers"],
] as const;
type Section = typeof sections[number][0];

export function WorkspaceClient() {
  const [session, setSession] = useState<Session | null>();
  const [problem, setProblem] = useState("");
  const [disabled, setDisabled] = useState(false);
  const [loading, setLoading] = useState(false);
  const [visible, setVisible] = useState(false);
  const [account, setAccount] = useState("");
  const problemRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    workspaceCall<Session>("/session", { signal: controller.signal }).then(setSession).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      setSession(null);
      if (error instanceof WorkspaceError && error.status === 404) setDisabled(true);
      else if (!(error instanceof WorkspaceError && error.status === 401)) setProblem("Could not connect to the workspace. Reload to check the gateway.");
    });
    return () => controller.abort();
  }, []);
  useEffect(() => { if (problem) problemRef.current?.focus(); }, [problem]);

  async function signIn(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const password = form.elements.namedItem("password") as HTMLInputElement;
    const credentials = JSON.stringify({ name: account, password: password.value });
    password.value = "";
    setVisible(false);
    setLoading(true);
    setProblem("");
    try {
      setSession(await workspaceCall<Session>("/session", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Workspace-Client": "browser" },
        body: credentials,
      }));
    } catch (error) {
      setProblem(error instanceof Error ? error.message : "Could not sign in.");
    } finally {
      setLoading(false);
    }
  }

  const expire = useCallback((name: string) => {
    setAccount(name);
    setSession(null);
    setProblem("Your session ended. Sign in again to continue.");
  }, []);

  if (session) return <Desk key={session.name + ":" + session.branch} session={session} onExpire={expire} onSignOut={() => {
    window.history.replaceState(null, "", "/workspace");
    setAccount(""); setProblem(""); setSession(null);
  }} />;
  return <div className="mx-auto max-w-lg space-y-6">
    <WorkspaceHeading title="Branch workspace" description="Sign in to manage stock requests and warehouse operations for your assigned role." />
    {session === undefined ? <p role="status">Checking your session…</p> : disabled ?
      <div className="space-y-4 rounded-xl border border-slate-200 bg-white p-5"><p>The branch workspace is disabled on this gateway.</p><Link className={secondaryAction} href="/products">Open demo inventory</Link></div> :
      <form className="space-y-4 rounded-xl border border-slate-200 bg-white p-5" onSubmit={signIn}>
        {problem && <div ref={problemRef} tabIndex={-1}><Warning>{problem}</Warning></div>}
        <Field name="Account"><input className={field} value={account} onChange={(event) => setAccount(event.target.value)} required name="name" maxLength={40} autoComplete="username" /></Field>
        <div className="flex flex-wrap items-end gap-2">
          <Field name="Password" className="flex-1"><input className={field} required type={visible ? "text" : "password"} name="password" maxLength={128} autoComplete="current-password" /></Field>
          <button type="button" className={secondaryAction} onClick={() => setVisible(!visible)}>{visible ? "Hide password" : "Show password"}</button>
        </div>
        <button type="submit" className={primaryAction} disabled={loading}>{loading ? "Signing in…" : "Sign in"}</button>
      </form>}
  </div>;
}

export type Submit = (action: Omit<Command, "key">) => Promise<void>;

function Desk({ session, onSignOut, onExpire }: { session: Session; onSignOut: () => void; onExpire: (name: string) => void }) {
  const params = useSearchParams();
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState<{ text: string; error: boolean; reference?: string; followUp?: string; shortage?: Command["body"] }>();
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const reserveFocus = useRef<HTMLInputElement>(null);
  const scope = `thogamura.workspace:${session.name}:${session.branch}`;
  const [pending, save] = useTabValue<Command | null>(`thogamura.workspace.pending:${session.name}:${session.branch}`, null);
  const [reserve, saveReserve] = useTabValue<Draft>(`${scope}:reserve`, emptyDraft);
  const [receiving, saveReceiving] = useTabValue<Draft>(`${scope}:receive`, emptyDraft);
  const drafts = useRef({ reserve, receiving });
  function saveDraft(kind: keyof typeof drafts.current, value: Draft) {
    drafts.current[kind] = value;
    (kind === "reserve" ? saveReserve : saveReceiving)(value);
  }
  const read = useCallback(async <T,>(path: string) => {
    try { return await workspaceCall<T>(path); }
    catch (error) {
      if (error instanceof WorkspaceError && error.status === 401) onExpire(session.name);
      throw error;
    }
  }, [onExpire, session.name]);

  const allowed = sections.filter(([id]) => id === "queue" ? session.role !== "OPERATOR" :
    id === "receiving" || id === "movements" ? session.role === "SUPERVISOR" : true);
  const sectionParam = params.get("section");
  const section = allowed.find(([id]) => id === sectionParam)?.[0] ?? (params.has("request") ? "requests" : "overview");
  const requestId = params.get("request") ?? "";
  const [origin, setOrigin] = useState<{ section: Section; id: string }>();
  const restoreFocus = useRef(false);
  const requestSection = section === "queue" ? "queue" : "requests";
  const returnSection = origin?.section ?? requestSection;
  useEffect(() => {
    if (requestId || !restoreFocus.current || !origin) return;
    const selector = '[data-request-link="' + CSS.escape(origin.id) + '"]';
    const focus = () => {
      const link = document.querySelector<HTMLElement>(selector);
      if (!link) return false;
      link.focus({ preventScroll: true });
      restoreFocus.current = false;
      return true;
    };
    if (focus()) return;
    const observer = new MutationObserver(() => { if (focus()) observer.disconnect(); });
    observer.observe(document.body, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, [requestId, origin]);
  const selectionKey = `${scope}:filters:${section}`;
  const current = params.size ? params : new URLSearchParams(readTabValue(selectionKey, ""));
  const filterKey = current.toString();
  useEffect(() => {
    const filters = new URLSearchParams(filterKey);
    filters.delete("request");
    if (filters.size) writeTabValue(selectionKey, filters.toString());
  }, [filterKey, selectionKey]);

  function sectionHref(next: Section, overrides: Record<string, string | null> = {}) {
    const nextParams = next === section ? new URLSearchParams(current) :
      new URLSearchParams(readTabValue(`${scope}:filters:${next}`, ""));
    nextParams.set("section", next);
    nextParams.delete("request");
    for (const [key, value] of Object.entries(overrides)) {
      if (value === null || value === "") nextParams.delete(key);
      else nextParams.set(key, value);
    }
    return `/workspace?${nextParams}`;
  }

  function rememberScroll() {
    if (requestId) return;
    const key = scrollKey();
    writeTabValue(key, window.scrollY);
  }
  function scrollKey() {
    return `${scope}:scroll:${section}:${current.get("q") ?? ""}:${current.get("status") ?? ""}:${current.get("filter") ?? ""}:${current.get("from") ?? ""}:${current.get("through") ?? ""}`;
  }

  function navigate(next: Section, overrides: Record<string, string | null> = {}, scroll = true) {
    rememberScroll();
    const href = sectionHref(next, overrides);
    const stored = new URLSearchParams(href.split("?")[1]);
    stored.delete("request");
    writeTabValue(next === section ? selectionKey : `${scope}:filters:${next}`, stored.toString());
    if (href === window.location.pathname + window.location.search) return;
    window.history.pushState(null, "", href);
    if (scroll) window.scrollTo({ top: 0, behavior: "instant" });
  }

  const view: View = {
    params: current, scope, read, revision, role: session.role, scrollKey: scrollKey(),
    update: (values) => navigate(section, values),
    open: (id) => {
      if (id) setOrigin({ section, id });
      else restoreFocus.current = true;
      navigate(id ? requestSection : returnSection, { request: id });
    },
    returnLabel: returnSection === "queue" ? "fulfillment queue" : returnSection,
    requestHref: (id) => sectionHref(id ? requestSection : returnSection, { request: id }),
  };

  async function submit(action: Omit<Command, "key"> | Command) {
    if (inFlight.current || (pending && (!("key" in action) || action.key !== pending.key))) return;
    const command = "key" in action ? action : { ...action, key: newRequestId() };
    inFlight.current = true;
    setBusy(true);
    setNotice(undefined);
    save(command);
    try {
      const result = commandResult(command, await workspaceCall<unknown>(command.path, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Workspace-CSRF": session.csrf, "Idempotency-Key": command.key },
        body: JSON.stringify(command.assignment ?? command.body),
      }));
      setNotice({ text: command.label + " confirmed.", error: false, reference: result.reference });
      if (command.path === "/requests" || command.path === "/receiving") {
        const kind = command.path === "/requests" ? "reserve" : "receiving";
        const saved = drafts.current[kind];
        if (saved.sku.trim() === command.body?.sku && saved.quantity.trim() === String(command.body.quantity) &&
          saved.note.trim() === (command.body.note ?? "")) {
          saveDraft(kind, emptyDraft);
        }
      }
      if (command.path.startsWith("/supplier-follow-ups")) navigate("suppliers", { sku: null, followUp: command.path.split("/")[2] ?? command.key });
      save(null);
      setRevision((value) => value + 1);
    } catch (error) {
      const details = error instanceof WorkspaceError ? error : null;
      if (details?.status === 401 || details?.code === "INVALID_CSRF") onExpire(session.name);
      const definitive = details && definitiveCommandFailure(details);
      if (definitive) {
        save(null);
        setRevision((value) => value + 1);
      }
      const shortage = details?.code === "INSUFFICIENT_STOCK" ? command.body : undefined;
      setNotice({ text: `${error instanceof Error ? error.message : "The result is uncertain."}${definitive ? "" : " Retry the saved action to confirm its outcome."}`,
        error: true, followUp: details?.code === "FOLLOWUP_OPEN" ? details.reference : undefined, shortage, reference: shortage && details?.reference === command.key ? command.key : undefined });
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  async function signOut() {
    try {
      await workspaceCall("/session", { method: "DELETE", headers: { "X-Workspace-CSRF": session.csrf } });
      onSignOut();
    } catch (error) {
      if (error instanceof WorkspaceError && error.code === "INVALID_CSRF") onExpire(session.name);
      setNotice({ error: true, text: error instanceof Error ? error.message : "Sign-out failed." });
    }
  }

  function selectSku(sku: string) {
    saveDraft("reserve", { ...reserve, sku });
    requestAnimationFrame(() => reserveFocus.current?.focus());
  }

  return <div className="space-y-5">
    <WorkspaceHeading title="Branch workspace" description={roles[session.role]} />
    {section === "overview" && <Overview view={view} />}
    <nav aria-label="Workspace sections" className="flex flex-wrap gap-2 border-b border-slate-200 pb-3">
      {allowed.map(([id, label]) => <Link key={id} href={sectionHref(id)} aria-current={id === section ? "page" : undefined}
        className={`${secondaryAction} ${id === section ? "border-teal-600 bg-teal-50 text-teal-950" : ""}`}
        onNavigate={(event) => { event.preventDefault(); navigate(id); }}>{label}</Link>)}
    </nav>
    {notice && <div role={notice.error ? "alert" : "status"} className={`space-y-2 rounded-lg border p-3 text-sm ${notice.error ? "border-amber-300 bg-amber-50 text-amber-950" : "border-teal-300 bg-teal-50 text-teal-950"}`}>
      <p>{notice.text}</p>
      <div className="flex flex-wrap gap-x-4">
        {notice.followUp && <Link className={textAction} href={followHref(notice.followUp)}>Open follow-up</Link>}
        {notice.reference && <Link data-request-link={notice.reference} className={textAction} href={sectionHref(section === "queue" ? "queue" : "requests", { request: notice.reference })}
          onNavigate={(event) => { event.preventDefault(); view.open(notice.reference!); }}>Open request</Link>}
        {notice.shortage && <>
          <button type="button" className={textAction} onClick={() => { navigate("inventory"); requestAnimationFrame(() => reserveFocus.current?.focus()); }}>Adjust quantity</button>
          <Link className={textAction} href={sectionHref("suppliers", { sku: notice.shortage.sku })}
            onNavigate={(event) => { event.preventDefault(); navigate("suppliers", { sku: notice.shortage!.sku }); }}>Check supplier for {notice.shortage.sku}</Link>
        </>}
      </div>
    </div>}
    {pending && <div role="status" className="space-y-2 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950">
      <p className="font-semibold">{busy ? "Confirming" : "Awaiting confirmation for"} {pending.label.toLowerCase()}</p>
      {(pending.body ?? pending.item) && <p>{(pending.body ?? pending.item)?.quantity} units · {(pending.body ?? pending.item)?.sku}</p>}
      {pending.path.startsWith("/requests/") && <p className="break-all">Request {pending.path.split("/")[2]}</p>}
      {pending.path === "/requests" && <p className="break-all">Request {pending.key}</p>}
      <p className="break-all">Command {pending.key}</p>
      <p>Stock actions are paused until this command is confirmed. You can keep browsing.</p>
      {!busy && <button className={secondaryAction} onClick={() => void submit(pending)}>Retry saved action</button>}
    </div>}
    {section === "inventory" && <div className={`grid items-start gap-5 ${session.role === "OPERATOR" ? "lg:grid-cols-[20rem_minmax(0,1fr)]" : ""}`}>
      {session.role === "OPERATOR" && <StockForm draft={reserve} saveDraft={(draft) => saveDraft("reserve", draft)} view={view} disabled={busy || !!pending}
        onSubmit={submit} quantityRef={reserveFocus} onSupplier={(sku) => navigate("suppliers", { sku })} />}
      <Inventory view={view} onReserve={session.role === "OPERATOR" ? selectSku : undefined}
        supplierHref={(sku) => sectionHref("suppliers", { sku })} onSupplier={(sku) => navigate("suppliers", { sku })} />
    </div>}
    {(section === "requests" || section === "queue") && <Requests key={section} target={requestId} queue={section === "queue"} view={view}
      onSubmit={submit} disabled={busy || !!pending} />}
    {section === "receiving" && <StockForm receive draft={receiving} saveDraft={(draft) => saveDraft("receiving", draft)} view={view}
      onSubmit={submit} disabled={busy || !!pending} />}
    {section === "movements" && <Ledger view={view} />}
    {section === "suppliers" && current.get("followUp") && !current.get("sku") ?
      <FollowUp key={current.get("followUp")} id={current.get("followUp")!} view={view} submit={submit} disabled={busy || !!pending} /> :
      section === "suppliers" && <Supplier view={view} submit={submit} disabled={busy || !!pending} returnToDraft={session.role === "OPERATOR" && !!reserve.sku ? () => navigate("inventory") : undefined} />}
    <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 pt-3 text-sm text-slate-700">
      <p className="break-words">{session.name} · {session.branch}</p>
      <button className={secondaryAction} disabled={busy} onClick={signOut}>Sign out</button>
    </div>
  </div>;
}
