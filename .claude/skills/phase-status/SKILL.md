---
name: phase-status
description: Report build progress against the Build-Delivery-Plan phases (0–10) and recommend what to do next. Use when the user asks "where are we", "what's next", "phase status", or wants to plan the next chunk of work on the sales-forecasting platform.
---

# Phase status

Assess where the repo stands against the private planning/design docs under `private/` and recommend the next step.

## Steps

1. **Read the plan** (`private/Build-Delivery-Plan-and-Repo-Structure.md`) for the phase list, exit criteria, and milestone map. Also read the **HLD v2 design doc** (in `private/`) — v2 adds the user-facing dashboard (presentation tier) as a built component; fold the dashboard into the relevant phase's expectations even though the original plan text predates it.

2. **Inspect the actual repo state** to judge each phase against its exit criteria — don't trust the plan's status markers, verify:
   - Is it a git repo yet? CI present (`.github/workflows/`)?
   - `service/` scaffolded? Which `topsales-*` modules exist and compile?
   - `infra/` CDK present? `local/docker-compose.yml`? `Makefile`? `docs/` artifacts? `data/`, `postman/`, `presentation/`?
   - Dashboard present (static HTML/JS + Chart.js under Spring Boot static resources)? Does it render table + chart + insight + status badge?
   - Tests present (Testcontainers)? Migrations?

3. **Map each phase (0–10) to a status:** done / in-progress / not-started, with one line of evidence each (what exists or is missing).

4. **Identify the current milestone** (DEMO-READY 0–2, FORECAST-READY 3–4, AI-READY 5, PROD-SHAPED 6–8, PRESENTATION-READY 9, PUBLIC 10) and the next unmet exit criterion.

## Output

- A compact phase table: phase → status → evidence.
- Current milestone and the gap to the next one.
- A short, ordered "do next" list (respecting the plan's tight-on-time sequencing: 0 → 1(LLD) → 2 → 3 → 4 → 5 → 9, then 6 → 7 → 8 → 10).
