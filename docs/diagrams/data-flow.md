# Data flow — write, forecast, read

The three end-to-end flows. Data shapes: `SaleEvent` → `AggregateRow` → `ForecastRow` →
`TopKResponse` (see [`../hld.md`](../hld.md) §11). Edge catalog in [`../component-deep-dive.md`](../component-deep-dive.md) §E.

## Write (ingestion)
```mermaid
flowchart LR
    U["Upstream"] -->|SaleEvent| K["Stream / POST /events"]
    K --> C["Consumer"]
    C -->|"dedupe on idempotencyKey"| G{"new?"}
    G -- no --> X["skip (deduped)"]
    G -- yes --> A["additive upsert → aggregates"]
    G -- yes --> R["append → raw log (SoT)"]
    C -. malformed .-> D["DLQ / quarantine"]
```

## Forecast (batch)
```mermaid
flowchart LR
    CR["cron"] --> F["Forecaster Job"]
    AGG["aggregates"] --> F
    F -->|"fit per (tenant,category) × horizon"| RK["rank top-k"]
    RK -->|"write version N + flip pointer"| SV["serving_rows (versioned)"]
    F -->|"predicted vs actual"| EV["Eval/Drift (WAPE, bias)"]
```

## Read (forecast mode, happy path)
```mermaid
flowchart LR
    UI["UI"] --> S["Service"]
    S --> T["TenantScopeFilter"]
    T --> H{"Redis hit?"}
    H -- yes --> RET["return (cached)"]
    H -- no --> P["ForecastProvider → serving_rows (active version)"]
    P --> I["InsightGenerator (lazy + cache)"]
    I --> POP["assemble + populate Redis"]
    POP --> RET2["return status=fresh + asOf"]
```
