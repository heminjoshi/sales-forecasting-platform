// Formatting helpers ported verbatim in behaviour from the static app.js, so the SPA renders figures
// identically. All date math is done in UTC / from the raw string to avoid viewer-timezone shifts.

import type { TopKResponse } from "./types";

const MONTHS = [
  "Jan", "Feb", "Mar", "Apr", "May", "Jun",
  "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

export function formatCurrency(value: number | string): string {
  const n = Number(value);
  if (!isFinite(n)) return String(value);
  return n.toLocaleString(undefined, { style: "currency", currency: "USD" });
}

export function formatDelta(d: number | string): string {
  const n = Number(d);
  if (!isFinite(n)) return "";
  const sign = n > 0 ? "+" : "";
  return sign + (n * 100).toFixed(1) + "%";
}

// Render an ISO instant as "as of 28 Jun 2026, 06:00" (UTC). Falls back to the raw string if unparsable.
export function formatAsOf(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d.getTime())) return "as of " + iso;
  const hh = String(d.getUTCHours()).padStart(2, "0");
  const mm = String(d.getUTCMinutes()).padStart(2, "0");
  return (
    "as of " + d.getUTCDate() + " " + MONTHS[d.getUTCMonth()] + " " + d.getUTCFullYear() +
    ", " + hh + ":" + mm
  );
}

function cap(s: string): string {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

// Format a "YYYY-MM-DD" date string without going through Date() (avoids browser-timezone shifts).
function fmtDate(s: string, withYear: boolean): string {
  if (!s) return "";
  const [y, m, d] = s.split("-").map(Number);
  return MONTHS[(m as number) - 1] + " " + d + (withYear ? ", " + y : "");
}

// "Actuals · Month · All — May 30 – Jun 28, 2026" from the response's own fields.
export function scopeLabel(body: TopKResponse): string {
  const parts = [cap(body.mode), cap(body.window), cap(body.channel)].filter(Boolean).join(" · ");
  const range =
    body.windowFrom && body.windowTo
      ? fmtDate(body.windowFrom, false) + " – " + fmtDate(body.windowTo, true)
      : "";
  return range ? parts + " — " + range : parts;
}
