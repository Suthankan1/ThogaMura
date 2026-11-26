"use client";

import React from "react";
import Link from "next/link";
import { useStockEvents } from "../lib/events/useStockEvents";

interface ConnectionIndicatorProps {
  compact?: boolean;
}

export function ConnectionIndicator({ compact = false }: ConnectionIndicatorProps) {
  const {
    sseStatus,
    upstreamNioStatus,
    isLive,
    isRefreshing,
    warningMessage,
  } = useStockEvents();

  // Determine user-facing text and aria announcements
  let statusText = "Connecting...";
  let statusBadgeStyle = "bg-slate-100 text-slate-700 border-slate-200 hover:bg-slate-200";
  let dotStyle = "bg-slate-400";
  let compactBg = "bg-slate-100 border-slate-200";

  if (isRefreshing) {
    statusText = "Refreshing...";
    statusBadgeStyle = "bg-teal-50 text-teal-800 border-teal-200 hover:bg-teal-100";
    dotStyle = "bg-teal-600 animate-pulse";
    compactBg = "bg-teal-50 border-teal-200";
  } else if (isLive) {
    statusText = "Live updates";
    statusBadgeStyle = "bg-emerald-50 text-emerald-800 border-emerald-200 hover:bg-emerald-100";
    dotStyle = "bg-emerald-500";
    compactBg = "bg-emerald-50 border-emerald-200";
  } else if (sseStatus === "CONNECTED" && upstreamNioStatus !== "CONNECTED") {
    statusText = "Warehouse feed interrupted";
    statusBadgeStyle = "bg-amber-50 text-amber-800 border-amber-200 hover:bg-amber-100";
    dotStyle = "bg-amber-500 animate-pulse";
    compactBg = "bg-amber-50 border-amber-200";
  } else if (sseStatus === "DISCONNECTED" || sseStatus === "ERROR") {
    statusText = "Live updates disconnected";
    statusBadgeStyle = "bg-rose-50 text-rose-800 border-rose-200 hover:bg-rose-100";
    dotStyle = "bg-rose-500 animate-ping";
    compactBg = "bg-rose-50 border-rose-200";
  }

  // Keep the connection state readable on narrow screens.
  if (compact) {
    return (
      <Link
        href="/activity"
        aria-label={`System status: ${statusText}. View diagnostics.`}
        title={`${statusText}. View diagnostics.`}
        className={`inline-flex min-h-11 shrink-0 items-center justify-center gap-1 rounded-lg border px-2 text-xs font-semibold text-slate-800 ${compactBg} transition-colors hover:shadow-xs focus:outline-hidden focus:ring-2 focus:ring-teal-600`}
      >
        <span className={`inline-block h-2.5 w-2.5 rounded-full ${dotStyle}`} aria-hidden="true" />
        <span>
          {isRefreshing
            ? "Syncing"
            : isLive
              ? "Live"
              : sseStatus === "CONNECTING"
                ? "Connecting"
                : sseStatus === "DISCONNECTED" || sseStatus === "ERROR"
                  ? "Offline"
                  : "Paused"}
        </span>
      </Link>
    );
  }

  // Desktop presentation: single consolidated System status utility
  return (
    <div className="flex items-center text-xs">
      <span className="sr-only" role="status" aria-live="polite">
        Network status: {statusText}. {warningMessage ? warningMessage : ""}
      </span>

      <Link
        href="/activity"
        aria-label={`System diagnostics: ${statusText}`}
        className={`inline-flex min-h-11 items-center gap-1.5 rounded-full border px-3 py-1 font-medium transition-colors hover:shadow-xs focus:outline-hidden focus:ring-2 focus:ring-teal-600 ${statusBadgeStyle}`}
        title={warningMessage || `SSE: ${sseStatus}, Warehouse NIO: ${upstreamNioStatus}`}
      >
        <span className={`inline-block h-2 w-2 shrink-0 rounded-full ${dotStyle}`} aria-hidden="true" />
        <span className="font-semibold">{statusText} ↗</span>
      </Link>
    </div>
  );
}
