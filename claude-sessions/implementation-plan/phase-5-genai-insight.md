# Phase 5 — GenAI insight layer

> **Milestone:** AI-READY · **Status:** ✅ done — see [phase-5 handoff](../handoff-docs/phase-5-handoff.md).
> Full execution detail in `~/.claude/plans/sparkling-inventing-wadler.md` (private).

## Objective & acceptance
Populate the previously-`null` `TopKResponse.insight` with a **grounded** one-line NL summary behind the
existing `InsightGenerator` seam: a deterministic local floor plus a designed, creds-gated Bedrock impl;
output validated to contain only computed numbers; untrusted category names handled as data.
**Acceptance:** top-k + dashboard show a grounded insight locally (template) and via Bedrock when enabled;
prompt-injection probes stay safe; insight failure degrades to the template without blocking the read.
_(Met — `make test` green at 131 tests; see handoff.)_

## Current state (entering) → what this phase added
- **Entering (Phase 4):** `InsightGenerator` + `InsightRequest` existed in `topsales-common`;
  `topsales-insight` was an empty module (only `package-info.java`), not depended on by the API;
  `TopKResponse.insight` always `null`; no AWS SDK anywhere; the dashboard already had an `#insight`
  element (added in Phase 4) waiting for a value.
- **Added:** `TemplateInsightGenerator` (always-on deterministic floor, DR-6) + `GroundingValidator` +
  shared `InsightFigures` allow-set; designed `BedrockInsightGenerator` (decorator → template fallback,
  AWS SDK confined via a `create(...)` factory); `InsightWiring`
  (`@ConditionalOnProperty(provider=bedrock)` + `@Bean @Primary`); `TopsalesProperties.Insight` record +
  `topsales.insight` config in both `application.yml`; `InsightAttacher` wiring insight **inside** the
  Phase-4 forecast cache supplier (lazy + cached under the same per-tenant Redis key); Postman
  insight-present assertions + a prompt-injection probe.

## Decisions locked (full table in the handoff)
Template floor (`@Component`) vs Bedrock decorator (conditional `@Primary`, **not** `@Profile`) ·
numbers-only grounding (untrusted category labels excluded) · cheap Bedrock model `anthropic.claude-haiku-4-5`,
`1500ms` timeout → template fallback · `bedrockruntime` `<optional>`, AWS symbols confined to
`topsales-insight` · insight cached inside the forecast cache supplier (no new key) · insight failure never
blocks the read.

## Out of scope / deferred
- **Numbers-only validation residual** — injected *non-numeric* prose could pass; closing it needs an
  output classifier. Documented in `docs/lld.md §9`; deferred (blast radius = decorative cached string).
- **AWS profile / live Bedrock guardrail in CDK** → Phases 6–8 (same seam; impl built, creds-gated).
- **Forecast/cache + insight Testcontainers ITs** (CI-only on this host) → optional, carried.

## Outcome
✅ Built via the new `/implement-phase` skill driving a deterministic Workflow (foundation → 3 parallel
ownership-partitioned streams → verify); `make test` green (131 tests); PR
[#9](https://github.com/heminjoshi/sales-forecasting-platform/pull/9) open. Handoff:
[phase-5-handoff](../handoff-docs/phase-5-handoff.md).
