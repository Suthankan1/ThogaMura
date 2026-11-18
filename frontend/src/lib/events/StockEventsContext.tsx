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

const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL ||
  (typeof window !== "undefined" ? window.location.origin : "http://localhost:8080");

type InvalidationCallback = (version: number, source: InvalidationEvent["source"]) => void | Promise<void>;

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
}

const StockEventsContext = createContext<StockEventsContextValue | null>(null);

export function StockEventsProvider({ children }: { children: React.ReactNode }) {
  const [sseStatus, setSseStatus] = useState<SseConnectionStatus>("CONNECTING");
  const [upstreamNioStatus, setUpstreamNioStatus] = useState<UpstreamFeedStatus>("CONNECTING");
  const [lastVersion, setLastVersion] = useState<number | null>(null);
  const [lastInvalidatedAt, setLastInvalidatedAt] = useState<Date | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [warningMessage, setWarningMessage] = useState<string | null>(null);
  const [eventsLog, setEventsLog] = useState<InvalidationEvent[]>([]);
  const [networkDetails, setNetworkDetails] = useState<NetworkStatusResponse | null>(null);

  const listenersRef = useRef<Set<InvalidationCallback>>(new Set());
  const eventSourceRef = useRef<EventSource | null>(null);
  const isRefreshingRef = useRef(false);
  const hasEverConnectedRef = useRef(false);
  const prevNioReconnectCountRef = useRef<number>(-1);
  const lastRefreshTimeRef = useRef<number>(0);

  // Executes an authoritative refresh across all registered components
  const triggerRefresh = useCallback(
    async (source: InvalidationEvent["source"] = "MANUAL") => {
      // Throttle rapid repeated refreshes (minimum 300ms between calls)
      const now = Date.now();
      if (isRefreshingRef.current || now - lastRefreshTimeRef.current < 300) {
        return;
      }

      isRefreshingRef.current = true;
      setIsRefreshing(true);
      lastRefreshTimeRef.current = now;

      try {
        const callbacks = Array.from(listenersRef.current);
        await Promise.allSettled(callbacks.map((cb) => cb(lastVersion ?? 0, source)));
        setLastInvalidatedAt(new Date());
      } finally {
        isRefreshingRef.current = false;
        setIsRefreshing(false);
      }
    },
    [lastVersion]
  );

  // Checks the upstream Java NIO connection status from the gateway
  const checkUpstreamStatus = useCallback(async () => {
    const details = await fetchNetworkStatus();
    setNetworkDetails(details);

    if (!details) {
      setUpstreamNioStatus("UNKNOWN");
      setWarningMessage("Unable to reach Spring Boot API gateway.");
      return;
    }

    const nio = details.nioFeed;
    const status = (nio?.status as UpstreamFeedStatus) || "UNKNOWN";
    setUpstreamNioStatus(status);

    if (!nio?.connected || status === "RECONNECTING" || status === "DISCONNECTED") {
      setWarningMessage("Warehouse Java NIO notification feed interrupted. Updates paused.");
    } else {
      setWarningMessage(null);
    }

    // If upstream NIO reconnected, trigger authoritative refresh
    if (nio && prevNioReconnectCountRef.current !== -1 && nio.reconnectCount > prevNioReconnectCountRef.current) {
      void triggerRefresh("RECONNECT");
    }
    if (nio) {
      prevNioReconnectCountRef.current = nio.reconnectCount;
    }
  }, [triggerRefresh]);

  // Subscribe a component callback to invalidation events
  const subscribeInvalidation = useCallback((callback: InvalidationCallback) => {
    listenersRef.current.add(callback);
    return () => {
      listenersRef.current.delete(callback);
    };
  }, []);

  // Connects to GET /api/v1/events using the native browser EventSource API
  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }

    let isMounted = true;
    const sseUrl = `${GATEWAY_URL}/api/v1/events`;

    const es = new EventSource(sseUrl);
    eventSourceRef.current = es;

    es.onopen = () => {
      if (!isMounted) return;
      setSseStatus("CONNECTED");
      void checkUpstreamStatus();

      // If reconnecting after a drop, execute authoritative state refresh
      if (hasEverConnectedRef.current) {
        setWarningMessage(null);
        void triggerRefresh("RECONNECT");
      }
      hasEverConnectedRef.current = true;
    };

    es.onerror = () => {
      if (!isMounted) return;
      setSseStatus("DISCONNECTED");
      setWarningMessage("Real-time event stream disconnected. Reconnecting to gateway...");
      // Browser EventSource automatically retries natively
    };

    // Listen specifically for the version-only invalidation signal
    es.addEventListener("stock-changed", (e: MessageEvent<string>) => {
      if (!isMounted) return;
      const ver = parseInt(e.data, 10);
      const parsedVersion = Number.isNaN(ver) ? 0 : ver;

      setLastVersion(parsedVersion);
      setEventsLog((prev) => [
        {
          version: parsedVersion,
          receivedAt: new Date(),
          source: "SSE_EVENT",
        },
        ...prev.slice(0, 9),
      ]);

      // CRITICAL ARCHITECTURAL RULE:
      // The version is strictly an invalidation hint; trigger authoritative REST refresh.
      // Under NO circumstances may the version number become an inventory quantity.
      void triggerRefresh("SSE_EVENT");
    });

    // Handle Page Focus / Tab Visibility recovery
    const handleVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        void checkUpstreamStatus();
        void triggerRefresh("PAGE_FOCUS");
      }
    };

    const handleFocus = () => {
      void checkUpstreamStatus();
      void triggerRefresh("PAGE_FOCUS");
    };

    document.addEventListener("visibilitychange", handleVisibilityChange);
    window.addEventListener("focus", handleFocus);

    // Periodic upstream NIO status check (every 12 seconds)
    const statusInterval = setInterval(() => {
      if (isMounted) {
        void checkUpstreamStatus();
      }
    }, 12000);

    // Initial check (deferred to prevent synchronous setState during effect mount)
    const initialCheckTimeout = setTimeout(() => {
      if (isMounted) {
        void checkUpstreamStatus();
      }
    }, 0);

    return () => {
      isMounted = false;
      clearTimeout(initialCheckTimeout);
      document.removeEventListener("visibilitychange", handleVisibilityChange);
      window.removeEventListener("focus", handleFocus);
      clearInterval(statusInterval);
      es.close();
      eventSourceRef.current = null;
    };
  }, [checkUpstreamStatus, triggerRefresh]);

  // isLive is true ONLY when both local SSE and upstream NIO feeds are connected
  const isLive = sseStatus === "CONNECTED" && upstreamNioStatus === "CONNECTED";

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
      }}
    >
      {children}
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
