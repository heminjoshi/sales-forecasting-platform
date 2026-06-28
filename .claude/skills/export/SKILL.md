---
name: export
description: Copy this project's Claude Code session transcript(s) into claude-sessions/export/ (gitignored). User-triggered via /export. Shadows the built-in /export.
disable-model-invocation: true
---

# Export session transcripts

Copy the raw `.jsonl` session transcript(s) for **this** project from Claude Code's storage into `claude-sessions/export/` (gitignored, private). User-triggered only.

## Steps

1. Resolve this project's transcript directory portably (Claude Code encodes the cwd by replacing `/` with `-`):
   ```bash
   SRC="$HOME/.claude/projects/$(pwd | tr '/' '-')"
   ```
   If `$SRC` doesn't exist, list `~/.claude/projects/` and pick the entry matching this repo, then proceed.

2. Ensure the destination exists: `mkdir -p claude-sessions/export`.

3. Copy every transcript, keeping the session-UUID filename so re-running refreshes the latest (the active session grows over time):
   ```bash
   cp -f "$SRC"/*.jsonl claude-sessions/export/
   ```

4. Refresh a manifest at `claude-sessions/export/MANIFEST.md` listing each exported file, its size, and the export timestamp (`date '+%Y-%m-%d %H:%M:%S'`).

5. Report: which files were copied, the count, and the destination. If `$SRC` has no `.jsonl`, say so — the session may not be flushed to disk yet.

## Notes
- Transcripts can contain employer/interview material. `claude-sessions/` is gitignored and must **never** be committed — `/public-repo-check` verifies this.
- This skill shadows the built-in `/export` (which exports a human-readable rendering of the conversation). This one copies the raw `.jsonl` session data instead.
