"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useStockEvents } from "./events/useStockEvents";

interface QueryState<T> {
  data: T | null;
  checkedAt: Date | null;
  latencyMs: number | null;
  error: string | null;
  loading: boolean;
}

export function useWarehouseQuery<T>(read: () => Promise<T>, enabled = true, live = true, cacheKey?: string) {
  const { subscribeInvalidation, readCache, writeCache } = useStockEvents();
  const cached = cacheKey ? readCache<T>(cacheKey) : undefined;
  const [state, setState] = useState<QueryState<T>>({
    data: cached?.data ?? null,
    checkedAt: cached?.checkedAt ?? null,
    latencyMs: cached?.latencyMs ?? null,
    error: null,
    loading: enabled,
  });
  const sequence = useRef(0);
  const invalidate = useCallback(() => { sequence.current++; }, []);

  const refresh = useCallback(async () => {
    if (!enabled) return;
    const request = ++sequence.current;
    setState((previous) => ({ ...previous, loading: true }));
    const started = performance.now();
    try {
      const data = await read();
      const latencyMs = Math.round(performance.now() - started);
      const checkedAt = new Date();
      if (cacheKey) writeCache(cacheKey, { data, checkedAt, latencyMs });
      if (request === sequence.current) {
        setState({ data, checkedAt, latencyMs, error: null, loading: false });
      }
    } catch (error) {
      if (request === sequence.current) {
        setState((previous) => ({
          ...previous,
          loading: false,
          error: error instanceof Error ? error.message : "The warehouse could not be reached.",
        }));
      }
    }
  }, [cacheKey, enabled, read, writeCache]);

  useEffect(() => {
    if (!enabled) return;
    const timer = setTimeout(() => void refresh(), 0);
    const unsubscribe = live ? subscribeInvalidation(() => refresh()) : undefined;
    return () => {
      clearTimeout(timer);
      unsubscribe?.();
      invalidate();
    };
  }, [enabled, invalidate, live, refresh, subscribeInvalidation]);

  return { ...state, refresh };
}
