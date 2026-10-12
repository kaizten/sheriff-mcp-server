# AGENTS.md

This file provides guidance to Codex, and to any agent that reads AGENTS.md,
when working with code in this repository.

**Read `CLAUDE.md` at the root before anything else, and follow it.** It is
the guide for every assistant, not only Claude Code: what the repository is,
the commands, what Sheriff does that will waste your time otherwise, the
architecture and the rules the code holds itself to. It is kept in one file
on purpose, as two copies of a guide drift apart; so add a fact there, never
here.

Where it speaks of Claude Code, read it as the assistant it is about: the
gate hook (`--hook-gate`) and `~/.claude/` are Claude Code's own, and what is
Codex's (`--install-codex`, `~/.codex/`, the Stop and turn hooks without the
gate) it says by name.
