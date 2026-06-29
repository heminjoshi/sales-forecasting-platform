# ADR-0005: Forecasting model — Java baseline vs Python/SageMaker ML

- **Status:** Accepted
- **Related:** `hld.md` §12 DR-4, §13; ADR-0002

## Context
What computes the forecasts for v1 — an in-house Java baseline, or a Python/SageMaker global ML model?

## Options

### A — Java baseline (built)
Holt-Winters (level/trend/seasonal) + seasonal-naive, arithmetic.
- **Pros:** single-language, runnable, interpretable; no training infra; strong on regular seasonal
  series; doubles as the in-process degradation fallback.
- **Cons:** weaker on sparse/irregular series.

### B — Python/SageMaker global model (designed)
DeepAR / GBDT on lag + calendar features.
- **Pros:** cross-series learning; better cold-start and sparse-series accuracy.
- **Cons:** training infrastructure; lower interpretability; cross-language operational surface.

## Decision
**Baseline now, ML behind the same `Forecaster` interface**, promoted **per-segment** when accuracy
data justifies it. Croston for intermittent demand is noted but **not built**.

## Why (requirements & assumptions)
A well-scoped, single-language v1 is lower-risk and fully runnable; the **A5** fan-out is
parallelizable in batch regardless of model. The interface (ADR-0002) makes the ML upgrade a drop-in.

## If the assumption changes
If accuracy data (WAPE, ADR-0006) shows broad baseline failure → promote ML more widely; champion/
challenger gates each promotion.

## Consequences
The same `Forecaster` seam backs both the baseline and the (designed) ML model and the read-path
degradation fallback — no serving-contract change when the model is swapped.
