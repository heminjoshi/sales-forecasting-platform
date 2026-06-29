// Wire types mirroring the Java DTOs (service/topsales-common/.../api). All enums serialize to their
// lowercase wire form except Confidence, which is uppercase (matches the DDL CHECK + §13 example).

export type Mode = "actuals" | "forecast";
export type Window = "week" | "month" | "year";
export type Channel = "all" | "online" | "offline";

// Status enum (Status.java) — drives the freshness badge. fresh=green stale=amber degraded=orange pending=grey.
export type Status = "fresh" | "stale" | "pending" | "degraded";

// Confidence enum (Confidence.java) — uppercase on the wire. Forecast-only.
export type Confidence = "HIGH" | "MEDIUM" | "LOW";

// Interval.java — forecast prediction interval. Numbers are JSON-serialized BigDecimals.
export interface Interval {
  low: number;
  high: number;
}

// TopKItem.java — one ranked category. deltaVsPrior/confidence/interval are forecast-only (null in
// actuals/pending), so they are optional/nullable here and the UI hides those columns when absent.
export interface TopKItem {
  rank: number;
  category: string;
  value: number;
  deltaVsPrior?: number | null;
  confidence?: Confidence | null;
  interval?: Interval | null;
}

// TopKResponse.java — the read response. Always carries a status + asOf (reads never fail closed).
// windowFrom/windowTo are inclusive ISO dates (YYYY-MM-DD). insight is the grounded NL line.
export interface TopKResponse {
  tenantId: string;
  mode: Mode;
  window: Window;
  channel: Channel;
  k: number;
  status: Status;
  asOf: string;
  windowFrom?: string | null;
  windowTo?: string | null;
  insight?: string | null;
  items: TopKItem[];
}

// ConfigController.UiConfig — drives the control selects. Option lists are lowercase wire values.
export interface UiConfig {
  kOptions: number[];
  kDefault: number;
  windowOptions: string[];
  windowDefault: string;
  channelOptions: string[];
  channelDefault: string;
}

// TenantsResponse.java — the tenant picker catalog.
export interface TenantsResponse {
  tenants: string[];
}

// The control selection the user drives.
export interface Query {
  tenantId: string;
  mode: Mode;
  window: string;
  channel: string;
  k: number;
}
