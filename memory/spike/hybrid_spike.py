"""
Cross-session-memory spike: prove the pgvector + pg_search BM25 hybrid retrieval
(RRF fusion) end-to-end against the live ParadeDB, embeddings from the live TEI.

Validates wayfinder #174 decisions: #176 HNSW/cosine, #177 RRF k=60 fusion in one
SQL round-trip, #178 metadata, #179 schema. infer=False (store verbatim).

Run:  python memory/spike/hybrid_spike.py
Needs: psycopg[binary], requests  (see requirements.txt). Mesh access to 100.64.0.2.

ponytail: hand-rolled SQL to de-risk the *fusion* — the novel bit. The mem0 fork
just wraps this query; wrapping is mechanical, so prove the query first.
"""
import json
import os
import urllib.request

import psycopg

# Secrets via env — never commit the password. Set MEMORY_DSN before running.
DSN = os.environ.get("MEMORY_DSN") or "postgresql://memory:PASSWORD@100.64.0.2:5432/memory"
TEI = os.environ.get("TEI_URL", "http://100.64.0.2:80")
RRF_K = 60          # #177
POOL_N = 60         # per-arm candidate pool (#177)


def embed(text: str, kind: str) -> list[float]:
    """kind: 'document' (stored) or 'query' (search). nomic prefixes matter."""
    prefix = "search_document: " if kind == "document" else "search_query: "
    req = urllib.request.Request(
        f"{TEI}/embed",
        data=json.dumps({"inputs": prefix + text}).encode(),
        headers={"Content-Type": "application/json"},
    )
    v = json.loads(urllib.request.urlopen(req, timeout=30).read())
    return v[0] if isinstance(v[0], list) else v


def vec_literal(v: list[float]) -> str:
    return "[" + ",".join(f"{x:.7f}" for x in v) + "]"


def store(cur, content: str, project: str, meta: dict):
    payload = {"project": project, **meta}
    cur.execute(
        "INSERT INTO memory.memories (vector, content, payload) "
        "VALUES (%s::vector, %s, %s::jsonb) "
        "ON CONFLICT ((payload->>'content_hash')) DO NOTHING",
        (vec_literal(embed(content, "document")), content, json.dumps(payload)),
    )


def hybrid_search(cur, query: str, project: str, limit: int = 5):
    """One round-trip: vector arm + BM25 arm, fused by RRF. Returns ranked rows."""
    qvec = vec_literal(embed(query, "query"))
    sql = """
    WITH vec AS (
      SELECT id, ROW_NUMBER() OVER (ORDER BY vector <=> %(qv)s::vector) AS r
      FROM memory.memories
      WHERE payload->>'project' = %(proj)s
      ORDER BY vector <=> %(qv)s::vector
      LIMIT %(pool)s
    ),
    bm25 AS (
      SELECT id, ROW_NUMBER() OVER (ORDER BY paradedb.score(id) DESC) AS r
      FROM memory.memories
      WHERE content @@@ %(q)s AND payload->>'project' = %(proj)s
      ORDER BY paradedb.score(id) DESC
      LIMIT %(pool)s
    )
    SELECT m.content, m.payload,
           COALESCE(1.0/(%(k)s + v.r), 0) AS vs,
           COALESCE(1.0/(%(k)s + b.r), 0) AS bs,
           COALESCE(1.0/(%(k)s + v.r), 0) + COALESCE(1.0/(%(k)s + b.r), 0) AS rrf
    FROM vec v
    FULL OUTER JOIN bm25 b USING (id)
    JOIN memory.memories m ON m.id = COALESCE(v.id, b.id)
    ORDER BY rrf DESC
    LIMIT %(limit)s
    """
    cur.execute(sql, {"qv": qvec, "q": query, "proj": project,
                      "k": RRF_K, "pool": POOL_N, "limit": limit})
    return cur.fetchall()


SEED = [
    ("We chose HNSW over IVFFlat for the memory vector index because the corpus is append-heavy.", {"content_hash": "seed1"}),
    ("Fusion happens inside the forked provider search() via reciprocal rank fusion, k=60.", {"content_hash": "seed2"}),
    ("The historical import stores memory logs verbatim with infer=False, deduped by content hash.", {"content_hash": "seed3"}),
    ("Blinkit expenditure dashboard uses DuckDB and a pie chart of categories.", {"content_hash": "seed4"}),
    ("The viewrr Hub transcodes HLS and serves streams to playback clients.", {"content_hash": "seed5"}),
]


def demo():
    with psycopg.connect(DSN, autocommit=True) as conn, conn.cursor() as cur:
        cur.execute("DELETE FROM memory.memories WHERE payload->>'project' = 'spike'")
        for content, meta in SEED:
            store(cur, content, "spike", meta)
        cur.execute("SELECT count(*) FROM memory.memories WHERE payload->>'project'='spike'")
        n = cur.fetchone()[0]
        print(f"seeded {n} rows")

        q = "which vector index did we pick and why"
        print(f"\nquery: {q!r}\n")
        rows = hybrid_search(cur, q, "spike")
        for content, payload, vs, bs, rrf in rows:
            print(f"  rrf={rrf:.5f} (vec={vs:.5f} bm25={bs:.5f})  {content[:70]}")

        # check: the HNSW memory (semantic + lexical match) ranks #1
        assert rows, "no results"
        assert "HNSW" in rows[0][0], f"expected HNSW memory top, got: {rows[0][0][:60]}"
        print("\nOK: hybrid RRF returned the right memory on top.")


if __name__ == "__main__":
    demo()
