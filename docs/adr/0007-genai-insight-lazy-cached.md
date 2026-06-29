# ADR-0007: GenAI insight generation — lazy-cached vs precompute-all

- **Status:** Accepted
- **Related:** `hld.md` §12 DR-6, §13 (AI integration), §17 (security)

## Context
Each top-k response carries a one-line grounded NL insight. When is it generated — lazily on first
view, or precomputed for every series in the batch?

## Options

### A — Lazy + cached (chosen)
Generate on first view, then cache with the response.
- **Pros:** pay only for **viewed** tenants; bounded cost (NFR5).
- **Cons:** a synchronous (bounded) model call on the cold path → needs timeout + fallback.

### B — Precompute all in batch
Generate for every series during the forecast batch.
- **Pros:** always present, no cold-path call.
- **Cons:** ~150M generations/day — wasteful and expensive for content most tenants never view.

## Decision
**Lazy + cached**, small model, with a **deterministic template fallback** on timeout/breaker. The
template (`TemplateInsightGenerator`) is the always-present floor; Bedrock is the upgrade.

## Why (requirements & assumptions)
Cost-efficiency (**NFR5**): viewing is sparse relative to the series fan-out (A5), so lazy generation
bounds spend to what's actually seen.

## If the assumption changes
If insights must be present pre-view (e.g. emailed digests), precompute **for that cohort only**.

## Consequences
Insight is grounded — the model verbalizes only provided numbers, output is validated, category names
are treated as untrusted (prompt-injection), and the call never blocks the response (`hld.md` §17).
