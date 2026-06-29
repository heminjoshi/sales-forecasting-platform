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
| MQ-00d | (P8) `make seed` then `make demo` | One-command seeded data appears; dashboard shows ranked categories |

## 1. Dashboard — happy path
| ID | Step | Expected |
|---|---|---|
| MQ-01 | Default view on load (`t_demo`) | Table renders top-k; chart renders; badge shows a status + "as of" timestamp |
| MQ-02 | Toggle **mode** actuals ↔ forecast | actuals → `fresh`; forecast → `pending` badge (P2 floor); table still populated (same data underneath) |
| MQ-03 | Change **window** week/month/year | Table + chart update; totals change consistently with the window span |
| MQ-04 | Change **k** (e.g. 5 → 20) | Row count changes accordingly; ranks contiguous 1..k |
| MQ-05 | (P2.5) Toggle **channel** all/online/offline | `all` = combined; per-channel views differ; `all` looks like the sum of the two |
| MQ-06 | Read-only check | No edit/write controls on the dashboard; it only reads the API |

## 2. End-to-end ingest → see it move
| ID | Step | Expected |
|---|---|---|
| MQ-10 | POST a SALE for `t_demo` (curl/Postman), then refresh | New/updated category appears; its value increased by the amount; `asOf` advances |
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
| MQ-30 | Ingest distinct data for `t_demo` and a second seeded tenant | Each dashboard/read shows only its own categories/totals |
| MQ-31 | Switch `X-Tenant-Id` between the two and read each path | No cross-tenant values ever appear; 403 if path≠header |

## 5. Degradation demo [P4]
| ID | Step | Expected |
|---|---|---|
| MQ-40 | With forecasts present, view forecast mode | badge `fresh`; intervals/confidence shown |
| MQ-41 | Wipe the serving table (Postman "wipe forecast" / SQL) and refresh | Dashboard **still renders** (degraded): falls back to seasonal-naive/actuals; badge `degraded`/`pending`; honest `asOf` — **no crash, no blank** |
| MQ-42 | Restore/rerun the batch | badge returns to `fresh` after refresh |

## 6. GenAI insight [P5]
| ID | Step | Expected |
|---|---|---|
| MQ-50 | View a populated forecast (local/template) | A grounded one-line insight appears, citing only figures shown in the table |
| MQ-51 | (Bedrock enabled) same view | Insight still grounded; on timeout/error it silently falls back to the template (no error to the user) |

## 7. Cross-browser / responsiveness / a11y (smoke)
| ID | Step | Expected |
|---|---|---|
| MQ-60 | Load in Chrome, Firefox, Safari | Renders consistently; no console errors; Chart.js (CDN) loads |
| MQ-61 | Narrow viewport / mobile width | Layout remains usable (table scroll/stack); controls reachable |
| MQ-62 | Keyboard-only | Controls are tabbable and operable; focus visible |
| MQ-63 | Offline/no-internet **except** the page itself | Local-served dashboard works; only the Chart.js CDN needs network — note this dependency for the live demo |

## 8. Cold-clone acceptance (the "stranger" test) [P10]
| ID | Step | Expected |
|---|---|---|
| MQ-70 | Fresh clone on a clean machine → `make up` → `make run` (→ `make seed`/`demo`) | Comes up in the documented 2 commands; dashboard shows data; design docs + ADRs readable |
| MQ-71 | Public-repo hygiene glance | No secrets/.env/employer-specific text visible (run `/public-repo-check` for the real gate) |

## Reporting
For each failed case record: ID, build/commit, steps, **actual** vs **expected**, screenshot (UI cases),
and the response body/status for API cases. File against the owning phase. A case whose feature is not
yet built (P3+) is **N/A**, not a fail — note the phase.
</content>
