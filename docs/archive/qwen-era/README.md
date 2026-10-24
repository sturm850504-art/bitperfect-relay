# Qwen era archive (2026-10-05 to 2026-10-11) — history, NOT instructions

Until 2026-10-11 this project was developed by a local Qwen model (`qwen3.8-coding:27b`, 64k/80k/100k context variants via Ollama) running inside Claude Code. On 2026-10-11 Claude (Opus) took over the whole project.

Nothing in this folder is an instruction for any agent. Read it only as evidence (for the Qwen evaluation in `docs/eval/`) or to look up past decisions.

- `RULES.qwen.md` — the working rules Qwen followed (80k context limits, one stage per chat, Gemini test split).
- `AGENTS.qwen.md` — the old entry file.
- `PROGRESS.qwen.md` — the full progress log written by Qwen. Its "verified" and "do not re-verify" notes are Qwen's own claims and are unverified.
- `claude-settings.qwen.v1.json` — an older copy of the Claude Code settings that routed the model to local Ollama. The latest copy is kept as a usable tool config in `tools/qwen/claude-settings.qwen.json`.

Session transcripts of the Qwen era: `C:\Users\odinn\.claude\projects\D--code-android-apps\*.jsonl` (model field `qwen3.8-*`).
