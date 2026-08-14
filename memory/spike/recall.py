"""
Recall from the cross-session-memory store — hybrid (vector + BM25 RRF) search,
with citations. The read primitive: call this when you need to remember something.

Usage:
    python memory/spike/recall.py "how does the buzz relay self-host"
    python memory/spike/recall.py "vector index decision" --k 8 --project viewrr

Auto-loads MEMORY_DSN from memory/spike/.memory.env if not already in the env.
Cites each hit as  memory/<date>.md › <section>  so claims are traceable.
Prints "no memories found" on empty — never invents.
"""
import argparse
import os
import sys

_BASE = os.path.dirname(__file__)
# auto-load the gitignored DSN so the tool is callable standalone
if not os.environ.get("MEMORY_DSN"):
    envf = os.path.join(_BASE, ".memory.env")
    if os.path.exists(envf):
        for line in open(envf):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k, v)

import psycopg  # noqa: E402
import hybrid_spike as h  # noqa: E402


def recall(query: str, k: int, project: str):
    with psycopg.connect(h.DSN, autocommit=True) as conn, conn.cursor() as cur:
        return h.hybrid_search(cur, query, project, limit=k)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("query")
    ap.add_argument("--k", type=int, default=5)
    ap.add_argument("--project", default="viewrr")
    args = ap.parse_args()

    rows = recall(args.query, args.k, args.project)
    if not rows:
        print("no memories found")
        return
    for content, payload, vs, bs, rrf in rows:
        src = payload.get("source_file", "?")
        section = payload.get("section", "?")
        arms = ("vec" if vs else "") + ("+" if vs and bs else "") + ("bm25" if bs else "")
        print(f"— {src} › {section}  (rrf={rrf:.4f}, {arms})")
        print(f"    {content.strip()[:300]}")
        print()


if __name__ == "__main__":
    main()
