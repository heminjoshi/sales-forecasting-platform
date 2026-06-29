# Integration Test Plan

Component- and API-level tests against a **real Postgres** (and Redis from P4) via Testcontainers, with
Flyway migrating on boot. These complement the existing unit tests (service-mocked) by exercising the
actual SQL constraints, the dedupe gate, Flyway schema, and the full HTTP path.

> **Runner note:** existing `*IT` tests use Testcontainers and are CI-only on the very-new-Docker-Desktop
> dev host (Docker API-version mismatch — see `../docs/runbook.md §7`). Run locally with `make verify`;
> they always run in CI.

**Scope of existing coverage** (do not duplicate; extend the gaps): `JdbcEventLedgerIT`,
`JdbcAggregateRepositoryIT`, `TopCategoriesReadIT`, plus unit suites for controllers, services, filter,
and enum wire-format. Gaps this plan targets: RFC 7807 body assertions, quarantine-row + reason strings,
concurrent dedupe, timezone bucketing edges, multi-tenant isolation end-to-end, and all P3+ specs.

---

## 1. Ingestion — `POST /api/v1/events` [P2]

Success = **202** with body `{received, applied, deduped, quarantined}`. Tenant from `X-Tenant-Id`.

### 1.1 Happy paths
| ID | Scenario | Setup → Action | Expected |
|---|---|---|---|
| IT-IN-01 | Single valid SALE | POST one well-formed SALE for `t_demo` | 202; `{1,1,0,0}`; one `events` row; `aggregates` sum_amount += amount, order_count = 1 |
| IT-IN-02 | Batch all valid | POST array of 3 distinct valid events | 202; `{3,3,0,0}`; 3 ledger rows; aggregates reflect all 3 |
| IT-IN-03 | Default idempotency key | POST event **without** `idempotencyKey` | 202; persisted `idempotency_key = orderId:eventType` |
| IT-IN-04 | Signed RETURN nets | POST SALE +100.00 then RETURN −30.00 (same category/day) | aggregates `sum_amount = 70.00`, `order_count = 2` |
| IT-IN-05 | ADJUSTMENT nets | POST ADJUSTMENT −15.00 | sum_amount decreases by 15.00; row persists with negative amount |
| IT-IN-06 | Tenant-local bucketing | POST event at `2026-06-21T05:00:00Z` for LA tenant | `bucket_date = 2026-06-20` (UTC−7), not `06-21` |

### 1.2 Idempotency / dedupe (corner)
| ID | Scenario | Expected |
|---|---|---|
| IT-IN-10 | Exact duplicate (same idempotency key) re-POSTed | 202; `{1,0,1,0}`; **no** second ledger row; aggregate **unchanged** (not double-counted) |
| IT-IN-11 | Duplicate **within one batch** (two items same key) | 202; `{2,1,1,0}`; aggregate counted once |
| IT-IN-12 | Same `orderId` different `eventType` (SALE then RETURN, no explicit key) | Both applied (keys `o:SALE` vs `o:RETURN` differ) → `{1,1,0,0}` each |
| IT-IN-13 | **Concurrent** duplicate: 2 threads POST same key simultaneously | Exactly one `applied`, one `deduped`; UNIQUE `uq_events_idem` + `ON CONFLICT DO NOTHING` prevents double-apply; no exception surfaces |

### 1.3 Quarantine — malformed events (each is **counted, not 4xx**)
Assert: 202, `quarantined` incremented, a `quarantine` row with the exact `reason`, and **no** aggregate change.
| ID | Bad input | Expected `reason` |
|---|---|---|
| IT-IN-20 | missing/blank `orderId` | `missing orderId` |
| IT-IN-21 | missing/blank `categoryId` | `missing categoryId` |
| IT-IN-22 | null `eventType` | `missing eventType` |
| IT-IN-23 | null `eventTime` | `missing eventTime` |
| IT-IN-24 | missing/blank `currency` | `missing currency` |
| IT-IN-25 | null `amount` | `missing amount` |
| IT-IN-26 | `amount` with scale 3 (e.g. `42.501`) | `amount scale exceeds 2` |
| IT-IN-27 | Unknown tenant (no `tenant_config` row) | `unknown tenant`; quarantined (not 404 — ingest path) |
| IT-IN-28 | Unparseable JSON object in array | `unparseable event payload`; other valid items in the batch still apply |
| IT-IN-29 | `eventType` not in enum (e.g. `"GIFT"`) | Quarantined (Jackson enum bind fails → `unparseable event payload`); DB `CHECK` never reached |
| IT-IN-30 | Mixed batch: 1 valid + 1 dupe + 1 malformed | `{3,1,1,1}`; counts exact; only valid one hits aggregates |

