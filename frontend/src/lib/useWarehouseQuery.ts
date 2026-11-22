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

export function useWarehouseQuery<T>(read: () => Promise<T>, enabled = true) {
  const [state, setState] = useState<QueryState<T>>({ data: null, checkedAt: null, latencyMs: null, error: null, loading: enabled });
  const sequence = useRef(0);
  const invalidate = useCallback(() => { sequence.current++; }, []);
  const { subscribeInvalidation } = useStockEvents();

  const refresh = useCallback(async () => {
    if (!enabled) return;
    const request = ++sequence.current;
    setState((previous) => ({ ...previous, loading: true }));
    const started = performance.now();
    try {
      const data = await read();
      if (request === sequence.current) {
        setState({ data, checkedAt: new Date(), latencyMs: Math.round(performance.now() - started), error: null, loading: false });
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
  }, [enabled, read]);

  useEffect(() => {
    if (!enabled) return;
    const timer = setTimeout(() => void refresh(), 0);
    const unsubscribe = subscribeInvalidation(() => refresh());
    return () => {
      clearTimeout(timer);
      unsubscribe();
      invalidate();
    };
  }, [enabled, invalidate, refresh, subscribeInvalidation]);

  return { ...state, refresh };
}
