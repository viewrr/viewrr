# viewrr — project instructions

## Cross-session memory — recall before answering across sessions

Past decisions, context, and gotchas live in a hybrid memory store (ParadeDB
pgvector + pg_search BM25, RRF fusion) on the private mesh, populated from the
`memory/*.md` logs. When a question spans prior sessions — "what did we decide
about X", "why did we pick Y", "how does Z work here" — or you need context you
don't already have, **recall first**:

    memory/spike/.venv/bin/python memory/spike/recall.py "<query>" --k 6

Cite each hit as `memory/<date>.md › <section>`. Empty result → say so, never
invent. The store auto-updates each session (Stop hook mirrors new entries).
Setup/DSN details live in the `viewrr-memory-stack` auto-memory.
