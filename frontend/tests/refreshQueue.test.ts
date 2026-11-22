import assert from "node:assert/strict";
import { test } from "node:test";
import { createRefreshQueue } from "../src/lib/events/refreshQueue.ts";

test("applies the latest hint after a slow in-flight refresh", async () => {
  const applied: number[] = [];
  let release!: () => void;
  const slowRequest = new Promise<void>((resolve) => { release = resolve; });
  const refresh = createRefreshQueue(async (version: number) => {
    applied.push(version);
    if (version === 1) await slowRequest;
  });

  const first = refresh(1);
  await Promise.resolve();
  assert.deepEqual(applied, [1]);
  assert.equal(refresh(2), first);
  assert.equal(refresh(3), first);
  release();
  await first;
  assert.deepEqual(applied, [1, 3]);

  await refresh(4);
  assert.deepEqual(applied, [1, 3, 4]);
});
