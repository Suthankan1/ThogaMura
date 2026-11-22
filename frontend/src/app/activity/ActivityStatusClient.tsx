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

  const [refreshAnnouncement, setRefreshAnnouncement] = useState<{ text: string; isError: boolean } | null>(null);

  const nioFeed = networkDetails?.nioFeed;
  const sseInfo = networkDetails?.sse;
  const tlsCommand = networkDetails?.tlsCommand;
  const tlsStatus = tlsCommand?.status || "DISABLED";

  const isTlsOperational = tlsStatus === "OPERATIONAL";
  const isTlsDisabled = tlsStatus === "DISABLED";
  const isTlsMisconfigured = tlsStatus === "MISCONFIGURED";
  const isTlsUnreachable = tlsStatus === "UNREACHABLE";

  // F32: Truthful operational evaluation (requires BOTH feed AND command transport health)
  const isFullyOperational = isLive && isTlsOperational;
  const isDevOperational = isLive && isTlsDisabled;
  const hasCommandOutage = isLive && (isTlsUnreachable || isTlsMisconfigured);

  // F33: Await reconciliation and report actual outcome
  async function handleRefreshClick() {
    setRefreshAnnouncement(null);
    try {
      await triggerRefresh("MANUAL");
      setRefreshAnnouncement({
        text: `Authoritative state reconciled ${formatFreshness(new Date())}.`,
        isError: false,
      });
    } catch {
      setRefreshAnnouncement({
        text: "Reconciliation failed. Central server unreachable.",
        isError: true,
      });
    }
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center rounded-full bg-teal-50 px-2.5 py-0.5 text-xs font-semibold text-teal-800 border border-teal-200">
            Network Health
          </span>
          <span className="text-xs text-slate-400">·</span>
          <span className="text-xs font-medium text-slate-500">3-Hop Architecture Diagnostics</span>
        </div>
        <h1 className="mt-1.5 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
          System Status &amp; Diagnostics
        </h1>
        <p className="mt-1 text-xs text-slate-600 sm:text-sm">
          Live diagnostics for the end-to-end communication path: Browser SSE bridge, Java NIO notification feed, and TCP/TLS command transport.
        </p>
      </div>

      {/* Top Operational Verdict Banner (F32: Truthful and decoupled status claims) */}
      {warningMessage ? (
        <div
          role="alert"
          className="rounded-2xl border border-amber-200 bg-amber-50/80 p-5 text-amber-950 flex items-start gap-3.5 shadow-xs"
        >
          <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-500 text-white font-bold text-xs mt-0.5">
            !
          </span>
          <div className="text-xs sm:text-sm">
            <h2 className="font-bold text-amber-900">Live update stream disconnected</h2>
            <p className="text-amber-800 mt-1 leading-normal">{warningMessage}</p>
            <p className="text-amber-700 mt-1 text-xs">
              Operational Impact: Read-only inventory browsing and manual order submissions remain active. Trigger a manual refresh or wait for automatic stream reconnection.
            </p>
          </div>
        </div>
      ) : hasCommandOutage ? (
        <div
          role="alert"
          className="rounded-2xl border border-rose-200 bg-rose-50/90 p-5 text-rose-950 flex items-start gap-3.5 shadow-xs"
        >
          <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-rose-600 text-white font-bold text-xs mt-0.5">
            ⚠
          </span>
          <div className="text-xs sm:text-sm">
            <h2 className="font-bold text-rose-900">Stock requests unavailable · Live updates connected</h2>
            <p className="text-rose-800 mt-1 leading-normal">
              Warehouse Java NIO feed is broadcasting version updates, but the Java TCP/TLS command socket on port 7443 is unreachable or misconfigured ({tlsStatus}).
            </p>
          </div>
        </div>
      ) : isFullyOperational ? (
        <div className="rounded-2xl border border-emerald-200 bg-emerald-50/80 p-5 text-emerald-950 flex items-center gap-3.5 shadow-xs">
          <span className="flex h-3 w-3 shrink-0 rounded-full bg-emerald-500 animate-pulse" />
          <p className="text-xs sm:text-sm font-semibold text-emerald-900">
            All systems operational. Warehouse Java NIO selector, browser SSE stream, and validated TLS command transport are healthy.
          </p>
        </div>
      ) : isDevOperational ? (
        <div className="rounded-2xl border border-teal-200 bg-teal-50/80 p-5 text-teal-950 flex items-center gap-3.5 shadow-xs">
          <span className="flex h-3 w-3 shrink-0 rounded-full bg-teal-600 animate-pulse" />
          <p className="text-xs sm:text-sm font-semibold text-teal-900">
            Live updates active · Plaintext TCP command mode (Local Development).
          </p>
        </div>
      ) : (
        <div className="rounded-2xl border border-slate-200 bg-slate-50 p-5 text-slate-800 flex items-center gap-3.5 shadow-xs">
          <span className="flex h-3 w-3 shrink-0 rounded-full bg-slate-400" />
          <p className="text-xs sm:text-sm font-semibold text-slate-700">
            System status: Initializing diagnostics connection...
          </p>
        </div>
      )}

      {/* 3-Hop Architecture Status Grid (F34: explicit 'Not reported' vs measured zero) */}
      <div className="grid gap-4 md:grid-cols-3">
        {/* Hop 1: Gateway SSE Stream */}
        <div className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-xs flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-500">
                1. Browser Stream
              </span>
              <span
                className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${
                  sseStatus === "CONNECTED"
                    ? "bg-emerald-100 text-emerald-800"
                    : "bg-rose-100 text-rose-800"
                }`}
              >
                {sseStatus === "CONNECTED" ? "Connected" : "Disconnected"}
              </span>
            </div>
            <h2 className="mt-2 text-base font-bold text-slate-900">HTTP Server-Sent Events</h2>
            <p className="mt-0.5 text-xs text-slate-500 font-mono">GET /api/v1/events</p>

            <dl className="mt-4 space-y-2 text-xs border-t border-slate-100 pt-3">
              <div className="flex justify-between">
                <dt className="text-slate-500">Active Emitters:</dt>
                <dd className="font-semibold text-slate-800">
                  {sseInfo?.activeClients !== undefined ? sseInfo.activeClients : "Not reported"}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">Last SSE Version:</dt>
                <dd className="font-semibold text-slate-800 font-mono">
                  {lastVersion ? `v${lastVersion}` : "No events in session"}
                </dd>
              </div>
            </dl>
          </div>
          <div className="mt-4 pt-3 border-t border-slate-100 text-[11px] text-slate-500">
            Spring Boot SSE Bridge (Member 3)
          </div>
        </div>

        {/* Hop 2: Warehouse Java NIO Feed */}
        <div className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-xs flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-500">
                2. Warehouse Feed
              </span>
              <span
                className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${
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
            <h2 className="mt-2 text-base font-bold text-slate-900">Java NIO SocketChannel</h2>
            <p className="mt-0.5 text-xs text-slate-500 font-mono">
              Port {networkDetails?.nioPort ? networkDetails.nioPort : "7444"} (Selector Loop)
            </p>

            <dl className="mt-4 space-y-2 text-xs border-t border-slate-100 pt-3">
              <div className="flex justify-between">
                <dt className="text-slate-500">Reconnect Count:</dt>
                <dd className="font-semibold text-slate-800">
                  {nioFeed?.reconnectCount !== undefined ? nioFeed.reconnectCount : "Not reported"}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">Inbound Signals:</dt>
                <dd className="font-semibold text-slate-800">
                  {nioFeed?.notificationCount !== undefined ? nioFeed.notificationCount : "Not reported"}
                </dd>
              </div>
            </dl>
          </div>
          <div className="mt-4 pt-3 border-t border-slate-100 text-[11px] text-slate-500">
            Private LAN NIO Invalidation (Member 3)
          </div>
        </div>

        {/* Hop 3: Command Transport */}
        <div className="rounded-2xl border border-slate-200/80 bg-white p-5 shadow-xs flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-slate-500">
                3. Command Transport
              </span>
              <span
                className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${
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
            <h2 className="mt-2 text-base font-bold text-slate-900">
              {isTlsOperational ? "JSSE TLS Socket" : "Java TCP Command Socket"}
            </h2>
            <p className="mt-0.5 text-xs text-slate-500 font-mono">Port 7443 (Stock Commands)</p>

            <dl className="mt-4 space-y-2 text-xs border-t border-slate-100 pt-3">
              <div className="flex justify-between">
                <dt className="text-slate-500">Configured Mode:</dt>
                <dd className="font-semibold text-slate-800">
                  {isTlsOperational ? "TLS 1.3 (Production)" : "Plaintext (Local Loopback)"}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">Encryption:</dt>
                <dd className="font-semibold text-slate-800">
                  {isTlsOperational ? "Enforced (X.509)" : "Disabled for dev"}
                </dd>
              </div>
            </dl>
          </div>
          <div className="mt-4 pt-3 border-t border-slate-100 text-[11px] text-slate-500">
            Owned by Member 1 (TCP) &amp; Member 5 (TLS)
          </div>
        </div>
      </div>

      {/* Authoritative State Reconciliation (F33: verified execution) */}
      <div className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-xs space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <h2 className="text-base font-bold text-slate-900">Authoritative State Reconciliation</h2>
            <p className="mt-1 text-xs text-slate-500">
              Last reconciled:{" "}
              <strong className="font-mono text-slate-700">
                {lastInvalidatedAt ? formatOperationalTime(lastInvalidatedAt) : "Pending initial load"}
              </strong>
            </p>
          </div>
          <button
            type="button"
            onClick={() => void handleRefreshClick()}
            disabled={isRefreshing}
            className="inline-flex min-h-[44px] items-center justify-center rounded-xl bg-teal-700 px-5 py-2.5 text-xs font-bold text-white shadow-xs hover:bg-teal-800 focus:outline-hidden focus:ring-2 focus:ring-teal-600 disabled:opacity-50 transition-colors"
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
            {isRefreshing ? "Reconciling..." : "Reconcile System State"}
          </button>
        </div>

        {refreshAnnouncement && (
          <div
            role="status"
            aria-live="polite"
            className={`rounded-xl p-3 text-xs font-semibold ${
              refreshAnnouncement.isError
                ? "bg-rose-50 border border-rose-200 text-rose-900"
                : "bg-teal-50 border border-teal-200 text-teal-900"
            }`}
          >
            {refreshAnnouncement.isError ? "⚠ " : "✓ "}
            {refreshAnnouncement.text}
          </div>
        )}
      </div>

      {/* Recent Invalidation Events Log */}
      <div className="rounded-2xl border border-slate-200/80 bg-white p-6 shadow-xs space-y-4">
        <div>
          <h2 className="text-base font-bold text-slate-900">Recent Invalidation Events Log</h2>
          <p className="mt-0.5 text-xs text-slate-500">
            Real-time version-only invalidation signals received from the Java NIO warehouse broadcast.
          </p>
        </div>

        {eventsLog.length === 0 ? (
          <div className="rounded-xl border border-dashed border-slate-200 p-6 text-center text-xs text-slate-500">
            No change events received yet in this browser session.
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border border-slate-200">
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-slate-200 text-left text-xs">
                <thead className="bg-slate-50 font-semibold text-slate-700">
                  <tr>
                    <th scope="col" className="px-4 py-2.5">Time</th>
                    <th scope="col" className="px-4 py-2.5">Source</th>
                    <th scope="col" className="px-4 py-2.5">Change Version</th>
                    <th scope="col" className="px-4 py-2.5">Action Dispatched</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 text-slate-600">
                  {eventsLog.map((ev, idx) => (
                    <tr key={`${ev.version}-${idx}`} className="hover:bg-slate-50">
                      <td className="px-4 py-2.5 font-mono text-slate-800" title={formatFullDateTime(ev.receivedAt)}>
                        {formatOperationalTime(ev.receivedAt)}
                      </td>
                      <td className="px-4 py-2.5 font-mono text-teal-700">{ev.source}</td>
                      <td className="px-4 py-2.5 font-semibold text-slate-900">v{ev.version}</td>
                      <td className="px-4 py-2.5 text-slate-600">Authoritative REST refetch triggered</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
