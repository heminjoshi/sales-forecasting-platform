import type { TopKItem } from "../types";
import { formatCurrency, formatDelta } from "../format";

// Whether any item carries a forecast-only field — drives column visibility (parity with app.js).
function present(items: TopKItem[], key: keyof TopKItem): boolean {
  return items.some((it) => it[key] !== undefined && it[key] !== null);
}

// Ranked table: rank, category, value, Δ vs prior, confidence chip, interval.
// deltaVsPrior / confidence / interval columns are hidden when absent (actuals/pending).
// Category names render as JSX text => escaped (untrusted input, injection-safe).
export function RankTable({ items }: { items: TopKItem[] }) {
  const flags = {
    delta: present(items, "deltaVsPrior"),
    conf: present(items, "confidence"),
    interval: present(items, "interval"),
  };

  return (
    <table className="table">
      <thead>
        <tr>
          <th className="num">#</th>
          <th>Category</th>
          <th className="num">Value</th>
          {flags.delta && <th className="num col-delta">Δ vs prior</th>}
          {flags.conf && <th className="col-conf">Confidence</th>}
          {flags.interval && <th className="col-interval">Interval</th>}
        </tr>
      </thead>
      <tbody>
        {items.map((it) => {
          const hasDelta = it.deltaVsPrior !== undefined && it.deltaVsPrior !== null;
          const deltaUp = hasDelta && Number(it.deltaVsPrior) >= 0;
          const hasInterval =
            it.interval != null &&
            it.interval.low !== undefined &&
            it.interval.high !== undefined;

          return (
            <tr key={it.rank}>
              <td className="num">{it.rank}</td>
              <td>{it.category}</td>
              <td className="num">{formatCurrency(it.value)}</td>

              {flags.delta && (
                <td className={"num col-delta " + (hasDelta ? (deltaUp ? "delta-up" : "delta-down") : "")}>
                  {hasDelta ? formatDelta(it.deltaVsPrior as number) : ""}
                </td>
              )}

              {flags.conf && (
                <td className="col-conf">
                  {it.confidence ? (
                    <span className={"chip conf-" + it.confidence}>{it.confidence}</span>
                  ) : null}
                </td>
              )}

              {flags.interval && (
                <td className="col-interval">
                  {hasInterval && it.interval
                    ? formatCurrency(it.interval.low) + " – " + formatCurrency(it.interval.high)
                    : ""}
                </td>
              )}
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
