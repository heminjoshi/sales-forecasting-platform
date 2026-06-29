# Architecture — four tiers

Presentation → Serving → Forecast (batch) → Ingestion. Forecast and Serving couple **only** through
the versioned Serving Table — no synchronous ML on the read path. Full narrative in [`../hld.md`](../hld.md) §6.

```mermaid
flowchart TB
    subgraph Presentation
      UI["Dashboard UI<br/>demo: static, served by Spring Boot<br/>prod: React SPA on Vercel<br/>(S3 + CloudFront = AWS-native alt)"]
    end
    subgraph Ingestion
      EV["Upstream sale/return events"] --> ST["Kinesis / MSK<br/>(local: POST /events)"]
      ST --> CO["Ingestion Consumer<br/>dedupe + additive upsert"]
      CO -->|idempotent upsert| AGG["Aurora Postgres<br/>(tenant, category, day) rollups"]
      CO -->|append| RAW["S3 Raw Event Log (SoT)<br/>(local: filesystem dir)"]
      CO -. malformed .-> DLQ["DLQ / quarantine"]
    end
    subgraph Forecast[Forecast batch]
      SCH["EventBridge cron<br/>(local: make forecast)"] --> FJ["Forecaster Job<br/>Java baseline / SageMaker (future)"]
      AGG --> FJ
      FJ -->|versioned write + swap| SRV["Serving Table<br/>precomputed top-k + intervals"]
      FJ --> EVAL["Eval / Drift (WAPE, bias)"]
    end
    subgraph Serving
      UI --> APIGW["API Gateway / ALB"]
      APIGW --> API["Spring Boot Service (stateless)"]
      API --> CA["Redis cache"]
      API --> SRV
      API --> AGG
      API -. lazy, cached .-> BR["Bedrock (grounded insight)<br/>→ template fallback"]
      API -. ML plane down .-> FB["JVM baseline fallback"]
    end
    EVAL --> MON["CloudWatch / dashboards<br/>(local: Micrometer + actuator)"]
```
