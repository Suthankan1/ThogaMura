"use client";

import React, { useState } from "react";
import { useStockEvents } from "@/lib/events/useStockEvents";
import { formatOperationalTime, formatFullDateTime, formatFreshness } from "@/lib/formatters";

export function ActivityStatusClient() {
  const {
    sseStatus,
    upstreamNioStatus,
    isLive,
    lastVersion,
    lastInvalidatedAt,
    isRefreshing,
    warningMessage,
    eventsLog,
    triggerRefresh,
    networkDetails,
  } = useStockEvents();

  const [refreshAnnouncement, setRefreshAnnouncement] = useState<string | null>(null);

  const nioFeed = networkDetails?.nioFeed;
  const sseInfo = networkDetails?.sse;
  const tlsCommand = networkDetails?.tlsCommand;
  const tlsStatus = tlsCommand?.status || "DISABLED";

  const isTlsOperational = tlsStatus === "OPERATIONAL";
  const isTlsDisabled = tlsStatus === "DISABLED";
  const isTlsMisconfigured = tlsStatus === "MISCONFIGURED";

  async function handleRefreshClick() {
    setRefreshAnnouncement(null);
    await triggerRefresh("MANUAL");
    setRefreshAnnouncement(`Authoritative state reconciled ${formatFreshness(new Date())}.`);
  }

  return (
    <div className="space-y-8">
      <div>
        <p className="text-xs font-semibold uppercase tracking-wider text-teal-700">
          Member 3 — Real-Time Notification &amp; Network Health
        </p>
        <h1 className="mt-1 text-3xl font-bold tracking-tight text-slate-900 sm:text-4xl">
          Activity &amp; Connection Status
        </h1>
        <p className="mt-3 max-w-3xl text-slate-700">
          Monitor the end-to-end Java networking path: from warehouse Java NIO change events
          to Spring Boot Server-Sent Events (SSE) and authoritative browser state reconciliation.
        </p>
      </div>

      {/* Network Alert / Warning Banner (QA-05) */}
      {warningMessage ? (
        <div
          role="alert"
          className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4 text-amber-900"
        >
          <svg className="mt-0.5 h-5 w-5 shrink-0 text-amber-600" fill="currentColor" viewBox="0 0 20 20">
            <path
              fillRule="evenodd"
              d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.875c.673 1.167-.17 2.625-1.516 2.625H3.72c-1.347 0-2.189-1.458-1.515-2.625L8.485 2.495zM10 6a.75.75 0 01.75.75v3.5a.75.75 0 01-1.5 0v-3.5A.75.75 0 0110 6zm0 9a1 1 0 100-2 1 1 0 000 2z"
              clipRule="evenodd"
            />
          </svg>
          <div>
            <h2 className="font-semibold text-amber-900">Live updates disconnected</h2>
            <p className="mt-1 text-sm text-amber-800">{warningMessage}</p>
            <p className="mt-1 text-xs text-amber-700">
              Displayed stock quantities may be outdated. The browser will automatically reconcile authoritative state upon reconnection.
            </p>
          </div>
        </div>
      ) : isLive ? (
        <div className="flex items-center gap-3 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-emerald-900">
          <span className="flex h-3 w-3 shrink-0 rounded-full bg-emerald-500" />
          <p className="text-sm font-medium">
            <strong>All real-time feeds active.</strong> Warehouse Java NIO notification feed and browser SSE stream are synchronized.
          </p>
        </div>
      ) : null}

      {/* 3-Hop Architecture Status Grid */}
      <div className="grid gap-5 md:grid-cols-3">
        {/* Hop 1: Gateway SSE Stream */}
        <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Browser Stream
            </span>
            <span
              className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ${
                sseStatus === "CONNECTED"
                  ? "bg-emerald-100 text-emerald-800"
                  : "bg-rose-100 text-rose-800"
              }`}
            >
              {sseStatus === "CONNECTED" ? "Connected" : "Disconnected"}
            </span>
          </div>
          <h2 className="mt-2 text-lg font-bold text-slate-900">HTTP Server-Sent Events</h2>
          <p className="mt-1 text-xs text-slate-500">GET /api/v1/events (EventSource)</p>

          <dl className="mt-4 space-y-2 text-xs">
            <div className="flex justify-between border-t border-slate-100 pt-2">
              <dt className="text-slate-500">Active Emitters:</dt>
              <dd className="font-semibold text-slate-800">{sseInfo?.activeClients ?? 1}</dd>
            </div>
            <div className="flex justify-between border-t border-slate-100 pt-2">
              <dt className="text-slate-500">Last SSE Version:</dt>
              <dd className="font-semibold text-slate-800">{lastVersion ? `v${lastVersion}` : "None"}</dd>
            </div>
          </dl>
        </div>

        {/* Hop 2: Warehouse Java NIO Feed */}
        <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Warehouse Feed
            </span>
            <span
              className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ${
                upstreamNioStatus === "CONNECTED"
                  ? "bg-emerald-100 text-emerald-800"
                  : upstreamNioStatus === "RECONNECTING"
                  ? "bg-amber-100 text-amber-800"
                  : "bg-rose-100 text-rose-800"
              }`}
            >
              {upstreamNioStatus}
            </span>
          </div>
          <h2 className="mt-2 text-lg font-bold text-slate-900">Java NIO SocketChannel</h2>
          <p className="mt-1 text-xs text-slate-500">
            {networkDetails?.centralHost || "127.0.0.1"}:{networkDetails?.nioPort || 7444} (Selector Loop)
          </p>

          <dl className="mt-4 space-y-2 text-xs">
            <div className="flex justify-between border-t border-slate-100 pt-2">
              <dt className="text-slate-500">Reconnect Count:</dt>
              <dd className="font-semibold text-slate-800">{nioFeed?.reconnectCount ?? 0}</dd>
            </div>
            <div className="flex justify-between border-t border-slate-100 pt-2">
              <dt className="text-slate-500">Inbound Changes:</dt>
              <dd className="font-semibold text-slate-800">{nioFeed?.notificationCount ?? 0}</dd>
            </div>
          </dl>
        </div>

        {/* Hop 3: Command Transport (QA-06: Separating Mode from Health) */}
        <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-500">
              Command Transport
            </span>
            <span
              className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-semibold ${
                isTlsOperational
                  ? "bg-emerald-100 text-emerald-800"
                  : isTlsDisabled
                  ? "bg-blue-100 text-blue-800"
                  : isTlsMisconfigured
                  ? "bg-rose-100 text-rose-800"
                  : "bg-amber-100 text-amber-800"
              }`}
            >
              {isTlsOperational
                ? "TLS 1.3 Active"
                : isTlsDisabled
                ? "Plaintext TCP (Dev)"
                : tlsStatus}
            </span>
          </div>
          <h2 className="mt-2 text-lg font-bold text-slate-900">
            {isTlsOperational ? "JSSE TLS Socket" : "Java TCP Command Socket"}
          </h2>
          <p className="mt-1 text-xs text-slate-500">Port 7443 (Authoritative Stock Commands)</p>

          <dl className="mt-4 space-y-2 text-xs">
            <div className="flex justify-between border-t border-slate-100 pt-2">
              <dt className="text-slate-500">Configured Mode:</dt>
              <dd className="font-semibold text-slate-800">
                {isTlsOperational
                  ? "TLS 1.3 (Production)"
                  : "Plaintext (Local Loopback)"}
              </dd>
            </div>
            <div className="flex justify-between border-t border-slate-100 pt-2">
              <dt className="text-slate-500">TLS Encryption:</dt>
              <dd className="font-semibold text-slate-800">
                {isTlsOperational
                  ? "Enforced (X.509 TrustStore)"
                  : "Disabled by config"}
              </dd>
            </div>
            <div className="flex justify-between border-t border-slate-100 pt-2">
              <dt className="text-slate-500">Owners:</dt>
              <dd className="font-semibold text-slate-800">M1 (TCP) &amp; M5 (TLS)</dd>
            </div>
          </dl>
        </div>
      </div>

      {/* Authoritative Refresh Panel (QA-12) */}
      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div>
            <h2 className="text-lg font-bold text-slate-900">Authoritative State Reconciliation</h2>
            <p className="mt-1 text-xs text-slate-500">
              Last reconciled:{" "}
              <span className="font-mono font-medium text-slate-700">
                {lastInvalidatedAt ? formatOperationalTime(lastInvalidatedAt) : "Pending initial load"}
              </span>
            </p>
          </div>
          <button
            type="button"
            onClick={() => void handleRefreshClick()}
            disabled={isRefreshing}
            className="inline-flex items-center rounded-lg bg-teal-700 px-4 py-2 text-sm font-semibold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 focus:ring-offset-2 disabled:opacity-50"
          >
            <svg
              className={`mr-2 h-4 w-4 ${isRefreshing ? "animate-spin" : ""}`}
              fill="none"
              viewBox="0 0 24 24"
              strokeWidth="2"
              stroke="currentColor"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0 3.181 3.183a8.25 8.25 0 0 0 13.803-3.7M4.031 9.865a8.25 8.25 0 0 1 13.803-3.7l3.181 3.182m0-4.991v4.99"
              />
            </svg>
            {isRefreshing ? "Reconciling..." : "Trigger Authoritative Refresh"}
          </button>
        </div>

        {refreshAnnouncement && (
          <div
            role="status"
            aria-live="polite"
            className="mt-4 rounded-lg bg-teal-50 border border-teal-200 p-3 text-xs font-semibold text-teal-900"
          >
            ✓ {refreshAnnouncement}
          </div>
        )}

        <div className="mt-4 rounded-lg bg-slate-50 p-3 text-xs text-slate-600 border border-slate-100">
          <strong>Architectural Disclosure:</strong> Invalidation signals (`stock-changed`) carry strictly version numbers.
          Product stock quantities, reservation status, and movements are never derived from SSE payloads.
          The browser reconciles authoritative data exclusively through validated REST endpoints (`/api/v1/products`).
        </div>
      </div>

      {/* Live Invalidation Log (QA-14) */}
      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
        <h2 className="text-lg font-bold text-slate-900">Recent Invalidation Events Log</h2>
        <p className="mt-1 text-xs text-slate-500">
          Record of real-time signals received by this browser session.
        </p>

        {eventsLog.length === 0 ? (
          <p className="mt-4 rounded-lg border border-dashed border-slate-200 p-6 text-center text-xs text-slate-500">
            No change events received yet. When warehouse inventory changes, version-only invalidation signals will stream here.
          </p>
        ) : (
          <div className="mt-4 overflow-hidden rounded-lg border border-slate-200">
            <table className="min-w-full divide-y divide-slate-200 text-left text-xs">
              <thead className="bg-slate-50 text-slate-700">
                <tr>
                  <th scope="col" className="px-4 py-2 font-semibold">Time</th>
                  <th scope="col" className="px-4 py-2 font-semibold">Source</th>
                  <th scope="col" className="px-4 py-2 font-semibold">Change Version</th>
                  <th scope="col" className="px-4 py-2 font-semibold">Action Dispatched</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-slate-600">
                {eventsLog.map((ev, idx) => (
                  <tr key={`${ev.version}-${idx}`}>
                    <td className="px-4 py-2 font-mono text-slate-800" title={formatFullDateTime(ev.receivedAt)}>
                      {formatOperationalTime(ev.receivedAt)}
                    </td>
                    <td className="px-4 py-2 font-mono text-teal-700">{ev.source}</td>
                    <td className="px-4 py-2 font-semibold text-slate-900">v{ev.version}</td>
                    <td className="px-4 py-2 text-slate-600">Authoritative REST refetch dispatched</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