### 1.4 Ingestion errors (the only ingest 4xx) — assert RFC 7807
| ID | Scenario | Expected |
|---|---|---|
| IT-IN-40 | Missing `X-Tenant-Id` header | **400**; problem `type=.../bad-request`, detail mentions the header |
| IT-IN-41 | Blank `X-Tenant-Id` header | **400** (same) |
| IT-IN-42 | Body tenantId ≠ header tenant | Header **wins**; event stored under header tenant (body field ignored) — assert aggregate under header tenant only |
| IT-IN-43 | Top-level body is malformed JSON (not an object/array) | **400** unparseable body (whole request rejected, distinct from per-item quarantine) |

---

## 2. Read — `GET /.../top-categories` [P2]

Success = **200** + `TopKResponse`. Enums on the wire are **lowercase** (`mode/window/status`).

### 2.1 Happy paths
| ID | Scenario | Expected |
|---|---|---|
| IT-RD-01 | Actuals, default window/k | 200; `mode=actuals`, `status=fresh`, `insight` omitted (null in P2); items ranked **descending** by value |
| IT-RD-02 | Ranking + tie-break | Two categories with equal sums → ordered by `categoryId` **ascending** |
| IT-RD-03 | Top-k truncation | 12 categories, `k=10` → exactly 10 items, ranks 1..10 |
| IT-RD-04 | Window spans | `week`=trailing 7d, `month`=30d, `year`=365d → sums include only in-window days |
| IT-RD-05 | `mode=forecast` (P2 floor) | 200; status **overridden to `pending`**; same actuals data underneath |

### 2.2 Edge / corner
| ID | Scenario | Expected |
|---|---|---|
| IT-RD-10 | `k=1` (min) | 200; single top item |
| IT-RD-11 | `k=50` (max) | 200; up to 50 items |
| IT-RD-12 | `k=0` | **400** bad-request `k must be between 1 and 50, got 0` |
| IT-RD-13 | `k=51` | **400** bad-request (got 51) |
| IT-RD-14 | `k` non-integer (`k=abc`) | **400** bad-request (type mismatch) |
| IT-RD-15 | Invalid `window=quarter` | **400** bad-request (reserved, not yet valid) |
| IT-RD-16 | Invalid `mode=guess` | **400** bad-request |
| IT-RD-17 | Tenant exists but has **no events** | 200; `items: []`; status `fresh`; `asOf` present — empty is not an error |
| IT-RD-18 | Case-insensitive enums (`mode=ACTUALS`) | 200; parsed, echoed lowercase |
| IT-RD-19 | More than `k` ties at the boundary | Deterministic cut by (value desc, categoryId asc) — stable across repeated calls |

### 2.3 Tenant isolation / errors — assert RFC 7807
| ID | Scenario | Expected |
|---|---|---|
| IT-RD-30 | Path tenant ≠ `X-Tenant-Id` | **403** `type=.../tenant-mismatch`, title `Tenant mismatch`, `instance` = URI |
| IT-RD-31 | Missing `X-Tenant-Id` (no authed tenant) | **403** tenant-mismatch (authed is null ≠ path) |
| IT-RD-32 | Unknown tenant (path = header, but no config row) | **404** `type=.../unknown-tenant`, title `Unknown tenant` |
| IT-RD-33 | **Cross-tenant leakage:** tenant A ingests, tenant B reads B's path | B sees only B's data (empty if none); A's totals never appear under B |
| IT-RD-34 | Tenant A tries to read A's path with B's data present | Only A's aggregates returned (scoping in the SQL `WHERE tenant_id`) |

---

## 3. Error-model conformance [P2]
| ID | Scenario | Expected |
|---|---|---|
| IT-ER-01 | Every 4xx returns `application/problem+json` | Content-Type asserted |
| IT-ER-02 | ProblemDetail completeness | `status`, `type`, `title`, `detail`, `instance` all present and correct per mapping |
| IT-ER-03 | No stack trace / internal leakage in body | `detail` is the curated message only |

---

## 4. Flyway / schema integrity [P2]
| ID | Scenario | Expected |
|---|---|---|
| IT-DB-01 | Migrations V1–V5 apply cleanly on empty DB | App boots; `flyway_schema_history` has 5 success rows |
| IT-DB-02 | `event_type` CHECK | Direct insert of `'GIFT'` → constraint violation (defense-in-depth below the app) |
| IT-DB-03 | `uq_events_idem` UNIQUE | Direct duplicate `idempotency_key` insert → unique violation |
| IT-DB-04 | `aggregates` PK = (tenant, category, bucket_date) | Two upserts same key → one row, summed |
| IT-DB-05 | Re-run migrations (idempotent) | Second boot applies nothing; checksums stable |

