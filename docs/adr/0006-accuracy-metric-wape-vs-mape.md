# ADR-0006: Accuracy metric — WAPE vs MAPE

- **Status:** Accepted
- **Related:** `hld.md` §12 DR-5, §13 (eval/drift)

## Context
The forecaster needs a primary accuracy metric for backtests, drift detection, and champion/
challenger gating. Long-tail categories have many near-zero-sales periods.

## Options

### A — WAPE (chosen)
`Σ|actual − forecast| / Σ|actual|`.
- **Pros:** scale-aware; robust to zero/near-zero periods; aggregates cleanly across series.
- **Cons:** less intuitive than a per-point percentage.

### B — MAPE
`mean(|actual − forecast| / |actual|)`.
- **Pros:** familiar, per-point percentage.
- **Cons:** **explodes on near-zero sales** (common in long-tail categories) → misleading.

## Decision
**WAPE**, reported alongside **bias** (to catch systematic over/under-forecasting).

## Why (requirements & assumptions)
Long-tail categories routinely have near-zero periods where MAPE is unstable; WAPE stays meaningful
and is the basis for drift thresholds.

## If the assumption changes
For per-series business reporting where a percentage is expected, MAPE/SMAPE can be shown as a
secondary display metric — but gating stays on WAPE + bias.

## Consequences
Eval/Drift emits rolling WAPE + bias per segment; threshold breaches trigger refit/alert.
