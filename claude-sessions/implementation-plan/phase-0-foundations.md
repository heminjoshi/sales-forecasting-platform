# Phase 0 — Foundations & Logistics — Implementation Plan

> Source: `private/Build-Delivery-Plan-v2.md` §Phase 0 · Workstreams WS-D (infra/tooling), WS-G (logistics).
> Effort: S–M. No milestone, but it's the gate everything else depends on.

## ✅ Outcome (executed 2026-06-28)
Done and verified. **Handoff:** [`../handoff-docs/phase-0-handoff.md`](../handoff-docs/phase-0-handoff.md). Locked decisions: **Spring Boot 4.1.0**, **Java 21 target** (local JDK 26 runs Boot 4.1; CI pins Temurin 21), Maven multi-module, base pkg `com.topsales`, **private** repo `heminjoshi/sales-forecasting-platform` (public deferred to Phase 10).
- Scaffolded: parent pom + 5 modules (bootable `topsales-api` + 4 libraries), `local/docker-compose.yml` (Postgres 16 + Redis 7, healthchecks), `Makefile`, `.github/workflows/{ci,infra}.yml`, MIT `LICENSE`, README skeleton, full dir tree.
- Verified: `mvn verify` green (6 modules); `make up` → both healthy; `make run` → API boots on :8080 (HTTP 404, no endpoints yet); `make down` clean; repo pushed; **CI green** (ci + infra); badge in README.
- Fix applied: `make run` is a two-step (`install` deps, then `spring-boot:run -pl topsales-api`) — running the goal across the reactor fails on the parent/library modules.
- Still yours (off-repo, WS-G): reply to recruiter with onsite blocks + prep-call times.

## Objective
Repo skeleton, tooling, local env, and the non-negotiable scheduling actions — so that from here on, *implementing* a feature is just filling in modules that already compile and run.

## Acceptance (the gate)
- [ ] Public repo exists with generic name + description, MIT `LICENSE`, README skeleton.
- [ ] Maven multi-module project: **all modules compile** (`mvn -q compile` green) even though they're empty.
- [ ] `docker-compose up` brings up Postgres + Redis, both reachable.
- [ ] `Makefile` targets exist and are thin wrappers over real commands.
- [ ] CI is green: `ci.yml` (build) + `infra.yml` (`cdk synth` placeholder) → green badge.
- [ ] Private location set up for `claude-sessions/export/` + interview prep.
- [ ] Recruiter replied with onsite blocks + prep-call times. *(External, do first — blocking.)*

---

## Current state (this repo, as of Phase 0 start)

Already done by the earlier setup work — **don't redo**:
- ✅ Git initialized (`main`), 4 commits.
- ✅ `.gitignore` already covers Java (`target/`, `*.class`), Node (`node_modules/`, `cdk.out/`), IDE (`.idea/`), `.env*`, and the private dirs (`private/`, `claude-sessions/`, `interview-prep/`). **Add only `build/`** (Gradle leftover guard) if you want; otherwise it's complete.
- ✅ `CLAUDE.md`, `.claude/skills/` (`/public-repo-check`, `/phase-status`) in place.
- ✅ `service/` dir exists but is **empty** — scaffold into it.

Still to do in Phase 0:
- ❌ `LICENSE`, README skeleton.
- ❌ Maven parent pom + 5 module poms.
- ❌ Directory scaffold: `infra/ web/ docs/ local/ data/ postman/ presentation/ .github/workflows/`.
- ❌ `local/docker-compose.yml`, `Makefile`, CI workflows.
- ❌ Remote `origin` + push (repo is local-only right now).

---

## Decisions to lock (recommend now, confirm before scaffolding)

