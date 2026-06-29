// The grounded NL insight line (Phase 5). Rendered as text (never HTML) so category names embedded
// in the line stay escaped. Hidden when the API returns no insight.
export function InsightLine({ insight }: { insight?: string | null }) {
  if (!insight) return null;
  return <p className="insight">{insight}</p>;
}
