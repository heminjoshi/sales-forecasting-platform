# ADR-0009: UI hosting — Spring-served vs Vercel vs S3+CloudFront

- **Status:** Accepted
- **Related:** `hld.md` §7, §12 DR-8, §17 (CORS)

## Context
The presentation tier is a thin, read-only dashboard. How is it hosted for the **live demo** and for
**production**?

## Options

### A — Static dashboard served by Spring Boot (demo run)
Static HTML + vanilla JS + Chart.js (CDN) from Spring static resources.
- **Pros:** single deployable, no Node toolchain, runs offline → reliable live demo.
- **Cons:** not a production-grade SPA.

### B — React SPA on Vercel (production deploy)
- **Pros:** git-push deploys, preview URLs, TLS + CDN, zero infra.
- **Cons:** cross-origin (needs **CORS**); third-party origin (not single-cloud).

### C — React SPA on S3 + CloudFront (AWS-native alternative)
- **Pros:** single-cloud, can be same-origin, in-account.
- **Cons:** more infra to write (bucket, distribution, OAC, cache policies).

## Decision
**Serve static from Spring Boot for the live demo; Vercel for the production deploy; S3+CloudFront
documented as the AWS-native alternative** (not built).

## Why (requirements & assumptions)
Demo reliability + portfolio speed. It's a **config decision, not an architecture change** — same
static build, same API. The API allow-lists the UI origin via CORS (`localhost` + Vercel).

## If the assumption changes
If single-cloud is a hard requirement → **S3+CloudFront** (same-origin, skips CORS). The CDK adds no
SPA hosting resources today; it only allow-lists the Vercel origin.

## Consequences
The live demo has **no internet dependency** (local Spring-served dashboard); Vercel is the
"and here it is in prod" link, not a demo dependency. The UI stays intentionally thin so the deep-dive
stays on forecasting/serving/AI.