---

## 5. Channel dimension [P2.5] — `@Disabled` until P2.5
| ID | Scenario | Expected |
|---|---|---|
| IT-CH-01 | V6 migration adds `channel` to aggregate PK | PK becomes (tenant, category, channel, bucket_date); existing rows backfill `channel='all'` or per design |
| IT-CH-02 | Event with `channel=ONLINE` vs `OFFLINE` | Aggregated into separate channel buckets |
| IT-CH-03 | Read `channel=all` (default) | Returns the **summed** rollup across channels (= old behavior) |
| IT-CH-04 | Read `channel=online` | Returns only ONLINE-grain ranking |
| IT-CH-05 | Read `channel=bogus` | **400** bad-request (enum) |
| IT-CH-06 | `all` equals sum of `online`+`offline` per category | Invariant assertion |

---

> **Coverage status (P3–P5).** These cases were specified as HTTP/Testcontainers integration tests,
> but the behavior actually shipped covered by **unit** suites (run in `make test`) plus one real
> serving-row IT — there is **no** `*IT` class carrying the `IT-FC`/`IT-CA`/`IT-AI` names, and nothing
> is `@Disabled`. The **Status** column records what is really implemented as of P5. Legend:
> **✅ unit** = covered by a unit test in `make test`; **✅ IT** = real Testcontainers `*IT` (CI-only);
> **⚠️** = partial / proxied (see note); **❌ gap** = no automated test yet. Promoting the ⚠️/❌ rows to
> the named ITs above is deferred to the Phase-8 test-hardening pass.

## 6. Forecasting + serving [P3 / P4]
| ID | Scenario | Expected | Status |
|---|---|---|---|
| IT-FC-01 | Batch writes versioned serving rows | `serving_rows` populated; `serving_active_version` points at newest version | ✅ IT `JdbcServingTableRepositoryIT` + ✅ unit `ForecasterJobTest` |
| IT-FC-02 | `mode=forecast` reads from serving table | status `fresh`; items carry interval + confidence | ⚠️ unit `ForecastReadServiceTest.servingFresh_whenAsOfWithinSlo_returnsFreshWithMappedItems` (service-level; no HTTP IT) |
| IT-FC-03 | Atomic version swap | Mid-batch, reads see old version until the pointer flips; no partial top-k | ⚠️ `JdbcServingTableRepositoryIT` (flip + rollback retention; **no** concurrent mid-batch assertion) |
| IT-FC-04 | Cold-start: <1 season of history | trend-only / low-confidence; flagged, not an error | ✅ unit `ColdStartForecasterTest` |
| IT-FC-05 | Sparse/intermittent category | simple method; no divide-by-zero; finite interval | ✅ unit `SeasonalNaiveForecasterTest` / `WapeCalculatorTest` / `BacktestRegressionTest` |
| IT-FC-06 | WAPE backtest on seed data | report emitted; WAPE within expected band; bias reported | ⚠️ unit `WapeCalculatorTest` + `BacktestRegressionTest` on a **synthetic in-test series**, not the committed seed in CI |
| IT-FC-07 | **Degradation:** serving table wiped | read falls back last-good → seasonal-naive (from actuals) → actuals `pending`; **still 200** with `degraded`/`pending` status | ✅ unit `ForecastReadServiceTest` (full 4-tier ladder + provider-throws); no HTTP/Testcontainers IT |
| IT-FC-08 | Last-good staleness past SLO | status `stale` with the older `asOf` | ✅ unit `ForecastReadServiceTest.servingStale_whenAsOfBeyondSlo_returnsStale` |

### 6.1 Redis cache [P4]
| ID | Scenario | Expected | Status |
|---|---|---|---|
| IT-CA-01 | Cache miss then hit | 2nd identical read served from Redis (assert backing store not re-queried) | ❌ gap — only the always-faulting fail-open path is unit-tested; no real-Redis hit-path test |
| IT-CA-02 | Key composition | key includes `(tenant, window, mode, channel, k)` — different params miss independently | ✅ unit `CacheKeysTest` |
| IT-CA-03 | Event-driven invalidation | new events bump the per-tenant version → next read misses and recomputes | ⚠️ key **format** only (`CacheKeysTest.tenantVersionKey`); the bump→miss→recompute **behavior** is ❌ gap |
| IT-CA-04 | TTL jitter | entries expire within TTL±jitter (no synchronized stampede) | ✅ unit `RedisCacheShellTest` |
| IT-CA-05 | Single-flight | concurrent misses for the same key cause one recompute, not N | ❌ gap — lock key format pinned, concurrency behavior untested |
| IT-CA-06 | Redis **down** | read degrades to direct store query (still 200), error logged/metered | ✅ unit `RedisCacheShellTest.failsOpenAndRunsSupplierExactlyOnceWhenRedisFaults` (fail-open at unit; not under real Redis) |

