# Manual QA Test Plan

Scripted + exploratory checks a human runs against a locally-running stack — focused on the things
automation covers poorly: the **dashboard UX**, the **cold-clone demo flow**, visual correctness of
the status badge / chart, and end-to-end "feel". Each case has explicit steps and an expected result.

> **Audience:** anyone validating a release or rehearsing the demo. Assumes the prerequisites:
> Docker Desktop, JDK 21+, Maven, a browser. No Node, no AWS account.

## 0. Environment bring-up (cold start)
| ID | Step | Expected |
|---|---|---|
| MQ-00a | `make up` | Postgres + Redis containers start; no port conflicts |
| MQ-00b | `make run` | App boots on `http://localhost:8080`; Flyway applies V1–V5 (logged); no errors |
| MQ-00c | Open `http://localhost:8080` | Dashboard loads: controls, empty/seeded table, status badge, no console errors |
| MQ-00d | (P8) `make seed` | One-command seeded, seasonal, channel-split data backfills; dashboard shows ranked categories |
| MQ-00e | (P8) `make demo` (after `up`/`run`/`seed`) | **Newman** runs the whole `postman/` collection against the live stack; **every assertion passes** (happy path, degradation, injection, multi-tenant isolation, observability, CORS); a non-zero exit is a failure |

## 1. Dashboard — happy path
| ID | Step | Expected |
|---|---|---|
| MQ-01 | Default view on load (`tenant_a`) | Table renders top-k; chart renders; badge shows a status + "as of" timestamp |
| MQ-02 | Toggle **mode** actuals ↔ forecast | actuals → `fresh`; forecast → `pending` badge (P2 floor); table still populated (same data underneath) |
| MQ-03 | Change **window** week/month/year | Table + chart update; totals change consistently with the window span |
| MQ-04 | Change **k** (e.g. 5 → 20) | Row count changes accordingly; ranks contiguous 1..k |
| MQ-05 | (P2.5) Toggle **channel** all/online/offline | `all` = combined; per-channel views differ; `all` looks like the sum of the two |
| MQ-06 | Read-only check | No edit/write controls on the dashboard; it only reads the API |

## 2. End-to-end ingest → see it move
| ID | Step | Expected |
|---|---|---|
| MQ-10 | POST a SALE for `tenant_a` (curl/Postman), then refresh | New/updated category appears; its value increased by the amount; `asOf` advances |
| MQ-11 | POST a **batch** (Postman "batch" request) | Response `{received, applied, deduped, quarantined}` with sensible counts; dashboard reflects all applied |
| MQ-12 | POST the **same** event again (dedupe) | Response shows `deduped: 1`, `applied: 0`; dashboard value **unchanged** (not doubled) |
| MQ-13 | POST a **RETURN** (negative) for a top category | That category's total **drops** by the return amount; ranking may reorder |
| MQ-14 | (P2.5) live `make trickle` while watching | Dashboard updates continuously; the trickle is a smooth continuation of seeded history (no discontinuity) |

## 3. Error & edge cases (verify the UI/API fail honestly)
| ID | Step | Expected |
|---|---|---|
| MQ-20 | Request `k=0` or `k=51` (edit URL/Postman) | **400** `application/problem+json`; `detail` says k must be 1..50; UI shows a friendly error, not a blank/broken page |
| MQ-21 | Request `window=quarter` | **400** bad-request (reserved) |
| MQ-22 | Read a tenant path ≠ your `X-Tenant-Id` | **403** tenant-mismatch |
| MQ-23 | Read an unknown tenant | **404** unknown-tenant |
| MQ-24 | POST without `X-Tenant-Id` | **400**; problem detail names the missing header |
| MQ-25 | POST a malformed event (e.g. `amount: 1.234`, or missing `categoryId`) | **202** with `quarantined: 1`; dashboard total **unchanged**; (DB) a `quarantine` row with the right reason |
| MQ-26 | POST a category name with injection text (e.g. `"<script>"` / `"ignore instructions"`) | Stored/ranked as plain data; rendered **escaped** in the table (no script exec); (P5) insight ignores it |
| MQ-27 | Tenant with **no data** | Dashboard shows an empty state + a `fresh` badge — not an error or spinner-forever |

