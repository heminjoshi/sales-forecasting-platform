# Top Sales — Production SPA (`web/`)

This is the **production, cross-origin React SPA** for the Top Sales by Category platform. It is
**feature-parity** with the Spring-served static demo dashboard
(`service/topsales-api/src/main/resources/static/`) but built as a separate, deployable bundle that
talks to the API across origins (CORS).

- **The live demo still runs the Spring static dashboard** (same-origin, no build step, no Node). That
  remains the canonical local demo per the build plan.
- **This SPA deploys to Vercel** (git-push deploys + preview URLs). S3 + CloudFront is the documented
  AWS-native alternative — not built.
- It is a **thin, read-only view**: plain `fetch` + React hooks, no state library, no business logic.
  It renders exactly what the API returns, including the `fresh|stale|pending|degraded` status badge,
  the degraded/pending banner, the grounded insight line, and prediction-interval error bars.

## Stack

Vite + React 18 + TypeScript (strict) + Recharts. No proxy — the bundle calls the API directly using
a build-time-injected base URL.

## Develop

```bash
npm ci
cp .env.example .env      # set VITE_API_BASE to your running API (default http://localhost:8080)
npm run dev               # http://localhost:5173
```

Run the API locally first (`docker-compose up` → `make run` → `make seed` in the repo root). The API
must **allow-list this SPA's origin** in CORS (`topsales.web.cors.allowed-origins`) — `localhost:5173`
for dev, the Vercel domain for prod.

## Build / typecheck

```bash
npm run typecheck         # tsc -b --noEmit
npm run build             # tsc -b && vite build  ->  dist/
npm run preview           # serve dist/ locally
```

## Configuration — `VITE_API_BASE`

The only environment variable. It is the **base URL of the public read API** (e.g.
`https://api.example.com`), injected at build time (Vite exposes `VITE_`-prefixed vars to the client
bundle). **It is a public API host, not a secret** — do not put credentials here.

- **Local:** `.env` (gitignored) → `VITE_API_BASE=http://localhost:8080`.
- **Vercel:** set `VITE_API_BASE` in the project's **Environment Variables** (Production / Preview).
- Empty/unset → requests are relative (same-origin); only useful if the SPA is ever co-hosted with the API.

## Deploy (Vercel)

`vercel.json` pins the Vite framework preset, `dist` as the output directory, and an SPA rewrite
(`/(.*) -> /index.html`) so client-side routes resolve. Push the repo; Vercel builds with
`npm ci && npm run build`. Set `VITE_API_BASE` in the project settings before the first production
deploy.

## API contract

Consumes the same endpoints as the static dashboard:

- `GET /api/v1/config` — control option sets + defaults (k / window / channel).
- `GET /api/v1/tenants` — tenant picker catalog.
- `GET /api/v1/tenants/{tenantId}/top-categories?mode&window&channel&k` — the `TopKResponse`, sent with
  an `X-Tenant-Id` header (dev stand-in for auth, mirrored from the path tenant).

See `docs/lld.md` §13 for the UI contract and the canonical response shape.
