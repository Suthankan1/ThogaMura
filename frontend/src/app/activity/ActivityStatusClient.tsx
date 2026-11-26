"use client";

import { useState } from "react";
import Link from "next/link";
import { workspaceCall, workspacePage, WORKSPACE_ENABLED, WorkspaceError } from "@/lib/api/workspace";
import { pingGateway, type PingResult } from "@/lib/api/network";
import type { Product } from "@/lib/types/inventory";
import { fetchProducts } from "@/lib/api/inventory";
import { useBrandIntro } from "@/components/BrandIntro";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { formatFullDateTime } from "@/lib/formatters";
import { Freshness, primaryAction, secondaryAction, surface, WorkspaceHeading } from "@/components/Workspace";

async function readInventory() {
  if (!WORKSPACE_ENABLED) return { signIn: false, items: await fetchProducts() };
  try {
    const page = await workspacePage<Product>(workspaceCall, "/products?limit=1");
    return { signIn: false, items: page.items };
  } catch (error) {
    if (error instanceof WorkspaceError && error.status === 401) return { signIn: true };
    throw error;
  }
}

const demonstrations = [
  ["M1 · TCP commands", "Send a framed request to the warehouse JVM. Replay a lost result using the original request reference and verify stock was reserved once."],
  ["M2 · Supplier HTTP", "Use the independent supplier simulator to observe positive, zero, unknown and unavailable outcomes. An inquiry leaves warehouse stock intact."],
  ["M3 · Java NIO", "Observe a version hint below, disconnect a branch subscriber and confirm it refreshes warehouse records after reconnecting."],
  ["M4 · Concurrent reservations", "Try two requests for eight units against ten available. Compare the two outcomes and verify that dispatch records one movement."],
  ["M5 · JSSE TLS", "Check that the branch can reach the warehouse with a trusted certificate and that an incorrect certificate identity fails in the LAN rehearsal."],
];

