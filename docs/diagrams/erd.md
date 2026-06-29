# ERD — local Postgres schema

The built (`local`) schema. DDL in [`../lld.md`](../lld.md) §2. `serving_active_version` is the
pointer that makes a batch refresh an atomic swap (and a rollback a flip).

```mermaid
erDiagram
    TENANT_CONFIG ||--o{ EVENTS : "scopes"
    TENANT_CONFIG ||--o{ AGGREGATES : "scopes"
    EVENTS }o--|| AGGREGATES : "additively rolls up into"
    AGGREGATES ||--o{ SERVING_ROWS : "forecast batch produces"
    SERVING_ROWS }o--|| SERVING_ACTIVE_VERSION : "active version selected by"

    TENANT_CONFIG {
      text tenant_id PK
      text timezone
      text reporting_currency
    }
    EVENTS {
      bigint id PK
      text tenant_id
      text order_id
      text category_id
      numeric amount
      text currency
      text event_type
      timestamptz event_time
      date bucket_date
      text idempotency_key UK
      timestamptz received_at
    }
    AGGREGATES {
      text tenant_id PK
      text category_id PK
      date bucket_date PK
      numeric sum_amount
      integer order_count
      text currency
      timestamptz updated_at
    }
    SERVING_ROWS {
      text pk PK
      integer version PK
      integer rank PK
      text category_id
      numeric value
      numeric interval_low
      numeric interval_high
      text confidence
      numeric delta_vs_prior
      timestamptz as_of
    }
    SERVING_ACTIVE_VERSION {
      text pk PK
      integer active_version
      timestamptz as_of
    }
```