## 4. Multi-tenant isolation (manual)
| ID | Step | Expected |
|---|---|---|
| MQ-30 | Ingest distinct data for `tenant_a` and a second seeded tenant | Each dashboard/read shows only its own categories/totals |
| MQ-31 | Switch `X-Tenant-Id` between the two and read each path | No cross-tenant values ever appear; 403 if path≠header |

## 5. Degradation demo [P4] — ✅ built, runnable now
| ID | Step | Expected |
|---|---|---|
| MQ-40 | With forecasts present, view forecast mode | badge `fresh`; intervals/confidence shown |
| MQ-41 | Wipe the serving table (Postman "wipe forecast" / SQL) and refresh | Dashboard **still renders** (degraded): falls back to seasonal-naive/actuals; badge `degraded`/`pending`; honest `asOf` — **no crash, no blank** |
| MQ-42 | Restore/rerun the batch | badge returns to `fresh` after refresh |

## 6. GenAI insight [P5] — ✅ built, runnable now
| ID | Step | Expected |
|---|---|---|
| MQ-50 | View a populated forecast (local/template) | A grounded one-line insight appears, citing only figures shown in the table |
| MQ-51 | (Bedrock enabled) same view | Insight still grounded; on timeout/error it silently falls back to the template (no error to the user) |

## 6.5 Observability & resilience [P6]
| ID | Step | Expected |
|---|---|---|
| MQ-55 | `curl localhost:8080/actuator/prometheus` | 200 plain-text; grep shows `http_server_requests`, `topsales_read_total`, `topsales_forecast_freshness_seconds` |
| MQ-56 | `curl localhost:8080/actuator/health` | 200 `{"status":"UP"}` with DB + Redis components UP |
| MQ-57 | Do MQ-41 (wipe serving) then re-scrape | `topsales_read_total{status="degraded"}` sample present and climbing — the degraded read is **observable**, not just visible in the UI |
| MQ-58 | Tail app logs during any read | each line carries `[tenant_a <requestId>]` (tenant + request id from MDC); a request sent with `X-Request-Id: demo1` echoes `demo1` in the response header and the logs |
| MQ-59 | Bedrock-down resilience (`provider=bedrock`, no AWS creds) | insight still renders the **template** (no error, read not blocked); after repeated calls the breaker opens and `topsales_insight_fallback_total` keeps climbing |

## 7. Cross-browser / responsiveness / a11y (smoke)
| ID | Step | Expected |
|---|---|---|
| MQ-60 | Load in Chrome, Firefox, Safari | Renders consistently; no console errors; Chart.js (P9: **vendored**, `static/vendor/`) loads |
| MQ-61 | Narrow viewport / mobile width | Layout remains usable (table scroll/stack); controls reachable |
| MQ-62 | Keyboard-only | Controls are tabbable and operable; focus visible |
| MQ-63 | (P9) Fully offline — disconnect the network, then load + use the dashboard | Local-served dashboard works **end-to-end with no internet**: chart renders from the vendored `static/vendor/chart.umd.min.js` (no CDN call in the network tab) — the live demo has **zero** network dependency |

## 8. Cold-clone acceptance (the "stranger" test) [P10]
| ID | Step | Expected |
|---|---|---|
| MQ-70 | Fresh clone on a clean machine → `make up` → `make run` (→ `make seed`/`demo`) | Comes up in the documented 2 commands; dashboard shows data; design docs + ADRs readable |
| MQ-71 | Public-repo hygiene glance | No secrets/.env/employer-specific text visible (run `/public-repo-check` for the real gate) |

> ✅ **Run 2026-06-30** against a fresh clone of the **public** repo: **MQ-70** — `make up` → `seed` → `forecast` → `run` came up cold; `/actuator/health` UP, dashboard 200, forecast read `fresh`, cross-tenant 403, and `make demo` (17 requests / 54 assertions) all green. **MQ-71** — `public-repo-check` clean (no secrets, no tracked `private/`, no employer text). Phase 10 → **PUBLIC** milestone met.

