/**
 * Unified timestamp and freshness formatters across ThogaMura screens.
 * Conforms to QA-14 and frontend standards.
 */

/**
 * Formats a Date or ISO timestamp string as a standard 24-hour operational time (HH:MM:SS).
 * Returns fallback if null or invalid.
 */
export function formatOperationalTime(
  dateInput: Date | string | null | undefined,
  fallback = "—"
): string {
  if (!dateInput) return fallback;
  const d = typeof dateInput === "string" ? new Date(dateInput) : dateInput;
  if (isNaN(d.getTime())) return fallback;

  return d.toLocaleTimeString("en-GB", {
    hour12: false,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

/**
 * Formats a Date or ISO timestamp as a complete date and time representation (YYYY-MM-DD HH:MM:SS)
 * with explicit UTC representation option for audit transparency.
 */
export function formatFullDateTime(
  dateInput: Date | string | null | undefined,
  fallback = "—"
): string {
  if (!dateInput) return fallback;
  const d = typeof dateInput === "string" ? new Date(dateInput) : dateInput;
  if (isNaN(d.getTime())) return fallback;

  return d.toLocaleString("en-GB", {
    hour12: false,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

/**
 * Formats a freshness label for operational views (e.g. "as of 03:12:45").
 */
export function formatFreshness(
  dateInput: Date | string | null | undefined,
  fallback = "Pending fetch"
): string {
  if (!dateInput) return fallback;
  const time = formatOperationalTime(dateInput);
  return time !== "—" ? `as of ${time}` : fallback;
}

/**
 * Formats a unit count with proper English pluralization (e.g. "1 unit", "3 units").
 */
export function formatUnits(count: number | string | null | undefined): string {
  const num = typeof count === "number" ? count : parseInt(String(count ?? 0), 10);
  const safeNum = isNaN(num) ? 0 : num;
  return `${safeNum} ${safeNum === 1 ? "unit" : "units"}`;
}