export function ActivityStatusClient() {
  const startIntro = useBrandIntro();
  const { sseStatus, upstreamNioStatus, networkDetails, eventsLog, warningMessage } = useStockEvents();
  const catalog = useWarehouseQuery(readInventory);
  const [pingResult, setPingResult] = useState<PingResult | null>(null);
  const [pingError, setPingError] = useState<string | null>(null);
  const [pinging, setPinging] = useState(false);

  async function runPingTest() {
    if (pinging) return;
    setPinging(true);
    setPingError(null);
    try {
      const res = await pingGateway();
      setPingResult(res);
    } catch (error) {
      setPingError(error instanceof Error ? error.message : "Warehouse PING failed.");
    } finally {
      setPinging(false);
    }
  }

  const feed = networkDetails?.nioFeed;
  const tls = networkDetails?.tlsCommand?.status;
  const clockSkewMs = networkDetails?.tlsCommand?.clockSkewMs;
  const transport = tls === "OPERATIONAL" ? "Verified TLS response"
    : tls === "DISABLED" ? "Local development · TLS disabled"
    : tls === "MISCONFIGURED" ? "TLS misconfigured"
    : tls === "UNREACHABLE" ? "TLS probe failed" : "Probe unavailable";
  const rows = [
    { name: "Browser event stream", value: sseStatus === "CONNECTED" ? "Connected" : sseStatus === "CONNECTING" ? "Connecting" : "Interrupted" },
    { name: "Warehouse NIO feed", value: upstreamNioStatus === "CONNECTED"
      ? "Connected · version hints only" : feed?.enabled === false ? "Disabled" : upstreamNioStatus.toLowerCase() },
    { name: "Warehouse command transport", value: transport },
    { name: "Warehouse inventory query", value: catalog.error ? "Read failed" : catalog.data?.signIn ? "Sign in to check inventory" : catalog.latencyMs !== null ? `Catalog read confirmed · ${catalog.latencyMs} ms round trip` : "Awaiting first read" },
  ];

  return (
    <div className="space-y-6">
      <WorkspaceHeading title="Network diagnostics" description="Check each connection in the stock workflow and inspect the change hints received by this browser."
        action={
          <div className="flex flex-wrap gap-2">
            <button type="button" className={primaryAction} disabled={pinging} onClick={() => void runPingTest()}>
              {pinging ? "Pinging warehouse…" : "Test TLS PING"}
            </button>
            <button type="button" className={secondaryAction} onClick={() => void catalog.refresh()}>Check inventory read</button>
            <button type="button" className={secondaryAction} onClick={startIntro}>Replay intro</button>
          </div>
        } />

      {warningMessage && <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">{warningMessage}</p>}
      {typeof clockSkewMs === "number" && clockSkewMs > 3000 && (
        <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">
          Clock skew detected between gateway and warehouse server ({clockSkewMs} ms). Timestamp relative ages may drift slightly.
        </p>
      )}
      {pingError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm text-rose-900">{pingError}</p>}
      {pingResult && (
        <div role="status" className="rounded-lg border border-teal-200 bg-teal-50 p-4 text-sm text-teal-950 flex flex-wrap items-center justify-between gap-2">
          <span>
            <strong>{pingResult.status}</strong> in <strong className="tabular-nums">{pingResult.latencyMs} ms</strong>
            {pingResult.serverTime && <> · Warehouse time {formatFullDateTime(pingResult.serverTime)}</>}
          </span>
          {pingResult.requestId && <span className="font-mono text-xs text-teal-800">Ref {pingResult.requestId}</span>}
        </div>
      )}

      <section className={surface} aria-labelledby="connections">
        <h2 id="connections" className="border-b border-slate-200 p-4 text-lg font-semibold sm:p-5">Connections</h2>
        <dl className="divide-y divide-slate-100">
          {rows.map(({ name, value }) => (
            <div key={name} className="grid gap-1 p-4 text-sm sm:grid-cols-[1fr_1fr] sm:p-5">
              <dt className="font-semibold">{name}</dt><dd className="text-slate-700 first-letter:uppercase">{value}
                {name === "Warehouse inventory query" && catalog.data?.signIn && <Link href="/workspace" className={`${secondaryAction} ml-2`}>Sign in</Link>}
              </dd>
            </div>
          ))}
          <div className="grid gap-1 p-4 text-sm sm:grid-cols-[1fr_1fr] sm:p-5">
            <dt className="font-semibold">Inventory check time</dt><dd>{catalog.data?.signIn ? "Sign-in required" : <Freshness {...catalog} />}</dd>
          </div>
          <div className="grid gap-1 p-4 text-sm sm:grid-cols-[1fr_1fr] sm:p-5">
            <dt className="font-semibold">Supplier service</dt>
            <dd><Link href={WORKSPACE_ENABLED ? "/workspace?section=suppliers" : "/suppliers"} className={secondaryAction}>Run a live inquiry</Link></dd>
          </div>
        </dl>
      </section>

      <section className={surface} aria-labelledby="events-title">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 p-4 sm:p-5">
          <h2 id="events-title" className="text-lg font-semibold">Recent change hints</h2>
          {feed && <p className="text-xs text-slate-600">{feed.notificationCount} received by gateway · {feed.reconnectCount} reconnects</p>}
        </div>
        {eventsLog.length ? (
          <ol className="divide-y divide-slate-100">
            {eventsLog.map((event, index) => (
              <li key={`${event.version}:${event.receivedAt.getTime()}:${index}`} className="flex flex-wrap justify-between gap-2 p-4 text-sm sm:p-5">
                <span className="font-semibold tabular-nums">Version {event.version}</span>
                <time dateTime={event.receivedAt.toISOString()} className="text-slate-600">{formatFullDateTime(event.receivedAt)}</time>
              </li>
            ))}
          </ol>
        ) : <p className="p-5 text-sm text-slate-600">This browser has yet to receive a warehouse change hint.</p>}
      </section>

      <section className={surface} aria-labelledby="demonstrations">
        <h2 id="demonstrations" className="border-b border-slate-200 p-4 text-lg font-semibold sm:p-5">Coursework demonstrations</h2>
        <div className="divide-y divide-slate-100">
          {demonstrations.map(([title, description]) => (
            <details key={title} className="p-4 sm:p-5">
              <summary className="cursor-pointer text-sm font-semibold text-teal-900">{title}</summary>
              <p className="mt-2 max-w-prose text-sm text-slate-700">{description}</p>
            </details>
          ))}
        </div>
      </section>
    </div>
  );
}
