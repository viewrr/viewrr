---
description: Recall cross-session memories (hybrid vector+BM25 search over the ParadeDB store)
---
Run this and report the results:

`memory/spike/.venv/bin/python memory/spike/recall.py "$ARGUMENTS" --k 6`

Present each returned memory with its `memory/<date>.md › <section>` citation. If it prints "no memories found", say so plainly — do not invent memories.
