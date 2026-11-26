"use client";

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import type {
  InvalidationEvent,
  NetworkStatusResponse,
  SseConnectionStatus,
  UpstreamFeedStatus,
} from "../types/network";
import { fetchNetworkStatus } from "../api/network";
import { createRefreshQueue } from "./refreshQueue";

const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL ||
  process.env.NEXT_PUBLIC_API_BASE_URL ||
  (typeof window !== "undefined" ? window.location.origin : "http://localhost:8080");

type InvalidationCallback = (version: number, source: InvalidationEvent["source"]) => void | Promise<void>;

export interface ToastNotice {
  id: number;
  message: string;
  tone?: "success" | "warning" | "error";
}

interface CacheEntry<T> {
  data: T;
  checkedAt: Date;
  latencyMs: number;
}

interface StockEventsContextValue {
  /** Connection state of the browser's HTTP SSE stream to the gateway */
  sseStatus: SseConnectionStatus;
  /** Connection state of the gateway's upstream Java NIO feed to the warehouse */
  upstreamNioStatus: UpstreamFeedStatus;
  /** True only if both SSE and upstream NIO feeds are healthy and connected */
  isLive: boolean;
  /** The most recent change version received as an invalidation hint */
  lastVersion: number | null;
  /** Timestamp when authoritative data was last invalidated/refreshed */
  lastInvalidatedAt: Date | null;
  /** Indicates whether an authoritative data refresh is currently in flight */
  isRefreshing: boolean;
  /** Human-readable warning message when connection or feed is degraded */
  warningMessage: string | null;
  /** Recent history of received invalidation events */
  eventsLog: InvalidationEvent[];
  /** Manually triggers an authoritative data refresh */
  triggerRefresh: (source?: InvalidationEvent["source"]) => Promise<void>;
  /** Subscribes a component-level refresh callback to invalidation signals */
  subscribeInvalidation: (callback: InvalidationCallback) => () => void;
  /** Detailed gateway network status if available */
  networkDetails: NetworkStatusResponse | null;
  /** Read-through query cache cleared on invalidation */
  readCache: <T>(key: string) => CacheEntry<T> | undefined;
  /** Write confirmed query result to cache */
  writeCache: <T>(key: string, entry: CacheEntry<T>) => void;
  /** Show a brief accessible status toast notification */
  notifyToast: (message: string, tone?: ToastNotice["tone"]) => void;
}

const StockEventsContext = createContext<StockEventsContextValue | null>(null);

