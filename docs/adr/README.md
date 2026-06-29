# Architecture Decision Records

Each ADR records one major decision in a **comparative** format —
*context → options (pros/cons) → decision → why (requirement/assumption) → if-the-assumption-changes
→ consequences* — so the trade-off, not just the outcome, is on the record. Full architecture in
[`../hld.md`](../hld.md); implementation in [`../lld.md`](../lld.md).

| ADR | Decision | Chosen |
|---|---|---|
| [0001](0001-forecast-compute-strategy.md) | Where/when forecasts are computed (the top-level fork) | **A — Precompute / batch** |
| [0002](0002-model-coupling-data-plane-vs-rpc.md) | Java ↔ model coupling | **Data-plane (serving table)** |
| [0003](0003-aggregate-store-postgres-vs-dynamodb.md) | Aggregate store | **Aurora Postgres** (+ KV serving) |
| [0004](0004-serving-store-kv-point-lookup.md) | Serving store shape | **KV point-lookup, versioned** |
| [0005](0005-forecasting-model-baseline-vs-ml.md) | Forecasting model | **Java baseline** (ML behind seam) |
| [0006](0006-accuracy-metric-wape-vs-mape.md) | Accuracy metric | **WAPE** (+ bias) |
| [0007](0007-genai-insight-lazy-cached.md) | GenAI insight generation | **Lazy + cached** (template floor) |
| [0008](0008-read-modes-forecast-and-actuals.md) | Read modes | **Two modes** (forecast + actuals) |
| [0009](0009-ui-hosting-spring-vercel-cloudfront.md) | UI hosting | **Spring-served demo + Vercel prod** |
| [0010](0010-channel-as-first-class-dimension.md) | Channel — key dimension vs post-filter | **First-class key dimension** (default `all`) |