---

## 7. GenAI insight [P5]
| ID | Scenario | Expected | Status |
|---|---|---|---|
| IT-AI-01 | Template insight (local profile) | `insight` present, references only computed figures | ✅ unit `TemplateInsightGeneratorTest` |
| IT-AI-02 | Grounding validation | a number not in the computed set ⇒ output rejected ⇒ template fallback | ✅ unit `GroundingValidatorTest` + `BedrockInsightGeneratorTest.fabricatedNumber_fallsBackToTemplate` |
| IT-AI-03 | Bedrock timeout/error | falls back to deterministic template; read still 200 | ✅ unit `BedrockInsightGeneratorTest` (clientException / timeout / emptyModelText → template) + `InsightAttacherTest.attach_generatorThrows_returnsResponseUnchanged_neverThrows` |
| IT-AI-04 | **Prompt injection** via category name (e.g. `"Ignore prior instructions…"`) | category treated as untrusted data; no instruction-following; figures unchanged | ✅ unit `BedrockInsightGeneratorTest.injectedCategoryName_isFencedAsData_andCannotProduceUngroundedOutput` |
| IT-AI-05 | Lazy + cached | first view generates; second view served from cache | ❌ gap — `InsightAttacherTest.attach_emptyItems_doesNotCallGenerator…` covers the lazy *floor*, but the cache/second-view path has no automated test |

---

## 8. Hardening: resilience & observability [P6]

> **Coverage status (P6).** Shipped covered by **unit** suites (in `make test`) plus a **Postman**
> actuator probe — there is no `IT-RS`/`IT-OB`/`IT-LG` Testcontainers class, and nothing is `@Disabled`.
> The **Status** column records what's really implemented. Legend as in §6 plus **✅ Postman** = asserted
> by a `postman/TopSales.postman_collection.json` request (run via `make verify`/Newman, not JUnit).
> The earlier ❌ gaps (RS-03, OB-02, OB-07, OB-08, LG-05) were closed in this pass — unit tests for the
> batch/MDC/resilience cases, Postman probes for the live actuator cases. Remaining ⚠️ rows are scoped
> by design (see notes); promoting them further is deferred to the Phase-8 test-hardening pass.

### 8.1 Resilience4j on Bedrock (circuit breaker + retry; fail-soft preserved)
Unit-level with a mocked `BedrockRuntimeClient` (no Testcontainers); the read must **never** block.
| ID | Scenario | Expected | Status |
|---|---|---|---|
| IT-RS-01 | Transient error then success (retry) | client throws `ApiCallTimeoutException` once then returns a grounded body → grounded text returned; `invokeModel` called twice (1 retry) | ✅ unit `BedrockInsightGeneratorTest.transientTimeout_isRetried_thenGroundedTextReturned` |
| IT-RS-02 | Breaker opens after threshold | drive ≥ `minimumNumberOfCalls` failures → breaker `OPEN`; next call short-circuits (`CallNotPermittedException`) **without** invoking the client → deterministic **template** returned | ✅ unit `BedrockInsightGeneratorTest.breakerOpens_afterRepeatedFailures_thenShortCircuitsWithoutInvoking` |
| IT-RS-03 | Breaker half-open recovery | after `waitDurationInOpenState`, a probe success closes the breaker; subsequent calls invoke the client again | ✅ unit `BedrockInsightGeneratorTest.breakerHalfOpen_probeSuccess_closesBreaker_andResumesInvoking` |
| IT-RS-04 | Timeout → template | `ApiCallTimeoutException` (no retry left) → template fallback; read still produces a grounded insight | ✅ unit `BedrockInsightGeneratorTest.timeout_fallsBackToTemplate` / `timeout_firesOnFallbackOnce_andServesTemplate` |
| IT-RS-05 | Fallback counter | every fallback path (exception, breaker-open, ungrounded/blank) increments `topsales.insight.fallback.total` **exactly once**; a grounded success does **not** | ✅ unit `BedrockInsightGeneratorTest` — counter asserted on the timeout / exception / ungrounded / empty paths **and** asserted **not** fired on grounded success |
| IT-RS-06 | No dep leakage | no `software.amazon.awssdk` / `resilience4j` symbol resolvable from `topsales-api` — confined to `topsales-insight` (compile-level invariant) | ⚠️ unit `BedrockDependencyConfinementTest` asserts the **AWS SDK** is unresolvable from `topsales-api`; resilience4j is intentionally a **non-optional** transitive dep (slf4j+vavr only) so its confinement is **not** an invariant — row scoped to the SDK |

