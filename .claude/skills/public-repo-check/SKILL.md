---
name: public-repo-check
description: Pre-publish hygiene gate for this public portfolio repo. Scans for secrets, .env/credentials, and any private/Intuit-specific framing before pushing or making the repo public. Use before any push, before tagging a release, or when the user asks to publish/share the repo.
---

# Public-repo hygiene check

This is a **public** portfolio repo. Before it is pushed or published, verify nothing private or sensitive is tracked. Report findings; do not auto-delete — surface what you find and let the user decide.

## Steps

1. **Confirm `.gitignore` covers private files.** Ensure `CLAUDE.local.md`, `.env`, `.env.*`, and credential files are ignored. Flag if any are tracked anyway (`git ls-files | grep -Ei 'CLAUDE.local|\.env($|\.)'`).

2. **Scan for secrets** across tracked files (skip `.git/`, `node_modules/`, `target/`, `cdk.out/`):
   - AWS keys: `AKIA[0-9A-Z]{16}`, `aws_secret_access_key`
   - Generic: `api[_-]?key`, `secret`, `password`, `token`, `BEGIN ... PRIVATE KEY`
   - Hardcoded connection strings with credentials.

3. **Scan for private / interview framing** that must stay out of the public repo:
   - Case-insensitive `intuit`, recruiter names, the verbatim problem statement.
   - Interview-prep content: STAR stories, "why Intuit" narratives, DSA practice, probe-bank framed as interview prep, round-by-round strategy.
   - Cross-check against the "Keep PRIVATE" section of `Build-Delivery-Plan-and-Repo-Structure.md`.
   - Note: `Build-Delivery-Plan-and-Repo-Structure.md` itself contains Intuit/interview framing — it should NOT be committed to the public repo. Flag it.

4. **Confirm generic framing** in user-facing files (`README.md`, `docs/`, `presentation/`): the project should read as a generic sales-forecasting platform.

## Output

A short report: ✅ clean items, ⚠️ findings with file:line and why each is a problem, and a recommended action per finding. End with a clear go / no-go for publishing.
