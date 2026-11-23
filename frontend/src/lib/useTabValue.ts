"use client";

import { useCallback, useState } from "react";

const memory = new Map<string, unknown>();

export function readTabValue<T>(key: string, fallback: T): T {
  if (memory.has(key)) return memory.get(key) as T;
  try {
    const value = sessionStorage.getItem(key);
    return value === null ? fallback : JSON.parse(value) as T;
  } catch {
    return fallback;
  }
}

export function writeTabValue<T>(key: string, value: T) {
  try {
    if (value === null) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, JSON.stringify(value));
    memory.delete(key);
  } catch {
    memory.set(key, value);
  }
}

export function useTabValue<T>(key: string, fallback: T) {
  const [value, setValue] = useState(() => readTabValue(key, fallback));
  const save = useCallback((next: T) => {
    setValue(next);
    writeTabValue(key, next);
  }, [key]);
  return [value, save] as const;
}
