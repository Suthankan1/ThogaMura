export function createRefreshQueue<T>(refresh: (value: T) => Promise<void>) {
  let pending: { value: T } | undefined;
  let running: Promise<void> | undefined;

  return function enqueue(value: T): Promise<void> {
    pending = { value };
    if (running) return running;

    running = Promise.resolve().then(async () => {
      try {
        while (pending) {
          const next = pending.value;
          pending = undefined;
          await refresh(next);
        }
      } finally {
        running = undefined;
      }
    });
    return running;
  };
}
