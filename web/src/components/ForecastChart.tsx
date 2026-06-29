import { BarChart, Bar, XAxis, YAxis, CartesianGrid, ErrorBar, ResponsiveContainer } from "recharts";
import type { TopKItem } from "../types";
import { formatCurrency } from "../format";

// One bar per category. When genuine forecast items carry a prediction interval, draw it as a
// vertical whisker (low→high) via Recharts <ErrorBar>. The error datum is the asymmetric offset pair
// [value - low, high - value], so the whisker spans the actual interval bounds centred on the bar.
// Absent for actuals/pending/degraded → plain bars (parity with the static dashboard).
interface Datum {
  category: string;
  value: number;
  errorY?: [number, number];
}

export function ForecastChart({ items }: { items: TopKItem[] }) {
  const hasInterval = (it: TopKItem): it is TopKItem & { interval: { low: number; high: number } } =>
    it.interval != null &&
    it.interval.low !== undefined &&
    it.interval.low !== null &&
    it.interval.high !== undefined &&
    it.interval.high !== null;

  const anyInterval = items.some(hasInterval);

  const data: Datum[] = items.map((it) => {
    const value = Number(it.value);
    if (hasInterval(it)) {
      const low = Number(it.interval.low);
      const high = Number(it.interval.high);
      return { category: it.category, value, errorY: [value - low, high - value] };
    }
    return { category: it.category, value };
  });

  // Stretch the axis so the highest whisker isn't clipped at the top of a bar.
  const maxVal = data.reduce((m, d) => Math.max(m, d.value, d.errorY ? d.value + d.errorY[1] : 0), 0);
  const yMax = maxVal > 0 ? Math.ceil(maxVal * 1.05) : 0;
  const yDomain: [number, number | string] = yMax ? [0, yMax] : [0, "auto"];

  return (
    <div className="chart-box">
      <ResponsiveContainer width="100%" height={240}>
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
          <CartesianGrid stroke="#2a3050" vertical={false} />
          <XAxis
            dataKey="category"
            tick={{ fill: "#9aa0b8", fontSize: 12 }}
            tickLine={false}
            axisLine={{ stroke: "#2a3050" }}
          />
          <YAxis
            domain={yDomain}
            width={72}
            tick={{ fill: "#9aa0b8", fontSize: 12 }}
            tickLine={false}
            axisLine={{ stroke: "#2a3050" }}
            tickFormatter={(v: number) => formatCurrency(v)}
          />
          <Bar dataKey="value" fill="#6c8cff" radius={[4, 4, 0, 0]} isAnimationActive={false}>
            {anyInterval && (
              <ErrorBar dataKey="errorY" width={5} strokeWidth={1.5} stroke="#e7e9f3" direction="y" />
            )}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
