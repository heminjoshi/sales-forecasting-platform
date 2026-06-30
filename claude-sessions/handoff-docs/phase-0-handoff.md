# Phase 0 — Handoff

> **Status:** ✅ Complete & verified. Hand-off for whoever picks up **Phase 1**.
> **Date:** 2026-06-28 · **Private/gitignored** — may reference employer/interview specifics.

## TL;DR
The repo skeleton is live: Maven multi-module project (compiles), local Docker stack
(Postgres + Redis, healthy), Makefile, GitHub Actions CI (green), MIT license, README skeleton,
and a **private** GitHub repo. The walking skeleton (Phase 1 design → Phase 2 code) can start
immediately against a known-good baseline.

## References (read these first)
- **Implementation plan (per-phase):** [`../implementation-plan/README.md`](../implementation-plan/README.md) · Phase 0 detail: [`../implementation-plan/phase-0-foundations.md`](../implementation-plan/phase-0-foundations.md)
- **Delivery plan (what/when):** `private/Build-Delivery-Plan-v2.md`
- **Design source-of-truth (architecture):** `private/Design-Doc-v3-Consolidated.md` (v3, supersedes HLD v2; has the component deep-dive, data-flow catalog, and data shapes)
- **Approved Phase 0 execution plan:** `~/.claude/plans/validated-launching-garden.md`
- **Repo guidance:** `CLAUDE.md` (tracked) · `CLAUDE.local.md` (private)

## What shipped in Phase 0
- **`service/`** — Maven multi-module, **Spring Boot 4.1.0**, base pkg `com.topsales`:
  `topsales-common`, `topsales-ingestion`, `topsales-forecast`, `topsales-insight` (libraries),
  `topsales-api` (bootable: `@SpringBootApplication`, `spring-boot-starter-web`, empty server).
  Each library has a `package-info.java` stub.
- **`local/docker-compose.yml`** — Postgres 16 + Redis 7 with healthchecks; `local/init.sql` placeholder.
- **`Makefile`** — `up · down · run · test · seed · demo · synth` (seed/demo/synth are Phase 8/7 placeholders).
- **`.github/workflows/`** — `ci.yml` (Temurin 21 build+test), `infra.yml` (green placeholder until Phase 7 CDK).
- **`LICENSE`** (MIT), **`README.md`** skeleton + CI badge, full public-repo dir tree (`infra web docker data postman presentation docs ...`).

## Locked decisions (carry forward)
| # | Decision | Value |
|---|---|---|
| Build | Maven multi-module, base pkg `com.topsales` | — |
| Spring Boot | **4.1.0** (GA, supported to 2027) | pinned in `service/pom.xml` |
| Java | **target 21** (`<java.version>21</java.version>`) | local JDK is **26** (Boot 4.1 supports ≤26); CI pins Temurin **21** |
| Repo | `heminjoshi/sales-forecasting-platform` | **private** now; flips public in **Phase 10** |
| Local stack | Postgres 16, Redis 7 | docker-compose |
| UI prod hosting | React SPA on **Vercel**; S3+CloudFront = AWS-native alt (not built) | CORS allow-list needed |

## How to build / run / verify
```bash
cd service && mvn -B verify            # all 6 modules compile + build (green)
make up                                # Postgres + Redis (wait for healthy)
make run                               # API boots on http://localhost:8080 (HTTP 404, no endpoints yet)
make down                              # tear down
```

## Gotchas / non-obvious
- **`make run` is two-step** (install deps, then `spring-boot:run -pl topsales-api`). Running the goal
  across the whole reactor fails on the parent/library modules ("Unable to find a suitable main class").
- **JDK 26 locally, target 21:** `--release 21` blocks post-21 APIs; CI on Temurin 21 is the source of truth.
- **`/export` runs the built-in**, not the custom skill — it writes a readable `.txt` to the repo root
  (a leak risk), *not* `claude-sessions/export/`. Open item: rename the skill (e.g. `/save-session`) if
  reliable `.jsonl` export is wanted.
- **Hygiene model:** `private/`, `claude-sessions/`, `interview-prep/` are gitignored and must never be
  committed; no employer name in tracked files. `/public-repo-check` enforces this before going public.

## Git / PR state
- `main`: Phase 0 scaffold (`259151e`) + CI badge (`6a8204f`), pushed.
- **PR #1** (`docs/sync-design-v3` → `main`): doc-sync to Design Doc v3, **CI green**, **open / not yet merged**.
  → <https://github.com/heminjoshi/sales-forecasting-platform/pull/1>

## Next: Phase 1 — Design artifacts (WS-A)
Per the delivery plan: `docs/lld.md` (schemas/DDL, API contract, interfaces, cache keys, degradation,
sizing, UI contract), `docs/adr/0001..0008` (incl. DR-8 UI split), `docs/api/openapi.yaml`,
`docs/diagrams/` (architecture, data-flow, ERD, read-sequence, ui-flow), `docs/runbook.md`.
Derive these from `private/Design-Doc-v3-Consolidated.md` (keep public docs **generic**).
Start by drafting `../implementation-plan/phase-1-design.md`.

## Open items / decisions pending
- [ ] Merge PR #1 (squash) + decide branch-per-phase vs. work-on-main going forward.
- [ ] Rename `/export` skill so it reliably targets `claude-sessions/export/` (optional).
- [ ] Lock the PR-body template as the standard (Summary · Context · What changed · Tests · Hygiene · Out-of-scope).
- [ ] Off-repo (WS-G): reply to recruiter with onsite blocks + prep-call times.
