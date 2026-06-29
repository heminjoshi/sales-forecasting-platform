// Typed, thin REST client. Cross-origin in production: every request targets
// `${VITE_API_BASE}/api/v1/...` and sends X-Tenant-Id (the dev stand-in for auth, mirrored from the
// path tenant). An empty/unset base falls back to relative (same-origin) URLs.
//
// No business logic here — the SPA renders exactly what the API returns (parity with the static app.js).

import type { Query, TopKResponse, UiConfig, TenantsResponse } from "./types";

// Trim a single trailing slash so `${base}/api/...` never doubles up.
const API_BASE = (import.meta.env.VITE_API_BASE ?? "").replace(/\/$/, "");

function url(path: string): string {
  return `${API_BASE}${path}`;
}

const JSON_HEADERS: HeadersInit = { Accept: "application/json" };

/** GET /api/v1/config — the control configuration (k / window / channel option sets + defaults). */
export async function getConfig(): Promise<UiConfig> {
  const res = await fetch(url("/api/v1/config"), { headers: JSON_HEADERS });
  if (!res.ok) throw new Error(`config request failed (HTTP ${res.status})`);
  return (await res.json()) as UiConfig;
}

/** GET /api/v1/tenants — the tenant catalog backing the tenant picker. */
export async function getTenants(): Promise<TenantsResponse> {
  const res = await fetch(url("/api/v1/tenants"), { headers: JSON_HEADERS });
  if (!res.ok) throw new Error(`tenants request failed (HTTP ${res.status})`);
  return (await res.json()) as TenantsResponse;
}

/**
 * GET /api/v1/tenants/{tenantId}/top-categories — the read/serving endpoint.
 *
 * Mirrors app.js error handling: prefers RFC 7807 problem+json title/detail, falls back to the raw
 * body, and wraps network failures with a reach-the-API message. The thrown Error's `message` is
 * ready to render in the error state.
 */
export async function getTopCategories(q: Query): Promise<TopKResponse> {
  const tenant = q.tenantId.trim();
  const path =
    `/api/v1/tenants/${encodeURIComponent(tenant)}/top-categories` +
    `?mode=${encodeURIComponent(q.mode)}` +
    `&window=${encodeURIComponent(q.window)}` +
    `&channel=${encodeURIComponent(q.channel)}` +
    `&k=${encodeURIComponent(String(q.k))}`;

  let res: Response;
  try {
    res = await fetch(url(path), {
      headers: {
        // Dev stand-in for auth; mirrors the path tenant. Allow-listed by the API's CORS config.
        "X-Tenant-Id": tenant,
        Accept: "application/json",
      },
    });
  } catch (err) {
    const detail = err instanceof Error ? err.message : String(err);
    throw new Error("Could not reach the API.\n" + detail);
  }

  const text = await res.text();
  let body: unknown = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    // non-JSON body — leave body null
  }

  if (!res.ok) {
    const problem = body as { title?: string; detail?: string } | null;
    let msg = `Request failed (HTTP ${res.status}).`;
    if (problem && (problem.title || problem.detail)) {
      msg =
        (problem.title ?? "Error") +
        (problem.detail ? "\n" + problem.detail : "") +
        `\n(HTTP ${res.status})`;
    } else if (text) {
      msg += "\n" + text;
    }
    throw new Error(msg);
  }

  if (!body) {
    throw new Error("Empty or invalid response body.");
  }

  return body as TopKResponse;
}