### 8.2 Metrics — Actuator + Micrometer (RED + ML-quality)
| ID | Scenario | Expected | Status |
|---|---|---|---|
| IT-OB-01 | Prometheus scrape live | `GET /actuator/prometheus` → 200; body contains `http_server_requests`, `topsales_read_total`, `topsales_forecast_freshness_seconds` | ✅ Postman item 8 "Observability — Prometheus scrape" (asserts all three meter names) |
| IT-OB-02 | Health endpoint | `GET /actuator/health` → 200 `status: UP`; DB + Redis components reported | ✅ Postman item 9 "Health — /actuator/health UP with db + redis" (needs `management.endpoint.health.show-components=always`; `show-details` intentionally left off to avoid info disclosure on an unauthenticated actuator) |
| IT-OB-03 | `topsales.read.total` tagging | each read increments the counter **once** with correct `status` (fresh/stale/degraded/pending) + `mode` (forecast/actuals) tags — no double-count across rungs | ✅ unit `ForecastReadServiceTest.readCounter_incrementsWith{Fresh,Degraded,Pending}StatusTag` (status tags; `stale`/`mode`-tag not separately asserted) |
| IT-OB-04 | Degraded-read observable | wipe serving table → read returns `degraded` (still 200) **and** `topsales_read_total{status="degraded"}` increments | ✅ unit `ForecastReadServiceTest.readCounter_incrementsWithDegradedStatusTag` + ✅ Postman degraded-sample probe |
| IT-OB-05 | Freshness gauge | after a batch, `topsales_forecast_freshness_seconds` ≈ age of newest serving row; **empty serving table → gauge does not throw** (NaN/−1 sentinel) | ✅ unit `ForecastFreshnessGaugeTest` (3 methods) |
| IT-OB-06 | Provider-fault counter | a serving-read RuntimeException (forced) drops a rung **and** increments `topsales_forecast_provider_faults_total`; read still 200 | ✅ unit `ForecastReadServiceTest.providerFaultCounter_incrementsWhenServingReadThrows` |
| IT-OB-07 | RED error rate | a 4xx (e.g. `k=0`) is recorded by `http_server_requests` with the matching `status`/`outcome` tags | ✅ Postman item 9 "RED error rate" — drives a `k=0` 400 then asserts `http_server_requests` carries `outcome="CLIENT_ERROR"` |
| IT-OB-08 | Batch structured metric | forecast batch logs one `batch_success=true tenants=… durationMs=… pkWrites=…` line on success; on a forced failure logs `batch_success=false` with duration before rethrow | ✅ unit `ForecasterJobTest.run_emitsOneStructuredBatchMetricLineOnSuccess` (ListAppender asserts `batch_success=true … pkWrites=9`); ⚠️ the failure-path line isn't separately asserted |

### 8.3 Structured logging (tenant + request id via MDC)
| ID | Scenario | Expected | Status |
|---|---|---|---|
| IT-LG-01 | Tenant + request id in log line | a read with `X-Tenant-Id: t_demo` emits log lines carrying `tenantId=t_demo` and a `requestId` (MDC pattern) | ✅ unit `TenantScopeFilterTest.headerPresent_setsAttribute` (MDC/attribute set from the header) |
| IT-LG-02 | Inbound request id honored | request with `X-Request-Id: abc123` → logs + the **response** `X-Request-Id` header echo `abc123` | ✅ unit `TenantScopeFilterTest.headerPresent_echoesRequestIdAndClearsMdc` |
| IT-LG-03 | Generated request id | request without the header → a UUID `requestId` is generated and returned in the response header | ✅ unit `TenantScopeFilterTest.missingRequestId_isGeneratedAndEchoed` |
| IT-LG-04 | **MDC cleared (no leak)** | after the filter returns, MDC has no `tenantId`/`requestId`; holds even when the chain throws (cleared in `finally`) — guards against cross-thread tenant-id leakage on pooled threads | ✅ unit `TenantScopeFilterTest.chainThrows_stillClearsMdc` (+ cleared-after assertions in the echo tests) |
| IT-LG-05 | Batch per-tenant MDC | `ForecasterJob.runTenant` sets `tenantId` in MDC and removes it in `finally` (no bleed across tenants in one batch run) | ✅ unit `ForecasterJobTest.runTenant_setsTenantIdInMdcDuringProcessing_andClearsItAfter` |
</content>
