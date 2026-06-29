import type { Status } from "../types";

// The freshness meta row: colour-coded status badge + scope label + human-readable as-of.
// Badge colour comes from the `.badge.<status>` CSS rules (fresh=green stale=amber degraded=orange
// pending=grey) — parity with the static dashboard.
export function StatusBadge({
  status,
  scope,
  asOf,
}: {
  status: Status;
  scope: string;
  asOf: string;
}) {
  const s = (status || "unknown").toLowerCase();
  return (
    <section className="meta">
      <span className={"badge " + s}>{s}</span>
      <span className="scope">{scope}</span>
      <span className="asof">{asOf}</span>
    </section>
  );
}
