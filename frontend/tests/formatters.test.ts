import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { formatOperationalTime, formatFullDateTime, formatFreshness } from "../src/lib/formatters.ts";

describe("QA-14: Consistent timestamp and freshness formatting across screens", () => {
  it("formats operational time in standard 24-hour HH:MM:SS format", () => {
    // Fixed UTC timestamp: 2026-11-22T10:15:30.000Z
    const isoString = "2026-11-22T10:15:30.000Z";
    const formatted = formatOperationalTime(isoString);

    assert.equal(/^\d{2}:\d{2}:\d{2}$/.test(formatted), true);
  });

  it("handles null, undefined and invalid timestamps gracefully", () => {
    assert.equal(formatOperationalTime(null), "—");
    assert.equal(formatOperationalTime(undefined), "—");
    assert.equal(formatOperationalTime("invalid-date"), "—");
    assert.equal(formatFreshness(null), "Pending fetch");
  });

  it("formats freshness with relative prefix", () => {
    const isoString = "2026-11-22T14:30:00.000Z";
    const freshness = formatFreshness(isoString);
    assert.equal(freshness.startsWith("as of "), true);
  });

  it("formats full date time with both date and time segments", () => {
    const isoString = "2026-11-22T14:30:00.000Z";
    const full = formatFullDateTime(isoString);
    // Should contain date and time segments (e.g. 22/11/2026, 14:30:00 or similar)
    assert.equal(full.includes("2026"), true);
    assert.equal(full.includes(":"), true);
  });
});
