# Read sequence — pipeline + degradation chain

The 7-step read pipeline ([`../lld.md`](../lld.md) §5). The `alt` block is the forecast-path
degradation chain; `actuals` mode skips it (always `fresh`).

```mermaid
sequenceDiagram
    participant UI
    participant Svc as Service
    participant Cache as Redis
    participant Srv as Serving Table
    participant Agg as Aggregates
    participant Ins as InsightGenerator

    UI->>Svc: GET top-categories (tenant, mode, window, k)
    Svc->>Svc: 1. TenantScopeFilter (assert path == authed)
    Svc->>Cache: 2. get(topk:tenant:ver:window:mode:k)
    alt cache hit
        Cache-->>Svc: cached TopKResponse
        Svc-->>UI: 200 (cached)
    else miss (single-flight lease)
        Note over Svc: 3. mode routing
        alt forecast mode
            Svc->>Srv: 4. read active version
            alt rows fresh
                Srv-->>Svc: rows → status=fresh
            else rows stale
                Srv-->>Svc: last-good → status=stale
            else rows absent
                Svc->>Agg: JVM seasonal-naive from actuals
                Agg-->>Svc: degraded forecast → status=degraded
            else no version yet
                Svc->>Agg: actuals top-k
                Agg-->>Svc: actuals → status=pending
            end
        else actuals mode
            Svc->>Agg: aggregate range-query
            Agg-->>Svc: actuals → status=fresh
        end
        Svc->>Ins: 6. generate (lazy; Bedrock→template)
        Ins-->>Svc: grounded insight (or template)
        Svc->>Cache: 7. populate (jittered TTL)
        Svc-->>UI: 200 (status + asOf + items + insight)
    end
```
