import { useEffect, useRef, useState } from "react";
import type { Query, TopKResponse, UiConfig } from "./types";
import { getConfig, getTenants, getTopCategories } from "./api";
import { formatAsOf, scopeLabel } from "./format";
import { Controls } from "./components/Controls";
import { StatusBadge } from "./components/StatusBadge";
import { DegradedBanner } from "./components/DegradedBanner";
import { InsightLine } from "./components/InsightLine";
import { RankTable } from "./components/RankTable";
import { ForecastChart } from "./components/ForecastChart";

// Used when GET /api/v1/config can't be reached, so the SPA still renders honest controls
// (parity with app.js FALLBACK_CONFIG).
const FALLBACK_CONFIG: UiConfig = {
  kOptions: [5, 7, 10],
  kDefault: 10,
  windowOptions: ["week", "month", "year"],
  windowDefault: "month",
  channelOptions: ["all", "online", "offline"],
  channelDefault: "all",
};

type View = "loading" | "results" | "empty" | "error";

export default function App() {
  const [config, setConfig] = useState<UiConfig>(FALLBACK_CONFIG);
  const [tenants, setTenants] = useState<string[]>([]);
  const [query, setQuery] = useState<Query | null>(null);

  const [view, setView] = useState<View>("loading");
  const [response, setResponse] = useState<TopKResponse | null>(null);
  const [errorMsg, setErrorMsg] = useState<string>("");

  const bootstrapped = useRef(false);

  // Fetch a top-k response for an explicit query and drive the view state machine. Never throws —
  // failures render the error state (reads never fail closed; parity with app.js).
  async function load(q: Query) {
    if (!q.tenantId.trim()) {
      setErrorMsg("Tenant id is required.");
      setResponse(null);
      setView("error");
      return;
    }
    setView("loading");
    try {
      const body = await getTopCategories(q);
      setResponse(body);
      setView(Array.isArray(body.items) && body.items.length > 0 ? "results" : "empty");
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err));
      setResponse(null);
      setView("error");
    }
  }

  // One-shot bootstrap: load the tenant catalog + control config (each with a fallback), seed the
  // initial query, then auto-load once so the demo shows data immediately.
  useEffect(() => {
    if (bootstrapped.current) return; // guard StrictMode double-invoke in dev
    bootstrapped.current = true;

    (async () => {
      const [tenantsRes, configRes] = await Promise.allSettled([getTenants(), getConfig()]);

      const ids =
        tenantsRes.status === "fulfilled" && tenantsRes.value.tenants?.length
          ? tenantsRes.value.tenants
          : ["tenant_a"];
      const cfg =
        configRes.status === "fulfilled" && Array.isArray(configRes.value.kOptions)
          ? configRes.value
          : FALLBACK_CONFIG;

      setTenants(ids);
      setConfig(cfg);

      const initial: Query = {
        tenantId: ids[0] ?? "tenant_a",
        mode: "actuals",
        window: cfg.windowDefault,
        channel: cfg.channelDefault,
        k: cfg.kDefault,
      };
      setQuery(initial);
      void load(initial);
    })();
  }, []);

  function onChange(patch: Partial<Query>) {
    setQuery((q) => (q ? { ...q, ...patch } : q));
  }

  // Reload immediately when the tenant changes — convenient for flipping between demo tenants
  // (parity with app.js).
  function onTenantChange(tenantId: string) {
    setQuery((q) => {
      if (!q) return q;
      const next = { ...q, tenantId };
      void load(next);
      return next;
    });
  }

  function onSubmit() {
    if (query) void load(query);
  }

  const showMeta = response != null && (view === "results" || view === "empty");

  return (
    <main className="wrap">
      <header className="hero">
        <h1>Top Sales by Category</h1>
        <p className="sub">Thin, read-only view over the REST API.</p>
      </header>

      {query && (
        <Controls
          tenants={tenants}
          config={config}
          value={query}
          loading={view === "loading"}
          onChange={onChange}
          onTenantChange={onTenantChange}
          onSubmit={onSubmit}
        />
      )}

      {showMeta && response && (
        <>
          <StatusBadge status={response.status} scope={scopeLabel(response)} asOf={formatAsOf(response.asOf)} />
          <InsightLine insight={response.insight} />
          <DegradedBanner status={response.status} />
        </>
      )}

      {view === "loading" && <div className="state">Loading…</div>}
      {view === "empty" && <div className="state">No categories for this selection.</div>}
      {view === "error" && <div className="state state-error">{errorMsg}</div>}

      {view === "results" && response && (
        <section>
          <ForecastChart items={response.items} />
          <RankTable items={response.items} />
        </section>
      )}
    </main>
  );
}
