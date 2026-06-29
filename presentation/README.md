# presentation/

Interview-presentation artifacts for the multi-tenant sales-forecasting platform — the slide deck,
the live-demo script, and the speaker material. **Generic by design** (this is a public repo): all
content is sourced from the public design docs (`docs/hld.md`, `docs/adr/*`, `docs/diagrams/*`). No
employer/recruiter/interview-specific framing belongs here — that stays in a private location. Run
`/public-repo-check` before pushing.

## Contents

| File | What |
|---|---|
| `deck/deck.md` | **The canonical deck** (~60 slides) to the Build-Plan §155 budget: Problem+Assumptions · Requirements · HLD · Component Deep-Dive · AI Integration · Scale&Perf · Q&A, with the §5 A/B/C fork + all 10 ADRs as the trade-offs slides. |
| `deck/index.html` | reveal.js wrapper — loads `deck.md` for offline clickable slides + rendered mermaid. |
| `intro-achievements.md` | The opener (5 min) + 2 quantified achievement stories (10 min). |
| `demo-script.md` | The cold-start live demo sequence + the degradation beat, with timings. |
| `speaker-notes.md` | Per-section talking points, the rejected-alternative beats, anticipated Q&A, timing table. |
| `screenshots/` | Dashboard captures (fresh + degraded) for the deck/README hero. |

## Rendering the deck — one source, three ways

`deck/deck.md` is the single content source. Pick a renderer:

- **GitHub (zero setup):** just open `deck/deck.md` on GitHub — it renders as a readable
  section-per-slide narrative, and the mermaid diagrams render inline.
- **reveal.js (clickable slides):** open `deck/index.html` in a browser. Arrow keys navigate; press
  `S` for speaker view; open `index.html?print-pdf` and print-to-PDF to export. It fetches `deck.md`,
  so serve the folder over `file://` works in most browsers; if a browser blocks the `fetch`, run a
  static server from this dir (e.g. `python3 -m http.server`) and open `deck/`.
- **Marp (PDF / PPTX):** `deck.md` carries Marp frontmatter. Use the **Marp for VS Code** extension
  (live preview + export), or the CLI: `npx @marp-team/marp-cli deck/deck.md --pdf`. Note: Marp does
  not render mermaid inline — those slides show the diagram source; reference `docs/diagrams/*` or the
  screenshots when presenting via Marp.

### Fully-offline talk

The dashboard demo is already fully offline (Chart.js is vendored in
`service/topsales-api/src/main/resources/static/vendor/`). The **reveal.js wrapper** still loads
reveal + mermaid from a CDN — for a no-wifi venue, download those libs into `deck/vendor/` and repoint
the `<script>`/`<link>` URLs in `index.html` (see the OFFLINE NOTE comment in that file), or present
via the GitHub/Marp rendering instead.

## Re-capturing screenshots

With the stack up (`make up && make seed && make run && make forecast`), open
`http://localhost:8080`:
1. **Fresh** — forecast mode, `fresh` badge, intervals visible → save as `screenshots/dashboard-fresh.png`.
2. **Degraded** — run the wipe from `demo-script.md` §3, refresh → `degraded` badge + banner →
   save as `screenshots/dashboard-degraded.png`.

See `screenshots/README.md` for the exact framing.