| # | Decision | Recommendation | Why |
|---|---|---|---|
| D0-1 | Build tool | **Maven multi-module** | Already locked in CLAUDE.md; simpler reactor for a portfolio reviewer to read than Gradle DSL. |
| D0-2 | Java version | **21 (LTS)** | Plan says 21+; LTS = safest for a public repo + matches `.idea` SDK. Use `<maven.compiler.release>21</maven.compiler.release>`. |
| D0-3 | Spring Boot version | **Latest stable 3.x** — pin the exact version from start.spring.io in the parent pom (don't float). | Boot 3.x requires Java 17+; pinning keeps CI reproducible. Verify the current GA before writing the pom rather than guessing. |
| D0-4 | Base package | **`com.topsales`** | Generic, matches the `topsales-*` module + `bin/topsales.ts` naming already in the plan. No employer reference. |
| D0-5 | Repo visibility at Phase 0 | **Private now, flip to public at Phase 10** | Plan's acceptance says "public," but history still carries scrubbed-then-fixed commits. Keep private until the Phase 10 history tidy + secret scan. Track this as a deviation. |
| D0-6 | CI provider | **GitHub Actions** | Free for public repos, matches `.github/workflows/` in the plan. |

> **Deviation note vs. the plan:** the plan's Phase 0 acceptance wants the repo *public* immediately for an early green badge. Recommend keeping it **private through Phase 9** and flipping at Phase 10 (after history tidy + secret scan), because the current history contains setup churn. The green badge still works on a private repo. Confirm you're OK with this.

---

## Steps

### 1. Logistics first (WS-G) — *blocking, external*
Reply to the recruiter with 2–3 onsite **4-hour blocks** + 2–3 prep-call times. This is off-repo; just don't let it slip because it gates the calendar. File the email + the verbatim problem under `interview-prep/` (gitignored), not in the repo.

### 2. LICENSE + README skeleton (WS-D)
- Add `LICENSE` — MIT, current year, generic author (no employer).
- `README.md` skeleton with placeholder sections to fill in Phase 10: pitch · architecture diagram · "run in 2 commands" · screenshots · design-doc links · tech stack · "built vs designed" note.
- **Verify:** files exist; README renders.

### 3. Directory scaffold (WS-D)
Create the top-level tree from the plan (keep empty dirs with a `.gitkeep`):
```
service/   infra/   web/   docs/   local/   data/   postman/   presentation/   .github/workflows/
docs/{adr,api,diagrams}/   data/{generator,seed}/
```
- **Why:** locks the layout now so every later phase drops files into a known home.
- **Verify:** `git status` shows the new dirs (via `.gitkeep`).

### 4. Maven multi-module skeleton (WS-D) — the heart of Phase 0
Goal: **empty modules that compile**, so Phases 2–5 only add classes.

`service/pom.xml` (parent / aggregator):
- `<packaging>pom</packaging>`, inherits `spring-boot-starter-parent` (pinned version, D0-3).
- `<properties>`: `maven.compiler.release=21`, project encoding UTF-8.
- `<modules>`: `topsales-common`, `topsales-ingestion`, `topsales-forecast`, `topsales-insight`, `topsales-api`.
- `<dependencyManagement>`: centralize versions (Testcontainers BOM, AWS SDK BOM) here so modules stay version-free.

Each module `service/topsales-<x>/pom.xml`:
- `<parent>` → the aggregator; `<artifactId>topsales-<x>`.
- Minimal deps only (e.g. `topsales-common` depends on nothing app-specific; `topsales-api` will later get `spring-boot-starter-web`). For Phase 0, keep deps empty/minimal so they compile fast.
- Package dir: `src/main/java/com/topsales/<x>/` with a single placeholder (e.g. `package-info.java`) so the module isn't empty.

Only `topsales-api` becomes a runnable Spring Boot app (has the `@SpringBootApplication` main + `spring-boot-maven-plugin`); the others are libraries. Defer the actual main class to Phase 2 — for Phase 0 a compiling stub is enough.

- **Why each piece:** parent pom = one place for versions; library modules = the interface seams live in `topsales-common`; only the API module is bootable so the reactor stays clean.
- **Verify:** `cd service && mvn -q compile` exits 0; `mvn -q -pl topsales-api -am package` produces a jar (once the main exists in Phase 2).

### 5. `local/docker-compose.yml` (WS-D)
Two services:
- **Postgres 16** — `POSTGRES_DB/USER/PASSWORD` via env, port `5432`, named volume, healthcheck (`pg_isready`).
- **Redis 7** — port `6379`, healthcheck (`redis-cli ping`).
- Optional placeholder comment for a later `localstack` profile (Phase 7).
- Add `local/init.sql` referenced by the Postgres init mount (empty for now; Flyway owns schema from Phase 2).
- **Why:** this is the "no AWS account" backbone; healthchecks let `make up` wait for readiness.
- **Verify:** `docker compose -f local/docker-compose.yml up -d` → `pg_isready` and `redis-cli ping` both succeed; `docker compose down`.

### 6. `Makefile` (WS-D)
Thin wrappers (don't bury real commands):
```
up      → docker compose -f local/docker-compose.yml up -d
down    → docker compose -f local/docker-compose.yml down
run     → mvn -pl service/topsales-api -am spring-boot:run   # real once Phase 2 main exists
test    → mvn -f service/pom.xml test
seed    → (placeholder; wired in Phase 8 data/seed)
demo    → (placeholder; Postman/newman run, wired later)
synth   → cd infra && npm install && npx cdk synth
```
- **Why:** the plan's acceptance + the demo all call `make ...`; keeping them thin means CI and humans run the same thing.
- **Verify:** `make up && make down` work; `make test` runs the (empty) reactor green.

### 7. CI skeleton (WS-D) → green badge
`.github/workflows/ci.yml`:
- Trigger: push + PR to `main`.
- `actions/setup-java@v4` (Temurin 21, maven cache) → `mvn -B -f service/pom.xml verify`.

`.github/workflows/infra.yml`:
- `actions/setup-node@v4` → `cd infra && npm ci && npx cdk synth` — **but** `infra/` has no CDK yet. For Phase 0 either (a) make it a no-op/`if: false` placeholder, or (b) defer until the CDK is moved in (Phase 7 says infra is ✅ from prep — move it in early if available). Recommend a placeholder that succeeds so the badge is green.
- **Why:** early green badge is a guiding-principle deliverable; CI also catches "doesn't compile" regressions from commit 1.
- **Verify:** push branch → both workflows green → add the badge to README.

### 8. Private location (WS-G)
- Create `claude-sessions/export/` (raw transcript dumps) and `interview-prep/` (recruiter emails, verbatim problem, probe bank, STAR, why-employer). Both already gitignored.
- **Verify:** `git status` shows nothing from these dirs; `git check-ignore claude-sessions/ interview-prep/` confirms.

---

## Acceptance checklist (Phase 0 done when all true)
- [ ] `cd service && mvn -q compile` → exit 0 (all 5 modules compile).
- [ ] `make up` → `pg_isready` + `redis-cli ping` succeed; `make down` clean.
- [ ] `make test` green (empty reactor).
- [ ] `ci.yml` + `infra.yml` green on a pushed branch; badge in README.
- [ ] `LICENSE` (MIT) + README skeleton committed.
- [ ] Directory tree scaffolded; `web/` exists (empty, designed SPA later).
- [ ] Private dirs created and confirmed gitignored.
- [ ] Recruiter replied (logistics).

## Out of scope / deferred
- Domain model, controllers, migrations → **Phase 2**.
- Actual `spring-boot:run` main wiring with endpoints → **Phase 2**.
- Real CDK `cdk synth` (move existing CDK in, fix drift) → **Phase 7** (pull earlier if the prep CDK is ready).
- Seed data + Postman collection content → **Phase 8**.
- Flipping the repo to public + history tidy → **Phase 10**.

## Risks / notes
- **CI on empty reactor:** `mvn verify` with zero tests passes fine; once Phase 8 adds a coverage gate, revisit thresholds.
- **`infra.yml` before CDK exists:** keep it a passing placeholder to avoid a red badge; swap to the real `cdk synth` when the CDK lands.
- **Spring Boot version drift:** pin it; don't use `LATEST`/ranges in the pom.
- **Repo-public timing:** see D0-5 deviation — recommend private until Phase 10.
