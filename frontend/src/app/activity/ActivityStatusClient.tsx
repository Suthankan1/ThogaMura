"use client";

import Link from "next/link";
import { fetchProducts } from "@/lib/api/inventory";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { useWarehouseQuery } from "@/lib/useWarehouseQuery";
import { formatFullDateTime } from "@/lib/formatters";
import { Freshness, secondaryAction, surface, WorkspaceHeading } from "@/components/Workspace";

const demonstrations = [
  ["M1 · TCP commands", "Send a framed request to the warehouse JVM. Replay a lost result using the original request reference and verify stock was reserved once."],
  ["M2 · Supplier HTTP", "Use the independent supplier simulator to observe positive, zero, unknown and unavailable outcomes. An inquiry leaves warehouse stock intact."],
  ["M3 · Java NIO", "Observe a version hint below, disconnect a branch subscriber and confirm it refreshes warehouse records after reconnecting."],
  ["M4 · Concurrent reservations", "Try two requests for eight units against ten available. Compare the two outcomes and verify that dispatch records one movement."],
  ["M5 · JSSE TLS", "Check that the branch can reach the warehouse with a trusted certificate and that an incorrect certificate identity fails in the LAN rehearsal."],
];

export function ActivityStatusClient() {
  const { sseStatus, upstreamNioStatus, networkDetails, eventsLog, warningMessage } = useStockEvents();
  const catalog = useWarehouseQuery(fetchProducts);
  const feed = networkDetails?.nioFeed;
  const tls = networkDetails?.tlsCommand?.status;
  const transport = tls === "OPERATIONAL" ? "Verified TLS response"
    : tls === "DISABLED" ? "Local development · TLS disabled"
    : tls === "MISCONFIGURED" ? "TLS misconfigured"
    : tls === "UNREACHABLE" ? "TLS probe failed" : "Probe unavailable";
  const rows = [
    { name: "Browser event stream", value: sseStatus === "CONNECTED" ? "Connected" : sseStatus === "CONNECTING" ? "Connecting" : "Interrupted" },
    { name: "Warehouse NIO feed", value: upstreamNioStatus === "CONNECTED"
      ? "Connected · version hints only" : feed?.enabled === false ? "Disabled" : upstreamNioStatus.toLowerCase() },
    { name: "Warehouse command transport", value: transport },
    { name: "Warehouse inventory query", value: catalog.error ? "Read failed" : catalog.latencyMs !== null ? `Catalog read confirmed · ${catalog.latencyMs} ms round trip` : "Awaiting first read" },
  ];

  return (
    <div className="space-y-6">
      <WorkspaceHeading title="Network diagnostics" description="Check each connection in the stock workflow and inspect the change hints received by this browser."
        action={<button type="button" className={secondaryAction} onClick={() => void catalog.refresh()}>Check inventory read</button>} />

      {warningMessage && <p role="status" className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950">{warningMessage}</p>}

      <section className={surface} aria-labelledby="connections">
        <h2 id="connections" className="border-b border-slate-200 p-4 text-lg font-semibold sm:p-5">Connections</h2>
        <dl className="divide-y divide-slate-100">
          {rows.map(({ name, value }) => (
            <div key={name} className="grid gap-1 p-4 text-sm sm:grid-cols-[1fr_1fr] sm:p-5">
              <dt className="font-semibold">{name}</dt><dd className="text-slate-700 first-letter:uppercase">{value}</dd>
            </div>
          ))}
          <div className="grid gap-1 p-4 text-sm sm:grid-cols-[1fr_1fr] sm:p-5">
            <dt className="font-semibold">Inventory check time</dt><dd><Freshness {...catalog} /></dd>
          </div>
          <div className="grid gap-1 p-4 text-sm sm:grid-cols-[1fr_1fr] sm:p-5">
            <dt className="font-semibold">Supplier service</dt>
            <dd><Link href="/suppliers" className="font-semibold text-teal-800 underline">Run a live inquiry</Link></dd>
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