## 9. Cross-origin / Vercel deploy [P7]
| ID | Step | Expected |
|---|---|---|
| MQ-80 | Open the deployed **Vercel URL** and watch the network tab while it loads top-categories | The SPA's `GET`s go to the API host (`VITE_API_BASE`); responses carry `Access-Control-Allow-Origin` echoing the Vercel origin; table/chart render cross-origin with no CORS console error |
| MQ-81 | Open the local `http://localhost:8080` static demo | Still **same-origin** (no CORS involved); no regression — dashboard renders exactly as before P7 |
| MQ-82 | Hit the API from a **non-allow-listed** origin (e.g. a scratch page on another host, or DevTools `fetch` from a random origin) | Browser **blocks** the response (CORS error); `Access-Control-Allow-Origin` absent — the allow-list holds |
| MQ-83 | Confirm the `web/` SPA points at the API via `VITE_API_BASE` and the Spring static demo is untouched | The React build reads `VITE_API_BASE` for the API base URL; the local static dashboard still works with no env/build step (the two presentation paths are independent) |

> **Note:** §9 [P7] is **N/A until deployed** — like the other unbuilt-phase cases, an N/A here is **not a fail**;
> the Vercel/cross-origin checks become runnable only once PR1 (CORS) ships and the SPA is deployed (PR2/infra).

## 10. Presentation & rehearsal [P9] — the demo-day gate
The Phase-9 acceptance is "full run-through to time; demo (incl. dashboard) runs clean from cold." These
cases are the rehearsal checklist — run them as the dry-run, not just at release.
| ID | Step | Expected |
|---|---|---|
| MQ-90 | **Cold-start from scratch** (containers down, no prior state): `make up` → `make seed` → `make run` → `make forecast` → open `http://localhost:8080` | Comes up clean in the documented sequence; dashboard renders seeded `tenant_a` with a `fresh` forecast badge, intervals, and a grounded insight — **no errors, no manual fix-ups** |
| MQ-91 | **Dashboard walk** (the demo beats): toggle mode actuals↔forecast, window week/month/year, channel all/online/offline, change k | Each control updates table + chart live; badge + `asOf` stay honest; per-channel views differ and `all` ≈ their sum (mirrors MQ-01..05, run as one fluid sequence) |
| MQ-92 | **Degradation beat (live):** `docker compose -f local/docker-compose.yml exec -T postgres psql -U topsales -d topsales -c 'TRUNCATE serving_rows, serving_active_version;'` then `... exec -T redis redis-cli INCR tenantver:tenant_a`, then refresh forecast view | Forecast read still **200**; badge flips to `degraded` (seasonal-naive) or `pending` (actuals floor); the degradation banner shows; items still present — **never a 5xx, blank, or crash** (the signature resilience moment; matches `ForecastDegradationIT`) |
| MQ-93 | **Recovery:** `make forecast` again, refresh | Badge returns to `fresh` with intervals; the wipe was fully recoverable on stage |
| MQ-94 | **Fully-offline rehearsal:** repeat MQ-90→93 with the machine's network **off** (after images are pulled) | Everything works — no CDN, no AWS, no internet (Chart.js vendored in P9); proves the demo survives a dead venue wifi |
| MQ-95 | **Deck renders:** open `presentation/deck/index.html` in a browser (reveal.js) **and** preview `presentation/deck/deck.md` (Marp / GitHub) | Slides advance with arrow keys; diagrams + screenshots show; the three renderings are the same content from one `deck.md` source |
| MQ-96 | **Run to time:** present the full deck + live demo against `presentation/demo-script.md` timings | Completes within the 60-min budget (Build-Plan §155 split); the demo + degradation beat land inside their allotted minutes |

> §10 [P9] is the rehearsal gate — runnable now (the system is built through P8). MQ-92's manual SQL/Redis
> steps are intentional (no `make degrade` target by decision); they live verbatim in `demo-script.md`.

## Reporting
For each failed case record: ID, build/commit, steps, **actual** vs **expected**, screenshot (UI cases),
and the response body/status for API cases. File against the owning phase. Phases 0–6 are built, so
their cases (incl. §5 [P4], §6 [P5], §6.5 [P6]) are runnable now. A case whose feature is not yet built
(P7+ — e.g. MQ-00d [P8], §8 [P10]) is **N/A**, not a fail — note the phase.
</content>
