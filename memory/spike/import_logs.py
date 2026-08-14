"""
Historical import of the memory/*.md daily logs into ParadeDB (#178).

Verbatim (infer=False, no summarize pass). Chunk per semantic unit:
  - one chunk per Stop-hook auto-block  <!-- auto:HASH -->  (hash reused as dedup key)
  - one chunk per manual ## section     (hash = sha256(file+heading+body))
Metadata: project=viewrr, source_file, log_date, section, kind, content_hash.
created_at backfilled to log_date so recency ranking reflects reality.
Idempotent: re-runs skip existing content_hash.

Run:  MEMORY_DSN=postgresql://... python memory/spike/import_logs.py
"""
import glob
import hashlib
import json
import os
import re

import psycopg

from hybrid_spike import DSN, embed, vec_literal

LOGDIR = os.path.join(os.path.dirname(__file__), "..")  # the repo memory/ dir
AUTO_RE = re.compile(r"<!--\s*auto:([0-9a-f]+)\s*-->", re.I)
CAPTURED_RE = re.compile(r"^_captured\s+([0-9:]+)_\s*$", re.M)
H2_SPLIT = re.compile(r"^##\s+", re.M)


def parse_file(path):
    """Yield chunk dicts: {content, section, kind, content_hash, captured_at?}."""
    stem = os.path.splitext(os.path.basename(path))[0]  # YYYY-MM-DD
    text = open(path, encoding="utf-8").read()

    # split manual region from the auto-captured region
    marker = "## Auto-captured"
    idx = text.find(marker)
    manual, auto = (text[:idx], text[idx:]) if idx != -1 else (text, "")

    # --- manual ## sections ---
    parts = H2_SPLIT.split(manual)
    for part in parts[1:]:  # parts[0] is the H1 title / preamble
        lines = part.splitlines()
        heading = lines[0].strip() if lines else ""
        body = "\n".join(lines[1:]).strip()
        # drop the auto-capture heading remnant + trivial/empty sections
        if not body or heading.startswith("Auto-captured"):
            continue
        content = f"{heading}\n{body}".strip()
        h = hashlib.sha256(f"{stem}|{heading}|{body}".encode()).hexdigest()[:16]
        yield {"content": content, "section": heading, "kind": "manual",
               "content_hash": h}

    # --- auto blocks ---
    matches = list(AUTO_RE.finditer(auto))
    for i, m in enumerate(matches):
        start = m.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(auto)
        block = auto[start:end]
        cap = CAPTURED_RE.search(block)
        captured_at = cap.group(1) if cap else None
        body = CAPTURED_RE.sub("", block).strip()
        if not body:
            continue
        yield {"content": body, "section": "auto-capture", "kind": "auto",
               "content_hash": m.group(1), "captured_at": captured_at}


def main():
    files = sorted(f for f in glob.glob(os.path.join(LOGDIR, "*.md"))
                   if os.path.basename(f)[0].isdigit())  # dated logs only
    print(f"{len(files)} log files")

    parsed = inserted = skipped = 0
    with psycopg.connect(DSN, autocommit=True) as conn, conn.cursor() as cur:
        # skip-before-embed: only embed chunks not already stored (so this is cheap
        # to run incrementally, e.g. from a Stop hook picking up new auto-blocks).
        cur.execute("SELECT payload->>'content_hash' FROM memory.memories "
                    "WHERE payload->>'project'='viewrr'")
        seen = {r[0] for r in cur.fetchall()}
        for path in files:
            stem = os.path.splitext(os.path.basename(path))[0]
            for c in parse_file(path):
                parsed += 1
                if c["content_hash"] in seen:
                    skipped += 1
                    continue
                seen.add(c["content_hash"])
                payload = {"project": "viewrr", "source_file": f"memory/{stem}.md",
                           "log_date": stem, "section": c["section"], "kind": c["kind"],
                           "content_hash": c["content_hash"]}
                if c.get("captured_at"):
                    payload["captured_at"] = c["captured_at"]
                cur.execute(
                    "INSERT INTO memory.memories (vector, content, payload, created_at) "
                    "VALUES (%s::vector, %s, %s::jsonb, %s) "
                    "ON CONFLICT ((payload->>'content_hash')) DO NOTHING",
                    (vec_literal(embed(c["content"], "document")), c["content"],
                     json.dumps(payload), f"{stem} 00:00:00+00"),
                )
                if cur.rowcount:
                    inserted += 1
                else:
                    skipped += 1
            print(f"  {stem}: parsed so far {parsed}, inserted {inserted}, dupes {skipped}")

        cur.execute("SELECT count(*) FROM memory.memories WHERE payload->>'project'='viewrr'")
        total = cur.fetchone()[0]

    print(f"\ndone: parsed {parsed}, inserted {inserted}, skipped(dupe) {skipped}")
    print(f"viewrr memories in store: {total}")
    assert inserted > 0 or skipped > 0, "parsed nothing — check log structure"


if __name__ == "__main__":
    main()
