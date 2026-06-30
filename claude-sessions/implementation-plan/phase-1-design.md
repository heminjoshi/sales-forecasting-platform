# Phase 1 — Design Artifacts (WS-A)

> **Objective (from `private/Build-Delivery-Plan-v3.md`):** the design is fully written so code + deck
> derive from it. **Acceptance:** the LLD lets a stranger implement API + UI; OpenAPI validates;
> **all 9 ADRs use the comparative format** (heavily scored).

The full, approved execution plan for this phase lives at
`~/.claude/plans/take-a-look-at-groovy-hammock.md` (drop-in HLD + deep-dive, author LLD, OpenAPI, 9
comparative ADRs, diagrams, runbook — delivered as three staged PRs).

## Outcome
✅ **Complete.** Delivered via PRs #2 (HLD+deep-dive+LLD, merged), #3 (OpenAPI, merged), #4
(ADRs+diagrams+runbook, open). See the handoff:
[`../handoff-docs/phase-1-handoff.md`](../handoff-docs/phase-1-handoff.md).

## What was produced (public `docs/`)
- `docs/hld.md`, `docs/component-deep-dive.md` — drop-ins of the private design docs (sanitized).
- `docs/lld.md` — implementation contract (DDL, `/api/v1`, seams, read pipeline + degradation, cache,
  idempotency, profiles, UI contract, errors).
- `docs/api/openapi.yaml` — OpenAPI 3.1, passes `redocly lint`.
- `docs/adr/0001-0009` (+ `README.md`) — 9 comparative ADRs.
- `docs/diagrams/` — architecture, data-flow, erd, read-sequence, ui-flow (Mermaid).
- `docs/runbook.md`.

## Out of scope / deferred
- PNG diagram exports (Mermaid renders natively on GitHub).
- Phase 2 code — begins from `docs/lld.md` §2 (Flyway DDL) + `docs/api/openapi.yaml`.
