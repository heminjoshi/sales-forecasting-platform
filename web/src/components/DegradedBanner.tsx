import type { Status } from "../types";

// Honest notice above the results when the API can't serve a genuine forecast (parity with app.js
// renderBanner). degraded => loud warning (on-the-fly estimate); pending => soft note (actuals stand-in).
// Any other status renders nothing.
export function DegradedBanner({ status }: { status: Status }) {
  const s = (status || "").toLowerCase();
  if (s === "degraded") {
    return (
      <div className="banner banner-degraded">
        ⚠ Forecasts unavailable — showing an on-the-fly seasonal-naive estimate (low confidence).
      </div>
    );
  }
  if (s === "pending") {
    return <div className="banner banner-pending">Forecast not yet computed — showing recent actuals.</div>;
  }
  return null;
}
