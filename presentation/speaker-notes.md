# Speaker notes

Per-section talking points, the *rejected-alternative* / *what-I-didn't-build* beats (the
highest-scored material — Build-Plan §156), and anticipated Q&A. Keep to time; the deck is
`deck/deck.md`, the live demo is `demo-script.md`, the opener is `intro-achievements.md`.

**The one through-line:** every choice is **availability-first** — keep ML off the read path. If you
only land one idea, land that.

---

## Timing (60 min total)

| Segment | Min | Source |
|---|---:|---|
| Intro | 5 | `intro-achievements.md` |
| Achievements (2 stories) | 10 | `intro-achievements.md` |
| Problem + Assumptions | 2 | deck |
| Requirements | 5 | deck |
| HLD (+ the §5 fork) | 10 | deck |
| Component Deep-Dive (+ 10 ADRs) | 20 | deck |
| AI Integration | 10 | deck |
| Scale & Perf | 5 | deck |
| **Live demo** | ~6 | `demo-script.md` |
| Q&A | 8 | deck Q&A + below |

> That sums past 60 — the deck's per-section budget is the *cap*. Trim the Deep-Dive ADR slides first
> (talk 4–5, name the rest from the summary table) if you're running long.

---

## Section beats

### Problem + Assumptions
- Lead with the seller's question ("which categories drive my sales — past and likely future").
- The assumptions aren't throat-clearing — **A4 and A5 decide the whole architecture**. Say so.
- Non-goals show judgment: each is pushed upstream by a specific assumption.

### Requirements
- The **NFR ranking is the slide** — availability #1, correctness #2, performance #3. Everything traces back.
- "I prioritized, I didn't list" — the ranking is a decision, not a wishlist.

### HLD — the §5 fork is the headline
- Walk the four tiers fast; spend time on the **coupling rule** (Forecast↔Serving touch only through
  the versioned serving table).
- The A/B/C table: **chose A (precompute)**. The beats that score:
  - **Rejected B** (on-demand): *"ML availability becomes read availability"* + prohibitive at ~1M fan-out.
  - **Didn't build C** (speed layer): unjustified under daily freshness — *avoiding over-engineering is a decision*.
  - **Reversible:** if freshness → sub-hour, it's a `ForecastProvider` swap, not a rewrite.

### Component Deep-Dive — the largest budget
- **Data spine first** (4 shapes) — "read these top-to-bottom and you have the system."
- **Exactly-once *effect*** — at-least-once delivery + idempotent additive upsert. The word "effect" matters.
- **Versioned serving table** — atomic swap + rollback = a flip.
- **Degradation chain** — the demo previews here; "never fail closed."
- **The 10 ADRs** — each is "here's what I rejected and why." Don't read them; tell the rejected-alternative story:
  - 0002 data-plane vs RPC · 0004 KV vs query-at-read · 0006 WAPE vs MAPE (near-zero blow-up) ·
    0007 lazy vs 150M generations/day · 0009 Vercel cross-origin trade-off owned · 0010 channel modeled vs filtered.

### AI Integration
- **Two AIs, two safeguards:** numbers (forecast → WAPE/rollback) vs words (insight → grounding/template).
- **Grounding is the headline:** the LLM verbalizes *only provided numbers*; `GroundingValidator` rejects
  any non-derivable figure; template floor on failure. Never on the critical path.
- **Prompt-injection:** category names are untrusted, delimited data; the numbers-only validation is the
  real backstop. **Own the residual** — a non-numeric injected label can echo as inert text; the test
  asserts *non-compliance*, not that the model is unbreakable. Honesty scores better than a false absolute.
- **Resilience4j** CB+retry on the single Bedrock call, confined to `topsales-insight`, fails soft.

### Scale & Perf
- Capacity math justifies precompute + lazy insight (150M of either would dominate cost).
- **Where autoscaling stops** — the refresh window + celebrity-tenant skew, *not* the read path. Name the
  lever (incremental refit + tiered cadence). This is the senior beat: knowing the limit, not just the scale.
- The metric names are a **contract** the Monitoring stack alarms on.

---

## Anticipated Q&A (beyond the deck's Q&A slides)

- **"Why not just cache the on-demand model?"** Caching helps repeat reads, but the *first* read still
  pays inference + ML availability, at a 1M-tenant fan-out. Precompute pays once, offline, for everyone.
- **"What if a tenant has no history?"** Cold-start: < 1 season → trend-only; none → prior/flat with
  **low confidence** surfaced honestly. We never fake precision.
- **"How do you know the insight isn't hallucinating?"** It can only emit provided numbers, and a
  validator rejects anything non-derivable → template. Faithfulness is also eval'd (LLM-judge + spot).
- **"Is the degradation real or staged?"** Real — `TRUNCATE serving_rows` live; it's `ForecastDegradationIT`
  in the suite. Offer to wipe it on stage.
- **"Single region — what about DR?"** A8 scopes v1 to one region; multi-region is designed, not built.
  S3 raw log (11-nines) is the durable rebuild source; everything downstream is regenerable.
- **"Why Java if ML is better in Python?"** Data-plane coupling — the Python/SageMaker model writes the
  serving table; the language boundary never enters the read budget. Same serving contract.
- **"What would you build next?"** The speed layer (C) *iff* freshness needs sub-hour, or promote the
  SageMaker global model per-segment where the baseline's WAPE is weak (sparse/cold-start).
- **"Biggest risk you didn't fully solve?"** The numbers-only insight residual (non-numeric injected
  prose), and the batch refresh window at extreme fan-out — both documented, with mitigations named.

---

## Public-repo reminder

This deck and all of `presentation/` are **committed to a public repo** — keep everything **generic**.
No employer name, no recruiter/interview specifics, no the-verbatim-problem-statement. The private
interview narrative (why-employer, STAR, recruiter context) lives outside this repo. Run
`/public-repo-check` before pushing.
