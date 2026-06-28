---
name: public-repo-check
description: Pre-publish hygiene gate for this public portfolio repo. Scans for secrets, .env/credentials, and any private/employer-specific or interview-prep framing before pushing or making the repo public. Use before any push, before tagging a release, or when the user asks to publish/share the repo.
---

# Public-repo hygiene check

This is a **public** portfolio repo. Before it is pushed or published, verify nothing private or sensitive is tracked. Report findings; do not auto-delete — surface what you find and let the user decide.

## Steps

1. **Confirm `.gitignore` covers private files.** Ensure `private/`, `claude-sessions/`, `interview-prep/`, `CLAUDE.local.md`, `.env`, `.env.*`, and credential files are ignored. Critically, confirm **nothing under those private dirs is tracked** (`git ls-files private/ claude-sessions/ interview-prep/` must return nothing) — they hold planning/design docs, raw Claude transcript exports, recruiter emails, the verbatim problem statement, and the probe bank. Also flag any tracked `CLAUDE.local.md` or `.env*`.

2. **Scan for secrets** across tracked files (skip `.git/`, `node_modules/`, `target/`, `cdk.out/`):
   - AWS keys: `AKIA[0-9A-Z]{16}`, `aws_secret_access_key`
   - Generic: `api[_-]?key`, `secret`, `password`, `token`, `BEGIN ... PRIVATE KEY`
   - Hardcoded connection strings with credentials.

3. **Scan for private / interview framing** that must stay out of the public repo:
   - Read the docs under `private/` to learn the specific terms to block, then case-insensitive scan **tracked** files for the **employer/company name** and **recruiter names** found there, plus the verbatim problem statement.
   - Interview-prep content: STAR stories, "why <company>" narratives, DSA practice, probe-bank framed as interview prep, round-by-round strategy.
   - Cross-check against the "Keep PRIVATE" section of the delivery plan (in `private/`).

4. **Confirm generic framing** in user-facing files (`README.md`, `docs/`, `presentation/`): the project should read as a generic sales-forecasting platform.

## Output

A short report: ✅ clean items, ⚠️ findings with file:line and why each is a problem, and a recommended action per finding. End with a clear go / no-go for publishing.
