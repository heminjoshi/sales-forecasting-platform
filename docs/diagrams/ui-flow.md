# UI flow — dashboard states

The dashboard is a thin, read-only view: control change → fetch → render, with explicit
loading/empty/error/degraded states. It renders whatever `status` the API returns — never crashes on
partial data. Contract in [`../lld.md`](../lld.md) §13.

```mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Loading: change mode / window / k
    Loading --> Rendered: 200 + items
    Loading --> Empty: 200 + items = []
    Loading --> Error: 4xx / 5xx / network

    Rendered --> Loading: control change
    Empty --> Loading: control change
    Error --> Loading: retry

    state Rendered {
        [*] --> Badge
        Badge --> Fresh: status=fresh
        Badge --> Stale: status=stale
        Badge --> Pending: status=pending (actuals shown)
        Badge --> Degraded: status=degraded (fallback)
    }
```

**Rendered view:** ranked table (rank · category · value · Δ vs prior · confidence), forecast-vs-actual
chart (Chart.js CDN), the `insight` line, and a status badge + `asOf`. `interval`/`confidence` columns
are hidden in `actuals`/`pending`.