export function StockEventsProvider({ children }: { children: React.ReactNode }) {
  const [sseStatus, setSseStatus] = useState<SseConnectionStatus>("CONNECTING");
  const [upstreamNioStatus, setUpstreamNioStatus] = useState<UpstreamFeedStatus>("CONNECTING");
  const [lastVersion, setLastVersion] = useState<number | null>(null);
  const [lastInvalidatedAt, setLastInvalidatedAt] = useState<Date | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [eventsLog, setEventsLog] = useState<InvalidationEvent[]>([]);
  const [networkDetails, setNetworkDetails] = useState<NetworkStatusResponse | null>(null);
  const [toasts, setToasts] = useState<ToastNotice[]>([]);

  const listenersRef = useRef<Set<InvalidationCallback>>(new Set());
  const cacheRef = useRef<Map<string, CacheEntry<unknown>>>(new Map());
  const toastSeqRef = useRef(0);
  const versionRef = useRef(0);
  const [enqueueRefresh] = useState(() => createRefreshQueue<InvalidationEvent["source"]>(async (source) => {
    setIsRefreshing(true);
    try {
      cacheRef.current.clear();
      const version = versionRef.current;
      const listeners = Array.from(listenersRef.current);
      await Promise.allSettled(listeners.map(async (listener) => listener(version, source)));
    } finally {
      setIsRefreshing(false);
    }
  }));

  const readCache = useCallback(<T,>(key: string) => cacheRef.current.get(key) as CacheEntry<T> | undefined, []);
  const writeCache = useCallback(<T,>(key: string, entry: CacheEntry<T>) => {
    cacheRef.current.set(key, entry as CacheEntry<unknown>);
  }, []);
  const dismissToast = useCallback((id: number) => {
    setToasts((prev) => prev.filter((item) => item.id !== id));
  }, []);
  const notifyToast = useCallback((message: string, tone: ToastNotice["tone"] = "success") => {
    const id = ++toastSeqRef.current;
    setToasts((prev) => [...prev.slice(-2), { id, message, tone }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((item) => item.id !== id));
    }, 3600);
  }, []);

  const triggerRefresh = useCallback((source: InvalidationEvent["source"] = "MANUAL") => {
    return enqueueRefresh(source);
  }, [enqueueRefresh]);

  const subscribeInvalidation = useCallback((callback: InvalidationCallback) => {
    listenersRef.current.add(callback);
    return () => {
      listenersRef.current.delete(callback);
    };
  }, []);

  useEffect(() => {
    let stream: EventSource | undefined;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let disposed = false;
    let probing = false;
    let openedBefore = false;
    let previousFeed: NetworkStatusResponse["nioFeed"] | null = null;

    async function checkUpstream() {
      if (disposed || probing) return;
      probing = true;
      try {
        const details = await fetchNetworkStatus();
        if (disposed) return;

        const feed = details?.nioFeed ?? null;
        setNetworkDetails(details);
        setUpstreamNioStatus(feed?.status ?? "UNKNOWN");
        if (feed?.connected && previousFeed !== null &&
            (!previousFeed.connected || previousFeed.reconnectCount !== feed.reconnectCount)) {
          void triggerRefresh("RECONNECT");
        }
        if (feed) previousFeed = feed;
      } finally {
        probing = false;
      }
    }

    function connect() {
      if (disposed) return;
      const source = new EventSource(`${GATEWAY_URL}/api/v1/events`);
      stream = source;
      source.onopen = () => {
        setSseStatus("CONNECTED");
        void checkUpstream();
        if (openedBefore) void triggerRefresh("RECONNECT");
        openedBefore = true;
      };
      source.onerror = () => {
        setSseStatus("DISCONNECTED");
        // EventSource reconnects on transient errors. Recreate it if the browser closed it permanently.
        if (source.readyState === EventSource.CLOSED && retryTimer === undefined) {
          retryTimer = setTimeout(() => {
            retryTimer = undefined;
            connect();
          }, 2000);
        }
      };
      source.addEventListener("stock-changed", (event: MessageEvent<string>) => {
        if (!/^\d+$/.test(event.data)) return;
        const version = Number(event.data);
        if (!Number.isSafeInteger(version)) return;

        versionRef.current = Math.max(versionRef.current, version);
        setLastVersion(versionRef.current);
        const receivedAt = new Date();
        setLastInvalidatedAt(receivedAt);
        setEventsLog((previous) => [{ version, receivedAt, source: "SSE_EVENT" }, ...previous.slice(0, 9)]);
        void triggerRefresh("SSE_EVENT");
      });
    }

    function onFocus() {
      if (document.visibilityState !== "visible") return;
      void checkUpstream();
      void triggerRefresh("PAGE_FOCUS");
    }

    connect();
    void checkUpstream();
    const statusInterval = setInterval(() => void checkUpstream(), 12000);
    window.addEventListener("focus", onFocus);
    document.addEventListener("visibilitychange", onFocus);
    return () => {
      disposed = true;
      stream?.close();
      clearTimeout(retryTimer);
      clearInterval(statusInterval);
      window.removeEventListener("focus", onFocus);
      document.removeEventListener("visibilitychange", onFocus);
    };
  }, [triggerRefresh]);

  const isLive = sseStatus === "CONNECTED" && upstreamNioStatus === "CONNECTED";
  const warningMessage = sseStatus === "DISCONNECTED"
    ? "Live connection interrupted. Refresh to check for new warehouse changes."
    : upstreamNioStatus === "UNKNOWN"
      ? "Gateway status is unavailable."
      : upstreamNioStatus !== "CONNECTED" && upstreamNioStatus !== "CONNECTING"
        ? "Warehouse change feed interrupted. Inventory can still be checked manually."
        : null;

  return (
    <StockEventsContext.Provider
      value={{
        sseStatus,
        upstreamNioStatus,
        isLive,
        lastVersion,
        lastInvalidatedAt,
        isRefreshing,
        warningMessage,
        eventsLog,
        triggerRefresh,
        subscribeInvalidation,
        networkDetails,
        readCache,
        writeCache,
        notifyToast,
      }}
    >
      {children}
      {toasts.length > 0 && (
        <div className="toast-region" aria-live="polite" aria-label="Notifications">
          {toasts.map((toast) => {
            const toneClass = toast.tone === "error"
              ? "border-rose-300 bg-rose-50 text-rose-950"
              : toast.tone === "warning"
                ? "border-amber-300 bg-amber-50 text-amber-950"
                : "border-teal-300 bg-white text-slate-900";
            return (
              <div key={toast.id} role="status" className={`toast-card flex items-start justify-between gap-3 rounded-xl border px-3.5 py-2.5 text-sm shadow-lg ${toneClass}`}>
                <p className="font-medium leading-snug">{toast.message}</p>
                <button
                  type="button"
                  aria-label="Dismiss notification"
                  onClick={() => dismissToast(toast.id)}
                  className="shrink-0 rounded-md px-1.5 py-0.5 text-xs font-semibold text-slate-600 hover:bg-slate-100"
                >
                  ×
                </button>
              </div>
            );
          })}
        </div>
      )}
    </StockEventsContext.Provider>
  );
}

export function useStockEvents() {
  const context = useContext(StockEventsContext);
  if (!context) {
    throw new Error("useStockEvents must be used within a StockEventsProvider");
  }
  return context;
}
