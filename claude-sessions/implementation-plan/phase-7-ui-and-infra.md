# Phase 7 — UI production path & AWS CDK infra validation  `[WS-C, WS-D]`

> Approved exec plan (driven via `/implement-phase 7`). Delivered as **two PRs**, each driven by a
> **deterministic 3-stage Workflow** (foundation → parallel work-streams → verify).

## Objective & acceptance

Give the service a real cross-origin **production UI** and the **AWS CDK** infra the design has only
described. **Acceptance (verbatim):** "`cdk synth` green + asserted; images build; SPA deploys to
Vercel; CORS configured."

## Current state (audited)

Mostly greenfield despite the plan's `✅` on "5-stack CDK":
- `infra/` and `web/` are **`.gitkeep` only** — no CDK, no SPA, no toolchain anywhere.
- **CORS documented only** (lld §11, ADR-0009) — no code, nothing in `application.yml`. Dashboard is
  same-origin (Spring-served static `index.html`/`app.js`/`styles.css`, Chart.js CDN) and **stays the
  live demo**.
- `.github/workflows/infra.yml` green placeholder; Makefile `synth:` stub; `docker/` empty.
- No `[P7]` test cases; `test-plan/` phase-gating stops at `[P6]`.
- **Env:** Node 24.16 / npm 11 / Docker 29 present → `cdk synth`, `npm run build`, `docker build` are
  locally verifiable. Testcontainers `*IT`s remain CI-only on this host.

## Decisions locked

- **UI:** build a **real React SPA** (Vite + React + Recharts + TS) in `web/` → Vercel (git-push). The
  Spring static dashboard stays the demo unchanged (demo needs no Node). SPA is now *built*, the
  cross-origin prod path. S3+CloudFront stays documented-only (DR-8).
- **Delivery:** two PRs — **PR1 = WS-C** (CORS + React SPA + UI tests), **PR2 = WS-D** (CDK + infra/test
  + docker + CI). Two separate Workflows.
- **CDK:** account-agnostic, **synth-only** (no `env`/`fromLookup`/deploy/creds). Pin `aws-cdk-lib` +
  CLI to the same exact current-stable v2 (≈2.177.x) so the Bedrock VPC-endpoint enum + L1 `CfnGuardrail`
  exist (the "version drift" fix). Use **L1 `CfnGuardrail`** (avoids the churny alpha L2).
- **`micrometer-registry-cloudwatch2`:** stays docs-only / designed (not a dependency) — would
  autoconfigure a CloudWatch client hitting the AWS credential chain every boot/CI. CDK Monitoring stack
  is the metric-contract source of truth.
- **Metric-name stability (critical):** Monitoring alarms reference the exact Phase-6 dotted meter names
  from `service/topsales-api/.../metrics/MetricNames.java` — `topsales.read.total`,
  `topsales.insight.fallback.total`, `topsales.forecast.freshness.seconds`,
  `topsales.forecast.provider.faults.total`, `http.server.requests` — pinned by `lib/metric-names.ts` +
  an assertion test.

## Steps

### PR1 — `feat/phase7-ui-cors` (WS-C)
1. **CORS (Java):** `TopsalesProperties` gains `Web web` → `Cors(List<String> allowedOrigins)`;
   `application.yml` `topsales.web.cors.allowed-origins` (localhost:8080, localhost:5173, commented
   Vercel; env-overridable); new `WebCorsConfig` `@Configuration WebMvcConfigurer.addCorsMappings` on
   `/api/**` (GET/HEAD/OPTIONS, headers X-Tenant-Id/X-Request-Id/Accept/Content-Type, expose
   X-Request-Id, no credentials, maxAge 3600). Config-binding unit test. _Preflight is answered downstream
   of `TenantScopeFilter` (a pass-through) → never blocked._
2. **React SPA** `web/` (Vite+React+Recharts+TS): `types.ts` (TopKResponse), `api.ts`
   (`${VITE_API_BASE}/api/v1/...`), components Controls/StatusBadge/DegradedBanner/InsightLine/RankTable/
   ForecastChart (Recharts `<BarChart>`+`<ErrorBar>` interval whiskers). `vercel.json` (Vite preset, SPA
   rewrite), `.env.example` (`VITE_API_BASE`).
3. **Docs+tests:** lld §10/§11/§13; `test-plan/` `IT-CO-01..07`, `CN-14/15`, `MQ-80..83`, README `[P7]`
   bullet; Postman CORS Origin probe.

### PR2 — `feat/phase7-infra` (WS-D)
1. **Scaffold** `infra/` (pinned `package.json` + `cdk.json` + `tsconfig` + `jest.config` +
   `bin/topsales.ts` env-unset + `lib/config.ts` + `lib/metric-names.ts`).
2. **5 stacks** Network (VPC + S3 gateway + interface endpoints incl. typed `BEDROCK_RUNTIME`),
   Storage (S3/Kinesis/DLQ/Aurora/DynamoDB/Redis-L1), Intelligence (Bedrock invoke policy + L1
   `CfnGuardrail` + SSM model-config + SageMaker group/role + ML ECR + artifact bucket), Application
   (3 ECR repos + ECS Fargate serving/consumer/forecaster, `imageTag` git-sha prop, role grants),
   Monitoring (Dashboard + Alarms on the exact metric names).
3. **`infra/test/`** `Template.fromStack` assertions per stack incl. the **metric-name contract gate**
   and imageTag-flow.
4. **docker/** 3 Dockerfiles (multi-stage temurin-21, git-sha tag, build-only); CI `infra.yml` rewrite +
   `docker.yml` + optional `web.yml`; Makefile `synth:` → `cd infra && npm ci && npx cdk synth`.

## Acceptance checklist
- [ ] PR1: `make test` green; `cd web && npm run build` green; CORS Origin probe (allowed→ACAO,
      disallowed→none); static demo unregressed.
- [ ] PR2: `npx cdk synth` green; `npm test` (stack assertions incl. metric-name gate) green;
      `docker build` one image; CI workflows green.
- [ ] `[P7]` cases authored; phase-gating list updated; `*IT`s correct-for-CI.
- [ ] Handoff written; index row ✅; `public-repo-check` clean; PR1 then PR2 opened.

## Outcome
✅ Shipped as **PR #13** (WS-C — CORS + React SPA) and **PR #14** (WS-D — CDK 5-stack + docker + CI),
both green & open. Handoff: [phase-7-handoff](../handoff-docs/phase-7-handoff.md).

## Out of scope / deferred
No CDK deploy/bootstrap; no AWS account use. No S3+CloudFront (documented alt). No
micrometer-cloudwatch dependency. `make demo` (newman) = Phase 8. Forecast time-series overlay deferred.
